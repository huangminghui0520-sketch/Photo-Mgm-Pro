// ui/stage/ReviewStage.kt —— 步骤 2：复核
//
// ★ 这一页的结构完全由"数据形状 + 人的复核动作"决定，而不是由栏数决定：
//   数据形状：events(行) ──photoIds──> photos(扁平表)，另有一份 unmatched(无归属)。
//   人的动作：先看"哪些事件有问题"（列表）→ 点进去看它的照片 → 改归属或指定台账照片。
//   于是结构必然是「事件列表 → 该事件的照片 → 该事件的检查器」，而不是"源目录 / 照片 / 事件"
//   这种把输入、输出、复核三类东西平铺并排的旧结构。
//
// ★ 去重模式放在这一页的工具栏，而不是设置页：core 里 setDedupMode **同步重算视图**、
//   不重跑扫描（见 PhotoMgmCoreImpl.setDedupMode）。它是"当前结果的看法"，不是"配置"。
//   放在设置里会让用户以为要重新分析；放在这里，切完立刻能看到合并组数变化。
package com.photomgm.desktop.ui.stage

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import algorithm.DedupKeepMode
import com.photomgm.desktop.core.api.AppState
import com.photomgm.desktop.core.api.EventSnapshot
import com.photomgm.desktop.ui.app.Actions
import com.photomgm.desktop.ui.component.ButtonKind
import com.photomgm.desktop.ui.component.EventRow
import com.photomgm.desktop.ui.component.FilterChip
import com.photomgm.desktop.ui.component.Hairline
import com.photomgm.desktop.ui.component.PmButton
import com.photomgm.desktop.ui.component.PmIconButton
import com.photomgm.desktop.ui.component.PmTextField
import com.photomgm.desktop.ui.component.Segmented
import com.photomgm.desktop.ui.component.Tone
import com.photomgm.desktop.ui.component.UnmatchedRow
import com.photomgm.desktop.ui.model.ReviewFilter
import com.photomgm.desktop.ui.model.Selection
import com.photomgm.desktop.ui.model.UiState
import com.photomgm.desktop.ui.model.WorkQueue
import com.photomgm.desktop.ui.model.Stage
import com.photomgm.desktop.ui.model.reviewLayout
import com.photomgm.desktop.ui.theme.PmColor
import com.photomgm.desktop.ui.theme.PmRadius
import com.photomgm.desktop.ui.theme.PmSpace
import com.photomgm.desktop.ui.theme.PmType

