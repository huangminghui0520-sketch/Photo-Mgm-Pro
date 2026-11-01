// ui/stage/ExportStage.kt —— 步骤 3：导出（前）
//
// ★ 这一页的存在理由：core 的导出是"快照 + 不可逆产物"（建目录、拷文件、写台账、写 manifest）。
//   人在按下去之前必须能看见**将要写出什么**——台账每一行、每行的两张照片、文件夹名。
//   所以主内容是台账预览表，而不是一句"准备导出"。
//
// ★ 阻断项（exportBlockers）直接来自 AppState，UI 不自己判断能不能导出：
//   `canExport` 的口径（READY + 输出目录非空 + 有事件）只有核心说得准，
//   UI 若自己再算一遍，两边迟早不一致。
package com.photomgm.desktop.ui.stage

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.photomgm.desktop.core.api.AppState
import com.photomgm.desktop.core.api.LedgerRowInfo
import com.photomgm.desktop.core.api.ResumeVerifyMode
import com.photomgm.desktop.ui.app.Actions
import com.photomgm.desktop.ui.component.ButtonKind
import com.photomgm.desktop.ui.component.Hairline
import com.photomgm.desktop.ui.component.InlineNotice
import com.photomgm.desktop.ui.component.Panel
import com.photomgm.desktop.ui.component.PmButton
import com.photomgm.desktop.ui.component.ReadonlyRow
import com.photomgm.desktop.ui.component.Segmented
import com.photomgm.desktop.ui.component.Tone
import com.photomgm.desktop.ui.model.UiState
import com.photomgm.desktop.ui.model.WorkQueue
import com.photomgm.desktop.ui.model.exportLayout
import com.photomgm.desktop.ui.theme.PmColor
import com.photomgm.desktop.ui.theme.PmRadius
import com.photomgm.desktop.ui.theme.PmSpace
import com.photomgm.desktop.ui.theme.PmType

@Composable
fun ExportStage(
    state: AppState,
    ui: UiState,
    contentWidth: Int,
    actions: Actions,
    onUi: (UiState) -> Unit,
) {
    val layout = exportLayout(contentWidth)
    val blockers = WorkQueue.exportBlockers(state)
    var optionsDialog by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(horizontal = PmSpace.xl, vertical = PmSpace.l)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("③", style = PmType.display, color = PmColor.accent)
            Spacer(Modifier.width(PmSpace.m))
            Column(Modifier.weight(1f)) {
                Text("导出", style = PmType.display, color = PmColor.ink)
                Text(
                    "核对台账的每一行，确认照片与文件夹名，然后落盘。",
                    style = PmType.caption,
                    color = PmColor.ink3,
                )
            }
            if (layout.sideW == 0) {
                PmButton("导出选项", { optionsDialog = true }, kind = ButtonKind.SECONDARY, icon = Icons.Filled.Tune)
                Spacer(Modifier.width(PmSpace.s))
            }
            PmButton(
                "返回复核",
                { actions.goStage(com.photomgm.desktop.ui.model.Stage.REVIEW) },
                kind = ButtonKind.GHOST,
            )
        }

        Spacer(Modifier.height(PmSpace.l))

        Row(Modifier.fillMaxWidth().weight(1f)) {
            LedgerTable(
                state = state,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                // ★ 920 而不是 720：970 dp 的窗口下表宽 905，若按 720 判"够宽"，
                //   7 列会一起挤进来，描述列只剩 250、照片列只剩文件名前缀——反而更难核对。
                compact = layout.tableW < 920,
            )
            if (layout.sideW > 0) {
                Spacer(Modifier.width(PmSpace.l))
                Box(Modifier.width(layout.sideW.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
                    OptionsPanel(state, ui, actions, onUi, blockers)
                }
            }
        }

        Spacer(Modifier.height(PmSpace.l))
        ExportFooter(state, blockers, actions)
    }

    if (optionsDialog) {
        Dialog(onDismissRequest = { optionsDialog = false }) {
            Box(
                Modifier
                    .width(460.dp)
                    .clip(RoundedCornerShape(PmRadius.panel))
                    .background(PmColor.canvas)
                    .padding(PmSpace.xl),
            ) {
                OptionsPanel(state, ui, actions, onUi, blockers)
            }
        }
    }
}

