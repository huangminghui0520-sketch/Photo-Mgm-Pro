// app/core/adapter/AndroidZipExportExecutor.kt —— 安卓导出**执行**器（app.port.ExportExecutor 的 ZIP 实现）
//
// ★ T11 提交②：本类是「导出计划上移、执行留平台」在安卓侧的落地。
//   在此之前安卓的导出是 `AlgorithmApi.exportZip(events, finalMap, unmatched, …)` ——
//   它内部又算了一遍「哪张照片进哪个文件夹、重名怎么加后缀、文件夹怎么编号」，
//   与共享层 `ExportPlanner` 是**第二份实现**（两份注释互相写着"与另一端一致"）。
//   现在计划由 `ExportUseCase` 交进来，本类只做三件事：
//     ① 按 `plan.files` 的顺序逐条写 ZIP 条目；
//     ② 按 `plan.ledger` 写台账 xlsx（并另存一份到私有目录，供「打开台账」）；
//     ③ 把成品复制到用户选的 SAF 树或应用私有目录。
//
// ★ 四处在搬动中**刻意改变**的行为（都不是顺手改的，理由逐条写在这里）：
//   1. **去掉断点续传状态文件**（原 `.export_state_<日期>.json`）。ZIP 文件名精确到秒
//      （`照片分类_20260814_131349.zip`），每次导出必定是新文件，状态文件里"上次已完成"
//      的判断只可能命中同一个秒内、同一批照片的重入 —— 而重入已被 ViewModel 的 CAS 门禁挡死。
//      留着它反而有害：状态文件按**巡查日期**命名、与输出位置解耦，换目录后命中跳过会让
//      调用方拿到 copied=0（零产出却提示"跳过已完成 N"），这正是 `ZipExporter` 里补过三次的坑。
//   2. **台账不计入 copied**。契约口径是"copied/skipped/failed 只数照片"（见 `ExportResult`），
//      旧实现把 extraFiles 也加进 copied，于是底栏写"成功 61"而实际 60 张照片 + 1 个表格。
//   3. **重名后缀从 `_2` 起**（旧 `ZipExporter` 是 `_1`）。这是 T09 统一后的规则
//      （`ExportPlanner.suffixed`），两端必须同源 —— 台账里「文件夹名/文件名」那一列是按它写的。
//   4. **export.log 写好但不进 ZIP**。PC 的日志落在导出目录里（用户能拿到）；安卓的 ZIP
//      是交付物，多一条日志会让"逐条对拍 ZIP 条目"永久多一条噪音。日志写应用私有目录，
//      排障时用 `adb pull` 取。
//   其余（ZIP 根结构、文件夹名、序号、"99_未分类"、32KB 流式、台账文件名带巡查日期）**不变**，
//   真机对拍基准就是沿用旧实现产出的 `照片分类_20260814_131349.zip`。
//
// ★ 不写 manifest：一次性 ZIP 没有跨次续传，manifest 没有消费方。`ExportResult.manifestFile`
//   回空串表示"本端不产出该文件"，语义见该字段注释。
package com.photomgm.app.core.adapter

import algorithm.AlgorithmApi
import algorithm.AppZone
import app.api.ExportBlockedException
import app.api.ExportFailure
import app.api.ExportFile
import app.api.ExportPlan
import app.api.ExportProgress
import app.api.ExportResult
import app.export.ExportLogWriter
import app.port.ExportExecutor
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.photomgm.data.image.BitmapImageEncoder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking

/**
 * @param context 任意 Context（内部只留 applicationContext）。
 * @param zone 业务时区（文件夹名、日志时间戳）；默认 [AppZone.ID]，与设备时区解耦。
 * @param clock 导出时刻（ZIP 名与台账名里的 `HHmmss`）。测试注入固定值。
 * @param imageEncoder 台账缩略图供给（参照 → JPEG 字节）。默认走 [BitmapImageEncoder]，
 *   测试可注入"永远返回 null"来验证「台账降级为只写文件名」不会连带把整次导出判失败。
 */
