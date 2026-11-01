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
package com.photomgm.desktop.ui.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

@Composable
fun SheetHost(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
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
        Box(
            Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
        ) {
            content()
        }
    }
}
