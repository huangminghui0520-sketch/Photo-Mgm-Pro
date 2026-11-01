// desktop/cli/Main.kt —— 无头命令行入口（逻辑核心的第一个消费者）
//
// 用法（示例）：
//   gradlew :cli:run --args="--source D:/巡查照片 --date 2026-10-30 --log D:/巡查日志.txt --out D:/导出"
//
// 设计约束：
//   · 本文件不 import 任何 UI/AWT 窗口类，只依赖 api 门面 —— 与未来的 UI 是平级消费者；
//   · 退出码即验收入口：0 = 成功，1 = 业务失败，2 = 参数/环境错误（便于脚本与 CI 断言）；
//   · 所有中文输出与术语沿用 Android 端（事件 / 未匹配照片 / 台账 / 三态去重）。
package com.photomgm.desktop.cli

import algorithm.AppZone
import algorithm.DedupKeepMode
import com.photomgm.desktop.core.api.AppState
import com.photomgm.desktop.core.api.CoreStatus
import com.photomgm.desktop.core.api.ExportListener
import com.photomgm.desktop.core.api.ExportProgress
import com.photomgm.desktop.core.api.ExportRequest
import com.photomgm.desktop.core.api.ExportResult
import com.photomgm.desktop.core.api.PhotoMgmCore
import com.photomgm.desktop.core.api.PhotoMgmCoreFactory
import com.photomgm.desktop.core.api.Progress
import com.photomgm.desktop.core.api.ProgressListener
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

private const val EXIT_OK = 0
private const val EXIT_FAILED = 1
private const val EXIT_USAGE = 2

fun main(args: Array<String>) {
    forceUtf8Output()
    val opts = try {
        Options.parse(args)
    } catch (e: IllegalArgumentException) {
        System.err.println("参数错误：${e.message}")
        printUsage()
        exitProcess(EXIT_USAGE)
    }
    if (opts.help) {
        printUsage()
        exitProcess(EXIT_OK)
    }

    val exit = runCatching { Runner(opts).run() }.getOrElse { t ->
        System.err.println("未预期的异常：${t.stackTraceToString()}")
        EXIT_FAILED
    }
    exitProcess(exit)
}

// ─────────────────────────────────────────────────────────────────────────────
// 输出编码
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 强制 stdout/stderr 为 UTF-8。
 *
 * 为什么不能只靠 JVM 参数：JDK 17 里 `System.out` 的字符集取 `sun.stdout.encoding`，
 * 该属性在部分 JDK 17 发行版与 Gradle JavaExec 下不生效，中文会按 GBK 写出，
 * 重定向到文件后就成了乱码（实测踩过）。这里直接重建 PrintStream，绕开 JDK 默认值。
 *
 * 副作用：在 cmd.exe 默认 GBK 代码页直接运行时仍会显示乱码 —— 先执行 `chcp 65001`；
 * Windows Terminal / PowerShell 7 / 管道重定向均为 UTF-8，无需额外处理。
 */
