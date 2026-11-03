// desktop/core/adapter/PcExportExecutor.kt —— 文件夹导出**执行**器（★ 方案 §4.5 契约的唯一桌面实现）
//
// 为什么不用核心的 ZipExporter：桌面端的交付形态是**文件夹**（用户直接看、直接给领导，
// 不用解压、不受 Windows 资源管理器 ZIP 中文名乱码影响）。
//
// ★ T09/T10 的分工（这条边界是本文件最重要的注释）：
//   **计划**（哪些照片进哪个文件夹、文件夹怎么编号、重名加 `_2`、未匹配进 `99_未分类`、
//   台账行是什么）已上移到 `shared/app/export/ExportPlanner`，由导出用例交进来；
//   本类只做**执行**：把这些相对路径的字节写出去、写台账、写清单、写日志。
//
//   切分的判据是"谁出错时会影响另一端"：
//     · 重名后缀 / 文件夹编号 → 影响台账里 `文件夹名/文件名` 这一列，两端必须同源 → 上移；
//     · 文件系统 API / 长路径约束 / 续传 manifest / AWT 图片编码 → 纯平台知识 → 留下。
//   在此之前这段规划逻辑在桌面 `buildPlan` 和安卓 `ZipExporter.export` 里各写了一遍，
//   两份的注释互相写着"与另一端一致" —— 那种一致性只存在于注释里。
//
// 本类仍逐条兑现契约（①~⑥ 见方案 §4.5）：
//   ① 产物结构：MMdd_HHmmss/{ledger.xlsx, export.log, export-manifest.json, NN_事件/, 99_未分类/}
//   ② 同一照片被多个事件引用 → 各文件夹各存一份（由计划决定，本类按计划逐个写）
//   ③ 重名唯一性 → 已由计划解决；本类只需保证"计划里有几条就写几条"
//   ④ export.log 为 JSON Lines（见 ExportLogWriter）
//   ⑤ 断点续传以 manifest 为准（见 ExportManifest / ResumeVerifyMode）
//   ⑥ 长路径：目标绝对路径 > 240 字符 → 导出前明确报错，禁止静默截断
package com.photomgm.desktop.core.adapter

import algorithm.AlgorithmApi
import algorithm.AppZone
import algorithm.model.LedgerRow
import app.api.ExportBlockedException
import app.api.ExportFile
import app.api.ExportPlan
import app.api.ExportProgress
import app.api.ExportResult
import app.api.ExportFailure
import app.api.ResumeVerifyMode
import app.export.ExportLogWriter
import app.port.ExportExecutor
import com.photomgm.desktop.core.export.ExportManifest
import com.photomgm.desktop.core.export.ManifestEntry
import com.photomgm.desktop.core.platform.PcFileHasher
import com.photomgm.desktop.core.platform.PcPaths
import domain.hash.Sha256
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Comparator

