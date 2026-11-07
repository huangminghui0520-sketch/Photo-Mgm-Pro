// ui/component/PmTimePicker.kt —— 时 / 分两列时间选择（手机导入弹窗）
//
// ★ 为什么是「两列直接点」而不是文本框：
//   桌面上敲一次 `23:55` 要按 5 次键，还得记住分隔符与补零；而现场要选的时刻往往只是
//   「比这张早一点」这种粗粒度意图。两列各点一下就生效，**没有「确定」按钮** ——
//   与日期格子同一个交互契约：点 = 选定，弹窗里立刻能看到「同步到」的变化。
//
// ★ 为什么分列是 5 分钟一档（不是 1）：
//   区间只用于文件夹命名与筛选边界，5 分钟粒度已比现场需求细；1 分钟要 60 项，
//   滚动长度翻 5 倍，选 23:55 得滚半屏。档位定义在 model（TIME_STEP_MINUTES /
//   MINUTE_OPTIONS），本组件只渲染，不另存一份常量。
//
// ★ 为什么高度固定 200dp：
//   两行（开始 / 结束）并排时列高必须一致，否则两行基线错开，扫一眼像填错了行；
//   高度随内容自适应还会让「选中之后列变高」，鼠标下的目标位置跳走。
//   200dp ≈ 7.5 项可见，翻一屏够看到相邻半小时。
//
// ★ 为什么不用 `pmPress`（日期格子用的那个）：
//   pmPress 在**按下**即回调，列本身要拖动滚动 —— 一次滚动意图会被当成一次选择。
//   这里用 clickable（抬起才判定 + 走 touch slop），滚动与选中才不会互相误判。
package com.photomgm.desktop.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import com.photomgm.desktop.ui.model.HOUR_OPTIONS
import com.photomgm.desktop.ui.model.MINUTE_OPTIONS
import com.photomgm.desktop.ui.model.snapToStep
import com.photomgm.desktop.ui.theme.PmColor
import com.photomgm.desktop.ui.theme.PmRadius
import com.photomgm.desktop.ui.theme.PmSpace
import com.photomgm.desktop.ui.theme.PmType

/** 两列合计的高度（含列头）。固定值，理由见文件头。 */
private val PICKER_H = 200.dp

/** 单行高度：24 项时一屏能看见 7 项多一点，滚动量不大。 */
private val ITEM_H = 24.dp

/** 列宽：两位数字 + 左右各 8dp 内边距，24 与 55 都不会被截。 */
private val COL_W = 48.dp

/** 首屏让选中项上方留 2 行上下文，用户能看见相邻档位而不是贴在顶边。 */
private const val LEAD_ROWS = 2

/**
 * 时 / 分两列选择器。
 *
 * @param hour 0..23；不在范围内时按 0 显示（界面不该传越界值，这里只兜底不报错）。
 * @param minute 任意 0..59；高亮取它所在的 5 分钟档（59 → 55 档），点档位才改成档位值。
 * @param enabled false 时整列置灰且不可点（同步进行中）。
 */
@Composable
fun PmTimePicker(
    hour: Int,
    minute: Int,
    onHourChange: (Int) -> Unit,
    onMinuteChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val selectedHour = hour.coerceIn(0, 23)
    // 档位落位：59 落在 55 档。用档位下沿做高亮，不悄悄改业务值。
    val selectedMinute = snapToStep(minute.coerceIn(0, 59))

    Column(modifier.height(PICKER_H)) {
        Row {
            ColumnHeader("时", COL_W)
            Spacer(Modifier.width(PmSpace.xs))
            ColumnHeader("分", COL_W)
        }
        Spacer(Modifier.height(PmSpace.xxs))
        Row {
            TimeColumn(HOUR_OPTIONS, selectedHour, enabled, onHourChange)
            Spacer(Modifier.width(PmSpace.xs))
            TimeColumn(MINUTE_OPTIONS, selectedMinute, enabled, onMinuteChange)
        }
    }
}

@Composable
private fun ColumnHeader(text: String, width: androidx.compose.ui.unit.Dp) {
    Box(Modifier.width(width), contentAlignment = Alignment.Center) {
        Text(text, style = PmType.micro, color = PmColor.ink3)
    }
}

@Composable
private fun TimeColumn(
    options: List<Int>,
    selected: Int,
    enabled: Boolean,
    onPick: (Int) -> Unit,
) {
    val state = rememberLazyListState()
    // 首屏把选中项滚进视野：17 时在 24 项里的第 18 位，不滚的话打开只看到 0~6 时，
    // 用户会以为"当前值没选中"。只在首次组合时滚一次，之后不再抢用户的滚动位置。
    LaunchedEffect(Unit) {
        val index = options.indexOf(selected).takeIf { it >= 0 } ?: 0
        state.scrollToItem((index - LEAD_ROWS).coerceAtLeast(0))
    }

    LazyColumn(
        state = state,
        modifier = Modifier
            .width(COL_W)
            .fillMaxHeight()
            .clip(RoundedCornerShape(PmRadius.control))
            .background(PmColor.surface2),
    ) {
        items(options, key = { it }) { value ->
            TimeCell(
                text = twoDigits(value),
                selected = value == selected,
                enabled = enabled,
                onClick = { onPick(value) },
            )
        }
    }
}

@Composable
private fun TimeCell(
    text: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(PmRadius.control)

    val bg = when {
        // 选中色与日历格子同源（accent + surface 文字）：全项目只有一个强调色。
        selected -> if (enabled) PmColor.accent else PmColor.lineStrong
        hovered && enabled -> PmColor.surface
        else -> Color.Transparent
    }
    // ink4 是"禁止用于文字"的非文本色，除非整列禁用（此时它不承载可读信息，只是灰条）。
    val fg = when {
        selected -> PmColor.surface
        !enabled -> PmColor.ink4
        else -> PmColor.ink
    }

    Box(
        Modifier
            .fillMaxWidth()
            .height(ITEM_H)
            .padding(horizontal = PmSpace.xxs)
            .clip(shape)
            .background(bg)
            .hoverable(interaction)
            .then(
                if (enabled) {
                    Modifier
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                            onClick = onClick,
                        )
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = PmType.numeric, color = fg)
    }
}

private fun twoDigits(v: Int): String = if (v < 10) "0$v" else v.toString()
