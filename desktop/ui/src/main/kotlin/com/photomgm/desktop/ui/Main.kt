// ui/Main.kt —— 桌面端入口：装配 core → 窗口 → 界面
//
// 这里的顺序是刻意的：
//   ① core 先建（它要读 settings.json / logs.txt，决定首屏有没有历史内容）
//   ② 窗口尺寸按"主屏工作区 + 记忆值"算（不写死，见 WindowGeometry）
//   ③ 界面只拿 state 快照 + 一个 actions 清单，不认识 core 的其它细节
//
// 调试开关（都只在命令行里用，不影响产品行为）：
//   --smoke              渲染 1.6s 后自动退出（供脚本断言"能起窗"）
//   --demo               造真实素材并跑真实分析（用来看真实 AppState 下的界面）
//   --size 984x540       强制窗口尺寸（验收各宽度档的排版）
//   --shot out.png       截图到文件（DPI 走查留证）
//   --log-file out.log   UTF-8 渲染日志
//   --data-dir <dir>     覆盖数据目录（演示/测试不污染 %APPDATA%）
package com.photomgm.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import algorithm.DedupKeepMode
import com.photomgm.desktop.core.api.CoreConfig
import com.photomgm.desktop.core.api.ExportListener
import com.photomgm.desktop.core.api.ExportProgress
import com.photomgm.desktop.core.api.ExportRequest
import com.photomgm.desktop.core.api.ExportResult
import com.photomgm.desktop.core.api.OperationHandle
import com.photomgm.desktop.core.api.PhotoMgmCore
import com.photomgm.desktop.core.api.PhotoMgmCoreFactory
import com.photomgm.desktop.ui.app.Actions
import com.photomgm.desktop.ui.app.AppShell
import com.photomgm.desktop.ui.app.UiLog
import com.photomgm.desktop.ui.app.WindowGeometry
import com.photomgm.desktop.ui.dev.DemoFixture
import com.photomgm.desktop.ui.model.AssignMode
import com.photomgm.desktop.ui.model.Busy
import com.photomgm.desktop.ui.model.Selection
import com.photomgm.desktop.ui.model.Sheet
import com.photomgm.desktop.ui.model.UiState
import com.photomgm.desktop.ui.model.initialUi
import com.photomgm.desktop.ui.model.reduce
import com.photomgm.desktop.ui.platform.NativeDialogs
import com.photomgm.desktop.ui.platform.ShellActions
import com.photomgm.desktop.ui.theme.PmTheme
import kotlinx.coroutines.delay
import java.awt.GraphicsEnvironment
import java.nio.file.Path
import java.nio.file.Paths

private data class Options(
    val smoke: Boolean = false,
    val demo: Boolean = false,
    val size: Pair<Int, Int>? = null,
    val shot: String? = null,
    val logFile: String? = null,
    val dataDir: Path? = null,
    /** 直接打开某个弹窗（只用于截图走查，不改变业务逻辑）。 */
    val sheet: String? = null,
)