private fun forceUtf8Output() {
    runCatching {
        System.setOut(PrintStream(FileOutputStream(FileDescriptor.out), true, "UTF-8"))
        System.setErr(PrintStream(FileOutputStream(FileDescriptor.err), true, "UTF-8"))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 参数
// ─────────────────────────────────────────────────────────────────────────────

private class Options(
    val sources: List<String>,
    val patrolDate: LocalDate,
    val logFile: Path?,
    val logText: String?,
    val outputDir: Path?,
    val template: Int,
    val dedup: DedupKeepMode,
    val includeLedger: Boolean,
    val dataDir: Path?,
    val dryRun: Boolean,
    val help: Boolean,
) {
    companion object {
        fun parse(args: Array<String>): Options {
            val sources = mutableListOf<String>()
            var date = LocalDate.now(AppZone.ID)
            var logFile: Path? = null
            var logText: String? = null
            var out: Path? = null
            var template = 1
            var dedup = DedupKeepMode.KEEP_WATERMARKED
            var includeLedger = true
            var dataDir: Path? = null
            var dryRun = false
            var help = false

            fun value(i: Int, name: String): String {
                require(i + 1 < args.size) { "$name 需要一个值" }
                return args[i + 1]
            }

            // ★ 安全路径解析：JDK 17 在 Windows 下对超过 260 字符的路径，
            //   Paths.get 会抛 InvalidPathException("Illegal character [?]...")（WindowsPathParser
            //   加 \\?\ 长路径前缀后又把 ? 判非法）。这里捕获并转成人类可读的明确提示，
            //   避免用户看到"参数错误：Illegal character [?]"这种天书（C3 长路径边界实测）。
            fun path(i: Int, name: String): Path = try {
                Paths.get(value(i, name))
            } catch (e: Exception) {
                val v = value(i, name)
                val reason = if (v.length > 260) "路径过长（超过 Windows 260 字符上限，请缩短目录层级）"
                else "含非法字符"
                throw IllegalArgumentException("$name 路径无效（$reason）：$v")
            }

            var i = 0
            while (i < args.size) {
                when (val a = args[i]) {
                    "--source", "-s" -> { sources += value(i, a); i += 2 }
                    "--date", "-d" -> { date = LocalDate.parse(value(i, a)); i += 2 }
                    "--log", "-l" -> { logFile = path(i, a); i += 2 }
                    "--log-text" -> { logText = value(i, a); i += 2 }
                    "--out", "-o" -> { out = path(i, a); i += 2 }
                    "--template", "-t" -> { template = value(i, a).toInt(); i += 2 }
                    "--dedup" -> {
                        dedup = when (value(i, a).lowercase()) {
                            "keep-watermarked", "watermarked", "0" -> DedupKeepMode.KEEP_WATERMARKED
                            "keep-plain", "plain", "1" -> DedupKeepMode.KEEP_PLAIN
                            "keep-both", "both", "2" -> DedupKeepMode.KEEP_BOTH
                            else -> throw IllegalArgumentException("--dedup 只能是 keep-watermarked / keep-plain / keep-both")
                        }
                        i += 2
                    }
                    "--no-ledger" -> { includeLedger = false; i++ }
                    "--data-dir" -> { dataDir = path(i, a); i += 2 }
                    "--dry-run" -> { dryRun = true; i++ }
                    "--help", "-h" -> { help = true; i++ }
                    else -> throw IllegalArgumentException("未知参数 $a")
                }
            }
            require(template in 0..1) { "--template 只能是 0 或 1" }
            require(date != null)
            return Options(sources, date, logFile, logText, out, template, dedup, includeLedger, dataDir, dryRun, help)
        }
    }
}

private fun printUsage() {
    println(
        """
        Photo-Mgm Windows 逻辑核心（无头模式）

        用法：
          gradlew :cli:run --args="--source <照片目录> [--source ...] --date 2026-10-30 --log <日志.txt> --out <导出目录>"

        参数：
          --source, -s     照片源目录（可重复，多个目录取并集）
          --date, -d       巡查日期 yyyy-MM-dd（默认今天，按北京时间）
          --log, -l        巡查日志文本文件（UTF-8 / GBK 自动识别）
          --log-text       直接传日志文本（与 --log 二选一）
          --out, -o        导出父目录（导出根建在其下；--dry-run 时可省略）
          --template, -t   命名模板 0=事件类型 / 1=序号+巡查日志内容（默认 1）
          --dedup          三态去重 keep-watermarked（默认）/ keep-plain / keep-both
          --no-ledger      不生成台账 xlsx（仅复制照片）
          --data-dir       数据目录（默认 %APPDATA%\PhotoMgm，测试用临时目录）
          --dry-run        只分析不导出
          --help, -h       显示本帮助

        示例：
          gradlew :cli:run --args="-s D:/巡查照片 -d 2026-10-30 -l D:/日志.txt -o D:/导出"
        """.trimIndent(),
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// 执行
// ─────────────────────────────────────────────────────────────────────────────

private class Runner(private val opts: Options) {

    fun run(): Int {
        opts.dataDir?.let { Files.createDirectories(it) }

        val core = PhotoMgmCoreFactory.create(
            com.photomgm.desktop.core.api.CoreConfig(
                dataDir = opts.dataDir,
                zone = AppZone.ID,
            ),
        )
        core.use {
            // ── 日志文本 ──────────────────────────────────────────────────
            val text = when {
                opts.logText != null -> opts.logText
                opts.logFile != null -> {
                    if (!Files.isRegularFile(opts.logFile)) return usageError("日志文件不存在：${opts.logFile}")
                    core.readLogFile(opts.logFile)
                }
                else -> return usageError("必须提供 --log 或 --log-text")
            }
            core.setLogText(text)

            // ── 设置 ──────────────────────────────────────────────────────
            val normalizedSources = opts.sources.map { it.replace('\\', '/').trimEnd('/') }
            normalizedSources.forEach {
                // ★ C3 长路径边界：超长路径下 Paths.get 抛 InvalidPathException，这里捕获转成明确提示，
                //   而非让用户看到 "Illegal character [?]" 天书。源目录不存在/无效都走统一 usageError。
                val isDir = runCatching { Files.isDirectory(Paths.get(it)) }.getOrElse { false }
                if (!isDir) return usageError("照片源目录不存在或路径无效（过长）：$it")
            }
            // ★ --dry-run 只分析不导出：--out 可选（不校验、不创建输出目录）。
            val outDir = if (opts.dryRun) {
                null
            } else {
                opts.outputDir ?: return usageError("必须提供 --out")
            }
            outDir?.let { Files.createDirectories(it) }

            core.updateSettings {
                it.copy(
                    sourceDirs = normalizedSources,
                    outputDir = outDir?.toAbsolutePath()?.toString()?.replace('\\', '/'),
                    patrolDate = opts.patrolDate,
                    namingTemplate = opts.template,
                    dedupMode = opts.dedup,
                )
            }

            // ── 分析 ──────────────────────────────────────────────────────
            println("▶ 分析中：日志 ${text.length} 字，源目录 ${normalizedSources.size} 个，巡查日期 ${opts.patrolDate}")
            val analyzed = analyzeAndWait(core)
            if (analyzed != EXIT_OK) return analyzed

            val st = core.state.value
            val parsed = st.parsed
            val ana = st.analysis
            println("  解析事件 ${parsed?.eventCount ?: 0} 条，跳过行 ${parsed?.skippedLineCount ?: 0}")
            println(
                "  照片 候选 ${ana?.candidateCount ?: 0} / 有效 ${ana?.photoCount ?: 0} / " +
                    "GPS 簇 ${ana?.clusterCount ?: 0} / 未匹配 ${ana?.unmatchedCount ?: 0}",
            )
            println(
                "  派生合并 ${ana?.mergedDerivedGroups ?: 0} 组，HEIC ${ana?.heicCount ?: 0} 张，" +
                    "无 GPS ${ana?.noGpsCount ?: 0} 张",
            )
            st.message?.let { println("  提示：$it") }
            st.error?.let { System.err.println("  错误：$it") }

            printEvents(st)

            if (opts.dryRun) {
                println("✔ 干跑结束（未导出）")
                return EXIT_OK
            }

            // ── 导出 ──────────────────────────────────────────────────────
            println("▶ 导出中…")
            val result = exportAndWait(core)
            println("  导出目录：${result.exportDir}")
            println(
                "  照片目标 ${result.totalTargets} 张：复制 ${result.copied}，跳过 ${result.skipped}，" +
                    "失败 ${result.failed}，耗时 ${result.durationMs}ms" +
                    if (result.resumed) "（命中续传，未重复复制）" else "",
            )
            result.ledgerFile?.let { println("  台账：$it") }
            println("  日志：${result.logFile}")
            println("  清单：${result.manifestFile}")
            result.warnings.take(10).forEach { println("  ⚠ $it") }
            result.failures.take(20).forEach { println("  ✘ ${it.relativePath} —— ${it.reason}") }
            if (result.failures.size > 20) println("  … 另有 ${result.failures.size - 20} 条失败，详见 export.log")

            // ── 自检：报告与实物必须自洽（命令行也是验收器） ──────────────
            // 注意：产物目录里除照片外还有 ledger.xlsx / export.log / export-manifest.json，
            //   必须按"导出根直属元文件"精确排除，否则会把清单算成照片（这里曾被 "export." 前缀坑过）。
            val metaNames = setOf("ledger.xlsx", "export.log", "export-manifest.json")
            val photoCount = Files.walk(result.exportDir).use { s ->
                s.filter { Files.isRegularFile(it) }
                    .filter { it.fileName.toString() !in metaNames }
                    .count().toInt()
            }
            val expected = result.totalTargets
            println("  自检：产物照片 $photoCount 张 / 目标 $expected 张 " + if (photoCount == expected) "✔" else "✘")
            if (result.failed > 0 || photoCount != expected) {
                System.err.println("✘ 导出不完整（失败 ${result.failed} 条，照片数不一致）")
                return EXIT_FAILED
            }
            println("✔ 完成")
            return EXIT_OK
        }
    }

    private fun analyzeAndWait(core: PhotoMgmCore): Int {
        val handle = core.analyze(object : ProgressListener {
            override fun onProgress(progress: Progress) {
                if (progress.total > 0) print("\r  ${progress.stage} ${progress.current}/${progress.total}   ")
            }
        })
        val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(60)
        while (System.nanoTime() < deadline) {
            val s = core.state.value.status
            if (s == CoreStatus.READY || s == CoreStatus.FAILED || s == CoreStatus.CLOSED) break
            if (handle.isCancelled) break
            Thread.sleep(80)
        }
        println()
        val st = core.state.value
        return when {
            st.status == CoreStatus.FAILED -> {
                System.err.println("✘ 分析失败：${st.error}")
                EXIT_FAILED
            }
            st.status != CoreStatus.READY -> {
                System.err.println("✘ 分析未完成（状态 ${st.status}）")
                EXIT_FAILED
            }
            else -> EXIT_OK
        }
    }

    private fun exportAndWait(core: PhotoMgmCore): ExportResult {
        val latch = CountDownLatch(1)
        val holder = arrayOfNulls<ExportResult>(1)
        var failure: Throwable? = null
        core.export(
            ExportRequest(
                outputDir = Paths.get(core.state.value.settings.outputDir!!),
                includeLedger = opts.includeLedger,
            ),
            object : ExportListener {
                override fun onProgress(progress: ExportProgress) {
                    if (progress.total > 0) print("\r  ${progress.stage} ${progress.copied}/${progress.total}   ")
                }

                override fun onFinished(result: ExportResult) {
                    holder[0] = result
                    latch.countDown()
                }

                override fun onFailed(error: Throwable) {
                    failure = error
                    latch.countDown()
                }
            },
        )
        latch.await(4, TimeUnit.HOURS)
        println()
        failure?.let { throw it }
        return holder[0] ?: error("导出未返回结果（超时）")
    }

    private fun printEvents(st: AppState) {
        if (st.events.isEmpty()) {
            println("  （无事件，检查日志格式与巡查日期）")
            return
        }
        println("  事件清单：")
        st.events.take(15).forEach { e ->
            val flag = if (e.isPending) "待定" else "  "
            println("   [$flag] #${e.id} ${e.eventType} ${e.location} 照片 ${e.photoIds.size} 张" +
                (if (e.hints.isEmpty()) "" else "  提示：${e.hints.first()}"))
        }
        if (st.events.size > 15) println("   … 另有 ${st.events.size - 15} 条")
        if (st.unmatchedPhotoIds.isNotEmpty()) println("  未匹配照片 ${st.unmatchedPhotoIds.size} 张（导出至 99_未分类/）")
    }

    private fun usageError(msg: String): Int {
        System.err.println("✘ $msg")
        return EXIT_USAGE
    }
}
