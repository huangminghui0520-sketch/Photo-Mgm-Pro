// desktop/core/DesktopCore.kt —— ★ 桌面端**组装根**（T10：从"实现"退化为"适配 + 装配"）
//
// 这个文件在 T10 之前是 743 行的 `logic/PhotoMgmCoreImpl.kt`，里面装着整条流水线：
// 解析日志、扫目录、粗筛、派生去重、EXIF 精读、GPS 分组、分类、视图重算、台账、导出规划…
// 那些逻辑现在都在 `shared/app`（用例 + 状态 + 导出计划），与安卓端**共用同一份**。
// 剩下的事情只有三件，全部是平台形态：
//
//   1. **长任务放在哪跑**：桌面用 Executor + 显式 OperationHandle，安卓用 viewModelScope；
//   2. **状态发到哪个生命周期**：桌面是一路 StateFlow 直到 close()；
//   3. **关闭时释放什么**：桌面的线程池、安卓的协程作用域。
//
// 所以这里刻意**不写** `PhotoMgmCore` 的共享实现（见 docs/REFACTOR-EXECUTION-PLAN.md §15.5）：
// 硬抽出来只会得到一个"线程/协程二选一"都不像的类。两端各自 ~60 行装配更便宜。
//
// ★ 装配顺序是有讲究的（改顺序会踩坑）：
//   ① 先建存储适配器并**立刻 load()** —— 首屏要不要显示历史日志、窗口多大，取决于它；
//   ② 再建 AppStateStore 的初始快照，`dedupMode` 从 settings 取（两者曾各自初始化，
//      结果是"设置里是仅保留带水印、状态里是都保留"，预览与台账按不同口径渲染）；
//   ③ 最后把用例接上端口。用例本身不持线程，谁调用谁决定线程。
package com.photomgm.desktop.core

import algorithm.DedupKeepMode
import app.api.AppState
import app.api.CoreStatus
import app.api.ExportListener
import app.api.ExportRequest
import app.api.ExportResult
import app.api.OperationHandle
import app.api.PhotoMgmCore
import app.api.ProgressListener
import app.api.Settings
import app.infra.DebouncedTextStore
import app.infra.DelayHandle
import app.infra.DelayScheduler
import app.infra.decodeText
import app.port.FileBytesReader
import app.port.ImageEncoder
import app.port.PhotoIdSource
import app.port.ShellLauncher
import app.port.ShellOutcome
import app.state.AppStateStore
import app.state.PhotoSession
import app.usecase.AdjustUseCase
import app.usecase.AnalysisPipeline
import app.usecase.ExportUseCase
import app.usecase.SettingsUseCase
import com.photomgm.desktop.core.adapter.PcExportExecutor
import com.photomgm.desktop.core.adapter.PcFileBytesReader
import com.photomgm.desktop.core.adapter.PcImageEncoder
import com.photomgm.desktop.core.adapter.PcLogTextStorage
import com.photomgm.desktop.core.adapter.PcPhotoIdSource
import com.photomgm.desktop.core.adapter.PcSettingsStorage
import com.photomgm.desktop.core.adapter.PcShellLauncher
import com.photomgm.desktop.core.platform.PcExifReader
import com.photomgm.desktop.core.platform.PcFileHasher
import com.photomgm.desktop.core.platform.PcPaths
import com.photomgm.desktop.core.platform.PcPhotoSourceProvider
import kotlinx.coroutines.flow.StateFlow
import java.nio.file.Path
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 桌面端核心（门面 + 组装根）。
 *
 * ★ 除共享 [PhotoMgmCore] 的成员外，还多三个**桌面独有**的成员：
 *   [windowState] / [saveWindow]（窗口几何）与 [setVerifyMode]（续传校验强度）。
 *   它们之所以不在共享契约里：安卓没有桌面窗口，也没有 manifest 续传机制。
 *   把它们塞进 `Settings` / `ExportRequest` 的代价，就是安卓那边永远背着两个恒为默认值的字段。
 */
