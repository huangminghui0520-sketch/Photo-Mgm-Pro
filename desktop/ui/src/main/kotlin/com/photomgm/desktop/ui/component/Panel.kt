// ui/component/Panel.kt —— 面板 / 提示条 / 键值行
//
// 三个都是"容器"，不是"控件"。放这里是为了让阶段文件只写结构，不重复写边框和留白。
package com.photomgm.desktop.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.photomgm.desktop.ui.theme.PmColor
import com.photomgm.desktop.ui.theme.PmRadius
import com.photomgm.desktop.ui.theme.PmSpace
import com.photomgm.desktop.ui.theme.PmType

@Composable
fun Panel(
    title: String?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    contentPadding: Int = 14,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(PmRadius.panel))
            .background(PmColor.surface)
            .border(1.dp, PmColor.line, RoundedCornerShape(PmRadius.panel))
            .padding(contentPadding.dp),
    ) {
        if (title != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = PmType.title, color = PmColor.ink)
                    if (subtitle != null) {
                        Text(subtitle, style = PmType.caption, color = PmColor.ink3)
                    }
                }
                trailing?.invoke()
            }
            Spacer(Modifier.height(PmSpace.m))
        }
        content()
    }
}

/** 提示条：紧贴顶栏之下，不遮内容、不自动消失（错误必须由人确认消除）。 */
@Composable
fun InlineNotice(
    tone: Tone,
    text: String,
    onDismiss: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    val icon: ImageVector = when (tone) {
        Tone.DANGER -> Icons.Filled.ErrorOutline
        Tone.WARN -> Icons.Filled.WarningAmber
        else -> Icons.Filled.Info
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(tone.bg())
            .padding(horizontal = PmSpace.l, vertical = PmSpace.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tone.fg(), modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(PmSpace.m))
        Text(
            text,
            style = PmType.body,
            color = PmColor.ink,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        action?.invoke()
        if (onDismiss != null) {
            Spacer(Modifier.width(PmSpace.s))
            PmIconButton(Icons.Filled.Close, "关闭提示", onDismiss, size = 24.dp)
        }
    }
}

/** 键值行：标签左、值右（值可选中复制由外层决定）。 */
@Composable
fun KeyValue(label: String, value: String, tone: Tone = Tone.NEUTRAL) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = PmType.caption, color = PmColor.ink3)
        Spacer(Modifier.weight(1f))
        Text(value, style = PmType.numeric, color = if (tone == Tone.NEUTRAL) PmColor.ink2 else tone.fg())
    }
}

/** 一列小键值对（用于检查器的事实区）。 */
@Composable
fun FactList(facts: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    Column(modifier) {
        facts.forEach { (k, v) -> KeyValue(k, v) }
    }
}

@Composable
fun Dash() {
    Spacer(
        Modifier
            .height(1.dp)
            .fillMaxWidth()
            .background(PmColor.line),
    )
}

@Composable
fun RowGap() = Spacer(Modifier.height(PmSpace.m))

@Composable
fun HSpacer(w: Int) = Spacer(Modifier.width(w.dp))

@Composable
fun BoxGap() = Spacer(Modifier.height(PmSpace.xl))
