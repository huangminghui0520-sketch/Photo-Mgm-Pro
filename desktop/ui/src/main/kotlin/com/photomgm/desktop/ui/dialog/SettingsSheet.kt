// ui/dialog/SettingsSheet.kt —— 设置弹窗（页面上唯一会弹窗的"非照片"操作）
//
// ★ 里面只放三类东西：
//     ① 分析输入：巡查日期、源目录 —— 改了就要求重新分析；
//     ② 导出输出：导出目录、是否生成台账、续传校验强度 —— 只在导出时用到；
//     ③ 一个明确的回退动作：恢复最初分类（core 的 resetManualAdjustments）。
//   去重口径**不在这里**：它是视图参数（core 同步重算、不重新扫描），放在列表上方工具条上
//   才符合"边看边切"的用法；放进设置会让人以为改完要重新分析。
package com.photomgm.desktop.ui.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.photomgm.desktop.core.api.AppState
import com.photomgm.desktop.core.api.ResumeVerifyMode
import com.photomgm.desktop.ui.app.Actions
import com.photomgm.desktop.ui.component.ButtonKind
import com.photomgm.desktop.ui.component.Field
import com.photomgm.desktop.ui.component.Hairline
import com.photomgm.desktop.ui.component.PmButton
import com.photomgm.desktop.ui.component.PmCheckbox
import com.photomgm.desktop.ui.component.PmDateField
import com.photomgm.desktop.ui.component.PmIconButton
import com.photomgm.desktop.ui.component.PmScrollArea
import com.photomgm.desktop.ui.component.Segmented
import com.photomgm.desktop.ui.component.shortPath
import com.photomgm.desktop.ui.model.UiState
import com.photomgm.desktop.ui.model.dialogHeight
import com.photomgm.desktop.ui.theme.PmColor
import com.photomgm.desktop.ui.theme.PmRadius
import com.photomgm.desktop.ui.theme.PmSpace
import com.photomgm.desktop.ui.theme.PmType

