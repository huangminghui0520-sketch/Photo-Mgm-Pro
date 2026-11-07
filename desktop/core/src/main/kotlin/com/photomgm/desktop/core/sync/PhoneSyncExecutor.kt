// desktop/core/sync/PhoneSyncExecutor.kt —— 一次手机导入的完整链路
//
// 顺序（指令 §2.2）：解析连接 → 建子文件夹 → 读状态与冲突检测 → 按文件名粗筛区间外照片 →
// 分批拉临时暂存区 → 按 EXIF 优先 / mtime 兜底筛选 → SyncDecider 逐文件判定 → 进度 → 取消 → 更新状态。
//
// ★ 三条不可动摇的边界：
//   ① 筛选分两级：pull 前只按**文件名**粗筛（解析不出时间一律放行），pull 后由 EXIF 终判；
//      **绝不按手机侧 mtime 直接判断**（ADB pull 会改写 mtime，且 mtime 与拍摄时间可能不一致，
//      用它排除会误杀 —— F3「EXIF 优先于 mtime」用例就是这个坑）；
//   ② 一次同步锁一份 [PhoneSource]（不混用 ADB 与盘符）；
//   ③ 取消只在**文件边界**停下，已写入文件与 sync-state.json 都保留。
//
// ★ 所有 IO 能力都经构造参数注入：单测用 @TempDir 与假件跑全链路，不依赖真机、不碰网络。
package com.photomgm.desktop.core.sync

import algorithm.AppZone
import domain.sync.SyncRangeFolder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Comparator

internal data class PhoneSyncJob(
    val sourceDir: String,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val phoneDir: String,
    val mode: ConnectionMode,
    // 用户已确认「另一目录 / 跨年同名」冲突，允许沿用旧记录。
    val confirmConflict: Boolean = false,
)

internal data class PhoneSyncProgress(
    val done: Int,
    val total: Int,
    val written: Int,
    val skipped: Int,
    val renamed: Int,
    val current: String,
    val connection: ConnectionKind,
)

internal data class PhoneSyncOutcome(
    val folderName: String,
    val targetDir: Path?,
    val connection: ConnectionKind,
    val written: Int = 0,
    val skipped: Int = 0,
    val rewritten: Int = 0,
    val renamed: Int = 0,
    val filtered: Int = 0,
    val failed: Int = 0,
    val cancelled: Boolean = false,
    // 需要用户确认才能继续（另一目录 / 跨年同名）；不静默合并。
    val conflict: String? = null,
    val error: String? = null,
    val logs: List<String> = emptyList(),
)

