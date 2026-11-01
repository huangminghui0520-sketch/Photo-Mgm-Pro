// desktop/ui/Main.kt —— U1 入口：窗口尺寸策略 + 主题 + 状态订阅 + 三栏骨架
//
// ── U1 相比 U0 改了什么 ────────────────────────────────────────────────────
//   U0：固定窗口 + 三个文本，只验证"能起窗"。尺寸是 Compose 默认的 800×600。
//   U1：窗口尺寸按主屏工作区自适应（1366@125% 的内业机也能完整显示），主题接设计令牌，
//       状态接 core.state（StateFlow），三栏骨架吃真实 AppState 字段。
//
// ── 窗口尺寸的三条规则（详见 window/WindowGeometry.kt）────────────────────
//   1. 首次启动：期望 1440×900，但被 clamp 到「工作区 × 90%」与「最小 900×540」之间；
//   2. 二次启动：恢复 settings.json 里用户调过的值，只做合法性修正（低于最小值抬高、
//      超出工作区居中）；
//   3. 关闭时写回实际 bounds —— 注意存的是 **dp 而非物理像素**，换显示器不漂。
//
// ── 命令行（开发/验收用）────────────────────────────────────────────────────
//   --smoke            渲染 1.5s 后自动退出（脚本断言用）
//   --demo             造临时照片+日志并跑真实分析流水线，让骨架显示真实数据
//   --size 900x540     强制指定初始尺寸（跳过 clamp，用于复现内业机小窗）
//   --data-dir <path>  覆盖数据目录（不碰用户 settings.json）
package com.photomgm.desktop.ui

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.photomgm.desktop.core.api.AppState
import com.photomgm.desktop.core.api.CoreConfig
import com.photomgm.desktop.core.api.PhotoMgmCore
import com.photomgm.desktop.core.api.PhotoMgmCoreFactory
import com.photomgm.desktop.core.api.WindowState as CoreWindowState
import com.photomgm.desktop.ui.design.PhotoMgmTheme
import com.photomgm.desktop.ui.dev.DemoFixture
import com.photomgm.desktop.ui.window.WindowSizing
import com.photomgm.desktop.ui.workspace.WorkspaceScreen
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

private data class UiOptions(
    val smoke: Boolean = false,
    val demo: Boolean = false,
    val size: Pair<Int, Int>? = null,
    val dataDir: Path? = null,
    /** --shot <png>：冒烟时把窗口截成图片。M6b 的 DPI 三档走查要靠它留证，不靠人肉截图。 */
    val shot: String? = null,
    /** --log-file <txt>：把日志同时写成 UTF-8 文件。走查记录要能直接附在验收单里。 */
    val logFile: String? = null,
)