internal class PcExportExecutor(
    private val zone: ZoneId = AppZone.ID,
    private val imageEncoder: (String) -> ByteArray? = { null },
    private val clock: Clock = Clock.systemUTC(),
    private val hasher: PcFileHasher = PcFileHasher(),
    /**
     * 续传校验强度的**取值函数**而不是值：UI 的设置页能在运行期改它，
     * 若构造时定死，用户改完要重启才生效 —— 那是一句没人会理解的说明书。
     */
    private val verifyMode: () -> ResumeVerifyMode = { ResumeVerifyMode.SIZE_AND_MTIME },
) : ExportExecutor {

    override fun execute(
        plan: ExportPlan,
        target: String,
        onProgress: (ExportProgress) -> Unit,
        isCancelled: () -> Boolean,
    ): ExportResult {
        val startedNs = System.nanoTime()

        val parent = runCatching { PcPaths.toPath(target) }.getOrElse {
            throw ExportBlockedException(
                "输出目录无效：$target（${it.message ?: "路径过长或含非法字符"}），请换一个更短的目录",
            )
        }
        if (!plan.hasTargets) {
            throw ExportBlockedException("没有可导出的照片：请先选择源目录与巡查日期并完成分析")
        }
        checkLongPaths(parent, plan.files.map { it.relativePath })

        val warnings = plan.warnings.toMutableList()

        // ── 1) 台账行（计划里已算好：既用于写文件，也用于续传指纹） ─────────────
        val ledgerName: String = plan.ledgerFileName
        val ledgerRows: List<LedgerRow> = plan.ledger
        val ledgerFingerprint = if (plan.includeLedger) ledgerFingerprint(ledgerRows) else null
        val photoSetHash = photoSetHash(plan.files)

        // ── 2) 续传探测：同目录下已有"同一批 + 完整"的导出 → 直接复用 ────────────
        findResumable(parent, plan, photoSetHash, ledgerFingerprint)?.let { existing ->
            ExportLogWriter(existing.resolve(LOG_NAME), zone).use { log ->
                log.resumeHit(existing.toString(), plan.files.size)
                log.end(plan.files.size, 0, plan.files.size, 0, false, 0)
            }
            return ExportResult(
                exportDir = PcPaths.normalize(existing.toString()),
                ledgerFile = existing.resolve(ledgerName).takeIf { Files.isRegularFile(it) }
                    ?.let { PcPaths.normalize(it.toString()) },
                logFile = PcPaths.normalize(existing.resolve(LOG_NAME).toString()),
                manifestFile = PcPaths.normalize(existing.resolve(ExportManifest.FILE_NAME).toString()),
                totalTargets = plan.files.size,
                copied = 0,
                skipped = plan.files.size,
                failed = 0,
                durationMs = (System.nanoTime() - startedNs) / 1_000_000,
                resumed = true,
                warnings = warnings,
            )
        }

        // ── 3) 建导出根 ────────────────────────────────────────────────────
        val root = createExportDir(parent, warnings)
        val logFile = root.resolve(LOG_NAME)

        var copied = 0
        var failed = 0
        val failures = mutableListOf<ExportFailure>()
        val entries = mutableListOf<ManifestEntry>()
        var cancelled = false
        val total = plan.files.size + if (plan.includeLedger) 1 else 0

        ExportLogWriter(logFile, zone).use { log ->
            log.start(plan.namingTemplate, plan.files.size, plan.includeLedger)
            plan.renames.forEach { r -> log.rename(r.originalName, r.toRelativePath) }

            // ── 4) 复制照片（32KB 流式；边复制边算 SHA-256，几乎零额外成本） ────
            for (file in plan.files) {
                if (isCancelled()) {
                    cancelled = true
                    break
                }
                val dest = root.resolve(file.relativePath)
                val fileStartNs = System.nanoTime()
                try {
                    Files.createDirectories(dest.parent)
                    val digester = Sha256.digester()
                    var size = 0L
                    Files.newInputStream(PcPaths.toPath(file.sourceRef)).use { ins ->
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
                                digester.update(buf, 0, n)
                                size += n
                            }
                        }
                    }
                    entries += ManifestEntry(
                        relativePath = file.relativePath,
                        size = size,
                        sha256 = digester.finish(),
                        modifiedMs = runCatching { Files.getLastModifiedTime(dest).toMillis() }.getOrDefault(0L),
                    )
                    copied++
                    log.copy(file.relativePath, size, (System.nanoTime() - fileStartNs) / 1_000_000)
                } catch (e: Exception) {
                    failed++
                    val reason = (e.message ?: e.javaClass.simpleName).take(120)
                    failures += ExportFailure(file.relativePath, reason)
                    log.failed(file.relativePath, reason)
                    runCatching { Files.deleteIfExists(dest) } // 不留半个文件（否则下次续传会把它当产物）
                }
                onProgress(
                    ExportProgress(
                        stage = "复制照片",
                        copied = copied + failed,
                        total = total,
                        currentFile = file.relativePath.substringAfterLast('/'),
                    ),
                )
            }

            // ── 5) 台账（最后写：它的内容依赖全部照片，且失败不应影响照片已导出） ──
            var ledgerFile: java.nio.file.Path? = null
            if (plan.includeLedger && !cancelled) {
                onProgress(ExportProgress("生成台账", copied + failed, total, ledgerName))
                val dest = root.resolve(ledgerName)
                try {
                    Files.newOutputStream(
                        dest,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE,
                    ).use { out ->
                        AlgorithmApi.exportLedgerXlsx(ledgerRows, out) { ref -> ref?.let(imageEncoder) }
                    }
                    ledgerFile = dest
                    // ★ 不计入 copied：copied/skipped/failed 是"照片"口径，台账成功与否看 ledgerFile
                    //   （混算会让 summary.success > summary.total，且 skipped 变负数）
                } catch (e: Exception) {
                    failed++
                    val reason = (e.message ?: e.javaClass.simpleName).take(120)
                    failures += ExportFailure(ledgerName, reason)
                    log.failed(ledgerName, reason)
                    runCatching { Files.deleteIfExists(dest) }
                }
            }

            val complete = !cancelled && failed == 0
            ExportManifest.write(
                root.resolve(ExportManifest.FILE_NAME),
                ExportManifest(
                    namingTemplate = plan.namingTemplate,
                    photoSetHash = photoSetHash,
                    ledgerFingerprint = ledgerFingerprint,
                    complete = complete,
                    createdAt = nowIso(),
                    entries = entries,
                ),
            )

            val durationMs = (System.nanoTime() - startedNs) / 1_000_000
            // ★ 不变式（硬验收）：copied + failed == 照片目标数（取消时除外），
            //   skipped 恒等于"报告跳过"= 未复制的数量，三者口径统一为"照片"。
            check(cancelled || copied + failed == plan.files.size) {
                "导出计数不自洽：copied=$copied failed=$failed targets=${plan.files.size}"
            }
            val skipped = (plan.files.size - copied - failed).coerceAtLeast(0)

            log.end(
                total = plan.files.size,
                success = copied,
                skipped = skipped,
                failed = failed,
                cancelled = cancelled,
                durationMs = durationMs,
            )

            return ExportResult(
                exportDir = PcPaths.normalize(root.toString()),
                ledgerFile = ledgerFile?.let { PcPaths.normalize(it.toString()) },
                logFile = PcPaths.normalize(logFile.toString()),
                manifestFile = PcPaths.normalize(root.resolve(ExportManifest.FILE_NAME).toString()),
                totalTargets = plan.files.size,
                copied = copied,
                skipped = skipped,
                failed = failed,
                failures = failures,
                durationMs = durationMs,
                cancelled = cancelled,
                resumed = false,
                warnings = warnings,
            )
        }
    }

    // ── 长路径前置校验 ──────────────────────────────────────────────────────

    private fun checkLongPaths(parent: Path, relatives: List<String>) {
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
        parent: Path,
        plan: ExportPlan,
        photoSetHash: String,
        ledgerFingerprint: String?,
    ): Path? {
        if (!Files.isDirectory(parent)) return null
        val candidates = runCatching {
            Files.list(parent).use { stream ->
                stream.filter { Files.isDirectory(it) }
                    .filter { isExportDirName(it.fileName.toString()) }
                    .sorted(Comparator.reverseOrder<Path>())
                    .toList()
            }
        }.getOrNull() ?: return null

        val mode = verifyMode()
        for (dir in candidates) {
            val m = ExportManifest.read(dir.resolve(ExportManifest.FILE_NAME)) ?: continue
            if (!m.complete) continue
            if (m.namingTemplate != plan.namingTemplate) continue
            if (m.photoSetHash != photoSetHash) continue
            if (m.ledgerFingerprint != ledgerFingerprint) continue
            if (m.entries.size != plan.files.size) continue
            if (!m.entries.all { entryIntact(dir, it, mode) }) continue
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

    /** 目标集合指纹：相对路径 + **源文件大小**（大小变了说明素材被换过，不能续传）。 */
    private fun photoSetHash(files: List<ExportFile>): String {
        val sb = StringBuilder()
        files.sortedBy { it.relativePath }.forEach { f ->
            sb.append(f.relativePath).append('|').append(sizeOf(f.sourceRef)).append('\n')
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

    private fun sizeOf(path: String): Long =
        runCatching { Files.size(Paths.get(path)) }.getOrDefault(-1L)

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

    companion object {
        /**
         * 导出根名示例：`月日_时分秒`（如 `1101_203200`），同秒重复导出加 `_2`/`_3`。
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

        /** 兼容别名：实际名字由 [ExportPlan.ledgerFileName] 带入，见该字段的说明。 */
        const val LEDGER_NAME = ExportPlan.DEFAULT_LEDGER_NAME
        const val LOG_NAME = "export.log"

        /** Windows 单段长路径硬约束（留余量，不是 260 逼近线）。 */
        const val MAX_PATH_LENGTH = 240

        private const val BUFFER_BYTES = 32 * 1024

        private val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("MMdd_HHmmss")
        private val ISO_OFFSET: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")
    }
}
