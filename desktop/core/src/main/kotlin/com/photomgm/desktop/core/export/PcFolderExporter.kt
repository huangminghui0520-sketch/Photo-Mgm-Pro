// desktop/core/export/PcFolderExporter.kt —— 文件夹导出器（★ 方案 §4.5 契约的唯一实现处）
//
// 为什么不再用核心的 ZipExporter：桌面端的交付形态是**文件夹**（用户直接看、直接给领导，
// 不用解压、不受 Windows 资源管理器 ZIP 中文名乱码影响）。
// 但文件夹的**编号命名必须复用核心的 ZipExporter.numberedFolderNames()** ——
// 因为 LedgerEngine 生成台账"照片列"的 `文件夹名/文件名` 前缀时用的就是同一个函数，
// 不共用就会出现"台账说在 01_护栏损坏，磁盘上是 02_标志牌倾斜"的漂移。
//
// 本类逐条实现契约：
//   ① 产物结构：MMdd_HHmmss/{ledger.xlsx, export.log, export-manifest.json, NN_事件/, 99_未分类/}
//   ② 同一照片被多个事件引用 → 各文件夹各存一份（禁止全导出只存一份）
//   ③ 重名唯一性范围 = 同一文件夹内；后缀插在扩展名前，无扩展名不拼尾随点
//   ④ export.log 为 JSON Lines（见 ExportLogWriter）
//   ⑤ 断点续传以 manifest 为准（见 ExportManifest / ResumeVerifyMode）
//   ⑥ 长路径：目标绝对路径 > 240 字符 → 导出前明确报错，禁止静默截断
package com.photomgm.desktop.core.export

import algorithm.AlgorithmApi
import algorithm.AppZone
import algorithm.ZipExporter
import algorithm.model.LedgerRow
import algorithm.model.LogEvent
import algorithm.model.Photo
import com.photomgm.desktop.core.api.ExportFailure
import com.photomgm.desktop.core.api.ExportProgress
import com.photomgm.desktop.core.api.ExportResult
import com.photomgm.desktop.core.api.ResumeVerifyMode
import com.photomgm.desktop.core.platform.PcFileHasher
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Comparator

/** 导出被前置校验阻断（不是"复制失败"，而是"根本不该开始"）。 */
internal class ExportBlockedException(
    message: String,
    val offendingPaths: List<String> = emptyList(),
) : Exception(message)