class AndroidZipExportExecutor(
    context: Context,
    private val zone: ZoneId = AppZone.ID,
    private val clock: () -> LocalDateTime = { LocalDateTime.now(zone) },
    private val imageEncoder: (String) -> ByteArray? = { ref ->
        runCatching { BitmapImageEncoder.process(context.applicationContext, ref) }.getOrNull()
    },
) : ExportExecutor {

    private val appContext = context.applicationContext

    override fun execute(
        plan: ExportPlan,
        target: String,
        onProgress: (ExportProgress) -> Unit,
        isCancelled: () -> Boolean,
    ): ExportResult {
        val startedNs = System.nanoTime()
        if (!plan.hasTargets) {
            // 空计划不是"成功导出 0 张"。共享层 `prepare` 已拦过一道，这里是执行器的自守：
            // 将来若有别的调用方直接调 execute，也不该产出一个只有台账的 ZIP。
            throw ExportBlockedException("没有可导出的照片：请先选择源目录与巡查日期并完成分析")
        }

        val stamp = clock()
        val zipName = "照片分类_${DATE.format(stamp)}_${TIME.format(stamp)}.zip"
        val tmpZip = File(appContext.cacheDir, zipName)
        val logFile = File(File(appContext.filesDir, "export"), LOG_NAME)
        val total = plan.files.size

        var copied = 0
        var failed = 0
        var cancelled = false
        val failures = mutableListOf<ExportFailure>()
        var ledgerBytes: ByteArray? = null

        try {
            ExportLogWriter(logFile.toPath(), zone).use { log ->
                log.start(plan.namingTemplate, plan.files.size, plan.includeLedger)
                plan.renames.forEach { r -> log.rename(r.originalName, r.toRelativePath) }

                FileOutputStream(tmpZip).use { fos ->
                    ZipOutputStream(fos.buffered()).use { zip ->
                        // ── 照片条目（先开源流再 putNextEntry：源缺失时不留半截条目） ──
                        for (file in plan.files) {
                            if (isCancelled()) {
                                cancelled = true
                                break
                            }
                            val fileStartNs = System.nanoTime()
                            var opened: InputStream? = null
                            try {
                                opened = openSource(file)
                                zip.putNextEntry(ZipEntry(file.relativePath))
                                val bytes = opened.use { ins -> streamTo(ins, zip) }
                                zip.closeEntry()
                                copied++
                                log.copy(file.relativePath, bytes, (System.nanoTime() - fileStartNs) / 1_000_000)
                            } catch (e: Exception) {   // ci-ok: 执行器是阻塞端口，本 try 内无挂起点；取消只经 isCancelled 在文件边界查询
                                failed++
                                val reason = (e.message ?: e.javaClass.simpleName).take(120)
                                failures += ExportFailure(file.relativePath, reason)
                                log.failed(file.relativePath, reason)
                                // putNextEntry 之后失败 → 条目已半截，必须先收尾，
                                // 否则下一个 putNextEntry 会把它当"还没关的条目"继续往里写。
                                runCatching { zip.closeEntry() }
                            } finally {
                                runCatching { opened?.close() }
                            }
                            onProgress(
                                ExportProgress(
                                    stage = "打包照片",
                                    copied = copied + failed,
                                    total = total,
                                    currentFile = file.relativePath.substringAfterLast('/'),
                                ),
                            )
                        }

                        // ── 台账（最后写：内容依赖全部照片，失败也不该影响照片已导出） ──
                        if (plan.includeLedger && !cancelled) {
                            onProgress(ExportProgress("生成台账", copied + failed, total, plan.ledgerFileName))
                            ledgerBytes = runCatching { buildLedgerXlsx(plan, isCancelled) }
                                .onFailure { Log.w(TAG, "台账生成失败（导出继续，照片不受影响）", it) }
                                .getOrNull()
                            if (ledgerBytes != null) {
                                // 提成局部 val：`ledgerBytes` 是会被 catch 分支改写的可变捕获变量，
                                // 直接用会拿不到 smart cast（编译期无法证明它在这段里没被改过）
                                val bytes = ledgerBytes!!
                                val entryStartNs = System.nanoTime()
                                try {
                                    zip.putNextEntry(ZipEntry(plan.ledgerFileName))
                                    zip.write(bytes)
                                    zip.closeEntry()
                                    log.copy(
                                        plan.ledgerFileName,
                                        bytes.size.toLong(),
                                        (System.nanoTime() - entryStartNs) / 1_000_000,
                                    )
                                    // ★ 不计入 copied：copied/skipped/failed 是"照片"口径
                                    //   （混算会让"成功 N"把表格算成一张照片，见类头注释第 2 条）
                                } catch (e: Exception) {   // ci-ok: 同照片段 —— 阻塞端口无挂起点，台账写失败不影响已写出的照片
                                    val reason = (e.message ?: e.javaClass.simpleName).take(120)
                                    failures += ExportFailure(plan.ledgerFileName, reason)
                                    log.failed(plan.ledgerFileName, reason)
                                    runCatching { zip.closeEntry() }
                                    ledgerBytes = null
                                }
                            }
                        }

                        val skipped = (total - copied - failed).coerceAtLeast(0)
                        log.end(
                            total = total,
                            success = copied,
                            skipped = skipped,
                            failed = failed,
                            cancelled = cancelled,
                            durationMs = (System.nanoTime() - startedNs) / 1_000_000,
                        )
                    }
                }
            }

            // ── 落地：取消 = 不交付半截包；否则复制到 SAF 树或私有目录 ─────────────
            val shown: String? = when {
                cancelled -> null
                copied == 0 && failed == 0 -> null
                else -> copyToOutput(tmpZip, zipName, target)
            }
            if (!cancelled && shown == null && (copied > 0 || failed > 0)) {
                throw ExportBlockedException(
                    "输出目录不可写：$target。请在「设置」页重新选择输出目录后再导出",
                )
            }

            // 台账另存一份到私有目录（ZIP 内的那份不便用 ACTION_VIEW 直接打开）
            val ledgerCachePath = ledgerBytes?.let { bytes ->
                runCatching {
                    val dir = File(appContext.getExternalFilesDir(null), "export").apply { mkdirs() }
                    File(dir, plan.ledgerFileName).apply { writeBytes(bytes) }.absolutePath
                }.getOrNull()
            }

            return ExportResult(
                exportDir = shown ?: target,
                ledgerFile = ledgerCachePath,
                logFile = logFile.absolutePath,
                manifestFile = "",
                totalTargets = total,
                copied = copied,
                skipped = (total - copied - failed).coerceAtLeast(0),
                failed = failed,
                failures = failures,
                durationMs = (System.nanoTime() - startedNs) / 1_000_000,
                cancelled = cancelled,
                resumed = false,
                warnings = plan.warnings,
            )
        } finally {
            // tmpZip 可能含全部原图（几十 MB），任何出口都不许留在缓存目录里
            runCatching { tmpZip.delete() }
        }
    }

    // ── 源字节 ──────────────────────────────────────────────────────────────

    /**
     * 打开一张照片的源字节。
     *
     * 三条路径按可靠性排序，**不是**防御性代码：
     *   1. `sourceRef` 本身就是 `content://`/`file://` → 直接交给 ContentResolver；
     *   2. 物理路径且文件可读 → `FileInputStream`。真机实测 MediaStore 的 DATA
     *      路径在已授予 READ_MEDIA_IMAGES 时可直接读，这是主路径；
     *   3. 物理路径不可读（分区存储收紧 / 文件被移动）→ 用计划里的 `photoId` 拼
     *      `content://media/external/images/media/<id>` 再试。计划带着 photoId 正是为了这一手：
     *      否则会因为"路径失效"把整批照片报成失败，而它们其实都还在库里。
     */
    private fun openSource(file: ExportFile): InputStream {
        val ref = file.sourceRef
        if (ref.startsWith("content://") || ref.startsWith("file://")) {
            return appContext.contentResolver.openInputStream(Uri.parse(ref))
                ?: throw java.io.IOException("无法读取：${ref.take(80)}")
        }
        val direct = File(ref)
        if (direct.isFile && direct.canRead()) return FileInputStream(direct)
        if (file.photoId > 0) {
            val uri = ContentUris.withAppendedId(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, file.photoId,
            )
            return appContext.contentResolver.openInputStream(uri)
                ?: throw java.io.IOException("照片不可读：${ref.substringAfterLast('/')}（id=${file.photoId}）")
        }
        throw java.io.IOException("照片不存在：${ref.take(80)}")
    }

    /** 32KB 流式转发，返回写入字节数（写进 export.log 的 `bytes` 字段）。 */
    private fun streamTo(ins: InputStream, out: ZipOutputStream): Long {
        var size = 0L
        val buf = ByteArray(BUFFER_BYTES)
        while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            if (n == 0) continue
            out.write(buf, 0, n)
            size += n
        }
        return size
    }

    // ── 台账 ────────────────────────────────────────────────────────────────

    /**
     * 生成台账 xlsx 字节。
     *
     * ★ 缩略图并行解码（沿用旧实现的 4 路 async + 固定并发）：
     *   `imageEncoder` 内部是一次完整解码 + 裁剪 + 压缩，串行做 60 张要好几秒。
     *   `Dispatchers.Default` 的线程数即自然限流，不额外建线程池。
     *
     * ★ 这里用 `runBlocking` 而不是 suspend：端口契约是**阻塞**的（见 `ExportExecutor`），
     *   而并行解码需要结构化并发。`runBlocking` 只在导出线程上跑（ViewModel 已放在 IO 调度器），
     *   不会阻塞主线程。
     */
    private fun buildLedgerXlsx(plan: ExportPlan, isCancelled: () -> Boolean): ByteArray? {
        val rows = plan.ledger
        if (rows.isEmpty()) return null
        val refs = rows.flatMap { listOfNotNull(it.photo1Ref, it.photo2Ref) }.distinct()
        val cache = ConcurrentHashMap<String, ByteArray?>()
        if (refs.isNotEmpty()) {
            runBlocking {
                coroutineScope {
                    refs.map { ref ->
                        async(Dispatchers.Default) {
                            if (isCancelled()) return@async
                            cache[ref] = imageEncoder(ref)
                        }
                    }.awaitAll()
                }
            }
            Log.d(TAG, "台账缩略图 ${cache.count { it.value != null }}/${refs.size} 成功")
        }
        val bos = ByteArrayOutputStream()
        AlgorithmApi.exportLedgerXlsx(rows, bos) { ref -> if (ref == null) null else cache[ref] }
        return bos.toByteArray().takeIf { it.isNotEmpty() }
    }

    // ── 输出落地 ────────────────────────────────────────────────────────────

    /**
     * 把临时 ZIP 复制到用户选的输出位置。
     *
     * @return 用户可见位置（SAF 的 `content://` 文档 URI 或本地绝对路径）。
     *   返回 null = 目标不可写 —— 调用方据此抛 [ExportBlockedException]，
     *   不能因为"没抛异常"就报成功（旧实现踩过：提示导出完成，输出目录里什么都没有）。
     */
    private fun copyToOutput(tmp: File, fileName: String, target: String): String? {
        if (target.startsWith("content://")) {
            val parent = runCatching {
                DocumentFile.fromTreeUri(appContext, Uri.parse(target))
            }.getOrNull() ?: return null
            // findFile 命中会复用同名文档：ZIP 名精确到秒，同秒重入已被门禁挡死，
            // 这里复用是"第二次点导出覆盖第一次"的正确语义（用户要的是最新那份）。
            val doc = parent.findFile(fileName)
                ?: parent.createFile("application/zip", fileName)
                ?: return null
            val os = runCatching { appContext.contentResolver.openOutputStream(doc.uri) }.getOrNull()
                ?: return null
            return runCatching {
                os.use { out -> tmp.inputStream().use { it.copyTo(out) } }
                doc.uri.toString()
            }.getOrNull()
        }
        val dir = if (target.isNotBlank()) File(target) else defaultOutDir()
        if (!dir.exists() && !dir.mkdirs()) return null
        if (!dir.isDirectory || !dir.canWrite()) return null
        val dest = File(dir, fileName)
        return runCatching {
            dest.outputStream().use { out -> tmp.inputStream().use { it.copyTo(out) } }
            dest.absolutePath
        }.getOrNull()
    }

    private fun defaultOutDir(): File =
        File(appContext.getExternalFilesDir(null), "export").apply { mkdirs() }

    companion object {
        private const val TAG = "PM_Export"
        private const val LOG_NAME = "export.log"
        private const val BUFFER_BYTES = 32 * 1024
        private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HHmmss")
    }
}
