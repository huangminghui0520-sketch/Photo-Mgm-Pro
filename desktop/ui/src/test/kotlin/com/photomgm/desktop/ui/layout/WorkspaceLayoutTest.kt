// desktop/ui/layout/WorkspaceLayoutTest.kt —— 三栏在 900 dp 下到底排不排得开
//
// 背景：900 + 420 + 320 的直觉算法是错的。左栏展开 300 时，900 剩给中栏的只有 599，
// 再扣右栏 320 → 中栏 279，破 420。所以 900 宽下**必须**折叠左栏。
// 这些用例把"哪一档宽度允许什么"钉死，U3 写布局时不会各自发挥。
package com.photomgm.desktop.ui.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkspaceLayoutTest {

    @Test
    fun `900 宽左栏折叠后中栏仍高于下限`() {
        val m = Workspace.metrics(900, railRequested = false)
        assertEquals(56, m.railWidth)
        assertEquals(482, m.gridWidth)
        assertTrue(m.gridWidth >= Workspace.GRID_MIN, "中栏 482 ≥ 420")
        assertEquals(360, m.detailWidth)
        assertEquals(2, m.gridColumns, "482 宽只够 2 列")
        assertEquals(900, m.consumedWidth, "三栏 + 分隔带必须正好吃掉整宽")
    }

    @Test
    fun `900 宽强行展开左栏会被拒绝`() {
        // 用户点了展开但窗口不够宽：不能顺从他，否则中栏破 420。
        val m = Workspace.metrics(900, railRequested = true)
        assertEquals(false, m.railExpanded, "900 < 1042，展开请求被否决")
        assertEquals(false, m.railCanExpand, "同时按钮应处于禁用态")
        assertEquals(56, m.railWidth)
    }

    @Test
    fun `1366 125% 初始尺寸 984 的三栏可读性`() {
        val m = Workspace.metrics(984, railRequested = false)
        assertEquals(566, m.gridWidth)
        assertEquals(3, m.gridColumns)
        assertTrue(m.detailWidth >= Workspace.DETAIL_MIN)
    }

    @Test
    fun `1366 125% 最大化到 1093 允许展开左栏且不破中栏`() {
        val m = Workspace.metrics(1093, railRequested = true)
        assertEquals(true, m.railExpanded)
        assertEquals(300, m.railWidth)
        assertEquals(431, m.gridWidth)
        assertTrue(m.gridWidth >= Workspace.GRID_MIN)
        assertEquals(2, m.gridColumns)
        assertEquals(1093, m.consumedWidth)
    }

    @Test
    fun `刚好允许展开的临界宽度 1042`() {
        val m = Workspace.metrics(Workspace.EXPAND_RAIL_FROM, railRequested = true)
        assertEquals(true, m.railExpanded)
        assertEquals(Workspace.GRID_MIN, m.gridWidth, "临界点中栏正好等于下限")
        assertEquals(Workspace.DETAIL_MIN, m.detailWidth)
    }

    @Test
    fun `刚差 1dp 就不允许展开`() {
        val m = Workspace.metrics(Workspace.EXPAND_RAIL_FROM - 1, railRequested = true)
        assertEquals(false, m.railExpanded)
    }

    @Test
    fun `宽屏下保证列数上限`() {
        val m = Workspace.metrics(3840, railRequested = true)
        assertEquals(Workspace.COLUMNS_MAX, m.gridColumns, "4K 全屏封顶 8 列")
    }

    @Test
    fun `窄到放不下三栏时右栏退出而不是出横向滚动条`() {
        val m = Workspace.metrics(Workspace.THREE_COLUMN_FROM - 1, railRequested = false)
        assertEquals(0, m.detailWidth, "右栏改为覆盖层")
        assertTrue(m.gridWidth >= Workspace.GRID_MIN, "中栏反而变宽，永不低于下限")
        assertEquals(Workspace.THREE_COLUMN_FROM - 1, m.consumedWidth)
    }

    @Test
    fun `中栏 420 时降为 2 列 324 以下降为 1 列`() {
        assertEquals(2, Workspace.gridColumns(420))
        assertEquals(2, Workspace.gridColumns(324))
        assertEquals(1, Workspace.gridColumns(323))
        assertEquals(1, Workspace.gridColumns(120), "极端窄也不返回 0（0 列会让网格崩）")
        assertEquals(1, Workspace.gridColumns(0))
    }

    @Test
    fun `所有常见窗宽都不产生负宽度且总宽守恒`() {
        for (w in 700..3840 step 7) {
            for (expand in listOf(true, false)) {
                val m = Workspace.metrics(w, expand)
                assertTrue(m.railWidth > 0 && m.gridWidth > 0, "w=$w 出现非正宽度")
                assertTrue(m.detailWidth >= 0)
                assertEquals(w, m.consumedWidth, "w=$w 总宽不守恒（$m）")
                assertTrue(m.gridWidth >= Workspace.GRID_MIN || m.detailWidth == 0,
                    "w=$w 三栏都在时中栏破了 420（$m）")
            }
        }
    }
}