internal class PcFolderExporter(
    private val zone: ZoneId = AppZone.ID,
    private val imageEmbedder: (String?) -> ByteArray? = { null },
    private val clock: Clock = Clock.systemUTC(),
    private val hasher: PcFileHasher = PcFileHasher(),
) {

    /** 导出输入（由核心从当前状态快照组装，导出器不读核心状态，便于独立单测）。 */
    data class Input(
        val outputParent: Path,
        val events: List<LogEvent>,
        val eventPhotoIds: Map<Int, List<Long>>,
        val unmatchedIds: List<Long>,
        val photoById: (Long) -> Photo?,
        val marked: Map<Int, Pair<Long?, Long?>> = emptyMap(),
        val namingTemplate: Int = 1,
        val includeLedger: Boolean = true,
        val verifyMode: ResumeVerifyMode = ResumeVerifyMode.SIZE_AND_MTIME,
        val onProgress: (ExportProgress) -> Unit = {},
        val isCancelled: () -> Boolean = { false },
    )

    fun export(input: Input): ExportResult {
        val startedNs = System.nanoTime()

        // ── 1) 规划：文件夹 + 目标 + 重名 ──────────────────────────────────
        val plan = buildPlan(input)
        if (plan.targets.isEmpty()) {
            throw ExportBlockedException("没有可导出的照片：请先选择源目录与巡查日期并完成分析")
        }
        checkLongPaths(input.outputParent, plan.targets.keys)

        // ── 2) 台账行（预生成：既用于写文件，也用于续传指纹） ────────────────
        val ledgerRows: List<LedgerRow> = if (input.includeLedger) {
            AlgorithmApi.buildLedger(
                input.events,
                input.eventPhotoIds,
                input.photoById,
                input.marked,
                input.namingTemplate,
                zone,
            )
        } else emptyList()
        val ledgerFingerprint = if (input.includeLedger) ledgerFingerprint(ledgerRows) else null

        val photoSetHash = photoSetHash(plan.targets)

        // ── 3) 续传探测：同目录下已有"同一批 + 完整"的导出 → 直接复用 ────────
        findResumable(input, photoSetHash, ledgerFingerprint, plan.targets.size)?.let { existing ->
            ExportLogWriter(existing.resolve(LOG_NAME), zone).use { log ->
                log.resumeHit(existing.toString(), plan.targets.size)
                log.end(plan.targets.size, 0, plan.targets.size, 0, false, 0)
            }
            return ExportResult(
                exportDir = existing,
                ledgerFile = existing.resolve(LEDGER_NAME).takeIf { Files.isRegularFile(it) },
                logFile = existing.resolve(LOG_NAME),
                manifestFile = existing.resolve(ExportManifest.FILE_NAME),
                totalTargets = plan.targets.size,
                copied = 0,
                skipped = plan.targets.size,
                failed = 0,
                durationMs = (System.nanoTime() - startedNs) / 1_000_000,
                resumed = true,
                warnings = plan.warnings,
            )
        }

        // ── 4) 建导出根 ────────────────────────────────────────────────────
        val root = createExportDir(input.outputParent, plan.warnings)
        val logFile = root.resolve(LOG_NAME)

        var copied = 0
        var failed = 0
        val failures = mutableListOf<ExportFailure>()
        val entries = mutableListOf<ManifestEntry>()
        var cancelled = false
        val total = plan.targets.size + if (input.includeLedger) 1 else 0

        ExportLogWriter(logFile, zone).use { log ->
            log.start(input.namingTemplate, plan.targets.size, input.includeLedger)
            plan.renames.forEach { (from, to) -> log.rename(from, to) }

            // ── 5) 复制照片（32KB 流式；边复制边算 SHA-256，几乎零额外成本） ──
            for ((relativePath, target) in plan.targets) {
                if (input.isCancelled()) {
                    cancelled = true
                    break
                }
                val dest = root.resolve(relativePath)
                val fileStartNs = System.nanoTime()
                try {
                    Files.createDirectories(dest.parent)
                    val digest = MessageDigest.getInstance("SHA-256")
                    var size = 0L
                    Files.newInputStream(Paths.get(target.sourcePath)).use { ins ->
                        Files.newOutputStream(
                            dest,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE,
                        ).use { out ->
                            val buf = ByteArray(BUFFER_BYTES)
                            while (true) {
                                val n = ins.read(buf)
                                if (n < 0) break
                                if (n == 0) continue
                                out.write(buf, 0, n)
                                digest.update(buf, 0, n)
                                size += n
                            }
                        }
                    }
                    entries += ManifestEntry(
                        relativePath = relativePath,
                        size = size,
                        sha256 = digest.digest().toHex(),
                        modifiedMs = runCatching { Files.getLastModifiedTime(dest).toMillis() }.getOrDefault(0L),
                    )
                    copied++
                    log.copy(relativePath, size, (System.nanoTime() - fileStartNs) / 1_000_000)
                } catch (e: Exception) {
                    failed++
                    val reason = (e.message ?: e.javaClass.simpleName).take(120)
                    failures += ExportFailure(relativePath, reason)
                    log.failed(relativePath, reason)
                    runCatching { Files.deleteIfExists(dest) } // 不留半个文件（否则下次续传会把它当产物）
                }
                input.onProgress(
                    ExportProgress(
                        stage = "复制照片",
                        copied = copied + failed,
                        total = total,
                        currentFile = relativePath.substringAfterLast('/'),
                    ),
                )
            }

            // ── 6) 台账（最后写：它的内容依赖全部照片，且失败不应影响照片已导出） ──
            var ledgerFile: Path? = null
            if (input.includeLedger && !cancelled) {
                input.onProgress(ExportProgress("生成台账", copied + failed, total, LEDGER_NAME))
                val dest = root.resolve(LEDGER_NAME)
                try {
                    Files.newOutputStream(
                        dest,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE,
                    ).use { out ->
                        AlgorithmApi.exportLedgerXlsx(ledgerRows, out, imageEmbedder)
                    }
                    ledgerFile = dest
                    // ★ 不计入 copied：copied/skipped/failed 是"照片"口径，台账成功与否看 ledgerFile
                    //   （混算会让 summary.success > summary.total，且 skipped 变负数）
                } catch (e: Exception) {
                    failed++
                    val reason = (e.message ?: e.javaClass.simpleName).take(120)
                    failures += ExportFailure(LEDGER_NAME, reason)
                    log.failed(LEDGER_NAME, reason)
                    runCatching { Files.deleteIfExists(dest) }
                }
            }

            val complete = !cancelled && failed == 0
            ExportManifest.write(
                root.resolve(ExportManifest.FILE_NAME),
                ExportManifest(
                    namingTemplate = input.namingTemplate,
                    photoSetHash = photoSetHash,
                    ledgerFingerprint = ledgerFingerprint,
                    complete = complete,
                    createdAt = nowIso(),
                    entries = entries,
                ),
            )

            val durationMs = (System.nanoTime() - startedNs) / 1_000_000
            // ★ 不变式（M5 硬验收）：copied + failed == 照片目标数（取消时除外），
            //   skipped 恒等于"报告跳过"= 未复制的数量，三者口径统一为"照片"。
            check(cancelled || copied + failed == plan.targets.size) {
                "导出计数不自洽：copied=$copied failed=$failed targets=${plan.targets.size}"
            }
            val skipped = (plan.targets.size - copied - failed).coerceAtLeast(0)

            log.end(
                total = plan.targets.size,
                success = copied,
                skipped = skipped,
                failed = failed,
                cancelled = cancelled,
                durationMs = durationMs,
            )

            return ExportResult(
                exportDir = root,
                ledgerFile = ledgerFile,
                logFile = logFile,
                manifestFile = root.resolve(ExportManifest.FILE_NAME),
                totalTargets = plan.targets.size,
                copied = copied,
                skipped = skipped,
                failed = failed,
                failures = failures,
                durationMs = durationMs,
                cancelled = cancelled,
                resumed = false,
                warnings = plan.warnings,
            )
        }
    }

    // ── 规划 ───────────────────────────────────────────────────────────────

    private class Plan(
        val targets: LinkedHashMap<String, Target>,
        val renames: MutableList<Pair<String, String>>,
        val warnings: MutableList<String>,
    )

    private class Target(val relativePath: String, val sourcePath: String, val sourceSize: Long)

    private fun buildPlan(input: Input): Plan {
        val targets = LinkedHashMap<String, Target>()
        val renames = mutableListOf<Pair<String, String>>()
        val warnings = mutableListOf<String>()
        val used = HashSet<String>()
        // 每个文件夹内已用过的 photoId（同一张照片在同一事件里被列两次时只保留一份）
        val usedIds = HashMap<String, HashSet<Long>>()

        val numbered = ZipExporter(zone).numberedFolderNames(input.events, input.namingTemplate)
        val ordered = input.events.sortedBy { it.endTimeMs ?: Long.MAX_VALUE }

        fun put(folder: String, p: Photo) {
            if (p.sourceRef.isBlank()) return
            val ids = usedIds.getOrPut(folder) { HashSet() }
            if (!ids.add(p.id)) return
            val base = p.displayName.ifBlank {
                p.sourceRef.substringAfterLast('/').substringAfterLast('\\')
            }
            var name = base
            // 与 Android 端 ZipExporter 一致：第一个重名后缀是 _2（不是 _1）
            var k = 2
            while ("$folder/$name" in used) {
                name = suffixed(base, k)
                k++
            }
            val rel = "$folder/$name"
            if (name != base) renames += base to rel
            used += rel
            targets[rel] = Target(rel, p.sourceRef, sizeOf(p.sourceRef))
        }

        for (e in ordered) {
            val folder = numbered[e.id] ?: continue
            val photos = (input.eventPhotoIds[e.id] ?: emptyList()).mapNotNull(input.photoById)
            if (photos.isEmpty()) continue // 契约：事件无照片不建文件夹（与 Android 端一致）
            photos.forEach { put(folder, it) }
        }
        input.unmatchedIds.mapNotNull(input.photoById).forEach { put(UNMATCHED_FOLDER, it) }

        return Plan(targets, renames, warnings)
    }

    /** 重名后缀：`IMG_001.jpg` → `IMG_001_2.jpg`；无扩展名 → `IMG_001_2`（绝不拼出尾随点）。 */
    private fun suffixed(base: String, k: Int): String {
        val dot = base.lastIndexOf('.')
        return if (dot > 0) "${base.substring(0, dot)}_$k${base.substring(dot)}" else "${base}_$k"
    }

    private fun sizeOf(path: String): Long =
        runCatching { Files.size(Paths.get(path)) }.getOrDefault(-1L)

    // ── 长路径前置校验 ──────────────────────────────────────────────────────

    private fun checkLongPaths(parent: Path, relatives: Set<String>) {
        val parentLen = parent.toAbsolutePath().toString().trimEnd('\\', '/').length
        val rootLen = parentLen + 1 + EXPORT_DIR_NAME.length
        val offending = relatives.filter { rootLen + 1 + it.length > MAX_PATH_LENGTH }
        if (offending.isNotEmpty()) {
            val sample = offending.take(5).joinToString("；")
            throw ExportBlockedException(
                "有 ${offending.size} 个目标路径超过 $MAX_PATH_LENGTH 字符，Windows 会写入失败：" +
                    "$sample。请缩短输出目录（越靠近盘符越好）或改用模板 0（事件类型命名）。",
                offending.toList(),
            )
        }
    }

    // ── 续传 ───────────────────────────────────────────────────────────────

    private fun findResumable(
        input: Input,
        photoSetHash: String,
        ledgerFingerprint: String?,
        expectedCount: Int,
    ): Path? {
        val parent = input.outputParent
        if (!Files.isDirectory(parent)) return null
        val candidates = runCatching {
            Files.list(parent).use { stream ->
                stream.filter { Files.isDirectory(it) }
                    .filter { isExportDirName(it.fileName.toString()) }
                    .sorted(Comparator.reverseOrder<Path>())
                    .toList()
            }
        }.getOrNull() ?: return null

        for (dir in candidates) {
            val m = ExportManifest.read(dir.resolve(ExportManifest.FILE_NAME)) ?: continue
            if (!m.complete) continue
            if (m.namingTemplate != input.namingTemplate) continue
            if (m.photoSetHash != photoSetHash) continue
            if (m.ledgerFingerprint != ledgerFingerprint) continue
            if (m.entries.size != expectedCount) continue
            if (!m.entries.all { entryIntact(dir, it, input.verifyMode) }) continue
            return dir
        }
        return null
    }

    private fun entryIntact(root: Path, entry: ManifestEntry, mode: ResumeVerifyMode): Boolean {
        val file = root.resolve(entry.relativePath)
        if (!Files.isRegularFile(file)) return false
        val size = runCatching { Files.size(file) }.getOrNull() ?: return false
        if (size != entry.size) return false
        return when (mode) {
            ResumeVerifyMode.SIZE_AND_MTIME ->
                runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrNull() == entry.modifiedMs

            ResumeVerifyMode.SIZE_AND_SHA256 ->
                entry.sha256.isNotEmpty() && hasher.sha256(file.toString()) == entry.sha256
        }
    }

    // ── 指纹 ───────────────────────────────────────────────────────────────

    private fun photoSetHash(targets: Map<String, Target>): String {
        val sb = StringBuilder()
        targets.toSortedMap().forEach { (rel, t) ->
            sb.append(rel).append('|').append(t.sourceSize).append('\n')
        }
        return hasher.sha256Of(sb.toString())
    }

    private fun ledgerFingerprint(rows: List<LedgerRow>): String {
        val sb = StringBuilder()
        rows.forEach { r ->
            sb.append(r.dateTime).append('|').append(r.endTime).append('|')
                .append(r.location).append('|').append(r.description).append('|')
                .append(r.photo1Ref ?: "").append('|').append(r.photo2Ref ?: "").append('|')
                .append(r.folderName).append('\n')
        }
        return hasher.sha256Of(sb.toString())
    }

    // ── 目录与命名 ──────────────────────────────────────────────────────────

    private fun createExportDir(parent: Path, warnings: MutableList<String>): Path {
        Files.createDirectories(parent)
        val ts = TIMESTAMP.withZone(zone).format(Instant.now(clock))
        var dir = parent.resolve(ts)
        var i = 2
        while (Files.exists(dir)) {
            dir = parent.resolve("${ts}_$i")
            warnings += "同一秒内重复导出，目录加序号 _${i} 避免覆盖"
            i++
        }
        Files.createDirectories(dir)
        return dir
    }

    private fun nowIso(): String = ISO_OFFSET.withZone(zone).format(Instant.now(clock))

    private fun ByteArray.toHex(): String {
        val out = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            out.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return out.toString()
    }

    companion object {
        /**
         * 导出根名：`月日_时分秒`（如 `1101_203200`），同秒重复导出加 `_2`/`_3`。
         *
         * ★ 2026-11-02 按要求去掉 `PhotoMgm_export_` 前缀：现场是**按日期目录归档**的，
         *   父目录已经说明了这是什么，前缀只是把每行都撑长一截（在资源管理器里还会被截断）。
         *   识别一个目录是不是导出根，改为看名字**是否符合时间戳形状**（[isExportDirName]）。
         */
        const val EXPORT_DIR_EXAMPLE = "1101_203200"

        /** 导出根名模板（定长 11，用于长路径预算；`MMdd_HHmmss`）。 */
        private const val EXPORT_DIR_NAME = "0000_000000"

        /**
         * 导出根名正则：`MMdd_HHmmss` + 可选的 `_N` 去重后缀。
         *
         * ★ 为什么要正则而不是 `startsWith(前缀)`：新命名没有固定前缀，
         *   `startsWith("")` 会把父目录下**任何**子目录都当成导出根，
         *   续传时就会去读别人目录里的 manifest（读不到只是慢，读到错的才是事故）。
         */
        private val EXPORT_DIR_REGEX = Regex("""\d{4}_\d{6}(_\d+)?""")

        /** 该目录名是否形如导出根（`1101_203200` / `1101_203200_2`）。 */
        fun isExportDirName(name: String): Boolean = EXPORT_DIR_REGEX.matches(name)

        const val LEDGER_NAME = "ledger.xlsx"
        const val LOG_NAME = "export.log"

        /**
         * 未匹配照片目录。
         * ★ 核心的 `ZipExporter.UNMATCHED_FOLDER` 是 `private`（ZipExporter.kt:358），桌面端无法引用，
         *   只能硬编码；靠「台账前缀 == 实际文件夹名」的单测兜底一致性。
         *   核心若改名，这里必须同步（代码审查重点）。
         */
        const val UNMATCHED_FOLDER = "99_未分类"

        /** Windows 单段长路径硬约束（留余量，不是 260 逼近线）。 */
        const val MAX_PATH_LENGTH = 240

        private const val BUFFER_BYTES = 32 * 1024

        private val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("MMdd_HHmmss")
        private val ISO_OFFSET: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")
        private val HEX = "0123456789abcdef".toCharArray()
    }
}