@Composable
fun ReviewStage(
    state: AppState,
    ui: UiState,
    contentWidth: Int,
    actions: Actions,
    onUi: (UiState) -> Unit,
) {
    val layout = reviewLayout(contentWidth)
    val stats = WorkQueue.stats(state)
    val visible = WorkQueue.filter(state, ui.filter, ui.query)

    Column(Modifier.fillMaxSize()) {
        ReviewToolbar(
            state = state,
            ui = ui,
            stats = stats,
            wide = contentWidth >= 1100,
            actions = actions,
            onUi = onUi,
        )
        Hairline()

        Box(Modifier.fillMaxWidth().weight(1f)) {
            Row(Modifier.fillMaxSize()) {
                if (layout.listW > 0) {
                    EventList(
                        state = state,
                        ui = ui,
                        visible = visible,
                        width = layout.listW,
                        onUi = onUi,
                    )
                    Hairline(Modifier.width(1.dp).fillMaxHeight(), vertical = true)
                }
                PhotoArea(
                    state = state,
                    ui = ui,
                    gridWidth = layout.gridW,
                    gridCols = layout.gridCols,
                    listCollapsed = layout.listW == 0,
                    actions = actions,
                    onUi = onUi,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                if (layout.inspectorW > 0) {
                    Hairline(Modifier.width(1.dp).fillMaxHeight(), vertical = true)
                    Inspector(
                        state = state,
                        ui = ui,
                        width = layout.inspectorW,
                        actions = actions,
                        onUi = onUi,
                    )
                }
            }

            // 窄窗：检查器改为覆盖式抽屉，不挤占照片列数（900 dp 下挤一栏就只剩 1 列）。
            if (layout.inspectorAsDrawer && ui.inspectorOpen) {
                Box(Modifier.fillMaxSize().background(PmColor.ink.copy(alpha = 0.18f))) {
                    Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(320.dp)) {
                        Inspector(
                            state = state,
                            ui = ui,
                            width = 320,
                            actions = actions,
                            onUi = onUi,
                            overlay = true,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewToolbar(
    state: AppState,
    ui: UiState,
    stats: WorkQueue.Stats,
    wide: Boolean,
    actions: Actions,
    onUi: (UiState) -> Unit,
) {
    val filters: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip("全部", stats.events, ui.filter == ReviewFilter.ALL, { onUi(ui.copy(filter = ReviewFilter.ALL)) })
            Spacer(Modifier.width(PmSpace.xs))
            FilterChip("待确认", stats.needReview, ui.filter == ReviewFilter.NEEDS_REVIEW, { onUi(ui.copy(filter = ReviewFilter.NEEDS_REVIEW)) }, tone = Tone.WARN)
            Spacer(Modifier.width(PmSpace.xs))
            FilterChip("无照片", stats.noPhoto, ui.filter == ReviewFilter.NO_PHOTO, { onUi(ui.copy(filter = ReviewFilter.NO_PHOTO)) }, tone = Tone.WARN)
            Spacer(Modifier.width(PmSpace.xs))
            FilterChip("待定时间", stats.pending, ui.filter == ReviewFilter.PENDING, { onUi(ui.copy(filter = ReviewFilter.PENDING)) }, tone = Tone.DANGER)
            Spacer(Modifier.width(PmSpace.xs))
            FilterChip("未匹配", stats.unmatched, ui.filter == ReviewFilter.UNMATCHED, { onUi(ui.copy(filter = ReviewFilter.UNMATCHED)) }, tone = Tone.WARN)
        }
    }

    val tools: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Segmented(
                options = listOf(
                    "仅带水印" to "KEEP_WATERMARKED",
                    "仅无水印" to "KEEP_PLAIN",
                    "全部保留" to "KEEP_BOTH",
                ),
                selectedIndex = when (state.dedupMode) {
                    DedupKeepMode.KEEP_WATERMARKED -> 0
                    DedupKeepMode.KEEP_PLAIN -> 1
                    DedupKeepMode.KEEP_BOTH -> 2
                },
                onSelect = { index ->
                    actions.setDedupMode(
                        when (index) {
                            0 -> DedupKeepMode.KEEP_WATERMARKED
                            1 -> DedupKeepMode.KEEP_PLAIN
                            else -> DedupKeepMode.KEEP_BOTH
                        },
                    )
                },
            )
            Spacer(Modifier.width(PmSpace.s))
            PmIconButton(Icons.Filled.RestartAlt, "恢复自动分类", actions.resetAdjustments)
            Spacer(Modifier.width(PmSpace.m))
            PmButton(
                text = "导出",
                onClick = { actions.goStage(Stage.EXPORT) },
                kind = ButtonKind.PRIMARY,
                icon = Icons.Filled.FileDownload,
            )
        }
    }

    Column(Modifier.fillMaxWidth().background(PmColor.surface).padding(horizontal = PmSpace.l, vertical = PmSpace.m)) {
        if (wide) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                filters()
                Spacer(Modifier.weight(1f))
                SearchBox(ui, onUi)
                Spacer(Modifier.width(PmSpace.l))
                tools()
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                filters()
                Spacer(Modifier.weight(1f))
                SearchBox(ui, onUi)
            }
            Spacer(Modifier.height(PmSpace.m))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "去重口径",
                    style = PmType.caption,
                    color = PmColor.ink3,
                )
                Spacer(Modifier.width(PmSpace.s))
                tools()
                Spacer(Modifier.weight(1f))
                Text(
                    "已归属 ${stats.matched} / 未匹配 ${stats.unmatched}",
                    style = PmType.caption,
                    color = if (stats.unmatched > 0) PmColor.warn else PmColor.ink3,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun SearchBox(ui: UiState, onUi: (UiState) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = PmColor.ink3, modifier = Modifier.width(14.dp).height(14.dp))
        Spacer(Modifier.width(PmSpace.xs))
        PmTextField(
            value = ui.query,
            onValueChange = { onUi(ui.copy(query = it)) },
            modifier = Modifier.width(180.dp),
            placeholder = "桩号 / 描述 / 类型",
            height = 28.dp,
        )
    }
}

@Composable
private fun EventList(
    state: AppState,
    ui: UiState,
    visible: List<EventSnapshot>,
    width: Int,
    onUi: (UiState) -> Unit,
) {
    Column(Modifier.width(width.dp).fillMaxHeight().background(PmColor.surface)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = PmSpace.m, vertical = PmSpace.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("事件", style = PmType.label, color = PmColor.ink2)
            Spacer(Modifier.weight(1f))
            Text("${visible.size} / ${state.events.size}", style = PmType.numeric, color = PmColor.ink3)
        }
        Hairline()

        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(visible, key = { _, e -> e.id }) { index, event ->
                EventRow(
                    index = index + 1,
                    event = event,
                    selected = (ui.selection as? Selection.Event)?.id == event.id,
                    onClick = { onUi(ui.copy(selection = Selection.Event(event.id), selectedPhotoIds = emptySet())) },
                )
                Hairline()
            }
        }

        Hairline()
        UnmatchedRow(
            count = state.unmatchedPhotoIds.size,
            selected = ui.selection == Selection.Unmatched,
            onClick = {
                onUi(ui.copy(selection = Selection.Unmatched, selectedPhotoIds = emptySet(), inspectorOpen = true))
            },
        )
    }
}
