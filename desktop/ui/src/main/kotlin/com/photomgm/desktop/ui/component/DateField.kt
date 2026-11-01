// ui/component/DateField.kt —— 巡查日期（日历组件）
//
// ★ 为什么从"文本框 + 前后一天"改成日历：巡查日期是**项目主键**，它决定扫哪些照片、
//   也决定日志里只有月日时补哪一年。原来那版要用户手敲 `2026-11-01`，于是有三类必然发生的事：
//     ① 敲错一位（2026-11-1O / 2026-1O-01）→ 界面把非法输入吞掉、不报错，用户以为改成功了；
//     ② 想选"上周五"要先算今天是几号，再心算减几天；
//     ③ 输入框里没有"这个月哪几天在干活"的信息，改日期前无从判断。
//   日历把这三件事一次解决：点格子 = 选定，淡色补位日让月份边界可见，面板里直接显示已选日期。
//
// ★ 面板是**就地展开**（不是浮层）：它长在主页输入带的日期列里（见 ui/page/LogBar 的 DateSection），
//   展开时把下面的日志/列表往下推。做成浮层就要处理 z 序、点外部关闭、贴着窗口下沿时的翻转，
//   而"往下推"在桌面端本来就是可预测的行为，也不需要额外状态
//   （open 是组件本地状态，不污染 UiState —— 它是一次瞬时展开，不是业务状态）。
package com.photomgm.desktop.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import com.photomgm.desktop.ui.format.dateText
import com.photomgm.desktop.ui.format.nowDate
import com.photomgm.desktop.ui.model.DayCell
import com.photomgm.desktop.ui.model.WEEKDAY_LABELS
import com.photomgm.desktop.ui.model.monthGrid
import com.photomgm.desktop.ui.theme.PmColor
import com.photomgm.desktop.ui.theme.PmRadius
import com.photomgm.desktop.ui.theme.PmSize
import com.photomgm.desktop.ui.theme.PmSpace
import com.photomgm.desktop.ui.theme.PmType
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle as JTextStyle
import java.util.Locale

/** 日历格：34×26。7 列 = 238，加面板内边距后 254 —— 主页日期列 312，放得下且不压到日志框。
 *  高度按"展开后整个面板 + 日志框仍能塞进 540 dp 高的窗口"倒推：6 行 × 26 是上限。 */
private val CELL_W = 34.dp
private val CELL_H = 26.dp

@Composable
fun PmDateField(
    date: LocalDate,
    onChange: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    // 面板打开时从当前选中月起步；换日期后重新从新日期起步（关掉再开会回到"我选的那天"）。
    var cursor by remember(date, open) { mutableStateOf(YearMonth.from(date)) }
    val today = remember { nowDate() }

    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DateButton(date = date, open = open, onClick = { open = !open })
            Spacer(Modifier.width(PmSpace.xs))
            PmIconButton(Icons.Filled.ChevronLeft, "前一天", { onChange(date.minusDays(1)) }, size = 28.dp)
            PmButton("今天", { onChange(today) }, kind = ButtonKind.GHOST)
            PmIconButton(Icons.Filled.ChevronRight, "后一天", { onChange(date.plusDays(1)) }, size = 28.dp)
        }

        if (open) {
            Spacer(Modifier.height(PmSpace.m))
            CalendarPanel(
                year = cursor.year,
                month = cursor.monthValue,
                selected = date,
                today = today,
                onShiftMonth = { delta -> cursor = cursor.plusMonths(delta) },
                onPick = { picked ->
                    onChange(picked)
                    open = false
                },
                onClose = { open = false },
            )
        }
    }
}

/** 字段本体：看起来是个输入框，实际是一个按钮（避免"能打字但打了也不生效"的半可用状态）。 */
@Composable
private fun DateButton(date: LocalDate, open: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(PmRadius.control)
    // ★ SHORT 格式在中文里已经是「周日」，前面不能再拼一个「周」（会变成"周周日"）。
    val weekday = date.dayOfWeek.getDisplayName(JTextStyle.SHORT, Locale.CHINA)

    Row(
        modifier = Modifier
            .width(196.dp)
            .height(PmSize.control)
            .clip(shape)
            .background(PmColor.surface)
            .border(1.dp, if (open || hovered) PmColor.accent.copy(alpha = 0.55f) else PmColor.lineStrong, shape)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = PmSpace.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.CalendarToday,
            contentDescription = null,
            tint = if (open) PmColor.accent else PmColor.ink2,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(PmSpace.m))
        Text(dateText(date), style = PmType.numeric, color = PmColor.ink)
        Spacer(Modifier.width(PmSpace.s))
        Text(weekday, style = PmType.caption, color = PmColor.ink3)
        Spacer(Modifier.weight(1f))
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = if (open) "收起日历" else "展开日历",
            tint = if (open) PmColor.accent else PmColor.ink4,
            modifier = Modifier.size(14.dp).rotate(if (open) -90f else 90f),
        )
    }
}

