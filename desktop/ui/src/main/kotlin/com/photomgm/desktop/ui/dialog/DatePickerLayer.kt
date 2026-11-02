// ui/dialog/DatePickerLayer.kt —— 巡查日期的浮层日历
//
// ★ 为什么必须是浮层、且浮层必须挂在根覆盖层上：
//   上一版把日历面板"就地展开"在日期列下方（Column 里多出一块），于是点一次日期，
//   下面的日志框、结果条、整列事件卡片会整体下移 240 dp 左右；再点一次又整体弹回。
//   那是**用布局变化表达一个瞬时选择**，代价是用户的视线和滚动位置都被打乱。
//   现在面板脱离文档流：日期列的高度永远是"一行"（标签 + 字段），页面一个像素都不动。
//
// ★ 为什么不用 Compose 的 Popup：桌面端 Popup 是否走独立 OS 窗口随版本/属性变化，
//   一旦走独立窗口就会重演"独立 Dialog 在 125% 缩放下尺寸错乱"的老问题（见 SheetHost 头注）。
//   这里直接用根 Box 里的绝对定位卡片：坐标系就是主窗口的像素，行为完全可预期。
//
// ★ 定位规则（三条，缺一条就会出现"面板跑到窗口外"）：
//   ① 左边缘对齐日期字段左边缘，并夹在窗口内（右侧留 8 dp）；
//   ② 默认长在字段下方 6 dp；
//   ③ 下方放不下（低分辨率/窗口拉高时常见）就翻到字段上方，仍放不下则贴窗口下沿。
//   位置用的是**窗口像素**，所以 100% / 125% / 150% 缩放都不需要另算一套。
package com.photomgm.desktop.ui.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.photomgm.desktop.ui.component.CALENDAR_PANEL_W
import com.photomgm.desktop.ui.component.PmCalendarPanel
import com.photomgm.desktop.ui.theme.PmRadius
import java.time.LocalDate

/** 面板与字段之间的缝（像素）：贴太紧会看起来像字段的下半部分。 */
private const val GAP_PX = 6

/** 面板离窗口边缘的最小距离。 */
private const val SCREEN_PAD_PX = 8

/** 面板高度的估算值（还没测量到时用它决定"下方够不够"）。 */
private val ESTIMATED_H = 264.dp

@Composable
fun DatePickerLayer(
    date: LocalDate,
    anchor: IntRect,
    windowWidth: Int,
    windowHeight: Int,
    onPick: (LocalDate) -> Unit,
    onClose: () -> Unit,
) {
    val size = remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val estimatedW = with(density) { CALENDAR_PANEL_W.toPx() }.toInt()
    val estimatedH = with(density) { ESTIMATED_H.toPx() }.toInt()
    val panelW = size.value.width.takeIf { it > 0 } ?: estimatedW
    val panelH = size.value.height.takeIf { it > 0 } ?: estimatedH

    // ① 左对齐 + 夹在窗口内
    val x = anchor.left.coerceIn(
        SCREEN_PAD_PX,
        (windowWidth - panelW - SCREEN_PAD_PX).coerceAtLeast(SCREEN_PAD_PX),
    )
    // ②③ 优先下方，放不下翻到上方，最后兜底夹到窗口内
    val below = anchor.bottom + GAP_PX
    val above = anchor.top - GAP_PX - panelH
    val y = when {
        below + panelH <= windowHeight - SCREEN_PAD_PX -> below
        above >= SCREEN_PAD_PX -> above
        else -> (windowHeight - panelH - SCREEN_PAD_PX).coerceAtLeast(SCREEN_PAD_PX)
    }

    // 全屏透明点击层：点面板外面任意位置 = 关闭。它不压暗主界面 ——
    // 选日期时用户还要看着日期字段本身与当天的日志摘要，压暗反而看不见。
    Box(
        Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClose,
            ),
    ) {
        Box(
            Modifier
                .offset { IntOffset(x, y) }
                .onSizeChanged { size.value = it }
                .shadow(14.dp, RoundedCornerShape(PmRadius.card))
                .clip(RoundedCornerShape(PmRadius.card))
                // 面板自己吞掉点击，否则点格子会顺着穿到遮罩上把日历关掉。
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
        ) {
            PmCalendarPanel(selected = date, onPick = onPick, onClose = onClose)
        }
    }
}
