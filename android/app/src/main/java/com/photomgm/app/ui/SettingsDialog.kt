// app/ui/SettingsDialog.kt —— 设置弹窗：①导入照片目录 ②输出目录 ③导出文件夹命名
// ★ 2026-10-07 UI 重建：容器由 Box+Surface 全屏覆盖层改为 Dialog 平台窗口 + Surface
//   （粘贴 AlertDialog 是真机唯一验证正常的弹窗机制，统一沿用）。
package com.photomgm.app.ui

import android.net.Uri
import algorithm.DedupKeepMode
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.photomgm.app.AppViewModel

/**
 * 设置弹窗。
 * 三组配置：
 *  1. 导入照片目录（源目录）—— SAF 系统选择器，可增删
 *  2. 输出目录—— SAF 系统选择器，导出必填
 *  3. 导出文件夹命名—— 日期+巡查日志内容 / 事件类型
 * 改动即时生效并持久化（日期/目录变更触发 ViewModel 自动重算）。
 */
@Composable
internal fun SettingsDialog(
    vm: AppViewModel,
    onDismiss: () -> Unit,
) {
    val state by vm.state.collectAsState()

    // SAF 选择器：完成后保持弹窗打开，方便连续配置
    val pickSource = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { vm.pickSourceDir(it) } }

    val pickOutput = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { vm.pickOutputDir(it) } }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 460.dp)
                .padding(horizontal = 16.dp, vertical = 24.dp)
                .heightIn(max = 720.dp)
                .imePadding(),
        ) {
            Column(Modifier.fillMaxWidth()) {
                // ── 标题栏 ──
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "设置",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, "关闭设置")
                    }
                }

                // ── 可滚动内容 ──
                Column(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    // ① 导入照片目录
                    SettingGroup(icon = Icons.Filled.Folder, title = "导入照片目录") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (state.sourceDirs.isEmpty()) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    shape = MaterialTheme.shapes.small,
                                ) {
                                    Text(
                                        "尚未添加。请选择照片所在文件夹（可添加多个）",
                                        Modifier.padding(10.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            } else {
                                state.sourceDirs.forEach { dir ->
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 48.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.secondaryContainer,
                                            shape = RoundedCornerShape(8.dp),
                                        ) {
                                            Text(
                                                dir,
                                                Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            )
                                        }
                                        Spacer(Modifier.weight(1f))
                                        IconButton(
                                            onClick = { vm.removeSourceDir(dir) },
                                            colors = IconButtonDefaults.iconButtonColors(
                                                contentColor = MaterialTheme.colorScheme.error,
                                            ),
                                        ) {
                                            Icon(Icons.Filled.Close, "删除 $dir")
                                        }
                                    }
                                }
                            }

                            OutlinedButton(
                                onClick = { pickSource.launch(null) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                                Text(" 添加照片目录")
                            }
                            Text(
                                "常用：DCIM/Camera · DCIM · Pictures",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    // ② 输出目录
                    SettingGroup(icon = Icons.Filled.FolderZip, title = "输出目录（导出必填）") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { pickOutput.launch(null) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Filled.FolderZip, null, Modifier.size(18.dp))
                                Text(if (state.outputDir.isNullOrBlank()) " 选择输出目录" else " 更换输出目录")
                            }

                            val d = state.outputDir
                            if (d.isNullOrBlank()) {
                                Text(
                                    "未设置输出目录时无法导出",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            } else {
                                Surface(
                                    color = MaterialTheme.colorScheme.tertiaryContainer,
                                    shape = RoundedCornerShape(8.dp),
                                ) {
                                    Text(
                                        "已选：${safeDirLabel(d)}",
                                        Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    )
                                }
                            }
                        }
                    }

                    // ③ 导出文件夹命名
                    SettingGroup(icon = Icons.Filled.Folder, title = "导出文件夹命名") {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            NamingOption(
                                selected = state.namingTemplate == 1,
                                onClick = { vm.setNamingTemplate(1) },
                                title = "日期 + 巡查日志内容",
                                example = "例：0908_巡查至北行K123+000M处发现护栏损坏",
                            )
                            NamingOption(
                                selected = state.namingTemplate == 0,
                                onClick = { vm.setNamingTemplate(0) },
                                title = "事件类型",
                                example = "例：交通事故处置",
                            )
                        }
                    }

                    // ④ 重复照片（★ 2026-10-24 B1：三态动态分类，默认仅保留带水印）
                    SettingGroup(icon = Icons.Filled.FolderZip, title = "重复照片") {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            NamingOption(
                                selected = state.dedupKeepMode == DedupKeepMode.KEEP_WATERMARKED,
                                onClick = { vm.setDedupKeepMode(DedupKeepMode.KEEP_WATERMARKED) },
                                title = "仅保留带水印（默认）",
                                example = "水印相机双份合并，保留带水印取证版",
                            )
                            NamingOption(
                                selected = state.dedupKeepMode == DedupKeepMode.KEEP_PLAIN,
                                onClick = { vm.setDedupKeepMode(DedupKeepMode.KEEP_PLAIN) },
                                title = "仅保留不带水印",
                                example = "双份合并保留原始版（会丢弃带水印取证版）",
                            )
                            NamingOption(
                                selected = state.dedupKeepMode == DedupKeepMode.KEEP_BOTH,
                                onClick = { vm.setDedupKeepMode(DedupKeepMode.KEEP_BOTH) },
                                title = "两者都保留",
                                example = "不去重，全部照片保留",
                            )
                            Text(
                                "单份照片（仅带水印或仅不带水印）不受影响、一律保留；" +
                                        "未匹配照片不参与去重、全量保留。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    // ⑤ 缓存管理（防线二：手动刷新照片缓存）
                    SettingGroup(icon = Icons.Filled.Refresh, title = "缓存管理") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    vm.refreshPhotoCache()
                                    onDismiss()
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp))
                                Text(" 刷新照片缓存")
                            }
                            Text(
                                "删除/移动了源目录照片后点此强制重新扫描（正常场景系统会自动校验）。" +
                                        "事件列表保留，重新解析即重新分类。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Spacer(Modifier.size(4.dp))
                }

                // ── 底部 ──
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Button(onClick = onDismiss) { Text("完成") }
                }
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────
// 分组卡片
// ──────────────────────────────────────────────────────────────────
@Composable
private fun SettingGroup(
    icon: ImageVector,
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Icon(
                        icon, null,
                        Modifier.padding(8.dp).size(18.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Text(
                    "  $title",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                )
            }
            content()
        }
    }
}

// ──────────────────────────────────────────────────────────────────
// 命名选项：整行 48dp 最小触控 + 示例预览
// ──────────────────────────────────────────────────────────────────
@Composable
private fun NamingOption(
    selected: Boolean,
    onClick: () -> Unit,
    title: String,
    example: String,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.padding(start = 4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                example,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** SAF tree URI → 可读目录名；取不到时给友好占位，绝不把裸 URI 显示给用户。 */
private fun safeDirLabel(uri: String): String = runCatching {
    val seg = Uri.parse(uri).lastPathSegment ?: return@runCatching FALLBACK
    val dec = java.net.URLDecoder.decode(seg, "UTF-8")
    dec.substringAfter(':').trim('/').ifBlank { FALLBACK }
}.getOrElse { FALLBACK }

private const val FALLBACK = "已选文件夹（点击「更换输出目录」可改）"
