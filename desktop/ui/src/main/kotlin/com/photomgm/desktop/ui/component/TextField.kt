// ui/component/TextField.kt —— 输入框
//
// ★ 不用 M3 的 OutlinedTextField：它的最小高度是 56 dp，在 540 dp 高的窗口里
//   三个字段就吃掉 1/3 屏。桌面表格型工具的输入框应该 30~32 dp。
//   这里用 BasicTextField 自绘，把高度、内边距、光标色都拿到手里。
package com.photomgm.desktop.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.photomgm.desktop.ui.theme.PmColor
import com.photomgm.desktop.ui.theme.PmRadius
import com.photomgm.desktop.ui.theme.PmSpace
import com.photomgm.desktop.ui.theme.PmType

@Composable
fun PmTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    singleLine: Boolean = true,
    textStyle: TextStyle = PmType.body,
    height: Dp = 32.dp,
    prefix: String? = null,
) {
    val shape = RoundedCornerShape(PmRadius.control)
    Row(
        modifier = modifier
            .height(height)
            .clip(shape)
            .background(if (enabled) PmColor.surface else PmColor.surface2)
            .border(1.dp, PmColor.lineStrong, shape)
            .padding(horizontal = PmSpace.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (prefix != null) {
            Text(prefix, style = PmType.caption, color = PmColor.ink3)
            Spacer(Modifier.width(PmSpace.xs))
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(placeholder, style = textStyle, color = PmColor.ink3, maxLines = 1)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = singleLine,
                textStyle = textStyle.copy(color = PmColor.ink),
                cursorBrush = SolidColor(PmColor.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 多行文本域（巡查日志）。
 *
 * ★ 关键约束：**必须自己滚动**。巡查日志常有一两百行，如果让它把父容器撑高，
 *   整个输入页就没底了；所以给死高度 + 内部滚动，顶部字段区始终可见。
 *   等宽字体不给中文日志用——中英文混排时等宽字体的中文回退字形会很丑且行距不齐。
 */
@Composable
fun PmTextArea(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(PmRadius.control)
    Box(
        modifier = modifier
            .clip(shape)
            .background(PmColor.surface)
            .border(1.dp, PmColor.lineStrong, shape)
            .padding(PmSpace.m),
    ) {
        if (value.isEmpty()) {
            Text(placeholder, style = PmType.body, color = PmColor.ink3)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            textStyle = PmType.body.copy(color = PmColor.ink, lineHeight = 21.sp),
            cursorBrush = SolidColor(PmColor.accent),
            modifier = Modifier.fillMaxSize(),
        )
    }
}
