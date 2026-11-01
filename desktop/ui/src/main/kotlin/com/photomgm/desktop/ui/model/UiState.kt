// ui/model/UiState.kt —— 单页界面的状态 + 归约
//
// ★ 界面结构与 core 的对应（这一版把"阶段"整套删掉了）：
//   core 只有一条流水线：输入 → analyze() → READY → export() → READY。
//   上一版把它切成 INPUT/REVIEW/EXPORT/RESULT 四个互斥页面，于是"看一眼事件就得换页"、
//   "导出完就离开列表"，而用户真正的工作是**在同一屏里反复核对事件和照片**。
//   现在只有一个页面：日志条 + 事件卡片列表；需要聚焦的（设置、某事件的照片）用弹窗。
//
// ★ UiState 仍然**不拥有**任何业务状态：这里只有
//     ① 谁在忙（busy）—— 由 CoreStatus 推导；
//     ② 用户当前在看什么（展开的弹窗、筛选、搜索、选中的照片）；
//     ③ 纯显示偏好（日志条是否展开、结果条是否已收起）。
//   凡是能从 AppState 算出来的一律现算（统计、分组、台账、卡片里的照片），不存副本。
package com.photomgm.desktop.ui.model

import com.photomgm.desktop.core.api.AppState
import com.photomgm.desktop.core.api.CoreStatus
import com.photomgm.desktop.core.api.ResumeVerifyMode

/** 长任务的两种情形。取消语义不同，见 BusyOverlay 里的说明文案。 */
enum class Busy(val label: String) {
    ANALYZE("正在分析"),
    EXPORT("正在导出"),
}

/** 照片集合的指向：某个事件，或"未匹配照片池"。 */
sealed interface Selection {
    data object None : Selection
    data class Event(val id: Int) : Selection
    data object Unmatched : Selection
}

/** 页面上唯一的"覆盖层"通道。同一时刻最多一个。 */
sealed interface Sheet {
    /** 设置：日期 / 源目录 / 导出目录 / 保留策略 / 校验方式。 */
    data object Settings : Sheet

    /** 某事件（或未匹配池）的全部照片 —— 点卡片打开，在这里操作照片。 */
    data class Photos(val target: Selection) : Sheet

    /** 把选中照片移到另一个事件（或移入未匹配池）。 */
    data class Move(val photoIds: List<Long>, val from: Selection) : Sheet

    /** 重新分析会丢弃人工调整，需要确认。 */
    data object ConfirmReanalyze : Sheet
}

/** 列表筛选（对应 hints / 空照片 / 待定事件这些真实业务信号）。 */
enum class ReviewFilter(val label: String) {
    ALL("全部"),
    NEEDS_REVIEW("待确认"),
    NO_PHOTO("无照片"),
    PENDING("无时间"),
    UNMATCHED("未匹配"),
}

/** 导出选项（直接映射 ExportRequest 的字段，不做二次解释）。 */
data class ExportOptions(
    val includeLedger: Boolean = true,
    val verifyMode: ResumeVerifyMode = ResumeVerifyMode.SIZE_AND_MTIME,
)

data class UiState(
    val busy: Busy? = null,

    /**
     * 日志输入框是否展开。
     *
     * ★ 这是本版最直观的交互变化：日志框只承担"把文本送进来"，不承担"阅读和编辑"。
     *   解析成功后自动收起（见 reduce ①），只留一行摘要；需要改的时候点「重新输入」再展开。
     *   依据：日志原文动辄上百行，常驻一个 96 dp 的框会把事件列表挤到只剩两行。
     */
    val logEntryVisible: Boolean = true,

    val filter: ReviewFilter = ReviewFilter.ALL,
    val query: String = "",
    val sheet: Sheet? = null,

    /** 照片弹窗里正在预览的那张（左大图）。 */
    val activePhotoId: Long? = null,
    val selectedPhotoIds: Set<Long> = emptySet(),

    /** 移动弹窗里的搜索词。 */
    val moveQuery: String = "",

    /** 日志文本的"外部修订号"：每次导入文件 +1，用来让文本框重新同步。 */
    val logRevision: Int = 0,

    val exportOptions: ExportOptions = ExportOptions(),

    /** 已呈现过的导出目录，用于识别"这次是新的导出结果"。 */
    val seenExportKey: String? = null,
    /** 已被用户收起的导出结果条（存 key，保证下一次导出还会出现）。 */
    val dismissedResultKey: String? = null,

    /**
     * 已被用户收起的错误文本。
     *
     * ★ 为什么存"收起的是哪条"而不是一个 Boolean：core 没有 clearError()，
     *   error 只在下一次成功分析/导出时消失。若只存布尔，用户收起后又发生新错误，
     *   新错误会被一并吞掉——那是最糟的一种静默失败。
     */
    val dismissedError: String? = null,
    /** 界面侧自己产生的提示（文件读不了、路径打不开）。 */
    val localMessage: String? = null,
)

