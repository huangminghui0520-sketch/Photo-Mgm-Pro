// ui/component/Notice.kt —— 提示条 / 键值行 / 分隔线
//
// 单页结构下这三样还在用：
//   · InlineNotice —— 错误与提示（顶栏下，错误必须由人确认才消失）；
//   · KeyValue     —— 照片层的"事件信息"事实栏；
//   · Dash         —— 事实栏里的分组线。
// 原来的 Panel / StatCell / SectionTitle / LoadingLine / ReadonlyRow / FactList / RowGap / HSpacer / BoxGap
// 随四阶段结构一起删除了：单页里没有"面板"，卡片自己就是容器；统计改成卡片标题行上的一行字；
// 留着一堆没人用的容器只会让人以为界面还有那些区域。
package com.photomgm.desktop.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.photomgm.desktop.ui.theme.PmColor
import com.photomgm.desktop.ui.theme.PmSpace
import com.photomgm.desktop.ui.theme.PmType

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

/** 键值行：标签左、值右。 */
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

@Composable
fun Dash() {
    Spacer(
        Modifier
            .height(1.dp)
            .fillMaxWidth()
            .background(PmColor.line),
    )
}