fun main(args: Array<String>) {
    // 与 :cli 同一处理：JDK 17 在中文 Windows 上默认 GBK，中文日志会变成乱码。
    // 注意：经 `gradle run` 时输出管道会被 Gradle 接管，这一步不足以救回控制台；
    // 真正可靠的是 --log-file（tee 成 UTF-8 文件）。打包成 exe 直接运行时这里才生效。
    runCatching {
        val utf8 = java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.out), true, "UTF-8")
        System.setOut(utf8)
        System.setErr(java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.err), true, "UTF-8"))
    }

    // 先解析参数：--log-file 要在第一行日志之前生效，否则日志头就丢了。
    val opts = parseOptions(args)
    opts.logFile?.let { installLogFile(it) }

    println("[UI] 启动 headless=${GraphicsEnvironment.isHeadless()} options=$opts")

    // --demo 用独立数据目录：绝不让演示数据写进用户的 settings.json。
    val dataDir = opts.dataDir ?: if (opts.demo) demoDataDir() else null
    val core: PhotoMgmCore = PhotoMgmCoreFactory.create(CoreConfig(dataDir = dataDir))

    try {
        if (opts.demo) {
            val src = DemoFixture.prepare(core)
            println("[UI] 演示数据就绪 source=$src")
        }

        val work = WindowSizing.primaryWorkArea()
        val saved = core.state.value.settings.window
        val rect = opts.size
            ?.let { (w, h) -> WindowSizing.centered(w, h, work) }
            ?: WindowSizing.resolve(saved, work)

        println(
            "[UI] 工作区 ${work.width}×${work.height} dp" +
                " 记忆=${saved?.let { "${it.width}×${it.height}@(${it.x},${it.y})" } ?: "无（首次启动）"}" +
                " → 初始 ${rect.width}×${rect.height}@(${rect.x},${rect.y})" +
                if (opts.size != null) " [--size 覆盖，已跳过 clamp]" else "",
        )

        var readBounds: () -> java.awt.Rectangle = { java.awt.Rectangle() }

        application(exitProcessOnExit = false) {
            val windowState = rememberWindowState(
                size = DpSize(rect.width.dp, rect.height.dp),
                position = WindowPosition(rect.x.dp, rect.y.dp),
            )

            Window(
                onCloseRequest = {
                    persistWindow(core, readBounds, opts)
                    exitApplication()
                },
                state = windowState,
                title = "巡查照片分类 · Photo-Mgm 2.0.0",
            ) {
                // 最小尺寸必须下到 AWT 层：Compose 的 WindowState 没有 minSize，
                // 只靠布局约束的话，用户还是能把窗口拖到三栏挤成一团。
                window.minimumSize = Dimension(WindowSizing.MIN_WIDTH, WindowSizing.MIN_HEIGHT)
                readBounds = { window.bounds }

                val state by core.state.collectAsState()
                val digest = stateDigest(state)

                // 这行是「骨架吃真实 AppState」的证据：每次状态变化都会打一条。
                LaunchedEffect(digest) { println("[UI] state $digest") }

                PhotoMgmTheme {
                    WorkspaceScreen(
                        state = state,
                        onToggleRail = { println("[UI] rail toggled") },
                        debugLayout = opts.demo,
                    )
                }

                if (opts.smoke) {
                    LaunchedEffect(Unit) {
                        kotlinx.coroutines.delay(600)
                        val b = window.bounds
                        val scale = runCatching {
                            GraphicsEnvironment.getLocalGraphicsEnvironment()
                                .defaultScreenDevice.defaultConfiguration.defaultTransform.scaleX
                        }.getOrDefault(1.0)
                        println("[UI] 实际窗口 ${b.width}×${b.height}@(${b.x},${b.y}) 缩放=$scale")
                        opts.shot?.let { path -> capture(window, path) }
                        kotlinx.coroutines.delay(900)
                        println("[UI] 冒烟完成，退出")
                        exitApplication()
                    }
                }
            }
        }
    } finally {
        core.close()
    }
    println("[UI] 已退出")
}

/**
 * 关闭时把窗口几何写回 settings.json。
 *
 * 存 `window.bounds`（AWT 用户空间像素）而不是 contentPane 尺寸：
 * 两者相差一圈标题栏/边框，用 contentPane 存、用 setSize 恢复会每次重启长高 39 px。
 * 存 bounds + 恢复时按 DpSize 走 setSize，是同一个口径，二次启动不会漂。
 */
private fun persistWindow(core: PhotoMgmCore, readBounds: () -> java.awt.Rectangle, opts: UiOptions) {
    if (opts.smoke || opts.size != null) return // 冒烟/强制尺寸不写记忆，免得把用户设置覆盖成测试值
    runCatching {
        val b = readBounds()
        if (b.width <= 0 || b.height <= 0) return
        core.updateSettings { s -> s.copy(window = CoreWindowState(b.x, b.y, b.width, b.height)) }
        println("[UI] 已记忆窗口 ${b.width}×${b.height}@(${b.x},${b.y})")
    }.onFailure { println("[UI] 记忆窗口失败（忽略）：${it.message}") }
}

