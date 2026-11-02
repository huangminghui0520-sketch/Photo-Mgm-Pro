// app/ui/LogInputSection.kt —— 巡查日志输入区（顶部栏下方，不随事件列表滚动）
// 结构：可编辑文本框 + [粘贴导入] [解析日志] [清空]
// ★ 2026-10-07 UI 重建：功能等价；弹窗沿用 AlertDialog（真机上唯一验证正常的弹窗机制）。
package com.photomgm.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 日志输入区。
 *
 * @param logsText 当前文本（来自 UiState.logsText）
 * @param onTextChange 文本变化
 * @param onParse 解析日志（解析 → 粗筛 → 精读 → 分类）
 * @param onClear 清空（同时清解析/分类结果）
 * @param busy 流水线进行中，禁用输入与按钮
 * @param collapsed 收起态：只显示一行摘要，点击可展开
 * @param onToggleCollapse 切换收起/展开
 * @param parseSummary 收起时显示的摘要（如「已解析 12 条事件」）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LogInputSection(
    logsText: String,
    onTextChange: (String) -> Unit,
    onParse: () -> Unit,
    onClear: () -> Unit,
    busy: Boolean,
    collapsed: Boolean = false,
    onToggleCollapse: () -> Unit = {},
    parseSummary: String? = null,
) {
    var pasteDialog by rememberSaveable { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (collapsed) {
            // ── 收起态：一行摘要，点击展开重编辑 ──
            Surface(
                onClick = onToggleCollapse,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Description, null,
                        Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        parseSummary ?: "巡查日志",
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "编辑 ›",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Icon(
                        Icons.Filled.ExpandMore, null,
                        Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            // ── 展开态：可编辑文本框 + 粘贴/解析 ──
            OutlinedTextField(
                value = logsText,
                onValueChange = onTextChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .focusRequester(focus),
                enabled = !busy,
                label = { Text("巡查日志（每行一条，可编辑）") },
                shape = MaterialTheme.shapes.medium,
                trailingIcon = if (logsText.isNotEmpty() && !busy) {
                    {
                        IconButton(
                            onClick = { onClear(); focus.requestFocus() },
                            modifier = Modifier.size(48.dp),
                            colors = IconButtonDefaults.iconButtonColors(
                                contentColor = MaterialTheme.colorScheme.outline,
                            ),
                        ) {
                            Icon(Icons.Filled.Clear, "清空日志", Modifier.size(20.dp))
                        }
                    }
                } else null,
            )

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { pasteDialog = true },
                    enabled = !busy,
                    modifier = Modifier.heightIn(min = 48.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp),
                ) {
                    Icon(Icons.Filled.ContentPaste, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("粘贴导入")
                }
                Spacer(Modifier.weight(1f))
                if (parseSummary != null) {
                    TextButton(onClick = onToggleCollapse) { Text("收起") }
                }
                Button(
                    onClick = onParse,
                    enabled = !busy && logsText.isNotBlank(),
                    modifier = Modifier.heightIn(min = 48.dp),
                    contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                ) {
                    Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("解析日志")
                }
            }
        }
    }

    if (pasteDialog) {
        PasteLogDialog(
            onConfirm = { text ->
                if (text.isNotBlank()) {
                    val merged = if (logsText.isBlank()) text else (logsText + "\n" + text).trim()
                    onTextChange(merged)
                    pasteDialog = false
                    // 粘贴即解析：用户意图明确，不必再点一次
                    onParse()
                } else {
                    pasteDialog = false
                }
            },
            onDismiss = { pasteDialog = false },
        )
    }
}

// ──────────────────────────────────────────────────────────────────
// 粘贴日志对话框（AlertDialog——真机验证正常的弹窗机制）
// ──────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PasteLogDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("粘贴日志文本") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .focusRequester(focus),
                minLines = 8,
                label = { Text("粘贴内容（多行，可修改）") },
                shape = MaterialTheme.shapes.medium,
                trailingIcon = if (text.isNotEmpty()) {
                    {
                        IconButton(
                            onClick = { text = ""; focus.requestFocus() },
                            modifier = Modifier.size(48.dp),
                            colors = IconButtonDefaults.iconButtonColors(
                                contentColor = MaterialTheme.colorScheme.outline,
                            ),
                        ) {
                            Icon(Icons.Filled.Clear, "清除", Modifier.size(20.dp))
                        }
                    }
                } else null,
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) {
                Text("追加并解析")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}