@Composable
private fun OptionsPanel(
    state: AppState,
    ui: UiState,
    actions: Actions,
    onUi: (UiState) -> Unit,
    blockers: List<String>,
) {
    Panel(title = "导出选项", subtitle = "改动只影响本次导出") {
        CheckRow(
            checked = ui.exportOptions.includeLedger,
            label = "生成台账 ledger.xlsx",
            hint = "台账含照片缩略图，生成需要几秒；排障时可关掉只拷照片",
            onToggle = {
                onUi(ui.copy(exportOptions = ui.exportOptions.copy(includeLedger = !ui.exportOptions.includeLedger)))
            },
        )

        Spacer(Modifier.height(PmSpace.l))

        Text("续传校验", style = PmType.label, color = PmColor.ink2)
        Spacer(Modifier.height(PmSpace.xs))
        Segmented(
            options = listOf("快速" to "SIZE_AND_MTIME", "强校验" to "SIZE_AND_SHA256"),
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
        Spacer(Modifier.height(PmSpace.xs))
        Text(
            if (ui.exportOptions.verifyMode == ResumeVerifyMode.SIZE_AND_MTIME) {
                "只看大小与修改时间，秒级完成。适合续传上次中断的导出。"
            } else {
                "逐张重算 SHA-256，1000 张约 4 GB 读取；仅在需要法证级留证时使用。"
            },
            style = PmType.caption,
            color = PmColor.ink3,
        )

        Spacer(Modifier.height(PmSpace.l))

        Text("导出到", style = PmType.label, color = PmColor.ink2)
        Spacer(Modifier.height(PmSpace.xs))
        if (state.settings.outputDir == null) {
            ReadonlyRow("未设置导出目录", icon = Icons.Filled.FolderOpen)
        } else {
            ReadonlyRow(state.settings.outputDir!!, icon = Icons.Filled.FolderOpen)
        }
        Spacer(Modifier.height(PmSpace.xs))
        PmButton("更换目录", actions.chooseOutputDir, kind = ButtonKind.SECONDARY)
        Spacer(Modifier.height(PmSpace.xs))
        Text(
            "每次导出会在其下新建 PhotoMgm_export_日期_时间，不覆盖旧结果。",
            style = PmType.caption,
            color = PmColor.ink3,
        )

        if (blockers.isNotEmpty()) {
            Spacer(Modifier.height(PmSpace.l))
            blockers.forEachIndexed { index, text ->
                InlineNotice(Tone.WARN, text)
                if (index < blockers.size - 1) Spacer(Modifier.height(PmSpace.xs))
            }
        }
    }
}

@Composable
private fun CheckRow(checked: Boolean, label: String, hint: String, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().pmToggle(onToggle), verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .width(16.dp)
                .height(16.dp)
                .clip(RoundedCornerShape(PmRadius.control))
                .background(if (checked) PmColor.accent else PmColor.surface)
                .then(if (checked) Modifier else Modifier.border(1.dp, PmColor.lineStrong, RoundedCornerShape(PmRadius.control))),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = PmColor.surface, modifier = Modifier.width(11.dp).height(11.dp))
            }
        }
        Spacer(Modifier.width(PmSpace.s))
        Column {
            Text(label, style = PmType.body, color = PmColor.ink)
            Text(hint, style = PmType.caption, color = PmColor.ink3)
        }
    }
}

private fun Modifier.pmToggle(onClick: () -> Unit): Modifier =
    this.clickable(indication = null, interactionSource = null, onClick = onClick)

