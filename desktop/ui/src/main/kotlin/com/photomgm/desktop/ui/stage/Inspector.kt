// ui/stage/Inspector.kt —— 检查器：当前选中对象的事实与可做的动作
//
// ★ 定位：**只回答"我选的这个东西是什么、还能对它做什么"**。
//   不做第二个导航区，也不重复照片区的内容。
//   宽窗常驻（≥1380），窄窗为抽屉——因为在 984 dp 上常驻 320 dp 会让照片只剩 1 列，
//   而"看清照片"是复核的第一需求，详情可以让一步。
//
// ★ 为什么这里放"台账照片1/2"而不是放在导出页：这两张照片是**事件的属性**，
//   在复核某条事件时顺手指定最自然；等到导出页才回头挑照片，等于让人重走一遍。
//   core 的 markLedgerPhoto 会立即重算台账（republishViews），所以这里改完，
//   导出页的台账预览就是新的，不需要"应用"。
package com.photomgm.desktop.ui.stage

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Image
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
import com.photomgm.desktop.core.api.EventSnapshot
import com.photomgm.desktop.core.api.PhotoInfo
import com.photomgm.desktop.ui.app.Actions
import com.photomgm.desktop.ui.component.ButtonKind
import com.photomgm.desktop.ui.component.Hairline
import com.photomgm.desktop.ui.component.PmButton
import com.photomgm.desktop.ui.component.PmIconButton
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
fun Inspector(
    state: AppState,
    ui: UiState,
    width: Int,
    actions: Actions,
    onUi: (UiState) -> Unit,
    overlay: Boolean = false,
) {
    val byId = WorkQueue.byId(state)
    val photos = WorkQueue.photosOf(state, ui.selection, byId)
    val event = (ui.selection as? Selection.Event)?.let { sel -> state.events.firstOrNull { it.id == sel.id } }

    Column(
        Modifier
            .width(width.dp)
            .fillMaxHeight()
            .background(PmColor.surface),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = PmSpace.l, vertical = PmSpace.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("检查器", style = PmType.label, color = PmColor.ink2)
            Spacer(Modifier.weight(1f))
            if (overlay) {
                PmIconButton(Icons.Filled.Close, "关闭检查器", { onUi(ui.copy(inspectorOpen = false)) })
            }
        }
        Hairline()

        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(PmSpace.l)) {
            if (event == null) {
                UnmatchedInspector(state, ui, onUi)
            } else {
                EventInspector(state, event, photos, ui, actions, onUi)
            }
        }
    }
}

@Composable
private fun EventInspector(
    state: AppState,
    event: EventSnapshot,
    photos: List<PhotoInfo>,
    ui: UiState,
    actions: Actions,
    onUi: (UiState) -> Unit,
) {
    val ledgerRow = ledgerByEvent(state)[event.id]
    val slots = slotPhotoIds(ledgerRow, photos)
    val selectedSingle = ui.selectedPhotoIds.singleOrNull()
    val gps = photos.count { it.hasGps }

    SectionLabel("事件")
    Text(
        event.eventType.ifBlank { "未分类" },
        style = PmType.subtitle,
        color = PmColor.ink,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.height(PmSpace.xs))
    Fact("时间", timeRange(event.startTimeMs, event.endTimeMs))
    Fact("桩号", event.location.ifBlank { "（日志里没有桩号）" })
    if (event.description.isNotBlank()) {
        Spacer(Modifier.height(PmSpace.s))
        Text(event.description, style = PmType.caption, color = PmColor.ink2, maxLines = 6, overflow = TextOverflow.Ellipsis)
    }

    if (event.hints.isNotEmpty()) {
        Spacer(Modifier.height(PmSpace.l))
        SectionLabel("需要确认")
        event.hints.forEach { hint ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(PmRadius.control))
                    .background(PmColor.warnSoft)
                    .padding(PmSpace.s),
            ) {
                Icon(
                    Icons.Filled.WarningAmber,
                    contentDescription = null,
                    tint = PmColor.warn,
                    modifier = Modifier.width(13.dp).height(13.dp),
                )
                Spacer(Modifier.width(PmSpace.xs))
                Text(hint, style = PmType.caption, color = PmColor.ink2)
            }
        }
    }

    Spacer(Modifier.height(PmSpace.l))
    SectionLabel("台账照片")
    Text(
        "台账这一行固定放两张。未指定时用该事件里最早的与最晚的拍摄时间。",
        style = PmType.caption,
        color = PmColor.ink3,
    )
    Spacer(Modifier.height(PmSpace.s))
    SlotRow(
        slot = 1,
        label = ledgerRow?.photo1Label,
        canUseSelected = selectedSingle != null && selectedSingle in event.photoIds,
        onUseSelected = { selectedSingle?.let { actions.markLedgerPhoto(event.id, 1, it) } },
        onReset = { actions.markLedgerPhoto(event.id, 1, null) },
        photoNameOf = { id -> photos.firstOrNull { it.id == id }?.displayName },
        slotPhotoId = slots.entries.firstOrNull { it.value == 1 }?.key,
    )
    Spacer(Modifier.height(PmSpace.xs))
    SlotRow(
        slot = 2,
        label = ledgerRow?.photo2Label,
        canUseSelected = selectedSingle != null && selectedSingle in event.photoIds,
        onUseSelected = { selectedSingle?.let { actions.markLedgerPhoto(event.id, 2, it) } },
        onReset = { actions.markLedgerPhoto(event.id, 2, null) },
        photoNameOf = { id -> photos.firstOrNull { it.id == id }?.displayName },
        slotPhotoId = slots.entries.firstOrNull { it.value == 2 }?.key,
    )

    Spacer(Modifier.height(PmSpace.l))
    SectionLabel("归属")
    Fact("本事件照片", "${photos.size} 张")
    Fact("其中有 GPS", "$gps 张")
    Spacer(Modifier.height(PmSpace.m))
    if (ui.selectedPhotoIds.isEmpty()) {
        Text(
            "在照片区点选一张（Ctrl 多选）后，可以在这里指定为台账照片，或移到别的事件。",
            style = PmType.caption,
            color = PmColor.ink3,
        )
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tag("已选 ${ui.selectedPhotoIds.size} 张", Tone.ACCENT)
            Spacer(Modifier.weight(1f))
            PmButton(
                "移出事件",
                { actions.movePhotosTo(ui.selectedPhotoIds.toList(), null) },
                kind = ButtonKind.SECONDARY,
                icon = Icons.Filled.DriveFileMove,
            )
        }
    }

    Spacer(Modifier.height(PmSpace.l))
    Hairline()
    Spacer(Modifier.height(PmSpace.m))
    Text(
        "文件夹名：${ledgerRow?.folderName?.ifBlank { "（跟随命名模板）" } ?: "（尚未匹配到台账行）"}",
        style = PmType.caption,
        color = PmColor.ink3,
    )
    Spacer(Modifier.height(PmSpace.xs))
    Text("改动立即生效，不需要重新分析。", style = PmType.caption, color = PmColor.ink3)
}