/**
 * 把核心快照归约成界面状态。
 *
 * 必须满足：**幂等**（同一快照重复归约不产生二次跳转）、**无副作用**（不调用 core）。
 */
fun reduce(ui: UiState, state: AppState): UiState {
    var next = ui

    // ① 分析完成：收起日志框、关掉"重新分析"确认框。
    //    但如果一个事件都没解析出来，日志框必须留着——否则用户连改的地方都没有。
    if (next.busy == Busy.ANALYZE && state.status == CoreStatus.READY) {
        next = next.copy(
            busy = null,
            logEntryVisible = state.events.isEmpty(),
            sheet = if (next.sheet == Sheet.ConfirmReanalyze) null else next.sheet,
        )
    }

    // ② 导出完成：核心没有"导出完成"枚举（完成后回到 READY），
    //    所以用 lastExport.exportDir 的变化作为边沿——这是唯一不会误判的信号。
    val exportKey = state.exportKey
    if (next.busy == Busy.EXPORT && state.status == CoreStatus.READY && exportKey != null && exportKey != next.seenExportKey) {
        next = next.copy(busy = null, seenExportKey = exportKey, dismissedResultKey = null)
    }

    // ③ 失败/关闭：撤掉忙碌态，让错误条能被看到并改正输入。
    if (state.status == CoreStatus.FAILED || state.status == CoreStatus.CLOSED) {
        next = next.copy(busy = null)
    }

    // ④ 防御：核心在导出中但界面没标记忙碌（例如外部调用），补上覆盖层。
    if (state.status == CoreStatus.EXPORTING && next.busy == null) {
        next = next.copy(busy = Busy.EXPORT)
    }

    // ⑤ 失效清理：重新分析后 event id / photo id 都会变，旧选择必须松开，
    //    否则弹窗会显示一个已经不存在的实体。
    next = next.copy(
        sheet = validSheet(next.sheet, state),
        selectedPhotoIds = next.selectedPhotoIds.filterTo(LinkedHashSet()) { id -> state.photos.any { it.id == id } },
        activePhotoId = next.activePhotoId?.takeIf { id -> state.photos.any { it.id == id } },
    )

    return next
}

/**
 * 首帧状态。
 *
 * ★ 正常启动时核心没有结果：日志框展开，等用户粘贴。
 *   但 --demo 或"会话恢复"会让启动时已有事件，那种情况必须把日志框收起来，
 *   否则用户会看到一个"明明已经分析完了却让我重新输入"的框。
 */
fun initialUi(state: AppState): UiState {
    val hasResult = state.events.isNotEmpty() && state.status == CoreStatus.READY
    return UiState(
        logEntryVisible = !hasResult,
        activePhotoId = if (hasResult) firstPhotoOf(state) else null,
    )
}

/** 导出结果条应当显示的那次结果；null = 不显示。 */
fun resultToShow(ui: UiState, state: AppState): com.photomgm.desktop.core.api.ExportResult? {
    val key = state.exportKey ?: return null
    if (key == ui.dismissedResultKey) return null
    // seenExportKey 是"界面已经识别过的"，dismissedResultKey 是"用户收起的"；
    // 二者都为 null 且 core 已有历史结果时（重启后）不主动弹结果条。
    if (ui.seenExportKey == null) return null
    return state.lastExport
}

/** 当前 sheet 指向的照片集合（未打开照片弹窗时为空）。 */
fun sheetSelection(ui: UiState): Selection = when (val s = ui.sheet) {
    is Sheet.Photos -> s.target
    is Sheet.Move -> s.from
    else -> Selection.None
}

private fun validSheet(sheet: Sheet?, state: AppState): Sheet? = when (sheet) {
    null, Sheet.Settings, Sheet.ConfirmReanalyze -> sheet
    is Sheet.Photos -> if (validTarget(sheet.target, state)) sheet else null
    is Sheet.Move -> {
        val ids = sheet.photoIds.filter { id -> state.photos.any { it.id == id } }
        if (ids.isEmpty()) null else sheet.copy(photoIds = ids)
    }
}

private fun validTarget(target: Selection, state: AppState): Boolean = when (target) {
    is Selection.Event -> state.events.any { it.id == target.id }
    Selection.Unmatched -> state.unmatchedPhotoIds.isNotEmpty()
    Selection.None -> false
}

private fun firstPhotoOf(state: AppState): Long? =
    state.events.firstOrNull { it.photoIds.isNotEmpty() }?.photoIds?.firstOrNull()
        ?: state.unmatchedPhotoIds.firstOrNull()

/** 导出目录作为"这一次导出"的身份（Path.toString 在 Windows 上是反斜杠，必须现取）。 */
val AppState.exportKey: String?
    get() = lastExport?.exportDir?.toString()
