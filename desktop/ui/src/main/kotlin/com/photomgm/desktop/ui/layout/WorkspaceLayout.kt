// desktop/ui/layout/WorkspaceLayout.kt —— 三栏骨架的尺寸决策（纯函数，可单测，无 Compose 依赖）
//
// ── 为什么布局要单独抽成纯函数 ────────────────────────────────────────────────
// "900 dp 宽放不放得下三栏"这个问题，如果只在 Composable 里写 if，就只能靠人工拖窗口验证；
// 抽成 metrics(width, railRequested) 之后，1366@125% 那台机器的每一档宽度都能在
// JVM 单测里断言，不必等上机。（U1 的验收方式仍是人工拖窗口，但回归靠单测。）
//
// ── 两条 U3 硬前提（来自 U1 的窗口尺寸修正，U3 不得绕开）─────────────────────
// 1. 左栏可折叠，折叠宽度 56 dp（图标轨）。折叠状态进 M6b 窗口记忆一起持久化。
//    原因：300 + 420 + 320 + 2×1 = 1042 > 900。**900 宽下左栏展开必然把中栏压破 420**，
//    所以折叠不是"锦上添花"，是 900 dp 能用的前提。
// 2. 中栏最小 420 dp；低于此值时**减少列数**（2 列 → 1 列），绝不横向出滚动条。
//    横向滚动等于让用户左右拖着看同一批照片，是这个工具最不该有的交互。
//
// ── 三栏宽度分配（先定死，避免 U3 各写一套）──────────────────────────────────
//   左栏：展开 300 / 折叠 56
//   中栏：flexible，保底 420
//   右栏：偏好 360，保底 320，窗口窄到放不下时整栏退出（0 = 改为覆盖层）
//   分隔带：1 dp × 2
//   因此：允许左栏展开的最小窗宽 = 300 + 420 + 320 + 2 = 1042；
//        能同时容纳三栏的最小窗宽 =  56 + 420 + 320 + 2 = 798。
package com.photomgm.desktop.ui.layout

/** 三栏骨架的尺寸常量（dp）。UI 层从这里取 Dp，不在组件里写裸数字。 */
object Workspace {
    /** 左栏展开宽度。 */
    const val RAIL_EXPANDED = 300

    /** 左栏折叠宽度（只剩图标轨）。 */
    const val RAIL_COLLAPSED = 56

    /** 中栏（照片网格）最小宽度 —— 硬约束。 */
    const val GRID_MIN = 420

    /** 右栏（事件卡 / 未匹配池）保底宽度。 */
    const val DETAIL_MIN = 320

    /** 右栏偏好宽度。 */
    const val DETAIL_PREF = 360

    /** 栏间分隔带宽度。 */
    const val DIVIDER = 1

    /** 缩略图单元格最小宽度（4:3，160×120）。 */
    const val CELL_MIN = 160

    /** 缩略图间距。 */
    const val CELL_GAP = 4

    /** 列数上限：4K 全屏下不要让 12 列蚂蚁图毁掉可读性。 */
    const val COLUMNS_MAX = 8

    /** 允许左栏展开的最小窗口宽度 = 300 + 420 + 320 + 2。 */
    const val EXPAND_RAIL_FROM = RAIL_EXPANDED + GRID_MIN + DETAIL_MIN + 2 * DIVIDER

    /** 能同时容纳三栏的最小窗口宽度 = 56 + 420 + 320 + 2。 */
    const val THREE_COLUMN_FROM = RAIL_COLLAPSED + GRID_MIN + DETAIL_MIN + 2 * DIVIDER

    /**
     * 中栏给定宽度下应显示的列数。
     *
     * 420 → 2 列，324 及以下 → 1 列；永不返回 0（0 列会让 LazyVerticalGrid 崩）。
     */
    fun gridColumns(gridWidth: Int): Int {
        if (gridWidth <= 0) return 1
        val n = (gridWidth + CELL_GAP) / (CELL_MIN + CELL_GAP)
        return n.coerceIn(1, COLUMNS_MAX)
    }

    /**
     * 计算三栏实际尺寸。
     *
     * @param windowWidth   窗口内容区宽度（dp）
     * @param railRequested 用户是否希望左栏展开（M6b 会把这个偏好持久化）
     */
    fun metrics(windowWidth: Int, railRequested: Boolean): WorkspaceMetrics {
        val canExpand = windowWidth >= EXPAND_RAIL_FROM
        val expanded = railRequested && canExpand
        val rail = if (expanded) RAIL_EXPANDED else RAIL_COLLAPSED

        // 左栏 + 分隔 + [中栏 + 分隔 + 右栏]
        val rest = windowWidth - rail - DIVIDER

        if (windowWidth < THREE_COLUMN_FROM) {
            // 连三栏都放不下（比最小窗宽还小，只可能来自系统强制缩放）：
            // 右栏退出为覆盖层，中栏独占 —— 仍然不出横向滚动条。
            val grid = rest.coerceAtLeast(GRID_MIN / 2)
            return WorkspaceMetrics(
                windowWidth = windowWidth,
                railWidth = rail,
                railExpanded = expanded,
                railCanExpand = canExpand,
                gridWidth = grid,
                detailWidth = 0,
                gridColumns = gridColumns(grid),
            )
        }

        // 右栏先拿偏好值，但要保证中栏不破 420：
        // rest - GRID_MIN ∈ [320, 360] 时右栏取该值，超出则夹到 [DETAIL_MIN, DETAIL_PREF]。
        val detail = (rest - DIVIDER - GRID_MIN).coerceIn(DETAIL_MIN, DETAIL_PREF)
        val grid = rest - DIVIDER - detail
        return WorkspaceMetrics(
            windowWidth = windowWidth,
            railWidth = rail,
            railExpanded = expanded,
            railCanExpand = canExpand,
            gridWidth = grid,
            detailWidth = detail,
            gridColumns = gridColumns(grid),
        )
    }
}

/** 一次布局计算的结果（U1 打印到日志，U3 直接照此排布）。 */
data class WorkspaceMetrics(
    val windowWidth: Int,
    val railWidth: Int,
    val railExpanded: Boolean,
    val railCanExpand: Boolean,
    val gridWidth: Int,
    /** 0 = 右栏退出，改为覆盖层。 */
    val detailWidth: Int,
    val gridColumns: Int,
) {
    /** 三栏 + 分隔带应等于窗口宽度（detailWidth=0 时不加右栏那一段）。 */
    val consumedWidth: Int
        get() = railWidth + Workspace.DIVIDER + gridWidth +
            (if (detailWidth > 0) Workspace.DIVIDER + detailWidth else 0)

    override fun toString(): String =
        "w=${windowWidth} rail=${railWidth}${if (railExpanded) "(展开)" else "(折叠)"} " +
            "grid=${gridWidth} cols=$gridColumns " +
            "detail=${if (detailWidth == 0) "隐藏" else detailWidth.toString()}"
}
