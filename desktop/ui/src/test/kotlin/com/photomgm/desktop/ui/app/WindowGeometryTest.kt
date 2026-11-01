// ui/test/WindowGeometryTest.kt —— 窗口尺寸策略
//
// 这组用例的目的不是"覆盖代码"，而是把目标机型的可用性钉住：
//   1366×768 @125% 的内业笔记本，工作区只有 1093×574 dp。
//   任何"最小 1280×800"或"0.9 倍夹取"的写法在那里都会出问题，这里逐档断言。
package com.photomgm.desktop.ui.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WindowGeometryTest {

    private fun area(w: Int, h: Int) = WindowGeometry.Area(0, 0, w, h)

    @Test
    fun `1366x768 100% 按工作区夹取`() {
        // 1920 屏上任务栏 40 → 1366x728
        val b = WindowGeometry.initial(area(1366, 728))
        assertEquals(1229, b.width)   // 1366 * 0.9
        assertEquals(655, b.height)   // 728 * 0.9
    }

    @Test
    fun `1366x768 125% 不超屏且不崩`() {
        // 目标机型真实工作区：1093×574 dp
        val b = WindowGeometry.initial(area(1093, 574))
        assertEquals(984, b.width)
        // ★ 关键：900 想变 517，但最小高是 540；若直接 coerceIn(540, 517) 会抛异常。
        //   正确结果是 540（仍在 574 之内，窗口放得下）。
        assertEquals(540, b.height)
        assertTrue(b.height <= 574, "窗口高度必须 ≤ 工作区高度")
        assertTrue(b.width <= 1093)
    }

    @Test
    fun `1920x1080 100% 用默认尺寸`() {
        val b = WindowGeometry.initial(area(1920, 1040))
        assertEquals(1440, b.width)
        assertEquals(900, b.height)
    }

    @Test
    fun `1920x1080 125%`() {
        val b = WindowGeometry.initial(area(1536, 816))
        assertEquals(1382, b.width)
        assertEquals(734, b.height)
    }

    @Test
    fun `1920x1080 150%`() {
        val b = WindowGeometry.initial(area(1280, 680))
        assertEquals(1152, b.width)
        assertEquals(612, b.height)
    }

    @Test
    fun `工作区比最小值还小时退化到工作区大小`() {
        val b = WindowGeometry.initial(area(800, 600))
        assertEquals(800, b.width)
        assertEquals(540, b.height)
    }

    @Test
    fun `首次启动居中`() {
        val b = WindowGeometry.initial(area(1920, 1040))
        assertEquals((1920 - b.width) / 2, b.x)
        assertEquals((1040 - b.height) / 2, b.y)
    }

    @Test
    fun `记忆值优先于默认值`() {
        val saved = WindowGeometry.Bounds(100, 80, 1100, 700)
        val b = WindowGeometry.initial(area(1920, 1040), saved)
        assertEquals(saved, b)
    }

    @Test
    fun `记忆值出屏时被夹回工作区`() {
        // 上次在 4K 屏右侧关窗，这次接 1366 笔记本
        val saved = WindowGeometry.Bounds(3000, 1500, 1600, 900)
        val b = WindowGeometry.initial(area(1093, 574), saved)
        assertEquals(1093, b.width)
        assertEquals(574, b.height)
        assertEquals(0, b.x)
        assertEquals(0, b.y)
    }

    @Test
    fun `全档尺寸都不出屏不崩`() {
        var w = 640
        while (w <= 3840) {
            var h = 480
            while (h <= 2160) {
                val b = WindowGeometry.initial(area(w, h))
                assertTrue(b.width in 1..w, "宽度越界 w=$w h=$h → ${b.width}")
                assertTrue(b.height in 1..h, "高度越界 w=$w h=$h → ${b.height}")
                assertTrue(b.x >= 0 && b.x + b.width <= w)
                assertTrue(b.y >= 0 && b.y + b.height <= h)
                h += 120
            }
            w += 160
        }
    }
}
