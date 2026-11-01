// ui/dialog/PhotosSheet.kt —— 事件照片弹窗（复核的主战场）
//
// ★ 为什么"点事件弹窗"比"事件在左、照片在右"好：
//   复核的动作是「这一条对不对」——一次只看一个事件。并排布局里，另一栏永远在
//   争夺注意力，而且宽度被劈成两半，照片只能排 3 列。弹窗把整块宽度给当前事件，
//   一屏能看 6~8 张，且关闭后列表原样还在（没有页面切换、没有滚动位置丢失）。
//
// ★ 结构：标题（哪个事件 + 前后切换） / 左大图 + 右侧事实 / 底部照片条 / 底部操作条。
//   大图是"确认这张对不对"，照片条是"切换到哪一张"，事实栏回答"这个事件的背景"。
//   三者都是围绕**同一张当前照片**，不存在"我在看哪张"的歧义。
//
// ★ 台账照片（导出表格里那 2 格）在这里指定，因为只有这里能同时看到"全部候选"和"当前照片"。
//   指定立即生效（core.markLedgerPhoto），不需要重新分析。
package com.photomgm.desktop.ui.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.photomgm.desktop.core.api.AppState
import com.photomgm.desktop.core.api.PhotoInfo
import com.photomgm.desktop.ui.app.Actions
import com.photomgm.desktop.ui.component.ButtonKind
import com.photomgm.desktop.ui.component.Dash
import com.photomgm.desktop.ui.component.Hairline
import com.photomgm.desktop.ui.component.KeyValue
import com.photomgm.desktop.ui.component.PmButton
import com.photomgm.desktop.ui.component.PmIconButton
import com.photomgm.desktop.ui.component.Tag
import com.photomgm.desktop.ui.component.Tone
import com.photomgm.desktop.ui.component.pmPress
import com.photomgm.desktop.ui.format.hhmm
import com.photomgm.desktop.ui.format.photoMeta
import com.photomgm.desktop.ui.format.timeRange
import com.photomgm.desktop.ui.model.Selection
import com.photomgm.desktop.ui.model.Sheet
import com.photomgm.desktop.ui.model.UiState
import com.photomgm.desktop.ui.model.WorkQueue
import com.photomgm.desktop.ui.model.ledgerByEvent
import com.photomgm.desktop.ui.model.photosSheetLayout
import com.photomgm.desktop.ui.model.slotPhotoIds
import com.photomgm.desktop.ui.theme.PmColor
import com.photomgm.desktop.ui.theme.PmRadius
import com.photomgm.desktop.ui.theme.PmSpace
import com.photomgm.desktop.ui.theme.PmType

@Composable
fun PhotosSheet(
    state: AppState,
    ui: UiState,
    onUi: (UiState) -> Unit,
    actions: Actions,
    windowWidth: Int,
    windowHeight: Int,
) {
    val sheet = ui.sheet as? Sheet.Photos ?: return
    val target = sheet.target
    val byId = WorkQueue.byId(state)
    val photos = WorkQueue.photosOf(state, target, byId)
    val event = (target as? Selection.Event)?.let { sel -> state.events.firstOrNull { it.id == sel.id } }
    val slots = if (event != null) slotPhotoIds(ledgerByEvent(state)[event.id], photos) else emptyMap()
    val active = photos.firstOrNull { it.id == ui.activePhotoId } ?: photos.firstOrNull()
    val layout = photosSheetLayout(windowWidth, windowHeight)
    val close = { onUi(ui.copy(sheet = null)) }

    SheetHost(onDismiss = close) {
        Column(
            Modifier
                .width(layout.width.dp)
                .height(layout.height.dp)
                .clip(RoundedCornerShape(PmRadius.panel))
                .background(PmColor.surface),
        ) {
            Header(
                state = state,
                event = event,
                target = target,
                photoCount = photos.size,
                onClose = close,
                onPick = { id -> onUi(ui.copy(sheet = Sheet.Photos(Selection.Event(id)), activePhotoId = null, selectedPhotoIds = emptySet())) },
            )
            Hairline()

            Row(Modifier.fillMaxWidth().weight(1f)) {
                Preview(photo = active, modifier = Modifier.weight(1f).fillMaxHeight())
                if (layout.sideW > 0) {
                    Hairline(vertical = true)
                    SidePanel(
                        state = state,
                        event = event,
                        target = target,
                        photos = photos,
                        slots = slots,
                        active = active,
                        modifier = Modifier.width(layout.sideW.dp).fillMaxHeight(),
                        onMark = { slot ->
                            val id = active?.id
                            if (event != null && id != null) actions.markLedgerPhoto(event.id, slot, id)
                        },
                        onResetSlots = { if (event != null) { actions.markLedgerPhoto(event.id, 1, null); actions.markLedgerPhoto(event.id, 2, null) } },
                    )
                }
            }

            Hairline()
            Strip(
                photos = photos,
                active = active,
                selected = ui.selectedPhotoIds,
                slots = slots,
                tile = layout.stripTile,
                height = layout.stripH,
                onPick = { photo, additive ->
                    val next = if (additive) {
                        if (ui.selectedPhotoIds.contains(photo.id)) ui.selectedPhotoIds - photo.id else ui.selectedPhotoIds + photo.id
                    } else {
                        setOf(photo.id)
                    }
                    onUi(ui.copy(activePhotoId = photo.id, selectedPhotoIds = next))
                },
            )

            Hairline()
            ActionBar(
                state = state,
                ui = ui,
                event = event,
                active = active,
                photoCount = photos.size,
                onUi = onUi,
                actions = actions,
                onClose = close,
            )
        }
    }
}

