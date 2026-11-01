// ui/model/Calendar.kt —— 月历网格（纯函数，无 Compose 依赖）
//
// ★ 为什么把日历的算术抽出来：日期是项目主键（决定扫哪些照片、也决定日志里只有月日时补哪一年），
//   一旦网格算错一格，用户会在"11 月 1 日"点到"10 月 31 日"，而这种错误在截图上几乎看不出来
//   —— 只能靠单测盯住。四条硬规则：
//     ① 周一起始（中文 Windows 的日历默认如此，六/日在末尾）；
//     ② 永远 6 行（42 格）：换月时面板高度不变，鼠标指针不会因为面板收缩而落到别的日期上；
//     ③ 上月/下月的补位日也画出来（淡色），但点它 = 选中那天并跳到那个月（不是"禁用"）；
//     ④ 只做"月"这一级，不做周视图/年视图：巡查是一天一批，用户要的是"这个月哪几天在干活"。
package com.photomgm.desktop.ui.model

import java.time.LocalDate
import java.time.YearMonth

/** 网格里的一格。`inMonth = false` 表示它是上月/下月补位。 */
data class DayCell(val date: LocalDate, val inMonth: Boolean)

data class MonthGrid(val year: Int, val month: Int, val weeks: List<List<DayCell>>) {
    val days: List<DayCell> get() = weeks.flatten()
    val title: String get() = "${year}年${month}月"
}

/** 周标题：周一起始。 */
val WEEKDAY_LABELS: List<String> = listOf("一", "二", "三", "四", "五", "六", "日")

const val WEEKS_IN_GRID = 6

/**
 * 某个年月的 6×7 网格。
 *
 * 首行从"包含 1 号的那一周的周一"开始，而不是从 1 号开始 —— 这样每一列永远是同一个星期几，
 * 扫视时"周五那列"不会随月份横移。
 */
fun monthGrid(year: Int, month: Int): MonthGrid {
    val first = LocalDate.of(year, month, 1)
    // DayOfWeek.value: 周一=1 … 周日=7 → 转成"距本周一几天"
    val lead = first.dayOfWeek.value - 1
    val start = first.minusDays(lead.toLong())
    val weeks = (0 until WEEKS_IN_GRID).map { w ->
        (0 until 7).map { d ->
            val date = start.plusDays((w * 7 + d).toLong())
            DayCell(date, date.monthValue == month && date.year == year)
        }
    }
    return MonthGrid(year, month, weeks)
}

/** 翻月：返回 (年, 月)，跨年自动进位。用 YearMonth 算，避免手写 12/1 的边界错误。 */
fun shiftMonth(year: Int, month: Int, delta: Long): Pair<Int, Int> {
    val ym = YearMonth.of(year, month).plusMonths(delta)
    return ym.year to ym.monthValue
}

/** 面板上该高亮哪一格：只有选中日与"今天"两种。 */
fun isSelected(cell: DayCell, selected: LocalDate): Boolean = cell.date == selected

fun isToday(cell: DayCell, today: LocalDate): Boolean = cell.date == today