/** 一行状态摘要：只打"会变且有意义"的字段，避免进度条把日志刷爆。 */
private fun stateDigest(s: AppState): String = buildString {
    append("status=").append(s.status.name)
    append(" events=").append(s.events.size)
    append(" photos=").append(s.photos.size)
    append(" unmatched=").append(s.unmatchedPhotoIds.size)
    append(" ledger=").append(s.ledger.size)
    append(" src=").append(s.settings.sourceDirs.size)
    s.analysis?.let {
        append(" matched=").append(it.matchedCount).append('/').append(it.photoCount)
        append(" noGps=").append(it.noGpsCount)
    }
    s.progress?.let { append(" stage=").append(it.stage) } // 不记 current/total：进度每张照片都变，会把日志刷爆
    s.message?.let { append(" msg=").append(it) }
    s.error?.let { append(" err=").append(it.take(80)) }
}

/**
 * 把 stdout 复制一份到 UTF-8 文件。
 *
 * 为什么不能只靠控制台重定向：中文 Windows 的 JDK 17 把 System.out 绑在控制台代码页（GBK）上，
 * `gradle run > log.txt` 得到的是 GBK 字节，交给 grep / 评审工具就是乱码。
 * 这里在进程内自己 tee 一份 UTF-8，日志文件与终端各自正确。
 */
private fun installLogFile(path: String) {
    runCatching {
        val file = java.io.File(path)
        file.parentFile?.mkdirs()
        val original = System.out
        val sink = java.io.FileOutputStream(file)
        val tee = object : java.io.OutputStream() {
            override fun write(b: Int) {
                original.write(b); sink.write(b)
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                original.write(b, off, len); sink.write(b, off, len)
            }

            override fun flush() {
                original.flush(); sink.flush()
            }
        }
        System.setOut(java.io.PrintStream(tee, true, "UTF-8"))
    }.onFailure { println("[UI] 日志文件不可用（忽略）：${it.message}") }
}

/**
 * 把窗口截成 PNG（AWT Robot）。
 *
 * ★ 必须先把窗口提到最前再截：Robot 抓的是"屏幕上那块坐标"，不是"这个窗口"。
 *   实测第一次没置顶时抓到的是前台聊天窗口 —— 这种截图混进验收记录比没有更糟，
 *   因为它看起来像 UI 崩了。
 */
private fun capture(window: java.awt.Window, path: String) {
    runCatching {
        window.isAlwaysOnTop = true
        window.toFront()
        window.requestFocus()
        Thread.sleep(400)
        val img = java.awt.Robot().createScreenCapture(window.bounds)
        java.io.File(path).parentFile?.mkdirs()
        javax.imageio.ImageIO.write(img, "png", java.io.File(path))
        window.isAlwaysOnTop = false
        println("[UI] 截图 $path ${img.width}×${img.height}")
    }.onFailure { println("[UI] 截图失败：${it.message}") }
}

private fun demoDataDir(): Path {    val appData = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }
    val base = if (appData != null) Paths.get(appData) else Paths.get(System.getProperty("java.io.tmpdir"))
    return base.resolve("PhotoMgm-demo").also { Files.createDirectories(it) }
}

private fun parseOptions(args: Array<String>): UiOptions {
    var opts = UiOptions()
    var i = 0
    while (i < args.size) {
        when (val a = args[i]) {
            "--smoke" -> opts = opts.copy(smoke = true)
            "--demo" -> opts = opts.copy(demo = true)
            "--size" -> {
                val raw = args.getOrNull(++i)
                val parts = raw?.lowercase()?.split('x')
                val w = parts?.getOrNull(0)?.toIntOrNull()
                val h = parts?.getOrNull(1)?.toIntOrNull()
                if (w != null && h != null) opts = opts.copy(size = w to h)
                else println("[UI] --size 需要形如 900x540，已忽略：$raw")
            }
            "--data-dir" -> {
                val raw = args.getOrNull(++i)
                if (raw != null) opts = opts.copy(dataDir = Paths.get(raw))
            }
            "--shot" -> opts = opts.copy(shot = args.getOrNull(++i))
            "--log-file" -> {
                val raw = args.getOrNull(++i)
                if (raw != null) opts = opts.copy(logFile = raw)
            }
            else -> println("[UI] 忽略未知参数 $a")
        }
        i++
    }
    return opts
}