@Composable
private fun Header(
    state: AppState,
    event: com.photomgm.desktop.core.api.EventSnapshot?,
    target: Selection,
    photoCount: Int,
    onClose: () -> Unit,
    onPick: (Int) -> Unit,
) {
    val index = event?.let { state.events.indexOfFirst { e -> e.id == it.id } + 1 } ?: 0
    val total = state.events.size

    Row(
        Modifier.fillMaxWidth().padding(start = PmSpace.xl, end = PmSpace.m, top = PmSpace.m, bottom = PmSpace.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (event != null) {
                    Text("事件 %02d / %02d".format(index, total), style = PmType.label, color = PmColor.ink3)
                    Spacer(Modifier.width(PmSpace.m))
                    Text(
                        timeRange(event.startTimeMs, event.endTimeMs),
                        style = PmType.numeric,
                        color = if (event.isPending) PmColor.warn else PmColor.ink2,
                    )
                    Spacer(Modifier.width(PmSpace.m))
                }
                Text(
                    // 弹窗标题只放地点：时间已在前面、描述在下方事实栏，三处重复反而挤掉宽度。
                    if (event != null) event.location.ifBlank { "（无桩号）" } else WorkQueue.titleOf(state, target),
                    style = PmType.title,
                    color = PmColor.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(PmSpace.m))
                (event?.let { WorkQueue.issueLabels(it) } ?: emptyList()).forEach { Tag(it, Tone.WARN) }
            }
            Text(
                when {
                    event == null -> "这些照片没有归到任何事件，导出时放进 99_未分类/"
                    event.photoIds.isEmpty() -> "这个事件没有照片，导出时会生成空文件夹"
                    else -> "$photoCount 张照片 · 点击下方缩略图切换，Ctrl+点击可多选后批量移动"
                },
                style = PmType.caption,
                color = PmColor.ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (event != null) {
            val prev = state.events.getOrNull(index - 2)
            val next = state.events.getOrNull(index)
            PmIconButton(Icons.Filled.ChevronLeft, "上一事件", { prev?.let { onPick(it.id) } }, enabled = prev != null)
            PmIconButton(Icons.Filled.ChevronRight, "下一事件", { next?.let { onPick(it.id) } }, enabled = next != null)
            Spacer(Modifier.width(PmSpace.xs))
        }
        PmIconButton(Icons.Filled.Close, "关闭", onClose)
    }
}

@Composable
private fun Preview(photo: PhotoInfo?, modifier: Modifier) {
    Column(modifier.background(PmColor.stage).padding(PmSpace.l)) {
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            if (photo == null) {
                Text("没有可显示的照片", style = PmType.body, color = PmColor.ink3)
            } else {
                val tone = PmColor.placeholder[(photo.id.hashCode() and 0x7fffffff) % PmColor.placeholder.size]
                Box(
                    Modifier
                        .fillMaxHeight()
                        .aspectRatio(4f / 3f)
                        .clip(RoundedCornerShape(PmRadius.card))
                        .background(tone)
                        .border(1.dp, PmColor.lineStrong, RoundedCornerShape(PmRadius.card)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.Image, contentDescription = null, tint = PmColor.ink4, modifier = Modifier.size(34.dp))
                        Spacer(Modifier.height(PmSpace.s))
                        Text(
                            photo.displayName.substringAfterLast('.', "").uppercase().ifEmpty { "IMG" },
                            style = PmType.label,
                            color = PmColor.ink3,
                        )
                        Spacer(Modifier.height(PmSpace.xs))
                        Text("缩略图待接入（U1.3）", style = PmType.micro, color = PmColor.ink4)
                    }
                }
            }
        }
        if (photo != null) {
            Spacer(Modifier.height(PmSpace.s))
            Text(
                photo.displayName,
                style = PmType.body,
                color = PmColor.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(photoMeta(photo), style = PmType.caption, color = PmColor.ink3, maxLines = 1)
        }
    }
}

