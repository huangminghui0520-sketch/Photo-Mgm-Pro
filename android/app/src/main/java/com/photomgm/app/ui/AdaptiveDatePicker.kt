// app/ui/AdaptiveDatePicker.kt —— 自适应日历（自绘 7 列网格，完全自管理状态）
// ★ 2026-10-07 UI 重建：核心修改——状态不再用 rememberSaveable 保存 LocalDate/YearMonth
//   （两者不可 Parcelable，真机 onSaveInstanceState 时抛异常并破坏 Compose 状态保存机制），
//   改为保存 Int/Long 基础类型（year/month/epochDay）。渲染逻辑与原实现等价。
package com.photomgm.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.WeekFields
import java.util.Locale

private val WeekdayShortNames = listOf("一", "二", "三", "四", "五", "六", "日")

/**
 * 某月需要渲染的周行数（4~6）。
 * @param ym 目标月份
 * @param firstDayOfWeek 周首日（java.time.DayOfWeek.value：周一=1 … 周日=7）
 */
internal fun monthRows(ym: YearMonth, firstDayOfWeek: Int): Int {
    val firstDow = ym.atDay(1).dayOfWeek.value
    val offset = (firstDow - firstDayOfWeek + 7) % 7
    return (offset + ym.lengthOfMonth() + 6) / 7
}

@Composable
fun AdaptiveDatePicker(
    initialDate: LocalDate,
    onDateSelected: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    // ★ 只保存基础类型：Int/Long 可被 rememberSaveable 序列化。
    //   LocalDate/YearMonth 没有 Saver，真机上保存时抛 IllegalStateException。
    var year by rememberSaveable { mutableIntStateOf(initialDate.year) }
    var month by rememberSaveable { mutableIntStateOf(initialDate.monthValue) }
    var selectedEpochDay by rememberSaveable { mutableLongStateOf(initialDate.toEpochDay()) }
    val today = LocalDate.now(algorithm.AppZone.ID)

    // 由 Int/Int 组装 YearMonth（纯函数，无保存问题）
    val displayedYm = YearMonth.of(year, month)
    val selected = LocalDate.ofEpochDay(selectedEpochDay)

    BoxWithConstraints(modifier) {
        // ── 宽度自适应：内容限宽 420dp 并居中；列宽 = 可用宽度 ÷ 7，保证 7 列永不被裁 ──
        val contentWidth = if (maxWidth > 420.dp) 420.dp else maxWidth
        val cellWidth = contentWidth / 7
        val cellHeight = cellWidth.coerceAtLeast(40.dp)
        val firstDayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek // zh → 周一
        // ★ 必须模 7：若周首是周日（=7）而当月 1 号是周一（=1），直接相减得 -6 会让网格错位
        val weekOffset = (displayedYm.atDay(1).dayOfWeek.value - firstDayOfWeek.value + 7) % 7
        val rowsNeeded = monthRows(displayedYm, firstDayOfWeek.value)

        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.width(contentWidth)) {
                // ── 标题行：‹ 2026年10月 › ──
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = {
                        val prev = displayedYm.minusMonths(1)
                        year = prev.year; month = prev.monthValue
                    }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "上一月")
                    }
                    Text(
                        "${displayedYm.year}年${displayedYm.monthValue}月",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                    IconButton(onClick = {
                        val next = displayedYm.plusMonths(1)
                        year = next.year; month = next.monthValue
                    }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "下一月")
                    }
                }

                // ── 表头：一二三四五六日（按周首日排列）──
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    (0 until 7).forEach { i ->
                        val dayIndex = (firstDayOfWeek.value - 1 + i) % 7 // 周一=0
                        Box(
                            Modifier
                                .weight(1f)
                                .height(cellHeight),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                WeekdayShortNames[dayIndex],
                                style = MaterialTheme.typography.labelMedium,
                                color = if (dayIndex == 6) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }

                // ── 日期网格：rowsNeeded 行 × 7 列 ──
                val gridStart = displayedYm.atDay(1).minusDays(weekOffset.toLong())
                for (row in 0 until rowsNeeded) {
                    Row(Modifier.fillMaxWidth()) {
                        for (col in 0 until 7) {
                            val date = gridStart.plusDays((row * 7 + col).toLong())
                            val inMonth = YearMonth.from(date) == displayedYm
                            val isSelected = date == selected
                            val isToday = date == today
                            AdaptiveDayCell(
                                date = date,
                                inMonth = inMonth,
                                isSelected = isSelected,
                                isToday = isToday,
                                cellWidth = cellWidth,
                                cellHeight = cellHeight,
                                onSelect = {
                                    selectedEpochDay = date.toEpochDay()
                                    onDateSelected(date)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AdaptiveDayCell(
    date: LocalDate,
    inMonth: Boolean,
    isSelected: Boolean,
    isToday: Boolean,
    cellWidth: Dp,
    cellHeight: Dp,
    onSelect: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(cellWidth, cellHeight)
            .padding(2.dp)
            .clip(CircleShape)
            .then(
                if (isSelected) Modifier.background(MaterialTheme.colorScheme.primary)
                else Modifier
            )
            .then(
                if (isToday && !isSelected) Modifier.border(1.dp, MaterialTheme.colorScheme.primary, CircleShape)
                else Modifier
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onSelect,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            date.dayOfMonth.toString(),
            style = MaterialTheme.typography.bodyMedium,
            color = when {
                isSelected -> MaterialTheme.colorScheme.onPrimary
                !inMonth -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                date.dayOfWeek.value == 7 -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurface
            },
            fontWeight = if (isSelected || isToday) FontWeight.Bold else FontWeight.Normal,
        )
        // 选中态内圆点（Material3 风格）
        if (isSelected) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 4.dp)
                    .size(4.dp)
                    .background(MaterialTheme.colorScheme.onPrimary, CircleShape)
            )
        }
    }
}