class DesktopCore internal constructor(private val config: DesktopCoreConfig) : PhotoMgmCore {

    private val zone = config.zone
    private val clock = config.clock ?: java.time.Clock.systemUTC()
    private val dataDir: Path = config.dataDir ?: PcPaths.appDataDir()

    // ── 线程 ──────────────────────────────────────────────────────────────
    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r -> daemonThread(r, "photomgm-io") }

    /** 长任务执行器：外部注入（测试用同步执行器）或核心自建单线程。 */
    private val executor: Executor = config.executor ?: Executors.newSingleThreadExecutor { r ->
        daemonThread(r, "photomgm-task")
    }
    private val ownsExecutor = config.executor == null

    // ── 端口适配器 ────────────────────────────────────────────────────────
    private val settingsStorage = PcSettingsStorage(dataDir.resolve("settings.json"))
    private val logStorage = PcLogTextStorage(dataDir.resolve("logs.txt"))
    private val bytesReader: FileBytesReader = PcFileBytesReader()
    private val shell: ShellLauncher = config.shell ?: PcShellLauncher()
    private val photoIds: PhotoIdSource = config.photoIdSource ?: PcPhotoIdSource()
    private val encoder: ImageEncoder = config.imageEncoder ?: PcImageEncoder()
    private val sourceProvider = config.sourceProvider ?: PcPhotoSourceProvider()
    private val exifReader = config.exifReader ?: PcExifReader(zone)
    private val hasher: (String) -> String? = config.hasher ?: (PcFileHasher()::sha256)

    /** 续传校验强度：UI 可在设置页改，导出执行器每次取值。 */
    @Volatile
    private var verifyMode = config.verifyMode

    private val exportExecutor = PcExportExecutor(
        zone = zone,
        imageEncoder = { ref -> encoder.encode(ref) },
        clock = clock,
        hasher = PcFileHasher(),
        verifyMode = { verifyMode },
    )

    // ── 状态与用例 ────────────────────────────────────────────────────────
    private val logs: DebouncedTextStore = DebouncedTextStore(
        persist = { text -> logStorage.persist(text) },
        scheduler = DelayScheduler { delayMs, action ->
            val future = scheduler.schedule(action, delayMs, TimeUnit.MILLISECONDS)
            DelayHandle { future.cancel(false) }
        },
    )

    private val session = PhotoSession(zone)
    private val store = AppStateStore(
        AppStateStore.initial(settings = settingsStorage.load(), logText = logStorage.load()),
    )

    private val pipeline = AnalysisPipeline(
        store = store,
        session = session,
        logs = logs,
        sourceProvider = sourceProvider,
        exifReader = exifReader,
        hasher = hasher,
        photoIds = photoIds,
    )
    private val adjust = AdjustUseCase(store, session)
    // ★ logs 传的是取值函数（T11 同步修正）：安卓端会把防抖器整体替换，冻结构造期的实例会静默失效。
    private val settingsUseCase = SettingsUseCase(store, session, settingsStorage) { logs }
    private val exportUseCase = ExportUseCase(store, session, exportExecutor)

    private val closed = AtomicBoolean(false)

    override val state: StateFlow<AppState> = store.state

    // ── 桌面独有：窗口几何 ────────────────────────────────────────────────

    /** 上次记住的窗口几何（启动时用来决定首屏尺寸）；没有则 null。 */
    val windowState: WindowState? get() = settingsStorage.windowState()

    /**
     * 记住窗口几何（关窗时调用）；传 null = 忘掉（`--demo` 要重置成默认尺寸）。
     *
     * ★ 必须把当前设置一起带上：settings.json 是一次性整体重写的，
     *   只写窗口会把刚改好的巡查日期/源目录写回旧值。
     */
    fun saveWindow(state: WindowState?) {
        settingsStorage.saveWindow(store.current().settings, state)
    }

    // ── 桌面独有：续传校验强度 ────────────────────────────────────────────

    /** 切换断点续传的产物校验强度（桌面独有，见 [app.api.ResumeVerifyMode]）。 */
    fun setVerifyMode(mode: app.api.ResumeVerifyMode) {
        verifyMode = mode
    }

    // ── 设置 ──────────────────────────────────────────────────────────────

    override fun updateSettings(block: (Settings) -> Settings) = settingsUseCase.updateSettings(block)

    override fun setLogText(text: String) = settingsUseCase.setLogText(text)

    override fun setDedupMode(mode: DedupKeepMode) = settingsUseCase.setDedupMode(mode)

    // ── 主流程 ────────────────────────────────────────────────────────────

    override fun analyze(listener: ProgressListener?): OperationHandle {
        val op = DesktopOperation()
        if (closed.get()) return op
        executor.execute { pipeline.analyze(listener, op::isCancelled) }
        return op
    }

    /** 读日志文件内容（UTF-8 / GBK 自动识别）。解码规则在共享层，这里只负责取字节。 */
    override fun readLogText(ref: String): String {
        val bytes = bytesReader.readBytes(ref)
            ?: throw IllegalArgumentException("无法读取日志文件：$ref（文件不存在、无读取权限或路径过长）")
        return decodeText(bytes)
    }

    override fun movePhotos(photoIds: List<Long>, targetEventId: Int?): Int =
        adjust.movePhotos(photoIds, targetEventId)

    override fun copyPhotos(photoIds: List<Long>, targetEventId: Int): Int =
        adjust.copyPhotos(photoIds, targetEventId)

    override fun markLedgerPhoto(eventId: Int, slot: Int, photoId: Long?) =
        adjust.markLedgerPhoto(eventId, slot, photoId)

    override fun resetManualAdjustments() = adjust.resetManualAdjustments()

    // ── 导出 ──────────────────────────────────────────────────────────────

    override fun export(request: ExportRequest, listener: ExportListener): OperationHandle {
        val op = DesktopOperation()
        if (closed.get()) return op
        // ★ 闸门（"导出中不许再导出"）在读用例内部同步判定，这里不再重复一遍判断：
        //   两处判定迟早会分叉，而导出重入的后果是两个导出共用同一批目标文件（产物损坏）。
        executor.execute { exportUseCase.export(request, listener, op::isCancelled) }
        return op
    }

    override fun openLedger(exportResult: ExportResult): Boolean {
        val file = exportResult.ledgerFile ?: return false
        return shell.open(file) == ShellOutcome.STARTED
    }

    override fun revealExportDir(exportResult: ExportResult): Boolean =
        shell.reveal(exportResult.exportDir) == ShellOutcome.STARTED

    override fun clearMessage() {
        store.withLock { store.publish { it.copy(message = null) } }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { settingsUseCase.flushLogs() }
        store.withLock { store.publish { it.copy(status = CoreStatus.CLOSED) } }
        if (ownsExecutor) runCatching { (executor as? ExecutorService)?.shutdownNow() }
        runCatching { scheduler.shutdownNow() }
    }

    // ── 内部类型 ──────────────────────────────────────────────────────────

    /** 桌面端的操作句柄：一个布尔标志。安卓那边对应的是 Job 的 cancel。 */
    private class DesktopOperation : OperationHandle {
        private val cancelled = AtomicBoolean(false)

        override fun cancel() {
            cancelled.set(true)
        }

        override val isCancelled: Boolean get() = cancelled.get()
    }

    companion object {
        /** 创建入口（UI 唯一需要的工厂）。 */
        fun create(config: DesktopCoreConfig = DesktopCoreConfig()): DesktopCore = DesktopCore(config)

        private fun daemonThread(r: Runnable, name: String): Thread =
            Thread(r, name).apply { isDaemon = true }
    }
}