@Composable
private fun SidePanel(
    state: AppState,
    event: com.photomgm.desktop.core.api.EventSnapshot?,
    target: Selection,
    photos: List<PhotoInfo>,
    slots: Map<Long, Int>,
    active: PhotoInfo?,
    modifier: Modifier,
    onMark: (Int) -> Unit,
    onResetSlots: () -> Unit,
) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(PmSpace.l)) {
        if (event == null) {
            Text("未匹配照片", style = PmType.title, color = PmColor.ink)
            Spacer(Modifier.height(PmSpace.xs))
            Text(
                "这些照片在文件名日期上属于巡查日，但没有匹配到任何事件的时间窗与位置。可以：\n" +
                    "· 选中后「移到其他事件…」放进正确的事件；\n" +
                    "· 不管它，导出时会统一放进 99_未分类/。",
                style = PmType.caption,
                color = PmColor.ink3,
            )
            Spacer(Modifier.height(PmSpace.l))
            KeyValue("照片数", "${photos.size} 张")
            return@Column
        }

        Text("台账里的两张照片", style = PmType.label, color = PmColor.ink2)
        Spacer(Modifier.height(PmSpace.xs))
        SlotRow(1, photos.firstOrNull { slots[it.id] == 1 }, active, onMark)
        Spacer(Modifier.height(PmSpace.xs))
        SlotRow(2, photos.firstOrNull { slots[it.id] == 2 }, active, onMark)
        if (slots.isNotEmpty()) {
            Spacer(Modifier.height(PmSpace.xs))
            PmButton("恢复自动选择", onResetSlots, kind = ButtonKind.GHOST)
        }

        Spacer(Modifier.height(PmSpace.m))
        Dash()
        Spacer(Modifier.height(PmSpace.m))

        Text("事件信息", style = PmType.label, color = PmColor.ink2)
        Spacer(Modifier.height(PmSpace.xs))
        KeyValue("类型", event.eventType.ifBlank { "未分类" })
        KeyValue("时间段", timeRange(event.startTimeMs, event.endTimeMs))
        KeyValue("照片", "${photos.size} 张")
        KeyValue("桩号", event.location.ifBlank { "—" })

        if (event.hints.isNotEmpty()) {
            Spacer(Modifier.height(PmSpace.m))
            Dash()
            Spacer(Modifier.height(PmSpace.m))
            Text("需要确认", style = PmType.label, color = PmColor.warn)
            Spacer(Modifier.height(PmSpace.xs))
            event.hints.forEach { hint ->
                Text("· $hint", style = PmType.caption, color = PmColor.ink3, modifier = Modifier.padding(vertical = 1.dp))
            }
        }

        Spacer(Modifier.height(PmSpace.m))
        Text("改动立即生效，不需要重新分析。", style = PmType.caption, color = PmColor.ink3)
    }
}

@Composable
private fun SlotRow(slot: Int, photo: PhotoInfo?, active: PhotoInfo?, onMark: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("第 $slot 格", style = PmType.caption, color = PmColor.ink3, modifier = Modifier.width(44.dp))
        Box(
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(PmRadius.control))
                .background(PmColor.surface2),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                photo?.captureTimeMs?.let { hhmm(it) } ?: "空",
                style = PmType.micro,
                color = if (photo == null) PmColor.ink4 else PmColor.ink2,
            )
        }
        Spacer(Modifier.width(PmSpace.s))
        Text(
            photo?.displayName ?: "未指定",
            style = PmType.caption,
            color = if (photo == null) PmColor.ink4 else PmColor.ink2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        PmButton(
            "用当前照片",
            { onMark(slot) },
            kind = ButtonKind.GHOST,
            enabled = active != null,
            height = 26.dp,
        )
    }
}

