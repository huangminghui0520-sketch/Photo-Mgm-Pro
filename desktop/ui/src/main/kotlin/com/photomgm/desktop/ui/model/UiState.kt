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

import app.api.AppState
import app.api.CoreStatus
import app.api.ResumeVerifyMode

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

/** 把选中的照片交给另一个事件时，是"搬过去"还是"那边也多一份"。 */
enum class AssignMode(val verb: String) {
    /** 移动：原事件不再有它（"它属于那边"）。 */
    MOVE("移动"),

    /** 复制：原事件保留，目标事件多一份（"两个事件都要用它"）。 */
    COPY("复制"),
}

/** 页面上唯一的"覆盖层"通道。同一时刻最多一个。 */
sealed interface Sheet {
    /** 设置：源目录 / 导出目录 / 导出内容 / 校验方式（**不含巡查日期**，它在主页输入带上）。 */
    data object Settings : Sheet

    /** 某事件（或未匹配池）的全部照片 —— 点卡片打开，在这里操作照片。 */
    data class Photos(val target: Selection) : Sheet

    /** 把选中照片移动/复制到另一个事件（移动模式下还可以移入未匹配池）。 */
    data class Assign(
        val photoIds: List<Long>,
        val from: Selection,
        val mode: AssignMode = AssignMode.MOVE,
    ) : Sheet

    /** 重新分析会丢弃人工调整，需要确认。 */
    data object ConfirmReanalyze : Sheet

    // 手机导入照片：从手机按时间区间同步到源目录下的子文件夹（桌面独有）。
    data object PhoneImport : Sheet
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

    /**
     * 巡查日期的浮层日历是否打开。
     *
     * ★ 放在 UiState 而不是组件本地状态：面板画在**根覆盖层**里（它必须盖住整页却不改变布局），
     *   而"要不要画"这件事得由持有 UiState 的一方决定（见 AppShell）。
     */
    val datePickerOpen: Boolean = false,

    val sheet: Sheet? = null,

    /** 照片弹窗里正在预览的那张（左大图）。 */
    val activePhotoId: Long? = null,

    /**
     * 照片弹窗里勾选的照片（要移动/复制/移出的那几张）。
     *
     * ★ 与 [activePhotoId] 是两件事：active 是"我在看哪张"，selected 是"我要对哪几张动手"。
     *   两者分开才可能"看着 A、对 B 动手"（比如挑出三张一起复制走）。
     *   抽屉式规则见 [operationTargets]：没勾选时，操作对象就是当前这张。
     */
    val selectedPhotoIds: Set<Long> = emptySet(),

    /**
     * Shift 连选的锚点：上一次**普通点 / 红勾 / Shift 终点**落在哪一张。
     *
     * ★ 锚点不等于 active：active 会跟着 Shift 连选的终点走（大图要显示刚点到的那张），
     *   而锚点必须留在起点 —— 否则"点 1 号、Shift+点 5 号、再 Shift+点 3 号"
     *   会变成"从 5 号往回选"，与资源管理器/Excel 的口径不同，用户要重新找起点。
     * ★ 只存 id、不存下标：列表会被搜索过滤、照片会被移走，下标立刻失效。
     *   下标在点击那一刻按**当前可见顺序**现算（见 [clickPhotoSelection]）。
     */
    val selectionAnchor: Long? = null,

    /** 照片弹窗里的搜索词（按文件名 / 拍摄时刻过滤缩略条）。 */
    val photoQuery: String = "",

    /** 分配弹窗（移动 / 复制）里的搜索词。 */
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
    /**
     * 界面侧自己产生的一次性提示：文件读不了、路径打不开，**以及某个动作的结果**。
     *
     * ★ 为什么替换掉原来的 `localMessage: String?` 而改成 [LocalNotice]：
     *   加了"移动 / 复制"之后，同一个地方要显示三种截然不同的结果 —— 成了、没生效、出错。
     *   全用同一张灰条的话，用户只能靠读字判断这次点成功了没；而"成了/没生效/出错"
     *   是必须一眼分开的三件事，所以语气（OK/WARN/DANGER）属于数据，不该由界面猜。
     */
    val notice: LocalNotice? = null,
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
        // 锚点指向的照片可能已经不在（重新分析后 id 全变）→ 松开，Shift 连选会退化成单选。
        selectionAnchor = next.selectionAnchor?.takeIf { id -> state.photos.any { it.id == id } },
    )

    // ⑥ 弹窗关掉后清掉它的搜索词：否则下次打开会看到一个"少了几张"的照片条，
    //    而搜索框已经不在视野里 —— 那会像照片丢了。
    if (next.sheet !is Sheet.Photos && next.photoQuery.isNotEmpty()) {
        next = next.copy(photoQuery = "")
    }
    if (next.sheet !is Sheet.Assign && next.moveQuery.isNotEmpty()) {
        next = next.copy(moveQuery = "")
    }

    // ⑦ 提示只负责"刚做完的那个动作"：一旦打开新的弹窗，人已经转向下一件事，
    //    留着上一件事的结果会让人误读（比如"已复制 1 张"挂在分配弹窗上方，
    //    看着像这次也会复制）。反过来不成立 —— 关掉弹窗后结果仍要看得见。
    if (ui.sheet == null && next.sheet != null && next.notice != null) {
        next = next.copy(notice = null)
    }

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
fun resultToShow(ui: UiState, state: AppState): app.api.ExportResult? {
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
    is Sheet.Assign -> s.from
    else -> Selection.None
}

