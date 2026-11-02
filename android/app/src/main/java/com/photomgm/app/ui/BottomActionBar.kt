// app/ui/BottomActionBar.kt —— 底部常驻操作条（导出）
// 始终可见 · 常显台账行数/待办数 · 未就绪时按钮说明缺什么、点击带去补
// ★ 2026-10-07 UI 重建：功能等价。
package com.photomgm.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 导出就绪度。
 * 每一档都带"缺什么"和"点了去哪补"。
 */
internal sealed interface ExportGate {
    /** 源目录未设置：根本无法分类 */
    data object NoSourceDir : ExportGate
    /** 日志未解析 */
    data object NoLogs : ExportGate
    /** 已解析但照片/分类还没就绪（例如当日无照片） */
    data object NoPhotos : ExportGate
    /** 可以导出；unmatched 为未匹配照片数（会提示将不入台账） */
    data class Ready(val unmatched: Int) : ExportGate
}

/**
 * 底部常驻操作条。
 *
 * @param gate 导出就绪度
 * @param ledgerRows 台账行数；null = 尚未生成
 * @param pendingEvents 待复查事件数
 * @param unmatchedPhotos 未匹配照片数
 * @param progress 导出进度 0..1；非空 = 正在导出
 * @param doneMessage 导出结果信息；非空 = 本次已结束（成功或失败由 [exportOk] 区分）
 * @param exportOk 本次导出是否成功；null = 尚未导出过
 * @param onExport 点「导出」时执行（就绪时）
 * @param onFix 点提示区时执行——跳到能解决问题的位置（设置 / 日志）
 */
@Composable
internal fun BottomActionBar(
    gate: ExportGate,
    ledgerRows: Int?,
    pendingEvents: Int,
    unmatchedPhotos: Int,
    progress: Float?,
    doneMessage: String?,
    exportOk: Boolean?,
    onExport: () -> Unit,
    onFix: () -> Unit,
    onOpenExportResult: (() -> Unit)? = null,
) {
    val ready = gate is ExportGate.Ready

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 8.dp,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                )
            }

            // ★ 2026-10-31 审查修复：doneMessage 在成功与失败时都非 null，必须按 exportOk 区分——
            //   否则导出失败（输出目录不可写、打包异常）也显示绿色「导出完成 ✓」，
            //   用户会据此认为产物已生成并直接外发台账。
            val (statusText, statusColor) = when {
                exportOk == false -> "导出未完成 ⚠" to MaterialTheme.colorScheme.error
                doneMessage != null -> "导出完成 ✓" to MaterialTheme.colorScheme.primary
                ready -> buildString {
                    append(ledgerRows?.let { "台账 $it 行" } ?: "台账待生成")
                    if (pendingEvents > 0) append(" · 待复查 $pendingEvents")
                    if (unmatchedPhotos > 0) append(" · 未匹配 $unmatchedPhotos（不入台账）")
                } to MaterialTheme.colorScheme.onSurfaceVariant
                else -> gateHint(gate) to MaterialTheme.colorScheme.error
            }

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!ready && exportOk != true) {
                    Icon(
                        Icons.Filled.Warning, null,
                        Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor,
                    maxLines = 2,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))

                if (ready) {
                    Button(
                        onClick = onExport,
                        // ★ 2026-10-31：导出进行中禁用入口（VM 侧已有同步门禁，此处为可见的界面反馈）
                        enabled = progress == null,
                        modifier = Modifier.heightIn(min = 44.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.tertiary,
                            contentColor = MaterialTheme.colorScheme.onTertiary,
                        ),
                    ) {
                        Icon(Icons.Filled.Upload, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("导出", fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    Button(
                        onClick = onFix,
                        modifier = Modifier.heightIn(min = 44.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ),
                    ) {
                        Text(
                            gateActionLabel(gate),
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                    }
                }
            }

            if (doneMessage != null && onOpenExportResult != null) {
                Text(
                    "打开导出的台账 ›",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.End)
                        .clickableNoRipple(onOpenExportResult),
                )
            }
        }
    }
}

/** 状态行文案：说明当前缺什么。 */
private fun gateHint(gate: ExportGate): String = when (gate) {
    ExportGate.NoSourceDir -> "未设置照片目录，无法读取照片"
    ExportGate.NoLogs -> "尚未解析巡查日志"
    ExportGate.NoPhotos -> "所选日期下没有可用照片"
    is ExportGate.Ready -> ""
}

/** 按钮文案：点了会去做什么。 */
private fun gateActionLabel(gate: ExportGate): String = when (gate) {
    ExportGate.NoSourceDir -> "先添加照片目录 ›"
    ExportGate.NoLogs -> "先粘贴并解析日志 ›"
    ExportGate.NoPhotos -> "检查日期或照片目录 ›"
    is ExportGate.Ready -> "导出"
}

/** 无涟漪点击（状态行右侧的「打开 ›」用）。 */
@Composable
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return this.then(
        Modifier.clickable(
            interactionSource = interaction,
            indication = null,
            onClick = onClick,
        )
    )
}
