// ui/dialog/SheetHost.kt —— 覆盖层外壳（替代独立窗口式 Dialog）
//
// ★ 为什么不用 androidx.compose.ui.window.Dialog：
//   它是**独立的操作系统窗口**，在 125% 缩放的机器上会被系统按另一套 DPI 处理，
//   实测我们显式给的 830×455 dp 被渲染成约 440×455，标题被裁、操作条按钮溢出到窗口外。
//   覆盖在主窗口内的 Scrim + 卡片没有这个问题：尺寸就是 dp 与主窗口同一套度量，
//   而且焦点 / 截图都在同一个窗口里，行为可预期（本项目不实现键盘关闭，关闭一律点 ✕）。
//
// ★ 交互：点遮罩关闭、点卡片不关闭（卡片自己消费点击）。这不是"弹窗礼仪"，是必要的：
//   复核时手上常常刚点完照片，误点遮罩就关掉会丢掉当前筛选的上下文。
//
// ★ 可拖动（v3.4）：遮罩是整窗的，弹窗永远居中会**永远挡住同一块区域** ——
//   照片弹窗里正要看的那张照片，可能恰好被弹窗盖住的位置在参考事件里。
//   所以头部可拖：拖动手柄由调用方贴到自己头部那一行（[content] 的入参），
//   位移被夹在窗口内（留 8 dp 边），永远不会拖出可见区域找不回来。
//   拖动识别是「按下后移动超过 touchSlop 才算」，所以头部里的按钮照样能点。
package com.photomgm.desktop.ui.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import java.awt.Cursor
import kotlin.math.roundToInt

@Composable
fun SheetHost(
    onDismiss: () -> Unit,
    /** 入参是「拖动手柄」修饰符：调用方把它贴到自己的标题行上，弹窗就能被拖动。 */
    content: @Composable (dragHandle: Modifier) -> Unit,
) {
    val offset = remember { mutableStateOf(Offset.Zero) }
    val panel = remember { mutableStateOf(IntSize.Zero) }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color(0x66101418))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // 窗口尺寸在这里是已知的（constraints 就是本方寸尺），用来把拖动结果夹回可视区。
        val winW = constraints.maxWidth
        val winH = constraints.maxHeight
        val dragHandle = Modifier
            // 可拖动的第一提示是光标形状：头部悬停显示"移动"光标，比加一行"可拖动"的文案省地方。
            .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)))
            .pointerInput(winW, winH) {
            awaitEachGesture {
                // Initial 通道 + requireUnconsumed = false：先看到按下，但不抢它 ——
                // 头部里的 ✕ / ‹ › 是普通按钮，它们要能正常收到这次点击。
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                var travelled = Offset.Zero
                var dragging = false
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (change.changedToUpIgnoreConsumed()) break
                    val delta = change.positionChange()
                    travelled += delta
                    // 超过阈值才算拖动：否则一次点击会被当成"零位移拖动"，按钮点不动。
                    if (!dragging && travelled.getDistance() > viewConfiguration.touchSlop) dragging = true
                    if (dragging) {
                        change.consume()
                        offset.value = clampToWindow(offset.value + delta, panel.value, winW, winH)
                    }
                }
            }
        }

        Box(
            Modifier
                .onSizeChanged { panel.value = it }
                .offset { IntOffset(offset.value.x.roundToInt(), offset.value.y.roundToInt()) }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
        ) {
            content(dragHandle)
        }
    }
}

/** 把弹窗位移夹在窗口内：面板整体始终可见（左右各留 8 dp，避免贴边后看不出是浮层）。 */
private fun clampToWindow(value: Offset, panel: IntSize, winW: Int, winH: Int): Offset {
    if (panel.width <= 0 || panel.height <= 0) return Offset.Zero
    val maxX = ((winW - panel.width) / 2f - 8f).coerceAtLeast(0f)
    val maxY = ((winH - panel.height) / 2f - 8f).coerceAtLeast(0f)
    return Offset(
        value.x.coerceIn(-maxX, maxX),
        value.y.coerceIn(-maxY, maxY),
    )
}
