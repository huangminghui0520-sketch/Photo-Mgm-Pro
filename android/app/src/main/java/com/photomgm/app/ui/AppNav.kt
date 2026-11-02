// app/ui/AppNav.kt —— 单页导航 + 2 步可视化进度条（点击跳转分区）
// ★ 2026-10-07 UI 重建：功能与原实现等价（AppNav 直渲染 UnifiedScreen；ProgressStrip 可点击跳转）。
package com.photomgm.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.photomgm.app.AppViewModel
import com.photomgm.app.UiState

@Composable
fun AppNav(vm: AppViewModel, modifier: Modifier = Modifier) {
    Column(modifier) {
        UnifiedScreen(vm)
    }
}

// ──────────────────────────────────────────────────────────────────
// 2 步可视化进度条：图标 + 对勾 + 连接线 · 点击步骤跳转对应分区
// 索引约定：0=日志输入区，1=事件列表（与 UnifiedScreen 的 LazyColumn 排列一致）
// ──────────────────────────────────────────────────────────────────
@Composable
fun ProgressStrip(state: UiState, onStepClick: (Int) -> Unit) {
    val logN = state.parsed?.events?.size ?: 0
    val photoM = state.photos.size
    val step1Done = state.parsed?.events?.isNotEmpty() == true
    // ★ 完成条件：导出完成（而非台账已生成——台账在分类后即生成，会让用户误以为已导出）
    val step2Done = state.exportMessage != null

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly,
            ) {
                StepChip(
                    idx = IDX_LOGS, label = "日志${logN}·照片${photoM}",
                    icon = Icons.Filled.Description, done = step1Done,
                    onClick = { onStepClick(IDX_LOGS) },
                )
                Connector(step1Done)
                StepChip(
                    idx = IDX_EXPORT, label = "导出",
                    icon = Icons.Filled.Upload, done = step2Done,
                    onClick = { onStepClick(IDX_EXPORT) },
                )
            }
            if (state.busy) {
                LinearProgressIndicator(
                    Modifier.fillMaxWidth().height(2.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.Transparent,
                )
                // ★ 2026-10-07 P2：流水线分阶段进度文案（解析中…/扫描照片中…/分类中…）
                state.progressStage?.let { stage ->
                    Text(
                        stage,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
                    )
                }
            } else {
                HorizontalDivider(color = Color.Transparent, thickness = 2.dp)
            }
        }
    }
}

@Composable
private fun StepChip(
    idx: Int,
    label: String,
    icon: ImageVector,
    done: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (done) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.secondaryContainer
    val fg = if (done) MaterialTheme.colorScheme.onPrimary
    else MaterialTheme.colorScheme.onSecondaryContainer
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
    ) {
        Box(
            Modifier
                .size(30.dp)
                .background(bg, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (done) Icons.Filled.Check else icon,
                contentDescription = null,
                modifier = Modifier.size(17.dp),
                tint = fg,
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = if (done) FontWeight.SemiBold else FontWeight.Medium,
                fontSize = 11.sp,
            ),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun Connector(highlight: Boolean) {
    Box(
        Modifier
            .height(2.dp)
            .width(22.dp)
            .background(
                if (highlight) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(1.dp),
            )
    )
}
