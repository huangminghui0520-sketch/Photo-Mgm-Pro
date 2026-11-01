// ui/test/LayoutTest.kt —— 宽度 → 三栏几何 / 照片列数
//
// 断言的不是"数字好看"，而是三条**可用性约束**：
//   ① 照片区永远 ≥240 dp 且列数 ≥1（任何宽度都不塌）；
//   ② 列宽永远 ≥168 dp（低于它缩略图认不出内容，应减列而不是压窄）；
//   ③ 各栏宽度之和 + 分隔线 == 内容宽度（不出现"少算一个分隔线导致横向滚动"）。
package com.photomgm.desktop.ui.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LayoutTest {

    @Test
    fun `900 dp 外框对应 886 内容：折叠事件列表、检查器抽屉`() {
        val l = reviewLayout(886)
        assertEquals(64, l.railW)
        assertEquals(0, l.listW, "886 放不下 240 列表 + 420 照片，必须折叠")
        assertTrue(l.inspectorAsDrawer)
        // 只有"步骤栏 | 内容区"这一条分隔线；列表和检查器都折叠了，不该多算
        assertEquals(886, l.railW + l.listW + l.inspectorW + 1 + l.gridW)
    }

    @Test
    fun `984 dp（1366 屏 125%）三列可读`() {
        val l = reviewLayout(984)
        assertEquals(240, l.listW)
        assertTrue(l.inspectorAsDrawer, "984 上常驻检查器会把照片压到 2 列")
        assertEquals(678, l.gridW)
        assertEquals(3, l.gridCols)
    }

    @Test
    fun `1380 dp 起常驻检查器`() {
        val l = reviewLayout(1380)
        assertEquals(280, l.listW)
        assertEquals(320, l.inspectorW)
        assertTrue(!l.inspectorAsDrawer)
        assertEquals(1380, l.railW + l.listW + l.inspectorW + 3 + l.gridW)
    }

    @Test
    fun `1920 dp 满宽最多 7 列`() {
        val l = reviewLayout(1920)
        assertEquals(7, l.gridCols)
        assertTrue(l.gridW >= 1200)
    }

    @Test
    fun `任何宽度都满足可用性约束`() {
        var w = 640
        while (w <= 3840) {
            val l = reviewLayout(w)
            assertTrue(l.gridW >= 240, "w=$w grid=${l.gridW}")
            assertTrue(l.gridCols >= 1)
            val dividers = 1 + (if (l.listW > 0) 1 else 0) + (if (l.inspectorW > 0) 1 else 0)
            assertEquals(w, l.railW + l.listW + l.inspectorW + dividers + l.gridW, "w=$w 宽度不守恒")
            val tile = tileWidth(l.gridW, l.gridCols)
            assertTrue(tile >= 150, "w=$w 格宽 $tile 太小，应减列")
            w += 7
        }
    }

    @Test
    fun `窄窗收窄步骤栏`() {
        assertEquals(56, railWidth(640))
        assertEquals(64, railWidth(900))
    }

    @Test
    fun `列数随格宽单调不倒退`() {
        var prev = 0
        var w = 400
        while (w <= 2000) {
            val c = gridColumns(w)
            assertTrue(c >= prev, "w=$w 列数倒退：$prev → $c")
            prev = c
            w += 20
        }
    }

    @Test
    fun `输入页在 860 以下才上下堆叠`() {
        // 内容宽 = 窗宽 - 步骤栏 - 1：970 的窗内容 905，必须并排（否则日志掉到折叠线以下）
        assertTrue(inputLayout(984).twoPane)
        assertEquals(340, inputLayout(984).materialsW)
        assertTrue(inputLayout(700).twoPane.not())
        assertEquals(392, inputLayout(1400).materialsW)
    }

    @Test
    fun `导出页窄窗把选项收进抽屉`() {
        val narrow = exportLayout(984)
        assertEquals(0, narrow.sideW)
        assertTrue(narrow.tableW > 600)

        val wide = exportLayout(1380)
        assertEquals(320, wide.sideW)
        assertEquals(1380, wide.railW + 1 + wide.sideW + 1 + wide.tableW)
    }
}