@Composable
private fun CalendarPanel(
    year: Int,
    month: Int,
    selected: LocalDate,
    today: LocalDate,
    onShiftMonth: (Long) -> Unit,
    onPick: (LocalDate) -> Unit,
    onClose: () -> Unit,
) {
    val grid = remember(year, month) { monthGrid(year, month) }
    val shape = RoundedCornerShape(PmRadius.card)

    Column(
        Modifier
            .width(262.dp)
            .clip(shape)
            .background(PmColor.surface)
            .border(1.dp, PmColor.line, shape)
            .padding(PmSpace.m),
    ) {
        // 标题行：月份是主角，翻月只留两个箭头（不做年视图，翻 12 次也就一秒）
        Row(verticalAlignment = Alignment.CenterVertically) {
            PmIconButton(Icons.Filled.ChevronLeft, "上个月", { onShiftMonth(-1) }, size = 26.dp)
            Spacer(Modifier.width(PmSpace.xs))
            Text(grid.title, style = PmType.bodyStrong, color = PmColor.ink)
            Spacer(Modifier.width(PmSpace.xs))
            PmIconButton(Icons.Filled.ChevronRight, "下个月", { onShiftMonth(1) }, size = 26.dp)
            Spacer(Modifier.weight(1f))
            // 只有"今天不在当前显示的月份"时才给这个入口：同月时它和标题重复，是噪音。
            if (year != today.year || month != today.monthValue) {
                PmButton("回今天", { onPick(today) }, kind = ButtonKind.GHOST)
            }
        }
        Spacer(Modifier.height(PmSpace.xs))

        Row {
            WEEKDAY_LABELS.forEach { label ->
                Box(Modifier.width(CELL_W), contentAlignment = Alignment.Center) {
                    Text(label, style = PmType.micro, color = PmColor.ink3)
                }
            }
        }
        Spacer(Modifier.height(2.dp))

        // ★ 固定 6 行：换月时面板高度不变，指针不会因为面板收缩落到别的日期上。
        grid.weeks.forEach { week ->
            Row {
                week.forEach { cell ->
                    DayCellView(
                        cell = cell,
                        selected = cell.date == selected,
                        today = cell.date == today,
                        onClick = { onPick(cell.date) },
                    )
                }
            }
        }

        Spacer(Modifier.height(PmSpace.xs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "已选 ${dateText(selected)}",
                style = PmType.caption,
                color = PmColor.ink3,
            )
            Spacer(Modifier.weight(1f))
            PmButton("关闭", onClose, kind = ButtonKind.GHOST)
        }
    }
}

@Composable
private fun DayCellView(cell: DayCell, selected: Boolean, today: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(PmRadius.control)

    val bg = when {
        selected -> PmColor.accent
        hovered -> PmColor.surface2
        else -> PmColor.surface
    }
    val fg = when {
        selected -> PmColor.surface
        !cell.inMonth -> PmColor.ink4
        today -> PmColor.accent
        else -> PmColor.ink
    }

    Box(
        Modifier
            .width(CELL_W)
            .height(CELL_H)
            .padding(1.dp)
            .clip(shape)
            .background(bg)
            .then(
                // 今天：没有选中时用一个细描边标出来（选中时底色已经是强调色，不需要再描）
                if (today && !selected) Modifier.border(1.dp, PmColor.accent.copy(alpha = 0.5f), shape) else Modifier,
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            cell.date.dayOfMonth.toString(),
            style = PmType.numeric,
            color = fg,
        )
    }
}

/** 路径取"尾部"而不是头部：截头会把唯一有辨识度的末级目录名切掉。 */
fun shortPath(path: String, keep: Int = 34): String {
    if (path.length <= keep) return path
    val parts = path.replace('\\', '/').split('/').filter { it.isNotEmpty() }
    if (parts.size <= 2) return path
    return "…/" + parts.takeLast(2).joinToString("/")
}

/** 辅助：某天在这套界面里统一写成 `2026-11-01 周日`。 */
fun dateWithWeekday(date: LocalDate): String =
    dateText(date) + " " + date.dayOfWeek.getDisplayName(JTextStyle.SHORT, Locale.CHINA)
