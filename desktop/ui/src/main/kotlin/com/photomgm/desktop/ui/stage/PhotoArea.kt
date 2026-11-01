// ui/stage/PhotoArea.kt —— 照片区（当前事件的照片 / 未匹配池）
//
// ★ 这一区只回答一个问题："这个事件现在有哪些照片，我要动哪几张。"
//   所以它顶上必须有**当前语境**（哪个事件、什么时间、什么桩号），
//   否则一排缩略图离开列表就看不出属于谁——这是旧设计最伤的一处：
//   照片网格与事件列表平行摆放，中间没有任何锚定关系。
//
// 批量动作的出现时机＝有选中。没选中时不显示灰按钮，直接不显示（减少噪音）。
package com.photomgm.desktop.ui.stage

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.window.Dialog
import com.photomgm.desktop.core.api.AppState
import com.photomgm.desktop.core.api.EventSnapshot
import com.photomgm.desktop.ui.app.Actions
import com.photomgm.desktop.ui.component.ButtonKind
import com.photomgm.desktop.ui.component.EmptyPhotoArea
import com.photomgm.desktop.ui.component.Hairline
import com.photomgm.desktop.ui.component.PhotoTile
import com.photomgm.desktop.ui.component.PmButton
import com.photomgm.desktop.ui.component.PmIconButton
import com.photomgm.desktop.ui.component.PmTextField
import com.photomgm.desktop.ui.component.Tag
import com.photomgm.desktop.ui.component.Tone
import com.photomgm.desktop.ui.format.timeRange
import com.photomgm.desktop.ui.model.Selection
import com.photomgm.desktop.ui.model.UiState
import com.photomgm.desktop.ui.model.WorkQueue
import com.photomgm.desktop.ui.model.ledgerByEvent
import com.photomgm.desktop.ui.model.slotPhotoIds
import com.photomgm.desktop.ui.theme.PmColor
import com.photomgm.desktop.ui.theme.PmRadius
import com.photomgm.desktop.ui.theme.PmSpace
import com.photomgm.desktop.ui.theme.PmType

@Composable
fun PhotoArea(
    state: AppState,
    ui: UiState,
    gridWidth: Int,
    gridCols: Int,
    listCollapsed: Boolean,
    actions: Actions,
    onUi: (UiState) -> Unit,
    modifier: Modifier = Modifier,
) {
    val byId = remember(state.photos) { WorkQueue.byId(state) }
    val photos = WorkQueue.photosOf(state, ui.selection, byId)
    val event = (ui.selection as? Selection.Event)?.let { sel -> state.events.firstOrNull { it.id == sel.id } }
    val ledgerRow = event?.let { ledgerByEvent(state)[it.id] }
    val slots = remember(ledgerRow, photos) { slotPhotoIds(ledgerRow, photos) }
    var moveDialog by remember { mutableStateOf(false) }

    Column(modifier.background(PmColor.stage)) {
        PhotoContextBar(
            state = state,
            ui = ui,
            event = event,
            photoCount = photos.size,
            listCollapsed = listCollapsed,
            onUi = onUi,
        )
        Hairline()

        if (ui.selectedPhotoIds.isNotEmpty()) {
            BatchBar(
                ui = ui,
                canRemoveFromEvent = event != null,
                onUi = onUi,
                onMove = { moveDialog = true },
                onRemove = { actions.movePhotosTo(ui.selectedPhotoIds.toList(), null) },
            )
            Hairline()
        }

        when {
            ui.selection == Selection.None ->
                EmptyPhotoArea(
                    text = "还没有可复核的内容",
                    hint = "先在左侧选一个事件；若列表是空的，回到输入页点「开始分析」",
                    modifier = Modifier.fillMaxSize(),
                )

            photos.isEmpty() && ui.selection == Selection.Unmatched ->
                EmptyPhotoArea(
                    text = "没有未匹配照片",
                    hint = "所有扫到的照片都已经归到事件里了",
                    modifier = Modifier.fillMaxSize(),
                )

            photos.isEmpty() ->
                EmptyPhotoArea(
                    text = "这个事件还没有照片",
                    hint = "从左侧「未匹配照片」里选几张移进来，或回输入页确认源目录与日期",
                    modifier = Modifier.fillMaxSize(),
                )

            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(gridCols),
                contentPadding = PaddingValues(PmSpace.l),
                horizontalArrangement = Arrangement.spacedBy(PmSpace.l),
                verticalArrangement = Arrangement.spacedBy(PmSpace.l),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(photos, key = { it.id }) { photo ->
                    PhotoTile(
                        modifier = Modifier.fillMaxWidth(),
                        photo = photo,
                        selected = photo.id in ui.selectedPhotoIds,
                        ledgerSlot = slots[photo.id],
                        onClick = { additive ->
                            val next = if (additive) {
                                if (photo.id in ui.selectedPhotoIds) ui.selectedPhotoIds - photo.id
                                else ui.selectedPhotoIds + photo.id
                            } else {
                                if (ui.selectedPhotoIds == setOf(photo.id)) emptySet() else setOf(photo.id)
                            }
                            onUi(ui.copy(selectedPhotoIds = next))
                        },
                    )
                }
            }
        }
    }

    if (moveDialog) {
        MoveDialog(
            movingCount = ui.selectedPhotoIds.size,
            events = state.events,
            currentEventId = event?.id,
            onDismiss = { moveDialog = false },
            onPick = { target ->
                actions.movePhotosTo(ui.selectedPhotoIds.toList(), target)
                onUi(ui.copy(selectedPhotoIds = emptySet()))
                moveDialog = false
            },
        )
    }
}

