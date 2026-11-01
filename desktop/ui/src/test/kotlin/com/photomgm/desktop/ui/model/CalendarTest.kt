// ui/test/CalendarTest —— 月历网格的纯函数
//
// ★ 这里每一条都是"用户能看见的错"的防线：网格错一格，点 11 月 1 日会选中 10 月 31 日，
//   而截图上看不出来。断言的日期都是硬编码的真实日历（2026 年 11 月 1 日是周日）。
package com.photomgm.desktop.ui.model

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CalendarTest {

    @Test
    fun `网格永远是 6 行 42 格`() {
        // 任意月份都必须是 6 行：换月时面板高度不变，指针不会落到别的日期上。
        listOf(2026 to 2, 2026 to 11, 2024 to 2, 2026 to 6).forEach { (y, m) ->
            val grid = monthGrid(y, m)
            assertEquals(WEEKS_IN_GRID, grid.weeks.size, "$y-$m 行数")
            grid.weeks.forEach { assertEquals(7, it.size, "$y-$m 每行 7 格") }
            assertEquals(42, grid.days.size)
        }
    }

    @Test
    fun `首行第一格是包含 1 号那一周的周一`() {
        // 2026-11-01 是周日 → 首格应是 10-26（周一）
        val grid = monthGrid(2026, 11)
        assertEquals(LocalDate.of(2026, 10, 26), grid.weeks[0][0].date)
        assertEquals(java.time.DayOfWeek.MONDAY, grid.weeks[0][0].date.dayOfWeek)
        assertEquals(LocalDate.of(2026, 11, 1), grid.weeks[0][6].date)
    }

    @Test
    fun `每行都是周一到周日`() {
        val grid = monthGrid(2026, 3)
        grid.weeks.forEach { week ->
            week.forEachIndexed { i, cell ->
                assertEquals(i + 1, cell.date.dayOfWeek.value, "${cell.date} 应落在第 ${i + 1} 列")
            }
        }
    }

    @Test
    fun `本月的日期标记为 inMonth，补位日不是`() {
        val grid = monthGrid(2026, 11)
        val november = grid.days.filter { it.inMonth }
        assertEquals(30, november.size, "11 月 30 天")
        assertEquals(LocalDate.of(2026, 11, 1), november.first().date)
        assertEquals(LocalDate.of(2026, 11, 30), november.last().date)
        assertFalse(grid.weeks[0][0].inMonth, "10-26 不该算作本月")
        assertTrue(grid.weeks[5].last().inMonth.not())
    }

    @Test
    fun `跨年补位不把 12 月的日子算成本月`() {
        // 2027-01-01 是周五 → 首格 2026-12-28，那一格的 year 不同，必须 inMonth=false
        val grid = monthGrid(2027, 1)
        assertEquals(LocalDate.of(2026, 12, 28), grid.weeks[0][0].date)
        assertFalse(grid.weeks[0][0].inMonth)
        assertEquals(31, grid.days.count { it.inMonth })
    }

    @Test
    fun `闰年二月 29 天且落在网格里`() {
        val grid = monthGrid(2024, 2)
        val days = grid.days.filter { it.inMonth }.map { it.date.dayOfMonth }
        assertEquals(29, days.size)
        assertTrue(days.contains(29))
        assertFalse(monthGrid(2026, 2).days.any { it.inMonth && it.date.dayOfMonth == 29 })
    }

    @Test
    fun `翻月跨年进位`() {
        assertEquals(2026 to 12, shiftMonth(2026, 11, 1))
        assertEquals(2027 to 1, shiftMonth(2026, 12, 1))
        assertEquals(2026 to 12, shiftMonth(2027, 1, -1))
        assertEquals(2026 to 1, shiftMonth(2025, 12, 1))
        assertEquals(2025 to 11, shiftMonth(2026, 1, -2))
    }

    @Test
    fun `相邻月份的网格首尾相接`() {
        // 一个月最后一格的下一天，必须等于下个月第一格 —— 否则换月会跳日或重复。
        for (m in 1..12) {
            val (ny, nm) = shiftMonth(2026, m, 1)
            val last = monthGrid(2026, m).weeks[5][6].date
            val first = monthGrid(ny, nm).weeks[0][0].date
            assertTrue(first <= last.plusDays(1), "2026-$m 末格 $last 与 $ny-$nm 首格 $first 不连续")
        }
    }

    @Test
    fun `选取与今天只按日期相等判断`() {
        val cell = monthGrid(2026, 11).weeks[0][6] // 2026-11-01
        assertTrue(isSelected(cell, LocalDate.of(2026, 11, 1)))
        assertFalse(isSelected(cell, LocalDate.of(2026, 11, 2)))
        assertTrue(isToday(cell, LocalDate.of(2026, 11, 1)))
        assertFalse(isToday(cell, LocalDate.of(2026, 10, 1)))
    }

    @Test
    fun `标题用中文年月`() {
        assertEquals("2026年11月", monthGrid(2026, 11).title)
        assertEquals(7, WEEKDAY_LABELS.size)
        assertEquals("一", WEEKDAY_LABELS.first())
        assertEquals("日", WEEKDAY_LABELS.last())
    }
}
