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
// 所以这里刻意**不写** `PhotoMgmCore` 的共享实现：
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
import com.photomgm.desktop.core.sync.AdbBridge
import com.photomgm.desktop.core.sync.AdbCommand
import com.photomgm.desktop.core.sync.AdbOutput
import com.photomgm.desktop.core.sync.AdbPhoneSource
import com.photomgm.desktop.core.sync.ConnectionKind
import com.photomgm.desktop.core.sync.ConnectionMode
import com.photomgm.desktop.core.sync.ConnectionProbe
import com.photomgm.desktop.core.sync.PhoneConnection
import com.photomgm.desktop.core.sync.PhoneSource
import com.photomgm.desktop.core.sync.PhoneSyncExecutor
import com.photomgm.desktop.core.sync.PhoneSyncJob
import com.photomgm.desktop.core.sync.PhoneSyncOutcome
import com.photomgm.desktop.core.sync.PhoneSyncProgress as CoreSyncProgress
import com.photomgm.desktop.core.sync.RemotePhoto
import com.photomgm.desktop.core.sync.UsbDriveScanner
import com.photomgm.desktop.core.sync.UsbPhoneSource
import domain.sync.SyncRangeFolder
import kotlinx.coroutines.flow.StateFlow
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDate
import java.time.LocalDateTime
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

    // ── 手机导入（桌面独有，全部 internal sync 包只在这里装配） ──────────
    // config 里的假件只用 JDK 类型表达，绝不把 internal 类型泄漏到 public 配置签名上。
    private val adbBridge: AdbBridge = config.adbCommandOutput?.let { fake ->
        AdbBridge(
            command = AdbCommand { args ->
                val (exitCode, stdout, stderr) = fake(args)
                AdbOutput(exitCode, stdout, stderr)
            },
            resolveOverride = config.adbResolvedPath,
        )
    } ?: AdbBridge(resolveOverride = config.adbResolvedPath)

    private val usbDriveScanner: UsbDriveScanner = run {
        val roots = config.usbRoots
        val readable = config.usbReadableDir
        when {
            roots != null && readable != null -> UsbDriveScanner(roots = roots, isReadableDir = readable)
            roots != null -> UsbDriveScanner(roots = roots)
            readable != null -> UsbDriveScanner(isReadableDir = readable)
            else -> UsbDriveScanner()
        }
    }

    @Volatile
    private var phoneSyncHandle: OperationHandle? = null

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

    // ── 桌面独有：手机导入照片 ────────────────────────────────────────────
    // F6：纯函数经门面转发到 shared/domain，UI 不 import domain.*；
    // 执行链路锁在 internal sync 包里，:ui 只看得见本文件的 public DTO。

    // 区间 → 子文件夹名（`04_2300__05_2359`）；纯函数转发，单一事实来源在 shared/domain。
    fun folderNameFor(start: LocalDateTime, end: LocalDateTime): String =
        PhoneSyncFormulas.folderNameFor(start, end)

    // 区间校验；返回提示文案，null = 可以提交。
    fun validateRange(start: LocalDateTime?, end: LocalDateTime?): String? =
        PhoneSyncFormulas.validateRange(start, end)

    // 弹窗首次打开时的默认区间：巡查日期 00:00 ~ 23:59。
    fun defaultRange(patrolDate: LocalDate): Pair<LocalDateTime, LocalDateTime> =
        PhoneSyncFormulas.defaultRange(patrolDate)

    // 手机目录候选项；手动选的本机目录由 UI 追加（F4-A1）。
    //
    // ★ 这里会真的去问手机（`ls` 若干次，单次最长 15 s 超时），**绝不能**在 Compose 的组合
    //   里同步调用：UI 侧已改为 LaunchedEffect + Dispatchers.IO 异步取（见 PhoneImportSheet）。
    // ★ 发现结果为空时回退 ADB_CAMERA_DIRS，保证下拉不会突然变空（老设备上仍然能用）。
    fun listPhoneDirs(mode: PhoneLinkMode): List<PhoneDirEntry> {
        val out = ArrayList<PhoneDirEntry>()
        if (mode != PhoneLinkMode.USB_DRIVE) {
            // 自检与目录发现是同一次 adb 旅程：自检复用发现的结果，不重复问一遍手机。
            // 自检结论以「提示项」的形式塞进同一个列表（id 带 PHONE_DIR_HINT_PREFIX），
            // 这样 UI 不必新增一个 action 签名就能把"为什么没有目录"讲清楚 ——
            // 详见 PHONE_DIR_HINT_PREFIX 的注释。
            val check = selfCheck()
            check.hints.distinct().forEach { hint -> out += PhoneDirEntry(PHONE_DIR_HINT_PREFIX + hint, hint) }
            check.photoDirs.ifEmpty { ADB_CAMERA_DIRS }
                .forEach { dir -> out += PhoneDirEntry("adb:$dir", "ADB · $dir") }
        }
        if (mode != PhoneLinkMode.ADB) {
            usbDriveScanner.visibleDrives().forEach { drive ->
                out += PhoneDirEntry(drive, "盘符 · $drive")
                UsbDriveScanner.candidateDirs(drive).forEach { sub ->
                    out += PhoneDirEntry(sub, "盘符 · $sub")
                }
            }
        }
        return out
    }

    // 启动一次手机导入。回调在**后台执行器线程**上触发，UI 侧负责切回 EDT 再写 Compose 状态。
    // 一次只存在一个任务：重复调用会先取消上一个，避免两批写同一个子文件夹。
    fun startPhoneSync(
        request: PhoneSyncRequest,
        onProgress: (PhoneSyncProgress) -> Unit,
        onFinished: (PhoneSyncResult) -> Unit,
    ) {
        if (closed.get()) {
            onFinished(PhoneSyncResult.failure("程序已关闭，无法同步"))
            return
        }
        phoneSyncHandle?.cancel()
        val op = DesktopOperation()
        phoneSyncHandle = op
        val capturedPhoneDir = request.phoneDir
        executor.execute {
            try {
                val sync = PhoneSyncExecutor(
                    hasher = { p -> hasher(p.toString()) },
                    timeOf = { p: Path, remote: RemotePhoto ->
                        exifReader.readCaptureTimeMs(p.toString()) ?: remote.modifiedMs
                    },
                    sourceFactory = { kind, dir -> phoneSourceFor(kind, dir) },
                    probe = {
                        ConnectionProbe(
                            adbAvailable = adbBridge.hasDevice(),
                            usbVisible = usbDriveScanner.visibleDrives().isNotEmpty() ||
                                runCatching { Files.isDirectory(Paths.get(capturedPhoneDir)) }.getOrDefault(false),
                        )
                    },
                    stagingDir = {
                        config.phoneSyncStaging?.invoke() ?: Files.createTempDirectory("photomgm-phone-sync-")
                    },
                    nowText = { config.phoneSyncNowText?.invoke() ?: clock.instant().toString() },
                    normalize = PcPaths::normalize,
                )
                val outcome = sync.run(
                    PhoneSyncJob(
                        sourceDir = request.sourceDir,
                        start = request.start,
                        end = request.end,
                        phoneDir = request.phoneDir,
                        mode = request.mode.toCoreMode(),
                        confirmConflict = request.confirmConflict,
                    ),
                    op::isCancelled,
                ) { p: CoreSyncProgress -> onProgress(p.toUi()) }
                onFinished(outcome.toUi())
            } finally {
                // 只清自己那一版句柄：旧任务收尾时若已经开始了新任务，不能把新句柄抹掉，
                // 否则「取消」按钮会对新任务失效。
                if (phoneSyncHandle === op) phoneSyncHandle = null
            }
        }
    }

    // 取消当前手机导入；只在文件边界停，已写入文件与 sync-state.json 都保留。
    fun cancelPhoneSync() {
        phoneSyncHandle?.cancel()
    }

    // 动态发现手机上的照片目录（列表页与下拉共用）。
    // AdbPhoneSource 的 remoteDir 只是构造参数，发现过程与它无关，这里给兜底清单的第一项。
    private fun discoverPhoneDirs(useFallback: Boolean = true): List<String> =
        AdbPhoneSource(adbBridge, ADB_CAMERA_DIRS.first()).discoverPhotoDirs(useFallback)

    /**
     * 手机环境自检：adb server 可用性 + 设备授权 + 相册目录可读性，各给一句可执行的提示。
     *
     * ★ 调用方必须是**后台线程**（会跑 `adb version` / `start-server` / `adb devices` 与若干次
     *   `ls`，单次最长 15 s）。目前唯一调用点是 [listPhoneDirs]，而它由 UI 侧经
     *   LaunchedEffect + Dispatchers.IO 触发 —— [AdbBridge.adbServerStatus] 的懒求值也借这条路，
     *   所以启动与单测阶段都不会有外部进程。
     * ★ 不抛异常：所有探测都走 [AdbBridge] 的 [AdbOutput]，进程失败也只是 exitCode != 0。
     * ★ 「目录为空」不能直接下结论 —— [AdbPhoneSource.discoverPhotoDirs] 默认会回退到
     *   [ADB_CAMERA_DIRS]，所以这里显式传 useFallback = false，拿到"真的一张都没找到"，
     *   再去区分是没权限、没这个目录，还是目录里确实没有受支持的照片。
     */
    fun selfCheck(): SelfCheckResult {
        val hints = ArrayList<String>()
        // ① adb server 自检（**懒求值的第一触发点**）：`AdbBridge.adbServerStatus` 是
        //    `by lazy`，构造 bridge 时不起任何进程，读到这一行才真正跑 `adb version` +
        //    `start-server`（不再 kill-server）。因为 selfCheck 只跑在
        //    PhoneImportSheet 的 LaunchedEffect + Dispatchers.IO 路径上，首次求值也在后台线程。
        // ★ 「未找到 adb」不在这里重复提示：下面 online == 0 时已经会加 ADB_NOT_FOUND_HINT，
        //   两条一起加等于同一句话说两遍。判据用失败消息里是否含有那条 hint 常量（该消息由
        //   AdbBridge.ensureAdbServer 拼出，不靠硬编码字符串漂移）。
        adbBridge.adbServerStatus.exceptionOrNull()
            ?.takeUnless { it.message.orEmpty().contains(AdbBridge.ADB_NOT_FOUND_HINT) }
            ?.let { failure -> hints += failure.message ?: "adb server 自检失败（原因未知）。" }

        val states = adbBridge.deviceStates()
        val online = states.count { it.state == "device" }
        val unauthorized = states.any { it.state == "unauthorized" }
        if (unauthorized) {
            hints += "手机已连接但未授权：请在手机上点「允许 USB 调试」后再试。"
        }
        if (online == 0 && !unauthorized) {
            hints += AdbBridge.ADB_NOT_FOUND_HINT
        }

        var photoDirs: List<String> = emptyList()
        if (online > 0) {
            photoDirs = discoverPhoneDirs(useFallback = false)
            if (photoDirs.isEmpty()) {
                val probes = listOf(
                    adbBridge.shellRaw("ls", "/sdcard/DCIM"),
                    adbBridge.shellRaw("ls", "/storage/emulated/0/DCIM"),
                )
                when {
                    probes.any { it.stderr.contains("Permission denied", ignoreCase = true) } ->
                        hints += "读不到手机相册目录（Permission denied）：请把 USB 模式切到「PTP / 传输照片」后重试。"
                    probes.all { it.exitCode != 0 } ->
                        hints += "手机上没有找到 DCIM 目录：请检查开发者选项里的「USB 调试（安全设置）」是否开启。"
                    else -> hints += "手机相册目录里没有受支持的照片。"
                }
            }
        }

        val result = SelfCheckResult(
            ok = online > 0 && photoDirs.isNotEmpty(),
            devicesOnline = online,
            unauthorized = unauthorized,
            photoDirs = photoDirs,
            hints = hints,
        )
        println(
            "ADB-DIAG: selfCheck ok=${result.ok} online=$online unauthorized=$unauthorized " +
                "dirs=${photoDirs.size} hints=${hints.size}",
        )
        return result
    }

    private fun phoneSourceFor(kind: ConnectionKind, phoneDir: String): PhoneSource? = when (kind) {
        ConnectionKind.ADB -> {
            // 盘符/手动目录被选中却解析到 ADB 时，退回默认相机目录，不把 Windows 路径当远端路径。
            val remote = phoneDir.removePrefix("adb:").takeIf { it.startsWith("/") }
                ?: ADB_CAMERA_DIRS.first()
            AdbPhoneSource(adbBridge, remote)
        }
        ConnectionKind.USB_DRIVE -> UsbPhoneSource(phoneDir)
        ConnectionKind.NONE -> null
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

// ── 手机导入：:ui 与 :core 之间的公开契约 ────────────────────────────────────
// 全部定义在本文件内，不新增 PhoneSyncApi.kt —— 方案 D 的改动清单只列 DesktopCore.kt。
// 签名只用 java.time / String / Int / Boolean：:ui 不需要 import domain.*，也看不到
// core/sync 里任何 internal 类型（P5 门禁 + :ui 只认根包 public 类型）。

// 兜底相机目录清单：单一事实来源在 AdbBridge 的 companion（发现逻辑也在那边用同一份），
// 这里只做别名，避免两处各抄一份迟早漂移。
private val ADB_CAMERA_DIRS: List<String> = AdbBridge.DEFAULT_CAMERA_DIRS

// UI 上的连接方式；与 internal 的 ConnectionMode 一一对应，避免把 internal 枚举暴露给 :ui。
enum class PhoneLinkMode(val label: String) {
    AUTO("自动（先 ADB，后 USB 盘符）"),
    ADB("ADB（USB 调试）"),
    USB_DRIVE("USB 盘符 / 手动目录"),
}

// 手机目录下拉的一项。[id] 会写进 sync-state.json，换目录即被冲突检测拦下。
data class PhoneDirEntry(val id: String, val label: String)

/**
 * 自检提示项在 [PhoneDirEntry.id] 上的前缀。
 *
 * 「手机目录为什么是空的」必须能讲清楚，而 :ui 只认 `Actions.phoneDirs` 这一条通道
 * （Actions 的签名本轮不在允许改动范围内）。所以自检结论复用同一个返回类型：
 * id 带本前缀的项**不是可选目录**，UI 必须把它渲染成一行提示文字、不进可点击列表。
 * 这样 [PhoneSyncRequest.phoneDir] 永远不会收到提示文案。
 */
const val PHONE_DIR_HINT_PREFIX: String = "hint:"

/**
 * 手机环境自检结论（[DesktopCore.selfCheck]）。
 *
 * 字段只用 JVM 基础类型 + `List<String>`：:ui 拿到它不需要 import `core/sync` 里的任何
 * internal 类型，也不碰 `domain.*`（P5 门禁口径与 [PhoneDirEntry] 一致）。
 */
data class SelfCheckResult(
    /** 设备在线**且**至少发现一个照片目录。 */
    val ok: Boolean,
    /** `adb devices` 里状态恰为 device 的条数。 */
    val devicesOnline: Int,
    /** 存在 unauthorized 设备（手机上还没点「允许 USB 调试」）。 */
    val unauthorized: Boolean,
    /** 动态发现的照片目录（不回退默认清单，因此可能是空的）。 */
    val photoDirs: List<String>,
    /** 给用户看的提示，按发现顺序；空列表 = 没发现问题。 */
    val hints: List<String>,
)

data class PhoneSyncRequest(
    val sourceDir: String,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val phoneDir: String,
    val mode: PhoneLinkMode,
    // 用户已确认「另一目录 / 跨年同名」冲突。
    val confirmConflict: Boolean = false,
)

data class PhoneSyncProgress(
    val done: Int,
    val total: Int,
    val written: Int,
    val skipped: Int,
    val renamed: Int,
    val current: String,
    val connectionLabel: String,
)

data class PhoneSyncResult(
    val folderName: String,
    val targetDir: String?,
    val connectionLabel: String,
    val written: Int,
    val skipped: Int,
    val rewritten: Int,
    val renamed: Int,
    val filtered: Int,
    val outOfRange: Int = 0,
    val unreadable: Int = 0,
    val failed: Int,
    val cancelled: Boolean,
    // 需要用户确认才能继续（另一目录 / 跨年同名），不静默合并。
    val conflict: String?,
    val error: String?,
    val summary: String,
) {
    companion object {
        fun failure(text: String): PhoneSyncResult = PhoneSyncResult(
            folderName = "", targetDir = null, connectionLabel = ConnectionKind.NONE.label,
            written = 0, skipped = 0, rewritten = 0, renamed = 0, filtered = 0, failed = 0,
            cancelled = false, conflict = null, error = text, summary = text,
        )
    }
}

// 纯函数转发对象：UI 的 model 层与单测可以**不持有 DesktopCore 实例**就调用同一套规则。
// 规则本体只在 domain.sync.SyncRangeFolder 里定义一次（F6，单一事实来源）。
object PhoneSyncFormulas {
    fun folderNameFor(start: LocalDateTime, end: LocalDateTime): String =
        SyncRangeFolder.folderName(start, end)

    fun validateRange(start: LocalDateTime?, end: LocalDateTime?): String? =
        SyncRangeFolder.validateRange(start, end)

    fun defaultRange(patrolDate: LocalDate): Pair<LocalDateTime, LocalDateTime> =
        SyncRangeFolder.defaultRange(patrolDate)
}

private fun PhoneLinkMode.toCoreMode(): ConnectionMode = when (this) {
    PhoneLinkMode.AUTO -> ConnectionMode.AUTO
    PhoneLinkMode.ADB -> ConnectionMode.ADB
    PhoneLinkMode.USB_DRIVE -> ConnectionMode.USB_DRIVE
}

private fun CoreSyncProgress.toUi(): PhoneSyncProgress = PhoneSyncProgress(
    done = done,
    total = total,
    written = written,
    skipped = skipped,
    renamed = renamed,
    current = current,
    connectionLabel = connection.label,
)

private fun PhoneSyncOutcome.toUi(): PhoneSyncResult = PhoneSyncResult(
    folderName = folderName,
    targetDir = targetDir?.toString(),
    connectionLabel = connection.label,
    written = written,
    skipped = skipped,
    rewritten = rewritten,
    renamed = renamed,
    filtered = filtered,
    outOfRange = outOfRange,
    unreadable = unreadable,
    failed = failed,
    cancelled = cancelled,
    conflict = conflict,
    error = error,
    summary = resultText(this),
)

// 结果文案按「失败 > 冲突 > 取消 > 已是最新 > 正常完成」由硬到软，不把失败说成成功。
private fun resultText(o: PhoneSyncOutcome): String = when {
    o.error != null -> o.error.orEmpty()
    o.conflict != null -> o.conflict.orEmpty()
    o.cancelled -> "已取消：保留已写入的 ${o.written} 张，可稍后继续。"
    o.written == 0 && o.skipped > 0 -> "已是最新：${o.skipped} 张已存在，无需重复导入。"
    else -> buildString {
        append("完成：写入 ${o.written} 张")
        if (o.skipped > 0) append("、跳过 ${o.skipped} 张")
        if (o.rewritten > 0) append("（其中重写 ${o.rewritten} 张")
        if (o.renamed > 0) append(if (o.rewritten > 0) "、另存 ${o.renamed} 张" else "（其中另存 ${o.renamed} 张")
        if (o.rewritten > 0 || o.renamed > 0) append("）")
        // 「筛掉」不是一个原因：确定在区间外 vs 拍摄时间根本读不到，给用户的下一步动作不同。
        // o.filtered 仍是两者之和（总数语义不变），但这句话按两个归因分别拼。
        when {
            o.outOfRange > 0 && o.unreadable > 0 ->
                append("、区间外 ${o.outOfRange} 张、${o.unreadable} 张无法读取拍摄时间")
            o.outOfRange > 0 ->
                append("、区间外 ${o.outOfRange} 张")
            o.unreadable > 0 ->
                append("、${o.unreadable} 张无法读取拍摄时间（可能是 PNG/WEBP/HEIC 或 EXIF 被剥离）")
            else -> {} // 两类都没有就不追加，避免「区间外 0 张」这种冗余
        }
        if (o.failed > 0) append("、失败 ${o.failed} 张")
        append("。")
    }
}
