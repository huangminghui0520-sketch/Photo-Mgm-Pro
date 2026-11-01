// ui/stage/ResultStage.kt —— 步骤 3：导出（后）结果
//
// ★ 结果的**计数口径**严格照 ExportResult 的约定：copied/skipped/failed/totalTargets 只算照片，
//   台账是否成功看 ledgerFile 是否为 null。界面绝不做"success = total"这种二次推算——
//   台账也成功时会多出 1 个文件，一旦把台账算进分母，就会出现「成功数 > 总数」。
//
// ★ 三种结果必须视觉可分：完成 / 取消（可续传）/ 部分失败。它们的下一步动作完全不同：
//   完成 → 打开台账；取消 → 再次导出（续传）；失败 → 看 export.log 定位。
package com.photomgm.desktop.ui.stage

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.photomgm.desktop.core.api.AppState
import com.photomgm.desktop.core.api.ExportResult
import com.photomgm.desktop.ui.app.Actions
import com.photomgm.desktop.ui.component.ButtonKind
import com.photomgm.desktop.ui.component.Panel
import com.photomgm.desktop.ui.component.PmButton
import com.photomgm.desktop.ui.component.ReadonlyRow
import com.photomgm.desktop.ui.component.StatCell
import com.photomgm.desktop.ui.component.Tag
import com.photomgm.desktop.ui.component.Tone
import com.photomgm.desktop.ui.component.fg
import com.photomgm.desktop.ui.model.Stage
import com.photomgm.desktop.ui.theme.PmColor
import com.photomgm.desktop.ui.theme.PmRadius
import com.photomgm.desktop.ui.theme.PmSpace
import com.photomgm.desktop.ui.theme.PmType

