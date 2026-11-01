// ui/model/UiState.kt —— 界面状态 + 状态机归约
//
// ★ 设计原则：界面**不拥有**任何业务状态。
//   这里存的只有三样东西：
//     ① 正在看哪一步（stage）与谁在忙（busy）—— 由 CoreStatus 推导，不独立维护；
//     ② 用户的选择（选中哪个事件、哪几张照片）—— 派生自 AppState，失效即清；
//     ③ 纯显示偏好（筛选、搜索词、检查器开合）—— 与业务无关。
//   凡是能从 AppState 算出来的（统计、分组、台账），一律现算，不存副本。
//   这是"界面与逻辑分离"在 UI 侧的具体落法：core 改了口径，UI 自动跟上，不会出现
//   "界面上的数字和导出结果对不上"的经典事故。
package com.photomgm.desktop.ui.model

import com.photomgm.desktop.core.api.AppState
import com.photomgm.desktop.core.api.CoreStatus
import com.photomgm.desktop.core.api.ResumeVerifyMode

/** 照片区的数据源：某个事件，或"未匹配照片池"。 */
sealed interface Selection {
    data object None : Selection
    data class Event(val id: Int) : Selection
    data object Unmatched : Selection
}

/** 复核筛选（对应 hints / 空照片 / 待定事件这些真实业务信号）。 */
enum class ReviewFilter(val label: String) {
    ALL("全部"),
    NEEDS_REVIEW("待确认"),
    NO_PHOTO("无照片"),
    PENDING("待定时间"),
    UNMATCHED("未匹配"),
}

/** 导出选项（直接映射 ExportRequest 的三个字段，不做二次解释）。 */
data class ExportOptions(
    val includeLedger: Boolean = true,
    val verifyMode: ResumeVerifyMode = ResumeVerifyMode.SIZE_AND_MTIME,
)

data class UiState(
    val stage: Stage = Stage.INPUT,
    val busy: Busy? = null,
    val selection: Selection = Selection.None,
    val selectedPhotoIds: Set<Long> = emptySet(),
    val filter: ReviewFilter = ReviewFilter.ALL,
    val query: String = "",
    val inspectorOpen: Boolean = false,
    /** 日志文本的"外部修订号"：每次导入文件 +1，用来让文本框重新同步（见 InputStage）。 */
    val logRevision: Int = 0,
    val exportOptions: ExportOptions = ExportOptions(),
    val confirmReset: Boolean = false,
    /** 上一帧看到的核心状态，用于识别"分析完成/导出完成"这两次边沿。 */
    val seenStatus: CoreStatus = CoreStatus.IDLE,
    /** 上一次已呈现给用户的导出目录，用于识别"这次 is 新的导出结果"。 */
    val seenExportKey: String? = null,
    /**
     * 已被用户收起的错误文本。
     *
     * ★ 为什么要存"收起的是哪条"而不是一个 Boolean：核心没有 clearError()，
     *   error 只在下一次成功分析/导出时消失。若只存布尔，用户收起后又发生新错误，
     *   新错误会被一并吞掉——那是最糟的一种静默失败。
     */
    val dismissedError: String? = null,
    /** 界面侧自己产生的提示（文件读不了、路径打不开）。核心的 error 只描述核心内部失败。 */
    val localMessage: String? = null,
)

/**
 * 把核心快照归约成界面状态。
 *
 * 必须满足：**幂等**（同一快照重复归约不产生二次跳转）、**无副作用**（不调用 core）。
 * 这两条是它可被单测覆盖的前提，也是避免"状态抖动导致界面来回跳"的关键。
 */
fun reduce(ui: UiState, state: AppState): UiState {
    var next = ui

    // ① 分析完成：边沿是 busy==ANALYZE 且核心已回到 READY。
    if (next.busy == Busy.ANALYZE && state.status == CoreStatus.READY) {
        next = next.copy(busy = null, stage = if (state.events.isNotEmpty()) Stage.REVIEW else Stage.INPUT)
    }

    // ② 导出完成：核心不提供"导出完成"枚举（完成后回到 READY），
    //    所以用 lastExport.exportDir 的变化作为边沿——这是唯一不会误判的信号。
    val exportKey = state.lastExport?.exportDir?.toString()
    if (next.busy == Busy.EXPORT && state.status == CoreStatus.READY && exportKey != null && exportKey != next.seenExportKey) {
        next = next.copy(busy = null, stage = Stage.RESULT, seenExportKey = exportKey)
    }

    // ③ 失败/关闭：撤掉忙碌态，停在当前阶段让用户看到错误横幅并改正输入。
    if (state.status == CoreStatus.FAILED || state.status == CoreStatus.CLOSED) {
        next = next.copy(busy = null)
    }

    // ④ 防御：核心在导出中但界面没标记忙碌（例如外部调用），补齐覆盖层。
    if (state.status == CoreStatus.EXPORTING && next.busy == null) {
        next = next.copy(busy = Busy.EXPORT, stage = Stage.EXPORT)
    }

    // ⑤ 选择失效清理：选择的实体不存在了（重新分析后 id 变了）就必须松开，
    //    否则检查器会显示一张已经不存在的照片。
    next = next.copy(
        selection = validSelection(next.selection, state),
        selectedPhotoIds = next.selectedPhotoIds.filterTo(LinkedHashSet()) { id ->
            state.photos.any { it.id == id }
        },
    )

    // ⑥ 首次进入复核且尚未选中任何东西时，把光标放在**第一个待确认事件**上。
    //    这一条是流程性的：复核的目标是清空待确认，不该让用户自己找第一个。
    if (next.stage == Stage.REVIEW && next.selection == Selection.None) {
        next = next.copy(selection = initialStateFor(state))
    }

    return next.copy(seenStatus = state.status)
}

/**
 * 首帧状态。
 *
 * ★ 正常启动时核心没有分析结果（events 为空）：阶段就是 INPUT。
 *   但 --demo 或将来"会话恢复"会让启动时已有结果，那种情况必须直接进复核，
 *   否则用户会看到一个"明明已经分析完了却让我重新分析"的输入页。
 */
fun initialUi(state: AppState): UiState {
    val hasResult = state.events.isNotEmpty() && state.status == CoreStatus.READY
    return UiState(
        stage = if (hasResult) Stage.REVIEW else Stage.INPUT,
        selection = if (hasResult) initialStateFor(state) else Selection.None,
        seenStatus = state.status,
    )
}

private fun validSelection(sel: Selection, state: AppState): Selection = when (sel) {
    is Selection.Event -> if (state.events.any { it.id == sel.id }) sel else Selection.None
    Selection.Unmatched -> if (state.unmatchedPhotoIds.isEmpty()) Selection.None else Selection.Unmatched
    Selection.None -> Selection.None
}

/**
 * 进入复核时的默认焦点。
 *
 * 优先级：待确认事件 → 有照片的事件 → 第一个事件 → 未匹配池 → 空。
 * 依据：`EventSnapshot.hints` 与"无照片"是核心明确交给人处理的两类问题
 * （见 PhotoMgmCoreImpl.hintsByEvent / classify 的 interventions）。
 */
fun initialStateFor(state: AppState): Selection {
    val pending = state.events.firstOrNull { WorkQueue.needsReview(it) && it.photoIds.isNotEmpty() }
        ?: state.events.firstOrNull { it.photoIds.isNotEmpty() }
        ?: state.events.firstOrNull()
    return when {
        pending != null -> Selection.Event(pending.id)
        state.unmatchedPhotoIds.isNotEmpty() -> Selection.Unmatched
        else -> Selection.None
    }
}
