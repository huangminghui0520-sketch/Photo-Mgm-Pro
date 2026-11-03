// app/AppViewModel.kt —— UI 状态投影 + 平台编排（T11：从 1221 行降到薄壳）
//
// ★ T11 的切法（与桌面端 T10 同构）：
//   分析流水线、视图派生、人工调整、三态展开、台账投影、设置失效判定**全部**在
//   `shared/app` + `shared/domain`，与桌面端共用同一份。本类只剩三件平台工作：
//     1. **状态投影**：`AppState` + `PhotoSession` → [UiState]（见 `core/UiProjection.kt`）；
//     2. **SAF / 权限 / 缩略图**：系统选择器、`takePersistableUriPermission`、Coil 缓存；
//     3. **协程形态**：`viewModelScope` + `Job`（桌面端对应 Executor + OperationHandle）。
//
// ★ 公开方法签名**全部保留**（`parseLogs` / `exportZip` / `movePhotos` / `markLedgerPhoto` / …）：
//   UI 层因此零改动。这不只是省事——`ExportGateTest` / `ThumbnailRefreshTest` /
//   `SetLogsTextWiringTest` 都是对着这些签名与注入点写的，改签名等于把这轮重构的护栏拆掉。
package com.photomgm.app

import algorithm.AppZone
import algorithm.ClassifyResult
import algorithm.DedupKeepMode
import algorithm.ParsedLog
import algorithm.model.Photo
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.api.LedgerRowInfo
import app.api.EventSnapshot
import app.api.ExportBlockedException
import app.api.ExportListener
import app.api.ExportProgress
import app.api.ExportResult
// T06：图片解码/裁剪/压缩是纯平台职责，已归位到 :data（原 app/ImageUtils.kt）。
// T06：用系统应用打开导出文件（FileProvider + ACTION_VIEW + chooser）同属平台职责。
import com.photomgm.data.shell.UriShellLauncher
// T05-C1：取消语义上移到 :shared:app（`runCatchingCancellable` 原来在 app/Cancellable.kt）。
import app.infra.runCatchingCancellable
import com.photomgm.app.core.AndroidCore
import com.photomgm.app.core.ExportUi
import com.photomgm.app.core.projectExportResult
import com.photomgm.app.core.projectUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean

/**
 * UI 状态（形状与 T11 之前**完全一致**，UI 层零改动）。
 *
 * ★ `parsed` / `photos` / `classify` / `overrideMap` / `marked` 仍是算法层的原始对象：
 *   投影层直接给的是 `PhotoSession` 的同一个引用（理由见 `UiProjection.kt` 文件头）。
 *
 * ★ 唯一的类型变化：`ledger` 从 `List<LedgerRow>?` 变成 `List<LedgerRowInfo>?`
 *   （`LedgerRowInfo` 是 `domain.ledger.ProjectedLedgerRow` 的别名，`photo1Ref` → `photo1Label`）。
 *   UI 只读 `state.ledger?.size`（`UnifiedScreen.kt` 一处），所以源码不用改。
 *   换掉的理由：共享层的 `ViewPublisher` 产出的是**展示行**（带"文件夹名/文件名"前缀），
 *   预览与导出的台账必须同源；若在 ViewModel 里再转一份 `LedgerRow`，就是又造了一份重复。
 *   写 xlsx 用的原始行在导出计划里（`ExportPlan.ledger`），不经过 UI。
 */
data class UiState(
    val date: LocalDate = LocalDate.now(AppZone.ID),
    val sourceDirs: List<String> = emptyList(),
    val outputDir: String? = null,
    val namingTemplate: Int = 1,          // 0=事件类型，1=巡查日志内容（默认1）
    /** ★ 2026-10-24 B1：重复照片保留策略（带水印/不带水印/都保留），默认仅保留带水印。 */
    val dedupKeepMode: DedupKeepMode = DedupKeepMode.KEEP_WATERMARKED,
    val coarseCount: Int? = null,          // 粗筛「已筛出 N 张」
    val logsText: String = "",
    val parsed: ParsedLog? = null,
    /** ★ 事件快照（`AppState.events` 投影，供 UI 渲染事件卡片/弹窗；替代读 `parsed.events` 的算法层对象）。 */
    val events: List<EventSnapshot> = emptyList(),
    val photos: List<Photo> = emptyList(),
    val classify: ClassifyResult? = null,
    val ledger: List<LedgerRowInfo>? = null,
    val busy: Boolean = false,
    /** ★ 2026-10-07 P2：流水线分阶段进度文案（busy 时在进度条下方显示；完成/失败置 null） */
    val progressStage: String? = null,
    val message: String? = null,
    /** 人工移动：photoId → 目标事件（§8.3），持久化在 SettingsStore */
    val overrideMap: Map<Long, Int> = emptyMap(),
    /** 台账照片标记：eventId → (照片1,照片2)，null 侧=默认最早+最晚（§7/§8.2） */
    val marked: Map<Int, Pair<Long?, Long?>> = emptyMap(),
    /** 导出进度 0..1；null=无进行中导出（§8.2 进度条） */
    val exportProgress: Float? = null,
    /** 导出完成信息（§7.4 完成页「成功N/失败M」） */
    val exportMessage: String? = null,
    /**
     * ★ 2026-10-31 审查修复：本次导出是否成功（null=尚未导出过）。
     *   [exportMessage] 在成功与失败时都非 null，底栏无法据此区分；失败却显示「导出完成 ✓」
     *   会让用户误以为产物已生成并据此外发。本字段是唯一的成功/失败判据。
     */
    val exportOk: Boolean? = null,
    /** 最近一次导出文件（用户可见位置：content:// 或本地路径），供「打开」入口 */
    val lastExport: String? = null,
    /**
     * ★ P0（2026-11-02）：本次会话**能否读到照片的真实位置信息**。
     *   null = 尚未判定（权限对话框还没回来）；
     *   false = 系统会剥离 EXIF GPS → 去重降级为「时间 + 文件名序号」、导出照片无定位。
     *   仅用于「说清降级口径」（设置页提示 / 权限回调提示），不参与算法分支：
     *   算法侧看的是 [Photo.latitude] 本身是否为 null，这条只是让用户知道**为什么**。
     */
    val geoAvailable: Boolean? = null,
)