@Composable
private fun SlotRow(
    slot: Int,
    label: String?,
    canUseSelected: Boolean,
    onUseSelected: () -> Unit,
    onReset: () -> Unit,
    photoNameOf: (Long) -> String?,
    slotPhotoId: Long?,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PmRadius.control))
            .background(PmColor.surface2)
            .padding(PmSpace.s),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("照片$slot", style = PmType.label, color = PmColor.ink2)
            Spacer(Modifier.weight(1f))
            Tag(if (label.isNullOrBlank()) "自动" else "已指定", if (label.isNullOrBlank()) Tone.NEUTRAL else Tone.OK)
        }
        Spacer(Modifier.height(2.dp))
        Text(
            label ?: "（该事件暂无照片）",
            style = PmType.caption,
            color = if (label == null) PmColor.ink3 else PmColor.ink2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (slotPhotoId != null) {
            Text(
                photoNameOf(slotPhotoId) ?: "",
                style = PmType.micro,
                color = PmColor.ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(PmSpace.xs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PmButton("用选中照片", onUseSelected, kind = ButtonKind.SECONDARY, enabled = canUseSelected)
            Spacer(Modifier.width(PmSpace.xs))
            PmButton("恢复自动", onReset, kind = ButtonKind.GHOST)
        }
    }
}

@Composable
private fun UnmatchedInspector(state: AppState, ui: UiState, onUi: (UiState) -> Unit) {
    val count = state.unmatchedPhotoIds.size
    SectionLabel("未匹配照片")
    Text(
        if (count == 0) "所有照片都已归入事件" else "$count 张照片没有归属事件",
        style = PmType.subtitle,
        color = PmColor.ink,
    )
    Spacer(Modifier.height(PmSpace.s))
    Text(
        "常见原因：照片时间落在两个事件之间、GPS 离任何事件都太远、或者当天日志里没有对应记录。" +
            "导出时它们会进 99_未分类 文件夹，台账里不出现。",
        style = PmType.caption,
        color = PmColor.ink3,
    )
    Spacer(Modifier.height(PmSpace.l))
    if (count > 0) {
        Text("在照片区点选后，用「移入事件…」把它们挂到正确的事件上。", style = PmType.caption, color = PmColor.ink2)
        Spacer(Modifier.height(PmSpace.s))
        Row {
            PmButton(
                "全选未匹配",
                { onUi(ui.copy(selectedPhotoIds = state.unmatchedPhotoIds.toSet())) },
                kind = ButtonKind.SECONDARY,
            )
        }
    }
    Spacer(Modifier.height(PmSpace.l))
    Hairline()
    Spacer(Modifier.height(PmSpace.m))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Image, contentDescription = null, tint = PmColor.ink4, modifier = Modifier.width(14.dp).height(14.dp))
        Spacer(Modifier.width(PmSpace.xs))
        Text("未匹配池不参与三态去重", style = PmType.caption, color = PmColor.ink3)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = PmType.micro, color = PmColor.ink3)
    Spacer(Modifier.height(PmSpace.xs))
}

@Composable
private fun Fact(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(label, style = PmType.caption, color = PmColor.ink3)
        Spacer(Modifier.weight(1f))
        Text(value, style = PmType.caption, color = PmColor.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