fun main(args: Array<String>) {
    // 中文 Windows 上 JDK 17 把 System.out 绑到 GBK，重定向到文件必乱码。
    // 与 :cli 同一处理：重建 UTF-8 的 PrintStream。
    runCatching {
        val utf8 = java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.out), true, "UTF-8")
        System.setOut(utf8)
    }

    val opts = parse(args)
    UiLog.attach(opts.logFile?.let { Paths.get(it) })
    UiLog.line("启动 options=$opts")

    val dataDir = opts.dataDir ?: defaultDataDir()
    val core = PhotoMgmCoreFactory.create(CoreConfig(dataDir = dataDir))

    if (opts.demo) {
        val ok = DemoFixture.install(core, dataDir)
        UiLog.line("演示数据安装 ${if (ok) "完成" else "超时"}")
    }

    val area = workArea()
    val savedWindow = core.state.value.settings.window
    val saved = savedWindow?.let { WindowGeometry.fromSaved(it.x, it.y, it.width, it.height) }
    val bounds = opts.size?.let { (w, h) -> WindowGeometry.Bounds(area.x + 40, area.y + 40, w, h) }
        ?: WindowGeometry.initial(area, saved)
    UiLog.line("工作区 ${area.width}×${area.height}@(${area.x},${area.y}) 记忆=${saved ?: "无"} → 窗口 ${bounds.width}×${bounds.height}@(${bounds.x},${bounds.y})")

    application {
        val windowState = rememberWindowState(
            size = DpSize(bounds.width.dp, bounds.height.dp),
            position = WindowPosition.Absolute(bounds.x.dp, bounds.y.dp),
        )

        var ui by remember {
            val snapshot = core.state.value
            val base = initialUi(snapshot)
            // --sheet 只用于截图走查：把某个覆盖层直接打开，业务逻辑一律不动。
            val forced: Sheet? = when (opts.sheet?.lowercase()) {
                "settings" -> Sheet.Settings
                "photos", "event" -> {
                    val pending = snapshot.events.firstOrNull { it.photoIds.isNotEmpty() }
                        ?: snapshot.events.firstOrNull { it.isPending }
                        ?: snapshot.events.firstOrNull()
                    val target = if (pending != null) {
                        Selection.Event(pending.id)
                    } else if (snapshot.unmatchedPhotoIds.isNotEmpty()) {
                        Selection.Unmatched
                    } else {
                        Selection.None
                    }
                    if (target == Selection.None) null else Sheet.Photos(target)
                }

                // 分配层：拿第一个有照片事件的前两张演示（截图走查用）；"copy" 看复制模式的文案
                "move", "copy" -> {
                    val src = snapshot.events.firstOrNull { it.photoIds.isNotEmpty() }
                    if (src == null) {
                        null
                    } else {
                        Sheet.Assign(
                            photoIds = src.photoIds.take(2),
                            from = Selection.Event(src.id),
                            mode = if (opts.sheet.equals("copy", ignoreCase = true)) AssignMode.COPY else AssignMode.MOVE,
                        )
                    }
                }

                else -> null
            }
            val shown = when (opts.sheet?.lowercase()) {
                "log" -> base.copy(logEntryVisible = true)
                null -> base
                else -> base.copy(sheet = forced, logEntryVisible = false)
            }
            mutableStateOf(shown)
        }
        var handle by remember { mutableStateOf<OperationHandle?>(null) }

        Window(
            onCloseRequest = {
                if (opts.size == null) saveGeometry(windowState.size, windowState.position, core)
                core.close()
                UiLog.close()
                exitApplication()
            },
            state = windowState,
            title = "巡查照片管理 · Photo-Mgm",
        ) {
            val state by core.state.collectAsState()
            val actions = rememberActions(core, { ui }, { ui = it }, { handle = it }, { handle })

            // 状态归约放在状态变化时触发：只认 core 的一次快照，不参与每帧重组。
            LaunchedEffect(state) { ui = reduce(ui, state) }

            PmTheme {
                AppShell(state = state, ui = ui, onUi = { ui = it }, actions = actions)
            }

            if (opts.smoke || opts.shot != null) {
                LaunchedEffect(Unit) {
                    delay(1100)
                    opts.shot?.let { capture(window, it) }
                    if (opts.smoke) {
                        delay(500)
                        UiLog.line("冒烟存活 1.6s，正常退出")
                        exitApplication()
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberActions(
    core: PhotoMgmCore,
    ui: () -> UiState,
    setUi: (UiState) -> Unit,
    setHandle: (OperationHandle?) -> Unit,
    handle: () -> OperationHandle?,
): Actions = remember(core) {
    Actions(
        setPatrolDate = { date -> core.updateSettings { it.copy(patrolDate = date) } },
        addSourceDirs = {
            NativeDialogs.pickDirectory("选择装着当天照片的文件夹")?.let { dir ->
                core.updateSettings { s ->
                    if (s.sourceDirs.contains(dir)) s else s.copy(sourceDirs = s.sourceDirs + dir)
                }
            }
        },
        removeSourceDir = { dir -> core.updateSettings { s -> s.copy(sourceDirs = s.sourceDirs - dir) } },
        chooseOutputDir = {
            NativeDialogs.pickDirectory("选择导出目录")?.let { dir ->
                core.updateSettings { it.copy(outputDir = dir) }
            }
        },
        revealPath = { path ->
            if (!ShellActions.reveal(path)) setUi(ui().copy(localMessage = "打不开该路径：$path"))
        },
        importLogFile = {
            val file = NativeDialogs.pickLogFile()
            if (file != null) {
                runCatching { core.readLogFile(Paths.get(file)) }
                    .onSuccess { text ->
                        core.setLogText(text)
                        setUi(ui().copy(logRevision = ui().logRevision + 1))
                    }
                    .onFailure { setUi(ui().copy(localMessage = "导入失败：${it.message}")) }
            }
        },
        setLogText = { core.setLogText(it) },
        analyze = {
            setUi(ui().copy(busy = Busy.ANALYZE, sheet = null))
            setHandle(core.analyze())
        },
        cancelAnalyze = { handle()?.cancel() },
        export = {
            val out = core.state.value.settings.outputDir
            if (out == null) {
                setUi(ui().copy(localMessage = "还没有选择导出目录"))
            } else {
                val request = ExportRequest(
                    outputDir = Paths.get(out),
                    includeLedger = ui().exportOptions.includeLedger,
                    verifyMode = ui().exportOptions.verifyMode,
                )
                setUi(ui().copy(busy = Busy.EXPORT, sheet = null))
                setHandle(
                    core.export(
                        request,
                        object : ExportListener {
                            override fun onProgress(progress: ExportProgress) = Unit
                            override fun onFinished(result: ExportResult) = Unit
                            override fun onFailed(error: Throwable) = Unit
                        },
                    ),
                )
            }
        },
        cancelExport = { handle()?.cancel() },
        movePhotosTo = { ids, eventId -> core.movePhotos(ids, eventId) },
        copyPhotosTo = { ids, eventId -> core.copyPhotos(ids, eventId) },
        markLedgerPhoto = { eventId, slot, photoId -> core.markLedgerPhoto(eventId, slot, photoId) },
        resetAdjustments = { core.resetManualAdjustments() },
        setDedupMode = { mode: DedupKeepMode -> core.setDedupMode(mode) },
        openLedger = { core.state.value.lastExport?.let { core.openLedger(it) } },
        revealExportDir = { core.state.value.lastExport?.let { core.revealExportDir(it) } },
        clearMessage = { core.clearMessage() },
    )
}

private fun saveGeometry(
    size: DpSize,
    position: WindowPosition,
    core: PhotoMgmCore,
) {
    runCatching {
        val w = size.width.value.toInt()
        val h = size.height.value.toInt()
        if (w <= 0 || h <= 0) return
        val absolute = position as? WindowPosition.Absolute ?: return
        core.updateSettings {
            it.copy(
                window = com.photomgm.desktop.core.api.WindowState(
                    x = absolute.x.value.toInt(),
                    y = absolute.y.value.toInt(),
                    width = w,
                    height = h,
                ),
            )
        }
        UiLog.line("记忆窗口 ${w}×${h}")
    }
}

/** 主屏工作区（已排除任务栏）。AWT 的用户坐标系与 dp 一致（含缩放），可直接当 dp 用。 */
private fun workArea(): WindowGeometry.Area {
    val rect = runCatching { GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds }.getOrNull()
    return if (rect != null) {
        WindowGeometry.Area(rect.x, rect.y, rect.width, rect.height)
    } else {
        WindowGeometry.Area(0, 0, 1920, 1040)
    }
}

/** 与 core/platform/PcPaths 的约定保持一致（:ui 不依赖 platform 层，所以这里重述一次）。 */
private fun defaultDataDir(): Path {
    val appData = System.getenv("APPDATA")
    return if (!appData.isNullOrBlank()) Paths.get(appData, "PhotoMgm")
    else Paths.get(System.getProperty("user.home"), ".config", "PhotoMgm")
}

private fun capture(window: java.awt.Window, path: String) {
    runCatching {
        // 置顶 + 抢焦点 + 等一帧：否则 Robot 抓到的是"这个矩形位置上原本那个窗口"
        // （实测会抓到 IDE 或资源管理器，截出来的图完全不是本程序）。
        window.isAlwaysOnTop = true
        window.toFront()
        window.requestFocus()
        Thread.sleep(450)
        // ★ 只抓客户区：window.bounds 含 DWM 的不可见缩放边框，
        //   直接抓会把窗口外的桌面/别的程序带进图片右边缘（实测会看到别人的字）。
        val ins = window.insets
        val rect = java.awt.Rectangle(
            window.bounds.x + ins.left,
            window.bounds.y + ins.top,
            window.bounds.width - ins.left - ins.right,
            window.bounds.height - ins.top - ins.bottom,
        )
        val image = java.awt.Robot().createScreenCapture(rect)
        java.io.File(path).parentFile?.mkdirs()
        javax.imageio.ImageIO.write(image, "png", java.io.File(path))
        window.isAlwaysOnTop = false
        UiLog.line("截图 $path ${image.width}×${image.height} 窗口 ${window.bounds.width}×${window.bounds.height}")
    }.onFailure { UiLog.line("截图失败：${it.message}") }
}

private fun parse(args: Array<String>): Options {
    var opts = Options()
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--smoke" -> opts = opts.copy(smoke = true)
            "--demo" -> opts = opts.copy(demo = true)
            "--size" -> {
                val raw = args.getOrNull(++i)
                val parts = raw?.split('x', 'X')
                if (parts?.size == 2) {
                    val w = parts[0].toIntOrNull()
                    val h = parts[1].toIntOrNull()
                    if (w != null && h != null) opts = opts.copy(size = w to h)
                }
            }

            "--shot" -> opts = opts.copy(shot = args.getOrNull(++i))
            "--log-file" -> opts = opts.copy(logFile = args.getOrNull(++i))
            "--data-dir" -> args.getOrNull(++i)?.let { opts = opts.copy(dataDir = Paths.get(it)) }
            "--sheet" -> opts = opts.copy(sheet = args.getOrNull(++i))
            else -> UiLog.line("忽略未知参数 ${args[i]}")
        }
        i++
    }
    return opts
}