@Composable
private fun Strip(
    photos: List<PhotoInfo>,
    active: PhotoInfo?,
    selected: Set<Long>,
    slots: Map<Long, Int>,
    tile: Int,
    height: Int,
    onPick: (PhotoInfo, additive: Boolean) -> Unit,
) {
    if (photos.isEmpty()) {
        Box(Modifier.fillMaxWidth().height(height.dp), contentAlignment = Alignment.Center) {
            Text("没有照片", style = PmType.caption, color = PmColor.ink3)
        }
        return
    }
    LazyRow(
        modifier = Modifier.fillMaxWidth().height(height.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = PmSpace.l, vertical = PmSpace.s),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(photos, key = { it.id }) { photo ->
            val isActive = photo.id == active?.id
            val isSelected = selected.contains(photo.id)
            val tone = PmColor.placeholder[(photo.id.hashCode() and 0x7fffffff) % PmColor.placeholder.size]
            Box(
                Modifier
                    .size(tile.dp)
                    .clip(RoundedCornerShape(PmRadius.control))
                    .background(tone)
                    .border(
                        width = if (isActive) 2.dp else 1.dp,
                        color = when {
                            isActive -> PmColor.accent
                            isSelected -> PmColor.accent.copy(alpha = 0.5f)
                            else -> PmColor.line
                        },
                        shape = RoundedCornerShape(PmRadius.control),
                    )
                    .pointerHoverIcon(PointerIcon.Hand)
                    .pmPress { additive -> onPick(photo, additive) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    photo.captureTimeMs?.let { hhmm(it) } ?: "无时间",
                    style = PmType.micro,
                    color = if (photo.captureTimeMs == null) PmColor.warn else PmColor.ink2,
                )
                val slot = slots[photo.id]
                if (slot != null) {
                    Box(
                        Modifier.align(Alignment.TopStart).padding(3.dp)
                            .clip(RoundedCornerShape(PmRadius.control))
                            .background(PmColor.accent)
                            .padding(horizontal = 4.dp),
                    ) {
                        Text("照$slot", style = PmType.micro, color = PmColor.surface)
                    }
                }
                if (isSelected) {
                    Box(
                        Modifier.align(Alignment.BottomEnd).padding(3.dp).size(14.dp)
                            .clip(RoundedCornerShape(PmRadius.control))
                            .background(PmColor.accent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = "已选中", tint = PmColor.surface, modifier = Modifier.size(10.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionBar(
    state: AppState,
    ui: UiState,
    event: com.photomgm.desktop.core.api.EventSnapshot?,
    active: PhotoInfo?,
    photoCount: Int,
    onUi: (UiState) -> Unit,
    actions: Actions,
    onClose: () -> Unit,
) {
    val selection = ui.selectedPhotoIds.toList()
    val hasSelection = selection.isNotEmpty()

    Row(
        Modifier.fillMaxWidth().padding(horizontal = PmSpace.l, vertical = PmSpace.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (hasSelection) "已选 ${selection.size} 张" else if (photoCount > 0) "点缩略图选择" else "无照片",
            style = PmType.caption,
            color = if (hasSelection) PmColor.accent else PmColor.ink3,
        )
        Spacer(Modifier.width(PmSpace.m))
        PmButton(
            "移到其他事件…",
            { onUi(ui.copy(sheet = Sheet.Move(selection, (ui.sheet as? Sheet.Photos)?.target ?: Selection.None))) },
            kind = ButtonKind.SECONDARY,
            enabled = hasSelection,
        )
        if (event != null) {
            Spacer(Modifier.width(PmSpace.xs))
            PmButton(
                "移出到未匹配",
                {
                    actions.movePhotosTo(selection, null)
                    onUi(ui.copy(selectedPhotoIds = emptySet()))
                },
                kind = ButtonKind.GHOST,
                enabled = hasSelection,
            )
        }
        Spacer(Modifier.weight(1f))
        if (active != null) {
            PmButton(
                "打开原文件",
                { actions.revealPath(active.sourceRef) },
                kind = ButtonKind.GHOST,
                icon = Icons.Filled.OpenInNew,
            )
        }
        Spacer(Modifier.width(PmSpace.xs))
        PmButton("关闭", onClose, kind = ButtonKind.SECONDARY)
    }
}
