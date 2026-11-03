// app/core/UiProjection.kt —— `AppState` + `PhotoSession` → `UiState` 的投影（T11）
//
// 为什么需要投影层而不直接让 UI 改读 `AppState`：
//   共享 [AppState] 给的是**平台无关的展示形状**（`PhotoInfo` / `EventSnapshot` / 事件照片表），
//   而安卓 UI 读的是**算法层的原始对象**（`Photo` / `ClassifyResult` / `ParsedLog`）。
//   两者字段集不同（`EventSnapshot` 把事件压平成列表，原始 `ParsedLog` 保留 notices/skippedLines）。
//   计划要求 T11 期间「UI 零逻辑改动」，所以保留 `UiState` 的形状，在中间加这一层映射。
//
// ★ 投影刻意**不做数据转换**：`parsed` / `photos` / `classify` / `marked` / `overrideMap`
//   直接取 `PhotoSession` 的**同一个引用**。理由有两条，缺一不可：
//   ① Compose 的 `remember(key)` 依赖引用相等；每次投影新建 List 会让所有事件卡片每帧重算
//      （`LogSection` 里 `remember(state.photos) { associateBy }` 会彻底失效）。
//   ② `ThumbnailRefreshTest` 断言「清缓存那一刻 `state.value.photos.size == 0`」，
//      它从**主线程**读 `state.value`；任何异步投影（collect 到 Main）都会让它读到上一帧的旧值。
//
// ★ 读侧一致性：`value` getter 不加锁读取 `session`（加锁会被分析流水线的长 IO 拖住 UI 线程）。
//   窗口确实存在——`session` 已改、`AppState` 还没发出去的那一瞬——但它比旧实现更窄：
//   旧实现里 `_state.update` 与字段赋值是两次独立写入，现在至少 `AppState` 侧是一次性快照。
package com.photomgm.app.core

import app.api.AppState
import app.api.CoreStatus
import app.state.PhotoSession
import com.photomgm.app.UiState
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.awaitCancellation

/**
 * 导出相关的**本地** UI 状态（安卓独有，不进共享 `AppState`）。
 *
 * ★ 为什么单独持有：共享层用 `lastExport: ExportResult` + `message` + `error` + `status`
 *   表达同一件事，字段语义更全；而安卓底栏只要"进度条 + 一句话 + 成功与否"三个值。
 *   把这三个塞进共享契约，桌面端得永远背着三个用不到的成员（与 `Settings.window` 同一个教训）。
 *
 * ★ T11 提交②把导出切成 `ExportUseCase` 后，本类退化为对 `ExportResult` 的投影。
 */
internal data class ExportUi(
    val progress: Float? = null,
    val message: String? = null,
    val ok: Boolean? = null,
    val lastExport: String? = null,
)

/**
 * 把「核心状态 + 会话状态」投影成 UI 状态。
 *
 * @param geoAvailable 环境权限判定（安卓独有，见 [UiState.geoAvailable]），由 ViewModel 维护。
 * @param exportProgress 本地导出进度（0..1）。导出仍在 ViewModel 侧执行，见 T11 提交①的说明。
 * @param exportMessage 本地导出结果文案。
 * @param exportOk 本地导出成功判据。
 */
internal fun projectUiState(
    app: AppState,
    session: PhotoSession,
    geoAvailable: Boolean?,
    exportProgress: Float?,
    exportMessage: String?,
    exportOk: Boolean?,
    lastExport: String?,
): UiState = UiState(
    date = app.settings.patrolDate,
    sourceDirs = app.settings.sourceDirs,
    outputDir = app.settings.outputDir,
    namingTemplate = app.settings.namingTemplate,
    dedupKeepMode = app.dedupMode,
    // ★ 粗筛计数只在「分析出过结果」时才有值：null 表示还没粗筛过（`ensurePipeline` 据此决定
    //   要不要补跑一次）。共享层用 `analysis` 是否为 null 表达同一件事。
    coarseCount = app.analysis?.candidateCount,
    logsText = app.logText,
    parsed = projectParsed(app.logText, session.parsed),
    photos = session.photos,
    classify = session.classify,
    ledger = app.ledger,
    // ★ busy 只认 ANALYZING：EXPORTING 时导出进度/结果各有专门的字段，
    //   若把导出也算 busy，`blockedByExport` 与 UI 的禁用态会互相叠加成"两个忙"。
    busy = app.status == CoreStatus.ANALYZING,
    progressStage = app.progress?.stage,
    message = app.message ?: app.error,
    overrideMap = session.manual.overrideMap,
    marked = session.manual.marked,
    exportProgress = exportProgress,
    exportMessage = exportMessage,
    exportOk = exportOk,
    lastExport = lastExport,
    geoAvailable = geoAvailable,
)

/**
 * `UiState.parsed` 的投影规则（**有语义，不是格式转换**）。
 *
 * 日志文本为空时，分析流水线仍会跑一遍 `parseLog("")` 并得到一个 `events = []` 的结果。
 * 若把它原样投影出去，界面会认为"日志已经解析过了"：
 *   · `AppNav` 的两步引导会把第 1 步点亮（`parsed?.events?.isNotEmpty() == true` 虽为假，
 *     但 `parsed != null` 在别处被当成"已解析"）；
 *   · 更直接的是 `AppViewModelTest` 的 await 判据 `it.parsed != null && !it.busy`——
 *     它在"选完日期自动扫照片"这一步就会被满足，用例于是拿到还没解析日志的中间态。
 *
 * 规则：**文本为空 ⇒ 没有解析结果**。`parsed` 是日志的产物，没有日志就没有产物。
 * 文本非空而解析出 0 事件（整篇都是无法识别的行）仍返回结果 —— 那才是"解析过了，只是没认出东西"，
 * 用户需要看见这个事实（`skippedLineCount` 会告诉他有几行没认出来）。
 */
private fun projectParsed(
    logText: String,
    parsed: algorithm.ParsedLog?,
): algorithm.ParsedLog? = when {
    parsed == null -> null
    logText.isBlank() && parsed.events.isEmpty() -> null
    else -> parsed
}

/**
 * 把一个 `StateFlow<S>` 按 [project] 映射成 `StateFlow<T>`，**`value` 每次读时即时计算**。
 *
 * ★ 为什么不用 `map(...).stateIn(scope)`：那需要协程作用域，且 `value` 是上一次派发的缓存值 ——
 *   对 UI 无妨，但 `ThumbnailRefreshTest` 这类在**主线程同步读** `state.value` 的断言会读到
 *   尚未派发的旧快照（表现为"清缓存时 photos 还有 30 张"这种假失败）。
 *   即时计算把 read-after-write 的一致性还给调用方，代价是每次读都跑一遍 [project] ——
 *   所以 [project] 必须是纯字段映射（见 [projectUiState] 的注释），不能做集合运算。
 *
 * ★ `collect` 必须永不返回（StateFlow 契约），否则 `collectAsState` 会认为流已结束。
 */
internal class ProjectedStateFlow<S, T>(
    private val source: StateFlow<S>,
    private val project: (S) -> T,
) : StateFlow<T> {

    override val value: T get() = project(source.value)

    override val replayCache: List<T> get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<T>): Nothing {
        source.collect { collector.emit(project(it)) }
        // 理论不可达（StateFlow.collect 永不返回）；写出来是为了满足 `Nothing` 返回类型，
        // 同时把"若真返回了就不要继续发值"这件事交给运行时挂起。
        awaitCancellation()
    }
}