internal class PhoneSyncExecutor(
    private val hasher: (Path) -> String?,
    private val timeOf: (Path, RemotePhoto) -> Long?,
    private val sourceFactory: (ConnectionKind, String) -> PhoneSource?,
    private val probe: () -> ConnectionProbe,
    private val stagingDir: () -> Path,
    private val nowText: () -> String,
    private val normalize: (String) -> String = { it },
    // 批拉大小：一次 adb pull 拉多少个文件。单次 adb 进程启动约 300 ms，是真正的瓶颈；
    // 50 只是折中（峰值暂存占用 ≈ 50 张照片），可按实测调整。
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
    private val log: (String) -> Unit = {},
) {

    fun run(
        job: PhoneSyncJob,
        isCancelled: () -> Boolean,
        onProgress: (PhoneSyncProgress) -> Unit,
    ): PhoneSyncOutcome {
        val logs = ArrayList<String>()
        fun note(text: String) {
            logs += text
            log(text)
        }

        // ① 连接：启动时解析一次，整批锁定（不混用）。
        val connection = PhoneConnection.resolve(job.mode, probe())
        if (connection == ConnectionKind.NONE) {
            return PhoneSyncOutcome(
                folderName = "", targetDir = null, connection = connection,
                error = PhoneConnection.failureText(connection),
            )
        }

        // ② 区间合法性与子文件夹名（domain 纯函数，确定性）。
        val rangeError = SyncRangeFolder.validateRange(job.start, job.end)
        if (rangeError != null) {
            return PhoneSyncOutcome("", null, connection, error = rangeError)
        }
        val folderName = SyncRangeFolder.folderName(job.start, job.end)

        // ③ 子文件夹：已存在不重建、不清空；创建失败明确报错且不开始传输。
        val target = runCatching {
            Paths.get(normalize(job.sourceDir)).resolve(folderName)
        }.getOrElse {
            return PhoneSyncOutcome(folderName, null, connection, error = "源目录路径无效：${job.sourceDir}")
        }
        val created = runCatching {
            if (!Files.isDirectory(target)) Files.createDirectories(target)
            true
        }.getOrDefault(false)
        if (!created || !Files.isDirectory(target)) {
            return PhoneSyncOutcome(
                folderName, target, connection,
                error = "无法创建子文件夹：$target（权限不足、磁盘已满或路径不可写），未开始传输。",
            )
        }

        // ④ 数据源与状态冲突检测（不静默合并 / 不静默续传）。
        val source = sourceFactory(connection, job.phoneDir)
            ?: return PhoneSyncOutcome(
                folderName, target, connection,
                error = PhoneConnection.failureText(connection),
            )
        val existing = SyncState.read(target)
        val conflict = SyncStateGuard.conflict(
            existing, source.id, job.start, job.end, job.confirmConflict,
        )
        if (conflict != null) {
            return PhoneSyncOutcome(
                folderName, target, connection,
                conflict = SyncStateGuard.prompt(conflict, existing, source.id),
            )
        }

        // ⑤ 临时暂存区：先把候选文件拉到本地，再判定时间与内容。
        val staging = runCatching { stagingDir().also { Files.createDirectories(it) } }
            .getOrElse {
                return PhoneSyncOutcome(
                    folderName, target, connection,
                    error = "无法创建临时暂存区：${it.message}", logs = logs,
                )
            }
        val photos = runCatching { source.list() }.getOrDefault(emptyList())
        val occupied = HashSet<String>()
        runCatching {
            Files.list(target).use { s -> s.forEach { occupied += it.fileName.toString() } }
        }

        var written = 0
        var skipped = 0
        var rewritten = 0
        var renamed = 0
        var filtered = 0
        var failed = 0
        var cancelled = false
        val records = LinkedHashMap<String, SyncFileRecord>()
        existing?.files?.forEach { records[it.name] = it }

        // 结束分钟按「整分钟包含」处理：23:59 表示 23:59:59，而不是 23:59:00。
        val endExclusive = job.end.plusMinutes(1)

        // ⑥ pull 前粗筛 + 批拉：文件名里带 yyyyMMdd_HHmmss 且落在区间外的照片直接不拉；
        // 解析不出时间的一律放行 → 交给后面的 EXIF 终判，所以 F3「EXIF 优先于 mtime」不会被误杀。
        val startMs = job.start.atZone(AppZone.ID).toInstant().toEpochMilli()
        val endExclusiveMs = endExclusive.atZone(AppZone.ID).toInstant().toEpochMilli()
        val candidates = photos.filter { shouldPull(it, startMs, endExclusiveMs) }
        filtered += photos.size - candidates.size

        val batch = batchSize.coerceAtLeast(1)
        // 粗筛跳过的同样算「已处理」，进度总数仍是 photos.size。
        var done = photos.size - candidates.size
        for ((i, photo) in candidates.withIndex()) {
            // ⑦ 取消：只在文件边界停下，已经写入的文件与其记录都保留。
            if (isCancelled()) {
                cancelled = true
                note("已取消：保留已写入的 $written 张，可稍后继续。")
                break
            }
            val batchStart = i / batch * batch
            if (i == batchStart) {
                // 批首：先清掉上一批的暂存（峰值占用 ≈ 一批，不是整批照片），再把这一批一次拉下来。
                for (k in maxOf(0, batchStart - batch) until batchStart) {
                    runCatching { Files.deleteIfExists(staging.resolve(candidates[k].name)) }
                }
                source.pullMany(candidates.subList(batchStart, minOf(batchStart + batch, candidates.size)), staging)
            }
            done++
            val staged = staging.resolve(photo.name)
            // 批拉的单文件成败不看退出码（实测一批里缺一个文件时 RC=1，同批其余仍照拉），
            // 只认「暂存区里到底有没有这个文件」。
            if (!runCatching { Files.isRegularFile(staged) }.getOrDefault(false)) {
                failed++
                note("拉取失败：${photo.name}")
                onProgress(PhoneSyncProgress(done, photos.size, written, skipped, renamed, photo.name, connection))
                continue
            }

            // ⑧ 时间筛选：EXIF DateTimeOriginal 优先，mtime 兜底；两者都无则跳过。
            val ms = runCatching { timeOf(staged, photo) }.getOrNull()
            if (ms == null) {
                filtered++
                note("无拍摄时间，跳过：${photo.name}")
                onProgress(PhoneSyncProgress(done, photos.size, written, skipped, renamed, photo.name, connection))
                continue
            }
            val wall = Instant.ofEpochMilli(ms).atZone(AppZone.ID).toLocalDateTime()
            if (wall.isBefore(job.start) || !wall.isBefore(endExclusive)) {
                filtered++
                onProgress(PhoneSyncProgress(done, photos.size, written, skipped, renamed, photo.name, connection))
                continue
            }

            // ⑨ 判据：先比大小，大小相同才算哈希；结果落到目标子文件夹。
            val dest = target.resolve(photo.name)
            val incomingSize = runCatching { Files.size(staged) }.getOrDefault(photo.size)
            val existingSize = if (Files.isRegularFile(dest)) {
                runCatching { Files.size(dest) }.getOrNull()
            } else null
            var incomingSha: String? = null
            val decision = SyncDecider.decide(
                name = photo.name,
                incomingSize = incomingSize,
                existingSize = existingSize,
                incomingHash = { incomingSha = hasher(staged); incomingSha },
                existingHash = { if (existingSize == null) null else hasher(dest) },
                occupied = occupied,
            )
            val finalName = decision.targetName
            val finalDest = target.resolve(finalName)
            val applied = runCatching {
                when (decision.action) {
                    SyncAction.SKIP -> true
                    SyncAction.REWRITE -> {
                        Files.deleteIfExists(dest)
                        Files.copy(staged, dest, StandardCopyOption.REPLACE_EXISTING)
                        true
                    }
                    SyncAction.COPY -> {
                        Files.copy(staged, finalDest)
                        true
                    }
                    SyncAction.RENAME -> {
                        Files.copy(staged, finalDest)
                        true
                    }
                }
            }.getOrDefault(false)

            if (!applied) {
                failed++
                note("写入失败：$finalName")
            } else {
                when (decision.action) {
                    SyncAction.SKIP -> skipped++
                    SyncAction.REWRITE -> { rewritten++; written++ }
                    SyncAction.COPY -> written++
                    SyncAction.RENAME -> {
                        renamed++
                        written++
                        note("同名不同内容，不覆盖，另存为 $finalName")
                    }
                }
                occupied += finalName
                records[finalName] = SyncFileRecord(
                    name = finalName,
                    size = incomingSize,
                    sha256 = incomingSha ?: (if (decision.action == SyncAction.SKIP) {
                        existing?.files?.firstOrNull { it.name == finalName }?.sha256.orEmpty()
                    } else hasher(finalDest).orEmpty()),
                )
            }
            onProgress(PhoneSyncProgress(done, photos.size, written, skipped, renamed, photo.name, connection))
        }

        // ⑩ 状态：正常完成与取消都写（取消时 complete=false，但记录保留 → 可续传）。
        SyncState.write(
            target,
            SyncState(
                range = SyncState.stamp(job.start, job.end),
                phoneDir = source.id,
                files = records.values.toList(),
                updatedAt = nowText(),
                complete = !cancelled && failed == 0,
            ),
        )

        // ⑪ 清理暂存区（只删本次创建的目录，失败只记日志，不影响产物）。
        runCatching {
            Files.walk(staging).use { s ->
                s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }.onFailure { note("暂存区清理失败：${it.message}") }

        return PhoneSyncOutcome(
            folderName = folderName,
            targetDir = target,
            connection = connection,
            written = written,
            skipped = skipped,
            rewritten = rewritten,
            renamed = renamed,
            filtered = filtered,
            failed = failed,
            cancelled = cancelled,
            logs = logs,
        )
    }

    // 文件名优先：解析得出时间就按它粗筛，解析不出就放行（交给 ⑧ 的 EXIF 终判）。
    // 刻意不看 mtime：mtime 与拍摄时间可能不一致（换机拷贝、备份恢复、第三方导出），
    // 用它排除会把「EXIF 在区间内」的照片误杀 —— F3 用例锁的就是这条。
    private fun shouldPull(photo: RemotePhoto, startMs: Long, endExclusiveMs: Long): Boolean {
        val nameTime = parseTimeFromFileName(photo.name) ?: return true
        return nameTime in startMs until endExclusiveMs
    }

    // 从文件名解析 `yyyyMMdd_HHmmss`，失败返回 null（不抛异常）。
    // 派生后缀（如 _original）不影响：只取第一处「8 位日期_6 位数字」。
    private fun parseTimeFromFileName(name: String): Long? {
        val match = FILE_TIME.find(name) ?: return null
        return try {
            val date = LocalDate.parse(match.groupValues[1], DateTimeFormatter.BASIC_ISO_DATE)
            val time = LocalTime.parse(match.groupValues[2], TIME_HHMMSS)
            ZonedDateTime.of(date, time, AppZone.ID).toInstant().toEpochMilli()
        } catch (e: DateTimeException) {
            null
        }
    }

    private companion object {
        // 批拉默认大小（构造参数可覆盖）。
        const val DEFAULT_BATCH_SIZE = 50

        // 8 位日期 + 下划线 + 6 位时间；后面跟派生后缀也能匹配。
        val FILE_TIME = Regex("""(\d{8})_(\d{6})""")
        val TIME_HHMMSS: DateTimeFormatter = DateTimeFormatter.ofPattern("HHmmss")
    }
}