@Composable
fun ResultStage(state: AppState, contentWidth: Int, actions: Actions) {
    val result = state.lastExport ?: run {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("还没有导出记录。", style = PmType.body, color = PmColor.ink3)
        }
        return
    }

    val maxWidth = if (contentWidth > 1000) 920 else contentWidth - 48

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .width(maxWidth.dp)
                .padding(vertical = PmSpace.xl)
                .verticalScroll(rememberScrollState()),
        ) {
            ResultHeadline(result)

            Spacer(Modifier.height(PmSpace.l))
            Panel(title = null, contentPadding = 0) {
                Row(Modifier.fillMaxWidth().padding(vertical = PmSpace.m)) {
                    StatCell("${result.totalTargets}", "目标照片")
                    VDivider()
                    StatCell("${result.copied}", "已复制", if (result.copied > 0) Tone.OK else Tone.NEUTRAL)
                    VDivider()
                    StatCell("${result.skipped}", "跳过（已存在）")
                    VDivider()
                    StatCell("${result.failed}", "失败", if (result.failed > 0) Tone.DANGER else Tone.NEUTRAL)
                    VDivider()
                    StatCell(formatDuration(result.durationMs), "耗时")
                }
            }

            Spacer(Modifier.height(PmSpace.l))
            Panel(title = "产物") {
                ReadonlyRow(result.exportDir.toString(), icon = Icons.Filled.FolderOpen)
                Spacer(Modifier.height(PmSpace.xs))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("台账 ledger.xlsx", style = PmType.caption, color = PmColor.ink3)
                    Spacer(Modifier.width(PmSpace.s))
                    Tag(
                        if (result.ledgerFile != null) "已生成" else "未生成",
                        if (result.ledgerFile != null) Tone.OK else Tone.NEUTRAL,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "export.log · export-manifest.json",
                        style = PmType.caption,
                        color = PmColor.ink3,
                    )
                }
                Spacer(Modifier.height(PmSpace.m))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PmButton("打开导出目录", actions.revealExportDir, kind = ButtonKind.SECONDARY, icon = Icons.Filled.FolderOpen)
                    Spacer(Modifier.width(PmSpace.s))
                    PmButton(
                        "打开台账",
                        actions.openLedger,
                        kind = ButtonKind.SECONDARY,
                        icon = Icons.Filled.InsertDriveFile,
                        enabled = result.ledgerFile != null,
                    )
                    Spacer(Modifier.weight(1f))
                    PmButton(
                        "再导出一次",
                        { actions.goStage(Stage.EXPORT) },
                        kind = ButtonKind.GHOST,
                        icon = Icons.Filled.Refresh,
                    )
                }
            }

            if (result.failures.isNotEmpty()) {
                Spacer(Modifier.height(PmSpace.l))
                Panel(title = "失败明细（${result.failures.size}）", subtitle = "完整记录见导出目录里的 export.log") {
                    result.failures.take(30).forEach { f ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Text(f.relativePath, style = PmType.caption, color = PmColor.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Spacer(Modifier.width(PmSpace.m))
                            Text(f.reason, style = PmType.caption, color = PmColor.danger, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (result.failures.size > 30) {
                        Text("仅显示前 30 条", style = PmType.caption, color = PmColor.ink3)
                    }
                }
            }

            if (result.warnings.isNotEmpty()) {
                Spacer(Modifier.height(PmSpace.l))
                Panel(title = "提示（${result.warnings.size}）", subtitle = "不影响导出结果，但值得看一眼") {
                    result.warnings.take(12).forEach {
                        Text("· $it", style = PmType.caption, color = PmColor.warn, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            Spacer(Modifier.height(PmSpace.l))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PmButton("返回复核", { actions.goStage(Stage.REVIEW) }, kind = ButtonKind.GHOST)
                Spacer(Modifier.weight(1f))
                Text(
                    "导出的照片是从源目录复制出来的副本，源文件不会被移动或删除。",
                    style = PmType.caption,
                    color = PmColor.ink3,
                )
            }
        }
    }
}

@Composable
private fun ResultHeadline(result: ExportResult) {
    val head = when {
        result.cancelled -> Headline(
            Tone.WARN,
            "导出已取消",
            "已复制 ${result.copied} 张，产物保留在目标目录；再次导出会从断点继续，不会重复复制。",
            Icons.Filled.HourglassEmpty,
        )

        result.failed > 0 -> Headline(
            Tone.DANGER,
            "导出完成，但有 ${result.failed} 张失败",
            "照片复制过程中出错，详见失败明细与 export.log。已成功的文件保持可用。",
            Icons.Filled.WarningAmber,
        )

        result.resumed -> Headline(
            Tone.INFO,
            "上次的产物仍然完好",
            "校验了 ${result.skipped} 个文件，本次没有重复复制，直接沿用既有目录。",
            Icons.Filled.CheckCircle,
        )

        else -> Headline(
            Tone.OK,
            "导出完成",
            "共 ${result.copied} 张照片 + ${if (result.ledgerFile != null) "台账 1 份" else "未生成台账"}",
            Icons.Filled.CheckCircle,
        )
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PmRadius.panel))
            .background(PmColor.surface)
            .padding(PmSpace.xl),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(head.icon, contentDescription = null, tint = head.tone.fg(), modifier = Modifier.width(30.dp).height(30.dp))
        Spacer(Modifier.width(PmSpace.l))
        Column {
            Text(head.title, style = PmType.display, color = PmColor.ink)
            Spacer(Modifier.height(2.dp))
            Text(head.body, style = PmType.body, color = PmColor.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

private data class Headline(
    val tone: Tone,
    val title: String,
    val body: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)

@Composable
private fun VDivider() {
    Box(Modifier.width(1.dp).height(36.dp).background(PmColor.line))
}

private fun formatDuration(ms: Long): String = when {
    ms <= 0 -> "—"
    ms < 1000 -> "${ms}ms"
    ms < 60_000 -> "%.1fs".format(ms / 1000.0)
    else -> "${ms / 60_000}m${(ms % 60_000) / 1000}s"
}