@Composable
private fun PhotoContextBar(
    state: AppState,
    ui: UiState,
    event: EventSnapshot?,
    photoCount: Int,
    listCollapsed: Boolean,
    onUi: (UiState) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(PmColor.surface.copy(alpha = 0.92f)).padding(horizontal = PmSpace.l, vertical = PmSpace.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (event == null) {
            Icon(Icons.Filled.Image, contentDescription = null, tint = PmColor.warn, modifier = Modifier.width(15.dp).height(15.dp))
            Spacer(Modifier.width(PmSpace.s))
            Column {
                Text("未匹配照片", style = PmType.bodyStrong, color = PmColor.ink)
                Text(
                    "这些照片没有归到任何事件，导出时进 99_未分类",
                    style = PmType.caption,
                    color = PmColor.ink3,
                    maxLines = 1,
                )
            }
        } else {
            Column(Modifier.weight(1f)) {
                if (listCollapsed) {
                    // ★ 事件列表被折叠时（<900 dp），这里是**唯一**的切换入口。
                    //   没有它，最小尺寸下用户根本换不了事件——那等于把一个动作弄丢了。
                    EventPicker(state, ui, event, onUi)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        event.eventType.ifBlank { "未分类事件" },
                        style = PmType.subtitle,
                        color = PmColor.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(PmSpace.s))
                    Text(timeRange(event.startTimeMs, event.endTimeMs), style = PmType.numeric, color = PmColor.ink2)
                    Spacer(Modifier.width(PmSpace.s))
                    Text(
                        event.location.ifBlank { "（无桩号）" },
                        style = PmType.caption,
                        color = PmColor.ink2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (event.description.isNotBlank() && !listCollapsed) {
                    Text(
                        event.description,
                        style = PmType.caption,
                        color = PmColor.ink3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tag("$photoCount 张", if (photoCount == 0) Tone.WARN else Tone.NEUTRAL)
            Spacer(Modifier.width(PmSpace.s))
            PmIconButton(
                Icons.Filled.Info,
                "事件详情",
                { onUi(ui.copy(inspectorOpen = !ui.inspectorOpen)) },
                selected = ui.inspectorOpen,
            )
        }
    }
}

@Composable
private fun BatchBar(
    ui: UiState,
    canRemoveFromEvent: Boolean,
    onUi: (UiState) -> Unit,
    onMove: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(PmColor.accentSoft).padding(horizontal = PmSpace.l, vertical = PmSpace.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("已选 ${ui.selectedPhotoIds.size} 张", style = PmType.bodyStrong, color = PmColor.accent)
        Spacer(Modifier.width(PmSpace.m))
        PmButton("移入事件…", onMove, kind = ButtonKind.SECONDARY, icon = Icons.Filled.DriveFileMove)
        if (canRemoveFromEvent) {
            Spacer(Modifier.width(PmSpace.xs))
            PmButton("移出到未匹配", onRemove, kind = ButtonKind.SECONDARY, icon = Icons.Filled.Delete)
        }
        Spacer(Modifier.weight(1f))
        PmButton("取消选择", { onUi(ui.copy(selectedPhotoIds = emptySet())) }, kind = ButtonKind.GHOST)
    }
}

/** 移入哪个事件：用带搜索的列表，不用下拉菜单——事件常常 20+ 条，下拉滚不动。 */
@Composable
private fun MoveDialog(
    movingCount: Int,
    events: List<EventSnapshot>,
    currentEventId: Int?,
    onDismiss: () -> Unit,
    onPick: (Int?) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, events) {
        if (query.isBlank()) events
        else events.filter {
            it.eventType.contains(query, true) || it.location.contains(query, true) || it.description.contains(query, true)
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(460.dp)
                .height(520.dp)
                .clip(RoundedCornerShape(PmRadius.panel))
                .background(PmColor.surface)
                .padding(PmSpace.xl),
        ) {
            Text("把 $movingCount 张照片移到…", style = PmType.title, color = PmColor.ink)
            Text("目标事件决定导出的文件夹与台账行；移到「未匹配」则进 99_未分类", style = PmType.caption, color = PmColor.ink3)
            Spacer(Modifier.height(PmSpace.m))
            PmTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = "搜索事件类型 / 桩号 / 描述",
            )
            Spacer(Modifier.height(PmSpace.m))

            LazyColumn(Modifier.weight(1f)) {
                items(filtered, key = { it.id }) { e ->
                    val disabled = e.id == currentEventId
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(PmRadius.control))
                            .background(if (disabled) PmColor.surface2 else PmColor.surface)
                            .pmClickable { if (!disabled) onPick(e.id) }
                            .padding(PmSpace.m),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(e.eventType.ifBlank { "未分类" }, style = PmType.bodyStrong, color = if (disabled) PmColor.ink4 else PmColor.ink)
                                Spacer(Modifier.width(PmSpace.s))
                                Text(timeRange(e.startTimeMs, e.endTimeMs), style = PmType.numeric, color = PmColor.ink3)
                            }
                            Text(
                                "${e.location.ifBlank { "无桩号" }} · ${e.description}",
                                style = PmType.caption,
                                color = PmColor.ink3,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            if (disabled) "当前" else "${e.photoIds.size} 张",
                            style = PmType.numeric,
                            color = PmColor.ink3,
                        )
                    }
                    Hairline()
                }
            }

            Spacer(Modifier.height(PmSpace.m))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PmButton("移到未匹配池", { onPick(null) }, kind = ButtonKind.SECONDARY)
                Spacer(Modifier.weight(1f))
                PmButton("取消", onDismiss, kind = ButtonKind.GHOST)
            }
        }
    }
}