/**
 * "这次动作作用在哪几张照片"的唯一判定。
 *
 * ★ 规则：勾了就用勾的，没勾就用当前大图。
 *   这是移除键盘多选（Ctrl+点击）之后补上的默认值 —— 绝大多数时候用户只想动正在看的那张，
 *   硬要求"先勾选再操作"等于把主路径变成两步。多选才需要去点勾选框。
 */
fun operationTargets(selected: Set<Long>, active: Long?): List<Long> =
    if (selected.isNotEmpty()) selected.toList() else listOfNotNull(active)

/**
 * 点一张缩略图后新的勾选集合（纯函数，单测盯住）。
 *
 * ★ 抽出来是因为这里出过一次真实事故：Ctrl+点看起来"最多只能选两张"。
 *   根因不在这个判断，而在点击回调拿到的是**旧的一份 UiState**（见 component/Press.kt），
 *   于是"在已选集上累积"这件事每次都从弹窗刚打开时的空集重来。
 *   现在把累积规则放在只有一个出口的纯函数里：加选是在 [selected] 上增删，不是重新构造。
 *
 * [additive]（Ctrl）= 切换这张、保留其它；否则 = 只留这张。
 */
fun nextPhotoSelection(selected: Set<Long>, id: Long, additive: Boolean): Set<Long> = when {
    !additive -> setOf(id)
    selected.contains(id) -> selected - id
    else -> selected + id
}

/** 一次点击的结果：新的勾选集合 + 新的连选锚点（两者必须一起写回，否则 Shift 会算错起点）。 */
data class PhotoSelectionResult(
    val selected: Set<Long>,
    val anchor: Long?,
)

/**
 * 点一张缩略图（带 Shift 状态）之后的勾选状态。
 *
 * 口径（v3.7 起只有两条；"加选/切换"不再由点图承担，见下）：
 *
 * | 按法 | 结果 | 锚点 |
 * | --- | --- | --- |
 * | 普通点 | **只留这张**（清掉其它勾选） | 移到这张 |
 * | Shift+点 | 从锚点到这张的**连续一段**（替换原勾选） | **不动** |
 *
 * ★ 为什么取消 Ctrl+点的加选：现场报的"点击判定错乱"里有一半是它引起的 ——
 *   缩略条上同一个 press 既可能来自格子、又可能来自右上角红勾（按下阶段不保证消费），
 *   谁先谁后决定了这次是"单选"还是"切换"，用户看到的就是"点一下选上、点一下又取消"。
 *   现在"切换这一张、保留其它"只有**红勾**一个入口（[nextPhotoSelection] + additive=true），
 *   点图本体永远只做"单选 / 连选"两件事，修饰键读错也不会造成勾选集合错乱。
 * ★ 为什么 Shift 要替换而不是追加：连选的用途是"这一批一起移走"，
 *   如果每次都往上叠，改一次主意就得逐个取消 —— 而现场复核是"先粗选一段、再微调"。
 * ★ 为什么锚点不跟着 Shift 走：见 [UiState.selectionAnchor] 的说明（要能反复调整终点）。
 * ★ 顺序取 [ordered]（**当前可见**的缩略条顺序），不取 id 顺序：
 *   搜索结果只有 3 张时，Shift 选的就是屏幕上这 3 张里的一段，跟眼睛看到的一致。
 * ★ 锚点缺失 / 已被过滤掉（换了事件、搜过别的词）→ 退化成普通点（单选）。
 *   宁可少选，也不要"从一张看不见的照片开始连选"，那必然选到用户没预期的集合。
 */
fun clickPhotoSelection(
    selected: Set<Long>,
    ordered: List<Long>,
    anchor: Long?,
    id: Long,
    shift: Boolean,
): PhotoSelectionResult {
    if (!shift) return PhotoSelectionResult(setOf(id), id)

    val from = anchor?.let { ordered.indexOf(it) } ?: -1
    val to = ordered.indexOf(id)
    if (from < 0 || to < 0) {
        // 没有可用锚点：当成普通点（宁可单选，也不要凭空连选）
        return PhotoSelectionResult(setOf(id), id)
    }
    val (lo, hi) = if (from <= to) from to to else to to from
    return PhotoSelectionResult(ordered.subList(lo, hi + 1).toSet(), anchor)
}

private fun validSheet(sheet: Sheet?, state: AppState): Sheet? = when (sheet) {
    null, Sheet.Settings, Sheet.ConfirmReanalyze, Sheet.PhoneImport -> sheet
    is Sheet.Photos -> if (validTarget(sheet.target, state)) sheet else null
    is Sheet.Assign -> {
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