@Composable
fun SettingsSheet(
    state: AppState,
    ui: UiState,
    onUi: (UiState) -> Unit,
    actions: Actions,
    windowWidth: Int,
    windowHeight: Int,
) {
    val width = minOf(560, windowWidth - 48).coerceAtLeast(420)
    val maxH = dialogHeight(windowHeight)

    SheetHost(onDismiss = { onUi(ui.copy(sheet = null)) }) {
        Column(
            Modifier
                .width(width.dp)
                .height(maxH.dp)
                .clip(RoundedCornerShape(PmRadius.panel))
                .background(PmColor.surface),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = PmSpace.xl, end = PmSpace.m, top = PmSpace.l, bottom = PmSpace.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("设置", style = PmType.display, color = PmColor.ink)
                Spacer(Modifier.weight(1f))
                PmIconButton(Icons.Filled.Close, "关闭", { onUi(ui.copy(sheet = null)) })
            }
            Hairline()

            PmScrollArea(
                Modifier.weight(1f, fill = false),
                contentPadding = PaddingValues(start = PmSpace.xl, end = PmSpace.xl, top = PmSpace.l, bottom = PmSpace.m),
            ) {
                Field(
                    label = "巡查日期",
                    hint = "只扫文件名包含这天的照片；日志里只有月日时，按这天的年份补全。",
                ) {
                    PmDateField(state.settings.patrolDate, actions.setPatrolDate)
                }

                Spacer(Modifier.height(PmSpace.m))

                Field(
                    label = "照片源目录（${state.settings.sourceDirs.size}）",
                    hint = if (state.settings.sourceDirs.isEmpty()) "至少选一个装着当天照片的文件夹，否则分析只能解析日志。" else null,
                    trailing = {
                        PmButton("添加", actions.addSourceDirs, kind = ButtonKind.SECONDARY, icon = Icons.Filled.Add)
                    },
                ) {
                    if (state.settings.sourceDirs.isEmpty()) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(PmRadius.control))
                                .background(PmColor.surface2)
                                .padding(PmSpace.m),
                        ) {
                            Text("尚未选择", style = PmType.caption, color = PmColor.ink3)
                        }
                    } else {
                        state.settings.sourceDirs.forEach { dir ->
                            PathRow(
                                path = dir,
                                onOpen = { actions.revealPath(dir) },
                                onRemove = { actions.removeSourceDir(dir) },
                            )
                            Spacer(Modifier.height(PmSpace.xs))
                        }
                    }
                }

                Spacer(Modifier.height(PmSpace.m))

                Field(
                    label = "导出目录",
                    hint = "每次导出会在其下新建 PhotoMgm_export_日期_时刻/ ，不改动源文件。",
                    trailing = {
                        if (state.settings.outputDir != null) {
                            PmButton(
                                "更换",
                                actions.chooseOutputDir,
                                kind = ButtonKind.SECONDARY,
                                icon = Icons.Filled.FolderOpen,
                            )
                        }
                    },
                ) {
                    val out = state.settings.outputDir
                    if (out == null) {
                        PmButton("选择导出目录", actions.chooseOutputDir, kind = ButtonKind.PRIMARY, icon = Icons.Filled.FolderOpen)
                    } else {
                        PathRow(path = out, onOpen = { actions.revealPath(out) }, onRemove = null)
                    }
                }

                Spacer(Modifier.height(PmSpace.m))

                Field(label = "导出内容") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PmCheckbox(
                            checked = ui.exportOptions.includeLedger,
                            onCheckedChange = { on -> onUi(ui.copy(exportOptions = ui.exportOptions.copy(includeLedger = on))) },
                            label = "生成台账 ledger.xlsx",
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(PmSpace.l))
                        Text("续传校验", style = PmType.label, color = PmColor.ink2)
                        Spacer(Modifier.width(PmSpace.s))
                        Segmented(
                            options = listOf("快速" to "", "强校验" to ""),
                            selectedIndex = if (ui.exportOptions.verifyMode == ResumeVerifyMode.SIZE_AND_MTIME) 0 else 1,
                            onSelect = { index ->
                                onUi(
                                    ui.copy(
                                        exportOptions = ui.exportOptions.copy(
                                            verifyMode = if (index == 0) ResumeVerifyMode.SIZE_AND_MTIME else ResumeVerifyMode.SIZE_AND_SHA256,
                                        ),
                                    ),
                                )
                            },
                        )
                    }
                    Spacer(Modifier.height(PmSpace.xs))
                    Text(
                        "关掉只复制照片。快速校验比大小与修改时间；强校验逐张算 SHA-256（涉密留证才需要）。",
                        style = PmType.caption,
                        color = PmColor.ink3,
                    )
                }

            }

            Hairline()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = PmSpace.xl, vertical = PmSpace.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PmButton(
                    "恢复最初分类",
                    actions.resetAdjustments,
                    kind = ButtonKind.GHOST,
                    enabled = state.events.isNotEmpty(),
                )
                Spacer(Modifier.weight(1f))
                PmButton("完成", { onUi(ui.copy(sheet = null)) }, kind = ButtonKind.PRIMARY)
            }
        }
    }
}

@Composable
private fun PathRow(path: String, onOpen: () -> Unit, onRemove: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PmRadius.control))
            .background(PmColor.surface2)
            .padding(start = PmSpace.m, end = PmSpace.xs, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Icon(
            Icons.Filled.FolderOpen,
            contentDescription = null,
            tint = PmColor.ink3,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(PmSpace.s))
        Text(
            shortPath(path, keep = 46),
            style = PmType.caption,
            color = PmColor.ink2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        PmIconButton(Icons.Filled.OpenInNew, "在资源管理器中打开", onOpen, size = 24.dp)
        if (onRemove != null) {
            PmIconButton(Icons.Filled.Close, "移除", onRemove, size = 24.dp)
        }
    }
}
