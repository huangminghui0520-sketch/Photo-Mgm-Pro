// ui/page/ResultBanner.kt —— 导出结果条（页内，不弹窗）
//
// ★ 为什么结果留在页面里而不是弹一个"导出成功"对话框：
//   导出之后用户下一步几乎总是「打开目录交付」或「补几张再导一次」，
//   这两件事都需要看着事件列表做判断。弹窗会挡住列表，还多一次关闭动作。
//   结果条只在顶部占 3 行，可收起，收起后本次结果仍然可从顶栏「导出」重看（不重复弹）。
//
// ★ 四种结局用同一个组件不同 tone 表达，不各写一套：
//   已取消 / 有失败 / 命中续传 / 全部完成。
package com.photomgm.desktop.ui.page

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.api.ExportResult
import com.photomgm.desktop.ui.app.Actions
import com.photomgm.desktop.ui.component.ButtonKind
import com.photomgm.desktop.ui.component.PmButton
import com.photomgm.desktop.ui.component.PmIconButton
import com.photomgm.desktop.ui.component.Tone
import com.photomgm.desktop.ui.component.bg
import com.photomgm.desktop.ui.component.fg
import com.photomgm.desktop.ui.theme.PmColor
import com.photomgm.desktop.ui.theme.PmRadius
import com.photomgm.desktop.ui.theme.PmSpace
import com.photomgm.desktop.ui.theme.PmType

@Composable
fun ResultBanner(result: ExportResult, onDismiss: () -> Unit, actions: Actions) {
    val (tone, title, icon) = when {
        result.cancelled -> Triple(Tone.WARN, "导出已取消", Icons.Filled.StopCircle)
        result.failed > 0 -> Triple(Tone.DANGER, "导出完成，但 ${result.failed} 张失败", Icons.Filled.ErrorOutline)
        result.resumed -> Triple(Tone.INFO, "上次已完整导出，本次直接沿用", Icons.Filled.CheckCircle)
        else -> Triple(Tone.OK, "导出完成", Icons.Filled.CheckCircle)
    }
    val seconds = (result.durationMs / 1000.0).let { if (it < 1) "<1 秒" else "%.1f 秒".format(it) }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = PmSpace.xl, vertical = PmSpace.m)
            .clip(RoundedCornerShape(PmRadius.panel))
            .background(tone.bg())
            .padding(start = PmSpace.l, end = PmSpace.m, top = PmSpace.m, bottom = PmSpace.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tone.fg(), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(PmSpace.m))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = PmType.bodyStrong, color = PmColor.ink)
                Spacer(Modifier.width(PmSpace.m))
                Text(
                    buildString {
                        append("共 ${result.totalTargets} 张 · 复制 ${result.copied}")
                        if (result.skipped > 0) append(" · 跳过 ${result.skipped}")
                        if (result.failed > 0) append(" · 失败 ${result.failed}")
                        append(" · $seconds")
                    },
                    style = PmType.numeric,
                    color = PmColor.ink2,
                )
            }
            Text(
                buildString {
                    append(result.exportDir.toString())
                    if (result.ledgerFile != null) append("  含台账 ledger.xlsx")
                },
                style = PmType.caption,
                color = PmColor.ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (result.warnings.isNotEmpty()) {
                Text(
                    "· ${result.warnings.first()}",
                    style = PmType.caption,
                    color = PmColor.warn,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (result.failures.isNotEmpty()) {
                Text(
                    "· ${result.failures.first().relativePath}：${result.failures.first().reason}",
                    style = PmType.caption,
                    color = PmColor.danger,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(PmSpace.m))
        Row(horizontalArrangement = Arrangement.spacedBy(PmSpace.xs), verticalAlignment = Alignment.CenterVertically) {
            PmButton("打开目录", actions.revealExportDir, kind = ButtonKind.SECONDARY, icon = Icons.Filled.FolderOpen)
            if (result.ledgerFile != null) {
                PmButton("打开台账", actions.openLedger, kind = ButtonKind.SECONDARY, icon = Icons.Filled.TableChart)
            }
            PmIconButton(Icons.Filled.Close, "收起结果", onDismiss)
        }
    }
}