/**
 * 缩略图预加载上限。
 * 超过这个数量就放弃预加载、交回事件卡片的惰性加载：
 * 一次解码上百张小图会长时间占用内存与主线程，收益不如让用户滚动时按需解码。
 */
private const val THUMB_PRELOAD_MAX = 500

/**
 * ★ 2026-10-08 P1-4：台账缩略图解码并发数（CPU 密集）。
 * 单张解码峰值约 2MB（已采样 800×600），4 并发瞬时 ≈ 8MB，安全；真机可调。
 */
private const val THUMB_DECODE_CONCURRENCY = 4

/**
 * 台账 xlsx 的 MIME（T06）：「打开台账」要告诉系统用哪类应用。
 *
 * 这个值留在 `:app` 而不进 `UriShellLauncher` 的原因：它是**业务决定**
 * （"我们导出的这个文件是 Excel"），而 UriShellLauncher 只负责"把给它的 MIME 交给系统"。
 * 两端（PC 导出器 / 安卓导出器）都认这个格式，将来若增加 PDF 导出，MIME 也是调用方选。
 */
private const val XLSX_MIME_TYPE =
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

/**
 * ★ 构造函数必须**只有 (Application) 一个参数**。
 *
 * 原因：框架的 `AndroidViewModelFactory` 通过反射查找恰好为 `(Application)` 的构造函数。
 * 一旦增加第二个参数（即使它有默认值），工厂就找不到构造函数，抛出
 * `RuntimeException: Cannot create an instance of class AppViewModel`，
 * 而 MainActivity 第一行就是 `viewModel()` —— 结果是打开即闪退（2026-10-07 实际踩过）。
 *
 * 需要注入依赖时，用次级构造函数或工厂方法，**不要**动这个签名。
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    /**
     * 设置与状态缓存的业务层。
     *
     * T11 之后它只剩下两个用途：`parsedLogsText`（"日志改没改过"的快照，安卓独有的快路径判据）
     * 与人工调整的落盘（`overrideMap` / `markedMap`）。设置项本身走 [core] 的用例。
     */
    private val store = SettingsStore(app)

    /**
     * 用系统应用打开导出文件（T06：平台壳层职责已归位到 `:data`）。
     * 本类只决定"要不要开"，不再关心 FileProvider authority / ACTION_VIEW / chooser。
     */
    private val shellLauncher = UriShellLauncher(app)

    /**
     * 日志文本落盘器（W3 / issue #4）：输入时只更新内存态，落盘防抖 400ms。
     * 见 [LogsTextStore] 头部说明——以前每敲一个字符就 MB 级全量重写 SharedPreferences。
     *
     * ★ `internal` var 是**唯一**的安全注入点（构造函数不能被扩展，见类头注释）。
     *   `core` 通过 [LogsTextStore.delegate] 取当前实例，所以整体替换后立即生效——
     *   `SetLogsTextWiringTest` 就是靠这条断言"接线"的。
     */
    internal var logsTextStore: LogsTextStore = LogsTextStore(
        persist = { store.logsText = it },
        scope = viewModelScope,
    )

    /** ★ 组装根：分析与视图派生的全部实现都在共享层，这里只接平台端口。 */
    private val core = AndroidCore(app) { logsTextStore.delegate }

    /**
     * 缩略图缓存。默认接 Coil 全局单例（见 [CoilThumbnailCache]）。
     *
     * ★ `internal` setter 仅供单元测试注入假实现以验证"先清后缓存"的顺序——
     *   因为构造函数签名不能被扩展（见上方注释），这是唯一安全的注入点。
     */
    internal var thumbCache: ThumbnailCache = CoilThumbnailCache(app)

    /**
     * ★ 供平台组件获取 applicationContext（§2.4 核心不碰平台，本类负责提供）。 */
    fun getApp(): Application = getApplication()

    /**
     * 导出结果/进度的**本地**状态（安卓独有）。
     *
     * ★ 为什么不进共享 `AppState`：共享层用 `lastExport: ExportResult` + `message` + `error`
     *   表达同一件事，字段语义更全；而安卓底栏只需要"进度条 + 一句话 + 成功与否"三个值。
     *   把这三个字段塞进共享契约，桌面端就得永远背着三个用不到的成员。
     *   T11 提交②把导出切成 `ExportUseCase` 后，这里会退化成对 `ExportResult` 的投影。
     */
    private val exportUi = MutableStateFlow(ExportUi())

    /**
     * UI 状态流。
     *
     * ★ `value` 是**即时计算**的（不是上一次派发的缓存）：`ThumbnailRefreshTest` 在主线程
     *   同步读 `state.value.photos`，若投影异步（`collect` 到 Main），它会读到上一帧的旧值，
     *   于是"清缓存那一刻 photos 应为 0"这条断言会变成随机的假失败。详见 `UiProjection.kt`。
     */
    val state: StateFlow<UiState> = object : StateFlow<UiState> {
        override val value: UiState
            get() = project(core.state.value, exportUi.value)

        override val replayCache: List<UiState> get() = listOf(value)

        override suspend fun collect(collector: FlowCollector<UiState>): Nothing {
            combine(core.state, exportUi) { app, ex -> project(app, ex) }
                .collect { collector.emit(it) }
            kotlinx.coroutines.awaitCancellation()
        }
    }

    private fun project(
        app: app.api.AppState,
        ex: ExportUi,
    ): UiState = projectUiState(
        app = app,
        session = core.session,
        geoAvailable = geoAvailable,
        exportProgress = ex.progress,
        exportMessage = ex.message,
        exportOk = ex.ok,
        lastExport = ex.lastExport,
    )

    /** ★ 权限落盘是持久的，构造时即可同步判定（不必等权限对话框回调）。 */
    private var geoAvailable: Boolean? = currentGeoAvailable()

    /** ★ 自动流水线任务句柄：日期/源目录变更后触发，新变更取消旧任务，避免并发扫描。 */
    private var pipelineJob: Job? = null

    /**
     * ★ 2026-10-31 审查修复：导出重入门禁（原子的 check-then-act）。
     *   false=空闲，true=正在导出。由 exportZip 在 launch 之前 CAS 置位；复位有**两处**：
     *   协程体的 finally（正常/异常/取消路径）+ 导出 Job 的 `invokeOnCompletion`
     *   （协程还没开始执行就被取消的路径 —— 那时 finally 根本不会跑）。
     *   不用 UiState.exportProgress 兼作门禁的原因见 exportZip 内注释（读-改-写非原子）。
     *
     *   `internal` 只为让测试能直接断言"取消后门禁确实释放了"。
     */
    internal val exportBusy = AtomicBoolean(false)

    /**
     * 导出协程所用的调度器（E / issue #6）。
     *
     * ★ 默认 `Dispatchers.IO` 即生产行为；注入点存在的唯一理由是**可测性**：
     *   原先 `viewModelScope.launch(Dispatchers.IO)` 写死，测试只能靠 `await { ... }` 轮询真实墙钟
     *   （本机 ~7.8s 且不确定），也无法构造"协程还没开始就被取消"这种时序。
     *   测试把它换成 `StandardTestDispatcher(testScheduler)`，配 `runTest` 精确推进虚拟时间。
     * ★ 与 [thumbCache]、[logsTextStore] 一样用 `internal var` 注入，不动构造函数签名
     *   （AndroidViewModelFactory 只认 `(Application)` 单参构造，见类头注释）。
     */
    internal var exportDispatcher: CoroutineDispatcher = Dispatchers.IO

    /**
     * ★ 2026-10-31 复核：导出 / 解析互斥的**另一半**（补全此前只有半边的门禁）。
     *
     *   已有的一半：exportZip 开头检查 `state.value.busy` —— 解析流水线跑着就不许导出。
     *   缺失的一半："导出跑着就不许重启解析"。不补全的后果：导出过程中改日期/改源目录/点解析，
     *   流水线会把 photos / classify / ledger 清空重算，而导出协程手里握的是**入口时抓的快照 s**，
     *   仍按 s.photos 逐张读盘打包——于是 ZIP 与界面、与台账三方不再对应；更糟的是断点续传
     *   状态文件按日期命名，新旧两批照片会在同一份续传状态里交叉，下次导出跳过本该打包的照片。
     *
     *   ★ 判据用 [exportBusy] 而不是 state.value.exportProgress：后者是**UI 状态**，
     *     提前返回分支会把它置 null，语义是"进度显示"，不是"是否正在导出"。
     *   ★ 只做同步判断 + 提示，不引入锁：解析与导出各跑各的协程，这里只是拒绝"启动"这一刻，
     *     成本是一次 volatile 读。
     *
     * @return true = 被导出挡住，调用方应直接 return（不启动流水线）
     */
    private fun blockedByExport(): Boolean {
        if (!exportBusy.get()) return false
        core.notify("导出进行中，请等待导出完成后再操作")
        return true
    }

    init {
        // ★ 2026-10-07 防线一：恢复的照片**异步强制校验**——进程重启期间照片可能被删除/新增
        //   （ContentObserver 收不到），靠这里发现：实时粗筛 ID 集合不一致 → 清照片/分类缓存 → 自动重扫。
        //   parsed（事件列表）与照片无关，保留。
        viewModelScope.launch(Dispatchers.IO) {
            val s0 = state.value
            if (s0.sourceDirs.isEmpty() || s0.photos.isEmpty()) return@launch
            val latest = core.currentPhotoIds(s0.date, s0.sourceDirs)
            if (latest != s0.photos.map { it.id }.toSet()) {
                core.clearCache()
                core.invalidateAnalysis()
                autoPipeline()
            }
        }
    }

    // ---------- 设置（§8.4 设置即存） ----------
    /**
     * ★ 巡查日期变更：自动触发 粗筛→精读（无需手动点击）；同时清空下游避免旧分类配新日期导出错乱。
     * 清粗筛缓存（§4.4）→ 确保从 MediaStore 重新查询最新照片，再自动重新匹配。
     *
     * ★ 同时清空缩略图缓存：换日期后上一批照片的缩略图已无用，且会白占内存额度。
     *   顺序为「清会话视图 → 清缓存 → 清缩略图 → 重新读取照片 → 预缓存新缩略图」。
     *
     * ★ 2026-10-07（P1）：日期变更 = 旧解析/分类缓存全部失效（事件时间戳基于旧日期）。
     *   界面回到"该日期尚未解析日志"，不残留旧日期事件。
     */
    fun setDate(d: LocalDate) {
        // ★ 日期没变就不重跑：避免重复选同一天时清掉刚缓存的缩略图、导致界面闪一下重新解码
        if (d == store.date) return
        core.updateScanScope { it.copy(patrolDate = d) }
        // ★ 顺序不能换：先让会话视图变空（`state.value.photos` 同步变成 0），
        //   再清缩略图缓存 —— `ThumbnailRefreshTest` 断言"清缓存那一刻照片数应为 0"，
        //   反过来就会清掉上一批照片的缓存后才告诉 UI 已经清空。
        core.invalidateAnalysis()
        core.clearCache()
        clearThumbnailCache()
        autoPipeline()
    }

    /** ★ 源目录变更：自动触发 粗筛→精读（照片集合已变，清空下游）。清缓存后重新查询最新照片。 */
    fun addSourceDir(dir: String) {
        val dirs = (state.value.sourceDirs + dir).distinct()
        applySourceDirs(dirs)
    }

    fun removeSourceDir(dir: String) {
        applySourceDirs(state.value.sourceDirs - dir)
    }

    private fun applySourceDirs(dirs: List<String>) {
        core.updateScanScope { it.copy(sourceDirs = dirs) }
        core.invalidateAnalysis()
        core.clearCache()
        clearThumbnailCache()
        autoPipeline()
    }

    /**
     * ★ 自动流水线：**只扫照片**（粗筛 → 派生去重 → EXIF 精读），不解析日志、不分类。
     *   变更即触发；取消旧任务防止快速连续操作导致旧结果覆盖新结果。
     *
     * ★ T11 恢复了旧实现的这条语义（「选日期只把照片扫出来」）：
     *   一度改成调完整的 [AndroidCore.analyze]，后果是 `parsed` 提前非空 ——
     *   `parseLogs` 的"日志没改过 → 只清人工移动"快路径会误判，用户点解析只得到一句
     *   "已是最初分类状态"，事件列表永远空。判据见 `AnalysisPipeline.scanPhotos` 的注释。
     */
    private fun autoPipeline() {
        // ★ 2026-10-31 复核：与导出互斥的半边（导出期间不重启扫描流水线）
        if (blockedByExport()) return
        pipelineJob?.cancel()
        pipelineJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val s = state.value
                if (s.sourceDirs.isEmpty()) {
                    // 旧语义：没有源目录就什么都不扫，只把下游清空（提示引导用户去选目录）
                    core.invalidateAnalysis()
                    return@launch
                }
                scanBlocking()
            } catch (e: CancellationException) {
                throw e   // ★ 协程取消必须重抛（结构化并发），不吞
            } catch (e: Exception) {
                // ★ 2026-10-31 崩溃防御：照片扫描流水线任何未预期异常不得杀死应用
                //   （共享流水线内部已把异常收口成 FAILED 状态，这里兜的是端侧故障）
                Log.w("Photo-Mgm", "扫描照片失败", e)
                core.notify("扫描照片失败：${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /**
     * 阻塞地扫一遍照片，并完成三件端侧收尾：
     * ① 人工调整落盘；② 状态缓存落盘（照片缓存，供下次启动恢复）；③ 缩略图预缓存。
     *
     * ★ 预缓存放在**扫描之后**（而不是像旧实现那样夹在"精读完成、分类之前"）：
     *   流水线是一口气跑完的，中间没有钩子。放在末尾的代价是预加载晚几十毫秒开始，
     *   收益是它拿到的 `photos` 一定是**最终**那批（旧实现拿的是精读后、去重前的批次）。
     *   `ThumbnailRefreshTest` 的判据（清缓存时 0 张、预加载时 > 0 张且 refs 与本批一致）都成立。
     */
    private fun scanBlocking() {
        core.scanPhotos(listener = null, isCancelled = { !viewModelScope.isActive })
        persistManual()
        // ★ 预缓存必须**紧贴**扫描结果，中间不夹任何慢操作：
        //   `persistCache()` 会把整批照片序列化成 JSON 写进 SharedPreferences（几十毫秒到几百毫秒），
        //   一旦它排在预缓存之前，"取消旧扫描 + 开始新扫描"就有足够窗口插进来把 `photos` 清空，
        //   于是旧协程收尾时读到空列表 —— 预缓存白做一次，且 `ThumbnailRefreshTest` 会看到
        //   "预加载时照片数为 0"。旧实现把预缓存放在 EXIF 之后、publish 之前，正是同一个道理。
        preloadThumbnailCache(core.session.photos)
        core.persistCache()
    }

    /**
     * 阻塞地跑一遍**完整**分析（解析日志 + 扫描 + 分类），并完成端侧收尾。
     * 只被 [parseLogs] 使用 —— 那是唯一"应该解析日志"的入口。
     */
    private fun analyzeBlocking() {
        core.analyze(listener = null, isCancelled = { !viewModelScope.isActive })
        persistManual()
        preloadThumbnailCache(core.session.photos)
        core.persistCache()
    }

    /**
     * ★ 人工调整落盘（安卓独有）。
     *
     * 共享层的 [app.usecase.AdjustUseCase] 只改会话状态、不落盘（桌面端重启不恢复人工调整，
     * 这是两端**产品形态的差异**，不是共享层的疏漏）。安卓要求关掉 App 再打开仍能看到
     * 上次的移动/标记，所以由本类在每次调整后把会话里的两张表写回存储。
     * 缓存版本位不变：`overrideMap` 的键是 MediaStore `_ID`，重扫后不变（见 `SettingsStore` 注释）。
     */
    private fun persistManual() {
        val manual = core.session.manual
        store.overrideMap = manual.overrideMap
        store.markedMap = manual.marked
    }

    /**
     * ★ 清空缩略图缓存（内存 + 磁盘）。
     *
     * 调用时机：巡查日期变更、源目录增删——即"上一批照片已经作废"的时刻。
     * 顺序：invalidateAnalysis() → clearThumbnailCache() → 流水线 → preloadThumbnailCache()
     * 必须先清后读，否则新照片的预缓存会和上一批的残留混在同一份缓存里，旧图白占额度。
     */
    private fun clearThumbnailCache() {
        runCatching { thumbCache.clear() }
    }

    /**
     * ★ 预缓存本批照片的缩略图，写入内存缓存供事件卡片直接命中。
     * 时机：分析流水线之后（此时才知道本批照片的真实路径）。
     * 预加载在 Coil 内部异步进行，此处不阻塞流水线。
     */
    private fun preloadThumbnailCache(photos: List<Photo>) {
        runCatching {
            thumbCache.preload(
                refs = photos.map { it.sourceRef },
                maxCount = THUMB_PRELOAD_MAX,
            )
        }
    }

    fun setOutputDir(d: String?) = core.updateSettings { it.copy(outputDir = d) }

    fun setNamingTemplate(t: Int) = core.updateSettings { it.copy(namingTemplate = t) }

    /**
     * ★ 2026-10-24 B1：重复照片保留策略切换（带水印 / 不带水印 / 都保留）。
     * 仅作用于输出层（台账/ZIP）：不重跑粗筛/EXIF/分组/分类；已分类事件照片按三态展开，
     * 人工标记（marked）优先保留；未匹配照片不参与、全量保留。
     *
     * ★ T11：同步重算在共享层（`SettingsUseCase.setDedupMode`），那段 P2-1 的竞态修复随之共用。
     */
    fun setDedupKeepMode(mode: DedupKeepMode) = core.setDedupMode(mode)

    // ---------- SAF 目录选择（§8.2 系统选择器选取，不再手打路径） ----------

    /**
     * 源目录选择：OpenDocumentTree 返回 content://…/tree/primary%3ADCIM%2FCamera，
     * 从 document id 提取相对路径（primary:DCIM/Camera → DCIM/Camera）供 MediaStore 查询。
     */
    fun pickSourceDir(uri: Uri) {
        val seg = uri.lastPathSegment ?: return
        val rel = Uri.decode(seg).substringAfter(':').trim('/')
        if (rel.isNotBlank()) addSourceDir(rel)
    }

    /** 输出目录选择：持久化 SAF 树授权（重启后可继续读写）。 */
    fun pickOutputDir(uri: Uri) {
        runCatching {
            getApp().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        setOutputDir(uri.toString())
    }

    // ---------- 照片流程（§4：粗筛 → 精读） ----------
    /**
     * ★ 被动触发兜底（切换到日志页时调用）：源目录 + 日期就绪且「尚未完成粗筛」时，
     * 补一次流水线，避免首次进入日志页时照片列表为空。
     * 已粗筛（coarseCount != null）则跳过——日期/源目录变更已由 setDate/addSourceDir/removeSourceDir 触发，
     * 防止每次切页重复扫描 MediaStore 与 EXIF。
     */
    fun ensurePipeline() {
        val s = state.value
        if (s.sourceDirs.isEmpty() || s.busy || s.coarseCount != null) return
        autoPipeline()
    }

    // ---------- 日志（§3：粘贴 → 解析，如实显示不脱敏） ----------
    /**
     * 日志文本变化（W3 / issue #4）。
     *
     * ★ 只更新内存态，落盘交给 [logsTextStore] 防抖：输入框的即时反馈不受写盘影响，
     *   连打时只在停手后写一次。解析用的是 `state.logsText`（内存态），因此"刚打完就点解析"
     *   拿到的一定是最新文本，不依赖落盘是否已完成。
     */
    fun setLogsText(text: String) = core.setLogText(text)

    /** ★ 删除日志：清文本 + 清解析/匹配结果（P3-4：粗筛已无 TTL 缓存，无需再清），避免旧匹配残留。 */
    fun clearLogs() {
        // ★ W3：必须先取消待落盘，否则防抖窗口到期会把刚删掉的旧文本写回去（表现为"删了又回来"）
        logsTextStore.cancelPending()
        store.logsText = ""
        store.parsedLogsText = ""
        // ★ 只清状态快照，不能再排一次落盘（见 AndroidCore.clearLogText 的说明）
        core.clearLogText()
        core.clearCache()
        core.invalidateAnalysis()
    }

    /**
     * W3：ViewModel 销毁前把待落盘的日志文本刷下去 —— 防抖窗口还没到期就退出 App，
     * 也不能丢最后一次输入。
     */
    override fun onCleared() {
        logsTextStore.flush()
        super.onCleared()
    }

    /**
     * ★ 2026-10-07 需求重定义「解析日志」（P0：照片流水线与日志解析解耦）：
     *   - 快路径：日志已解析、文本未改动、已有分类、**照片未增删**（防线一校验）→ **只清人工移动表**，
     *     分类恢复为最初自动分类状态；**不重跑粗筛/精读/分类**（classify 从未被 overrideMap 修改，
     *     它只是叠加层，清掉即恢复最初分类）。
     *   - 完整流水线：照片未就绪或**校验不一致（照片已增删）**→ 清缓存重新 粗筛→精读→分类。
     * ★ 2026-10-08 修复：快路径前置照片校验——否则删除照片后文本未改仍命中快路径，
     *   旧 classify 引用已删照片 URI → 事件网格显示灰色方块缩略图。
     *
     * ★ T11：快路径判定留在本类（"解析时刻的日志快照"是安卓独有的持久化概念，
     *   共享流水线每次都是完整分析）；完整路径委派 [AndroidCore.analyze]。
     */
    fun parseLogs() {
        // ★ 2026-10-31 复核：与导出互斥的半边（导出期间不重启解析流水线）
        if (blockedByExport()) return
        val s = state.value
        // ── 快路径：已解析 + 已分类 + 文本未变 + 照片未增删 → 只清人工移动表 ──
        if (s.parsed != null && s.classify != null && s.logsText == store.parsedLogsText) {
            // ★ 防线一：照片校验——删除/新增照片后即使文本未改也要重扫重分类，
            //   避免旧 classify 引用失效 URI（灰块）。
            val photosUnchanged = s.sourceDirs.isEmpty() || s.photos.isEmpty() ||
                core.currentPhotoIds(s.date, s.sourceDirs) == s.photos.map { it.id }.toSet()
            if (photosUnchanged) {
                // ★ P3-8：人工移动被清空 → 分类恢复最初状态，共享层同步重算并刷新台账行
                core.resetManualAdjustments()
                persistManual()
                return
            }
            // 照片已增删 → 落入下方完整流水线（清缓存重扫 + 重新解析分类）
        }
        // ★ 记录本次解析的文本快照（供"只清人工移动"分支判定日志是否被修改）
        store.parsedLogsText = s.logsText
        pipelineJob?.cancel()
        pipelineJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                if (s.sourceDirs.isEmpty()) {
                    // 无源目录：只解析日志（共享流水线内部即此语义），不必清照片缓存
                    core.clearCache()
                }
                analyzeBlocking()
            } catch (e: CancellationException) {
                throw e   // ★ 协程取消必须重抛（结构化并发），不吞
            } catch (e: Exception) {
                // ★ 2026-10-31 崩溃防御：解析流水线任何未预期异常都不得杀死应用
                Log.w("Photo-Mgm", "解析日志失败", e)
                core.notify("解析日志失败：${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /**
     * ★ 2026-10-07 防线二：手动刷新照片缓存（设置页入口）。
     * 清进程内单日缓存 + 缩略图缓存 + 持久化状态缓存，强制完整重扫（P3-4：粗筛已无 TTL 缓存）。
     * 事件列表（parsed）保留——照片变化不影响日志事件；重新解析即自动重新分类。
     */
    fun refreshPhotoCache() {
        core.invalidateAnalysis()
        clearThumbnailCache()
        core.clearCache()
        autoPipeline()
    }

    // ---------- 分类 + 台账（§5/§6/§7） ----------

    /**
     * 台账行异步重建。
     *
     * ★ T11 起本方法不再需要自己做重算：共享层的 `ViewPublisher.publish` 在分析、
     *   人工调整、三态切换、导出收尾四个入口都会**一次算齐**并发布台账，
     *   旧实现那套"ledgerJob + isActive 检查 + 取消旧任务"的防竞态措施也随之消失
     *   （同步发布天然没有"晚到覆盖"）。保留方法是为了守住公开签名（UI 可能调用）。
     */
    fun buildLedger() = core.republish()

    // ---------- 人工调整（§8.3） ----------
    /** 把照片移动到目标事件（从原位置移除，含 unmatched）。 */
    fun movePhoto(photoId: Long, targetEventId: Int) = applyMove(listOf(photoId), targetEventId)

    /** ★ 批量移动照片到目标事件（§8.3 多选移动）。 */
    fun movePhotos(photoIds: List<Long>, targetEventId: Int) {
        if (photoIds.isEmpty()) return
        applyMove(photoIds, targetEventId)
    }

    /**
     * ★ 从事件移除照片 → 回到未匹配照片池（§8.3 弹窗「移除」）。
     * 仅调整分组归属，不删除源文件；overrideMap 记 -1 表示「移出所有事件」。
     *
     * ★ T11：「移除」= 移动到 `null`（共享层用 `AdjustmentLayer.REMOVED = -1` 表达），
     *   不再是本类手写 `map[it] = -1` —— 两端对"移除"的编码从此只有一处定义。
     */
    fun removePhotosFromEvent(photoIds: List<Long>) {
        if (photoIds.isEmpty()) return
        applyMove(photoIds, null)
    }

    /**
     * 人工移动的统一入口。
     *
     * ★ 分类未就绪时走 [AndroidCore.recordManualMove]：`AdjustUseCase` 在那种情况下会直接
     *   拒绝（"没分析过就没有归属概念"），而 `overrideMap` 作为独立叠加层并不依赖分类结果。
     *   生产路径上两者等价（UI 只在有分类时给入口），但双跑护栏 `ViewDerivationParityTest`
     *   是直接调 `movePhoto` 的，判据对着"无条件写入"的既有行为。
     */
    private fun applyMove(photoIds: List<Long>, targetEventId: Int?) {
        if (core.session.classify == null) core.recordManualMove(photoIds, targetEventId)
        else core.movePhotos(photoIds, targetEventId)
        persistManual()
    }

    /** ★ 重置全部人工移动（2026-10-01 新增）：清空 overrideMap，恢复纯自动分类结果（撤销所有手动调整）。 */
    fun resetOverrides() {
        // 分类未就绪时用例同样会拒绝——此时把叠加层直接清空即可（无语义争议：清空恒成立）
        core.resetManualAdjustments()
        persistManual()
    }

    fun clearMessage() = core.clearMessage()

    /** ★ UI 轻提示（Snackbar）：供界面层校验类提示（如禁止跨事件移动）使用。 */
    fun notify(msg: String) = core.notify(msg)

    // ---------- 权限（★ P0，2026-11-02） ----------

    /** 当前是否持有「照片位置信息」权限（API < 29 无此概念，视为可用）。 */
    private fun currentGeoAvailable(): Boolean {
        val geo = MediaPermissions.plan(Build.VERSION.SDK_INT).geo ?: return true
        return ContextCompat.checkSelfPermission(getApp(), geo) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 媒体权限申请结果回调（MainActivity 的 RequestMultiplePermissions 转交）。
     *
     * 职责有两个，缺一不可：
     *  1. 记录 [UiState.geoAvailable]，供设置页说明"为什么重复判定降级了"；
     *  2. 未授予位置权限时**主动提示降级口径**——静默降级会让用户把"去重结果"当准的用。
     *
     * ★ 刻意不在"新获得权限"时自动重扫：本轮照片已按旧口径解析完毕，静默重扫会打断用户
     *   （还可能要重新下载缩略图）。改成提示用户重新解析一次，见 [MediaPermissions.HINT_GEO_UNAVAILABLE]。
     */
    fun onMediaPermissionsResult(granted: Map<String, Boolean>, sdkInt: Int) {
        val available = MediaPermissions.geoAvailable(sdkInt, granted)
        val wasAvailable = geoAvailable
        geoAvailable = available
        if (!available) {
            notify(MediaPermissions.HINT_GEO_UNAVAILABLE)
        } else if (wasAvailable == false) {
            // 用户刚在系统设置里补授了权限 → 上一轮照片是按"无 GPS"解析的，提示重解析
            notify("已获得照片位置信息权限，请重新解析一次以更新定位与去重判定")
        }
    }

    // ---------- 导出（§7.3 Excel / §7.4 ZIP） ----------

    /** ★ 打开最近导出的台账文件（导出Excel 2.0 方案 §4.4：成功后提供「打开」）。
     *  用 Toast 直接提示（不依赖页面滚动位置）。
     *  ★ T06：FileProvider / ACTION_VIEW / chooser 全部移到 `:data` 的 [UriShellLauncher]，
     *    本方法只剩"按结果给用户一句话"——平台细节不再漏进编排层。 */
    fun openExportedFile() {
        val shown = state.value.lastExport ?: return
        when (shellLauncher.openDocument(shown, XLSX_MIME_TYPE, "打开台账")) {
            UriShellLauncher.Result.STARTED -> Unit
            UriShellLauncher.Result.URI_UNAVAILABLE ->
                android.widget.Toast.makeText(
                    getApp(), "无法打开：$shown", android.widget.Toast.LENGTH_LONG
                ).show()
            UriShellLauncher.Result.NO_VIEWER_APP ->
                android.widget.Toast.makeText(
                    getApp(), "未找到可打开表格的应用", android.widget.Toast.LENGTH_LONG
                ).show()
        }
    }

    /**
     * 导出 ZIP：按事件分类文件夹打包（命名模板由设置页选择），未匹配→未分类（§7.4）。
     * 压缩包同时包含 照片分类文件夹 + 台账 Excel。
     *
     * ★ T11 提交②：本方法从"自己实现导出"退化为**只负责协程形态与门禁**。
     *   计划生成（哪些照片进哪个文件夹、重名后缀、台账行）、前置闸门（未分析 / 未设目录 /
     *   无可导出照片）、结果回填，全部在共享 `ExportUseCase` + `AndroidZipExportExecutor`。
     *   本类保留的只有三件平台工作，理由与类头一致：
     *     1. **门禁必须是原子的 CAS 且发生在 launch 之前**（见下）；
     *     2. **协程生命周期**：Job 级兜底复位（协程还没开始就被取消时，body 里的 finally 不跑）；
     *     3. **状态投影**：`ExportResult` → 底栏要的"进度 + 一句话 + 成功与否"。
     *
     * ★ 导出重入门禁为什么不能交给共享层：共享层的闸门是"`status == EXPORTING` 就拒绝"，
     *   而状态变更发生在协程**体内** —— 两次快速点击会在同一个主线程 tick 内先后通过检查。
     *   原来踩过的故障是两个导出协程共用同一个 tmpZip（文件名精确到秒），先结束者删掉
     *   对方正在写的文件，产物损坏。CAS 把"检查 + 置位"压成一个操作，跨线程也成立。
     */
    fun exportZip() {
        // ★ 解析流水线跑着就不许导出：导出按入口快照打包，而流水线会把 photos / classify 换掉
        if (state.value.busy) return
        if (!exportBusy.compareAndSet(false, true)) return   // 已有导出在跑 → 直接丢弃本次点击
        // 清掉上一次的结果，避免旧文案误导（"成功 0"看起来像这次的结果）
        exportUi.update { it.copy(progress = 0f, message = null, ok = null, lastExport = null) }

        val exportJob = viewModelScope.launch(exportDispatcher) {
            // 取消查询取的是**本协程 Job** 的状态：`isCancelled` 会被一路传到执行器的每个
            // 文件边界，协程被取消时导出在下一条照片之前停下，不再白读几十张图。
            val selfJob = coroutineContext[Job]
            try {
                core.exportZip(
                    listener = object : ExportListener {
                        override fun onProgress(progress: ExportProgress) {
                            setExportProgress(progress.fraction)
                        }

                        override fun onFinished(result: ExportResult) {
                            // 文案 / 成功判据 / 台账路径三者的唯一投影入口（见 projectExportResult）
                            exportUi.value = projectExportResult(result)
                            // 失败明细在底栏放不下（底栏只有一行），走提示通道补一句可执行的处置建议
                            if (result.failed > 0) {
                                val sample = result.failures.take(2)
                                    .joinToString("；") { "${it.relativePath}（${it.reason}）" }
                                core.notify("有 ${result.failed} 张照片未打包：$sample")
                            }
                        }

                        override fun onFailed(error: Throwable) {
                            // 两类失败的呈现方式不同，混在一起会让用户误读：
                            //   · ExportBlockedException = 前置条件没满足（未设目录 / 未分析 /
                            //     无可导出照片）。文案已由 AndroidCore 或共享层写进 AppState.error，
                            //     它会被投影成 `UiState.message` 直接显示。**不设 exportMessage** ——
                            //     否则 AppNav 的第 2 步会被点亮成"已导出"，而实际上是根本没开始。
                            //   · 其他 = 真的执行失败了（IO/OOM…）。这时要设 exportMessage + ok=false，
                            //     底栏才会显示红色的"导出未完成 ⚠"。
                            if (error is ExportBlockedException) {
                                exportUi.update { it.copy(progress = null) }
                            } else {
                                exportUi.update {
                                    it.copy(
                                        progress = null,
                                        message = "ZIP 导出失败：${error.message ?: error.javaClass.simpleName}",
                                        ok = false,
                                    )
                                }
                            }
                        }
                    },
                    isCancelled = { selfJob?.isActive != true },
                )
            } finally {
                // ★ 门禁释放与进度复位统一收口在 finally：不再依赖"每个提前 return 都记得手写"。
                //   提前 return 的分支现在都在共享层的 `prepare` 里，但仍保留这一层 ——
                //   将来若本方法加回同步前置判断，漏写会表现为"导出按钮永久点不动"。
                exportBusy.set(false)
                if (exportUi.value.progress != null) setExportProgress(null)
            }
        }
        bindExportGateTo(exportJob)
    }

    /** 写导出进度（本地状态，不进共享 `AppState`，理由见 [exportUi] 注释）。 */
    private fun setExportProgress(value: Float?) = exportUi.update { it.copy(progress = value) }

    /**
     * 门禁与进度的 **Job 级**兜底复位（与 exportZip 里 finally 的复位互为补充，重复执行无副作用）。
     *
     * 为什么必须在 Job 上再挂一次：`viewModelScope.launch(...)` 之后、协程真正开始执行之前，
     * Job 就可能被取消。此时协程体**一次都不跑**，写在体里的 finally 自然不触发 ——
     * 门禁从此永久占用，导出按钮再也点不动。`invokeOnCompletion` 挂在 Job 生命周期上：
     * body 跑没跑、正常结束、抛异常、被取消，都必定回调一次（对已完成 Job 注册也会立即回调）。
     */
    private fun bindExportGateTo(job: Job) {
        job.invokeOnCompletion {
            exportBusy.set(false)
            if (exportUi.value.progress != null) setExportProgress(null)
        }
    }
    /** 台账照片标记：slot=1 照片1 / slot=2 照片2；photoId=null 表示恢复默认（最早+最晚）。 */
    fun markLedgerPhoto(eventId: Int, slot: Int, photoId: Long?) {
        core.markLedgerPhoto(eventId, slot, photoId)
        persistManual()
    }

    /**
     * 把照片**复制**到另一个事件（多归属，原事件保留一份）。
     *
     * ★ T12（D2）：能力对齐 PC —— 转发共享 `AdjustUseCase.copyPhotos`，本类不再自己写复制逻辑。
     *   **UI 入口暂不加**（见计划 P-3），当前无调用方。返回实际新增份数，落盘用 [persistManual]。
     */
    fun copyPhotos(photoIds: List<Long>, targetEventId: Int): Int {
        if (photoIds.isEmpty()) return 0
        val added = core.copyPhotos(photoIds, targetEventId)
        if (added > 0) persistManual()
        return added
    }

    /** ★ 2026-10-31 审查：原「台账照片字节供给」段（readPhotoBytes / compressForExcel）已删除。
     *  全仓库无任何调用方（台账图片实际走 prepareLedger → BitmapImageEncoder.process），属死代码；
     *  且它与现行实现重复定义了一套压缩参数（480px JPEG85 vs 400×300 JPEG50），
     *  留着会误导后续维护者改错地方。 */

    /** 合并 overrideMap 后的事件照片表（§8.3 移动兜底）。target=-1（移除）→ 从所有事件移除、不归入任何事件。 */
    fun effectiveMap(c: ClassifyResult): Map<Int, List<Long>> = core.effectiveMap(c)

    /**
     * 有效未匹配照片（§8.3 移除兜底）：算法未匹配 + 被人工「从事件移除」（overrideMap=-1）的照片，
     * 再扣除已人工「移动到事件」（overrideMap>=0）的照片。
     * 导出 ZIP / 预览页未匹配池均以它为准，避免被移除的照片丢失、已移动的照片重复显示。
     */
    fun effectiveUnmatched(c: ClassifyResult): List<Long> = core.effectiveUnmatched(c)

    /** ★ UI 预览数据源（事件卡片 + 事件照片弹窗）：人工移动 + 三态展开，与导出同一份口径。 */
    fun previewEventMap(c: ClassifyResult): Map<Int, List<Long>> = core.previewEventMap(c)
}
