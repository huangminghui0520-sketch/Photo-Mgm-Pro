// desktop/ui/window/WindowGeometryTest.kt —— 尺寸策略的回归防线
//
// 这些用例的价值：**把内业机的屏幕规格变成可执行的断言**。
// 没有它们，窗口尺寸只能在开发机的 1920@125% 上"看着没问题"，
// 而真正的目标机型（1366@125%）要等到用户装机才发现窗口出屏。
package com.photomgm.desktop.ui.window

import com.photomgm.desktop.core.api.WindowState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WindowGeometryTest {

    /** 由物理分辨率与缩放推导工作区（扣 Windows 任务栏 40 dp）。 */
    private fun workArea(physicalW: Int, physicalH: Int, scale: Double, taskbar: Int = 40): ScreenRect {
        val w = (physicalW / scale).toInt()
        val h = (physicalH / scale).toInt()
        return ScreenRect(0, 0, w, h - taskbar)
    }

    // ── 首次启动：各档 DPI 的实际开出尺寸 ──────────────────────────────────

    @Test
    fun `1366x768 at 125% 开在最小高度而不是崩溃或出屏`() {
        // 这台就是内业机：1093×574 工作区，574×0.9=517 < 540 最小高。
        // 原 clamp 公式在此处上下界交叉（517 < 540），直接 coerceIn 会抛异常 —— 本用例钉死修法。
        val work = ScreenRect(0, 0, 1093, 574)
        val r = WindowSizing.resolve(null, work)

        assertEquals(984, r.width, "宽 = min(1440, round(1093×0.9))")
        assertEquals(540, r.height, "高被最小高兜底，不是 517")
        assertTrue(r.height <= work.height, "窗口高度必须 ≤ 工作区高度，否则下边框被裁")
        assertEquals(54, r.x, "居中：(1093-984)/2 = 54")
        assertEquals(17, r.y, "居中：(574-540)/2 = 17")
    }

    @Test
    fun `1366x768 at 100% 留出余量`() {
        val r = WindowSizing.resolve(null, workArea(1366, 768, 1.0))
        assertEquals(1229, r.width)
        assertEquals(655, r.height)
    }

    @Test
    fun `1920x1080 at 100% 期望值原样落地`() {
        val r = WindowSizing.resolve(null, workArea(1920, 1080, 1.0))
        assertEquals(1440, r.width)
        assertEquals(900, r.height)
    }

    @Test
    fun `1920x1080 at 125% 与 150% 都按工作区收缩`() {
        val at125 = WindowSizing.resolve(null, workArea(1920, 1080, 1.25))
        assertEquals(1382, at125.width)
        assertEquals(742, at125.height)

        val at150 = WindowSizing.resolve(null, workArea(1920, 1080, 1.5))
        assertEquals(1152, at150.width)
        assertEquals(612, at150.height)
    }

    @Test
    fun `4K at 200% 等价于 1920x1080 at 100%`() {
        val r = WindowSizing.resolve(null, workArea(3840, 2160, 2.0))
        assertEquals(1440, r.width)
        assertEquals(900, r.height)
    }

    @Test
    fun `比最小要求还小的屏不让位给最小值而占满屏幕`() {
        // 800×500 工作区：最小 900×540 根本放不下。
        // 此时若坚持最小值，窗口必然超出屏幕 —— 让位给屏幕才是对的。
        val r = WindowSizing.resolve(null, ScreenRect(0, 0, 800, 500))
        assertEquals(800, r.width)
        assertEquals(500, r.height)
        assertTrue(r.right <= 800 && r.bottom <= 500)
    }

    @Test
    fun `任何工作区都不会抛异常且窗口完整落在工作区内`() {
        // 扫一遍常见分辨率 × 缩放，确保没有空区间崩溃（原公式在 1366@125% 就会崩）。
        val resolutions = listOf(1366 to 768, 1440 to 900, 1600 to 900, 1920 to 1080, 2560 to 1440, 3840 to 2160)
        val scales = listOf(1.0, 1.25, 1.5, 1.75, 2.0)
        for ((pw, ph) in resolutions) {
            for (s in scales) {
                val work = workArea(pw, ph, s)
                val r = WindowSizing.resolve(null, work)
                assertTrue(r.width > 0 && r.height > 0, "${pw}x$ph@$s 尺寸非正")
                assertTrue(r.x >= work.x && r.y >= work.y, "${pw}x$ph@$s 左上角出界")
                assertTrue(r.right <= work.right, "${pw}x$ph@$s 右侧出界（${r.right} > ${work.right}）")
                assertTrue(r.bottom <= work.bottom, "${pw}x$ph@$s 底部出界（${r.bottom} > ${work.bottom}）")
            }
        }
    }

    // ── 二次启动：恢复用户的调整 ────────────────────────────────────────────

    @Test
    fun `合法位置原样恢复`() {
        val work = workArea(1920, 1080, 1.0)
        val saved = WindowState(x = 100, y = 80, width = 1200, height = 800)
        val r = WindowSizing.resolve(saved, work)
        assertEquals(ScreenRect(100, 80, 1200, 800), r)
    }

    @Test
    fun `恢复尺寸不做 90% 收缩`() {
        // 用户特意拖到 1450×950（超过期望值），不能被"期望值"再压回 1440×900。
        val work = workArea(1920, 1080, 1.0)
        val saved = WindowState(0, 0, 1900, 1030)
        val r = WindowSizing.resolve(saved, work)
        assertEquals(1900, r.width)
        assertEquals(1030, r.height)
    }

    @Test
    fun `旧版本留下的过小尺寸被抬到最小值`() {
        val work = workArea(1920, 1080, 1.0)
        val saved = WindowState(0, 0, 700, 400)
        val r = WindowSizing.resolve(saved, work)
        assertEquals(WindowSizing.MIN_WIDTH, r.width)
        assertEquals(WindowSizing.MIN_HEIGHT, r.height)
    }

    @Test
    fun `历史 640 高度的旧值不会被裁到屏幕外`() {
        // 假设某台机器留了个 1000×640：宽合法、高也 ≥540，应原样保留。
        val work = ScreenRect(0, 0, 1093, 574)
        val r = WindowSizing.resolve(WindowState(0, 0, 1000, 640), work)
        assertEquals(574, r.height, "超过工作区的旧高度必须压回 574")
    }

    @Test
    fun `换到小屏后旧尺寸放大屏的坐标会居中`() {
        val work = ScreenRect(0, 0, 1093, 574)
        val saved = WindowState(x = 1400, y = 300, width = 1200, height = 700)
        val r = WindowSizing.resolve(saved, work)
        assertTrue(r.right <= work.right && r.bottom <= work.bottom, "必须回到工作区内")
        assertEquals((1093 - 1093) / 2, r.x)
        assertEquals(1093, r.width)
    }

    @Test
    fun `显示器被拔掉时不留一个看不见的窗口`() {
        val work = ScreenRect(0, 0, 1536, 824)
        val saved = WindowState(x = -1800, y = 0, width = 1200, height = 800)
        val r = WindowSizing.resolve(saved, work)
        assertTrue(r.x >= 0, "负坐标要修正")
    }
}