@Composable
private fun LedgerTable(state: AppState, modifier: Modifier, compact: Boolean) {
    Column(
        modifier
            .clip(RoundedCornerShape(PmRadius.panel))
            .background(PmColor.surface),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = PmSpace.l, vertical = PmSpace.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("台账预览", style = PmType.title, color = PmColor.ink)
            Spacer(Modifier.width(PmSpace.m))
            Text("${state.ledger.size} 行 = ${state.events.size} 个事件", style = PmType.caption, color = PmColor.ink3)
            Spacer(Modifier.weight(1f))
            Text(
                if (compact) "照片列 = 导出时写进子目录的文件名" else "照片列决定导出的文件名，文件夹名列决定子目录",
                style = PmType.caption,
                color = PmColor.ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Hairline()
        HeaderRow(compact)
        Hairline()

        if (state.ledger.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("台账还是空的：先回到输入页完成分析。", style = PmType.body, color = PmColor.ink3)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(state.ledger, key = { index, row -> "$index-${row.folderName}" }) { index, row ->
                    LedgerRowView(index, row, compact)
                    Hairline()
                }
            }
        }
    }
}

@Composable
private fun HeaderRow(compact: Boolean) {
    Row(
        Modifier.fillMaxWidth().background(PmColor.surface2).padding(horizontal = PmSpace.m, vertical = PmSpace.s),
    ) {
        Cell("时间", 104, header = true)
        if (!compact) Cell("结束", 44, header = true)
        Cell("地点", 110, header = true)
        Cell("描述", 0, header = true, weight = true)
        Cell("照片1", 148, header = true)
        Cell("照片2", 148, header = true)
        if (!compact) Cell("文件夹", 120, header = true)
    }
}

@Composable
private fun LedgerRowView(index: Int, row: LedgerRowInfo, compact: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = PmSpace.m, vertical = PmSpace.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cell(row.dateTime.ifBlank { "—" }, 104)
        // 结束列只留时分：核心给的是 "yyyy-MM-dd HH:mm"，同一天里日期是噪音，
        // 而 48 dp 宽放不下完整日期（实测会挤成 "2026-..."）。
        if (!compact) Cell(row.endTime.takeLast(5), 44)
        Cell(row.location, 110)
        Cell(row.description, 0, weight = true)
        // 照片列只显示文件名：文件夹前缀由"文件夹"列或本行语境交代，
        // 放进 132 dp 只会把真正的文件名挤掉（实测只显示到 "01_1101_到达上行K1..."）。
        val p1 = row.photo1Label?.substringAfterLast('/')
        val p2 = row.photo2Label?.substringAfterLast('/')
        Cell(p1 ?: "（无）", 148, muted = p1 == null)
        // 事件只有一张照片时，核心会让照片 1/2 指向同一张。写"同上"比抄两遍文件名清楚。
        Cell(if (p2 != null && p2 == p1) "同上" else p2 ?: "（无）", 148, muted = p2 == null)
        if (!compact) Cell(row.folderName, 120, muted = row.folderName.isBlank())
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Cell(
    text: String,
    width: Int,
    header: Boolean = false,
    weight: Boolean = false,
    muted: Boolean = false,
) {
    val style = if (header) PmType.micro else PmType.caption
    val color = when {
        header -> PmColor.ink3
        muted -> PmColor.ink3
        else -> PmColor.ink2
    }
    val base = if (weight) Modifier.weight(1f) else Modifier.width(width.dp)
    Box(base.padding(end = PmSpace.s)) {
        Text(text, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ExportFooter(state: AppState, blockers: List<String>, actions: Actions) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PmRadius.panel))
            .background(PmColor.surface)
            .padding(horizontal = PmSpace.l, vertical = PmSpace.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (blockers.isEmpty()) {
                    "将写出 ${state.photos.size - state.unmatchedPhotoIds.size} 张照片 + ${state.unmatchedPhotoIds.size} 张未分类，共 ${state.photos.size} 张"
                } else {
                    blockers.first()
                },
                style = PmType.caption,
                color = if (blockers.isEmpty()) PmColor.ink2 else PmColor.danger,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "可随时取消，已复制的文件保留，下次导出会自动续传。",
                style = PmType.caption,
                color = PmColor.ink3,
            )
        }
        Spacer(Modifier.width(PmSpace.l))
        PmButton(
            text = "开始导出",
            onClick = actions.export,
            kind = ButtonKind.PRIMARY,
            icon = Icons.Filled.FileDownload,
            enabled = state.canExport,
            height = 34.dp,
        )
    }
}