/** 无涟漪点击（Dialog 内的行不需要 hover 态，只要可点）。 */
private fun Modifier.pmClickable(onClick: () -> Unit): Modifier =
    this.clickable(indication = null, interactionSource = null, onClick = onClick)

/** 折叠态的事件下拉：序号 + 需要确认计数 + 类型，展开后可跳到任一事件或未匹配池。 */
@Composable
private fun EventPicker(
    state: AppState,
    ui: UiState,
    current: EventSnapshot?,
    onUi: (UiState) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val index = state.events.indexOfFirst { it.id == current?.id }
    val need = state.events.count { WorkQueue.needsReview(it) }

    Box {
        Row(
            Modifier
                .clip(RoundedCornerShape(PmRadius.control))
                .background(PmColor.surface2)
                .clickable(indication = null, interactionSource = null) { open = true }
                .padding(horizontal = PmSpace.s, vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${index + 1}/${state.events.size}",
                style = PmType.micro,
                color = PmColor.ink3,
            )
            Spacer(Modifier.width(PmSpace.xs))
            Text("切换事件", style = PmType.micro, color = PmColor.accent)
            if (need > 0) {
                Spacer(Modifier.width(PmSpace.xs))
                Tag("$need 待确认", Tone.WARN)
            }
        }

        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.width(340.dp)) {
            state.events.forEachIndexed { i, e ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${i + 1}", style = PmType.numeric, color = PmColor.ink3)
                            Spacer(Modifier.width(PmSpace.s))
                            Text(
                                e.eventType.ifBlank { "未分类" },
                                style = PmType.body,
                                color = PmColor.ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (WorkQueue.needsReview(e)) Tag("待确认", Tone.WARN)
                        }
                    },
                    onClick = {
                        onUi(ui.copy(selection = Selection.Event(e.id), selectedPhotoIds = emptySet()))
                        open = false
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("未匹配照片（${state.unmatchedPhotoIds.size}）", style = PmType.body, color = PmColor.warn) },
                onClick = {
                    onUi(ui.copy(selection = Selection.Unmatched, selectedPhotoIds = emptySet()))
                    open = false
                },
            )
        }
    }
}
