// ui/dialog/AssignSheet.kt —— "把这些照片移动 / 复制到哪个事件"
//
// ★ 为什么用两段式（选中 → 选目标）而不是拖放：
//   拖放要求源和目标同时可见，而照片条在弹窗里、目标事件在列表里，跨区域拖拽时
//   既看不见目标、也没法滚动；一次要移十来张时命中率很低。
//   两段式只多一次点击，但每一段都是"看得见的列表"，而且不会误投。
//
// ★ 一个弹窗承担"移动"和"复制"两种模式：两者的第一步完全相同（选目标事件），
//   差别只在"原事件要不要保留"。做成两个弹窗会让用户在同一屏里找两个几乎一样的界面，
//   而模式差异用标题 + 一行说明 + 目标行上的一句后果提示说清楚就够了。
package com.photomgm.desktop.ui.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import com.photomgm.desktop.core.api.AppState
import com.photomgm.desktop.core.api.EventSnapshot
import com.photomgm.desktop.ui.app.Actions
import com.photomgm.desktop.ui.component.ButtonKind
import com.photomgm.desktop.ui.component.Hairline
import com.photomgm.desktop.ui.component.PmButton
import com.photomgm.desktop.ui.component.PmIconButton
import com.photomgm.desktop.ui.component.PmTextField
import com.photomgm.desktop.ui.component.Tag
import com.photomgm.desktop.ui.component.Tone
import com.photomgm.desktop.ui.format.timeRange
import com.photomgm.desktop.ui.model.AssignMode
import com.photomgm.desktop.ui.model.Selection
import com.photomgm.desktop.ui.model.Sheet
import com.photomgm.desktop.ui.model.UiState
import com.photomgm.desktop.ui.model.dialogHeight
import com.photomgm.desktop.ui.model.dialogWidth
import com.photomgm.desktop.ui.theme.PmColor
import com.photomgm.desktop.ui.theme.PmRadius
import com.photomgm.desktop.ui.theme.PmSpace
import com.photomgm.desktop.ui.theme.PmType

@Composable
fun AssignSheet(
    state: AppState,
    ui: UiState,
    onUi: (UiState) -> Unit,
    actions: Actions,
    windowWidth: Int,
    windowHeight: Int,
) {
    val sheet = ui.sheet as? Sheet.Assign ?: return
    val copy = sheet.mode == AssignMode.COPY
    val fromEventId = (sheet.from as? Selection.Event)?.id
    val picked = sheet.photoIds.toSet()

    val candidates = remember(state.events, ui.moveQuery, fromEventId, picked, copy) {
        val q = ui.moveQuery.trim()
        state.events
            .filter { it.id != fromEventId }
            // 复制模式下，已经全都有的事件列出来也没用（点了不会新增任何东西），直接隐藏。
            .filterNot { copy && picked.all { id -> id in it.photoIds } }
            .filter {
                q.isEmpty() ||
                    it.location.contains(q, ignoreCase = true) ||
                    it.description.contains(q, ignoreCase = true) ||
                    it.eventType.contains(q, ignoreCase = true)
            }
    }
    var draft by remember(ui.moveQuery) { mutableStateOf(ui.moveQuery) }
    val width = minOf(560, dialogWidth(windowWidth))
    // ★ 不能"比可用高度再高一点"：弹窗底部会越过窗口下沿，最后一行被切成半行，
    //   而且没有滚动条可抓（LazyColumn 的滚动只在鼠标悬停时生效）。永远取可用高度的 min。
    val height = minOf(520, dialogHeight(windowHeight))

    fun assignTo(eventId: Int?) {
        if (copy && eventId != null) actions.copyPhotosTo(sheet.photoIds, eventId)
        else actions.movePhotosTo(sheet.photoIds, eventId)
        onUi(ui.copy(sheet = null, selectedPhotoIds = emptySet()))
    }

    SheetHost(onDismiss = { onUi(ui.copy(sheet = null)) }) {
        Column(
            Modifier
                .width(width.dp)
                .height(height.dp)
                .clip(RoundedCornerShape(PmRadius.panel))
                .background(PmColor.surface),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = PmSpace.xl, end = PmSpace.m, top = PmSpace.l, bottom = PmSpace.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "把 ${sheet.photoIds.size} 张照片${sheet.mode.verb}到…",
                        style = PmType.title,
                        color = PmColor.ink,
                    )
                    Text(
                        if (copy) {
                            "原事件保留这些照片，目标事件也多一份；导出时两个文件夹各有一份。"
                        } else {
                            "选择一个事件；选错了可以再移回来。"
                        },
                        style = PmType.caption,
                        color = PmColor.ink3,
                    )
                }
                PmIconButton(Icons.Filled.Close, "关闭", { onUi(ui.copy(sheet = null)) })
            }
            Hairline()

            Row(Modifier.fillMaxWidth().padding(horizontal = PmSpace.xl, vertical = PmSpace.m)) {
                PmTextField(
                    value = draft,
                    onValueChange = {
                        draft = it
                        onUi(ui.copy(moveQuery = it))
                    },
                    modifier = Modifier.weight(1f),
                    placeholder = "搜索地点 / 描述 / 类型",
                )
                if (!copy && fromEventId != null) {
                    Spacer(Modifier.width(PmSpace.m))
                    PmButton("移入未匹配池", { assignTo(null) }, kind = ButtonKind.SECONDARY)
                }
            }

            if (candidates.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        if (ui.moveQuery.isBlank()) "没有可用的其他事件" else "没有匹配的事件",
                        style = PmType.body,
                        color = PmColor.ink3,
                    )
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(bottom = PmSpace.m),
                ) {
                    items(candidates, key = { it.id }) { event ->
                        EventPickRow(
                            event = event,
                            index = state.events.indexOfFirst { it.id == event.id } + 1,
                            alreadyHas = if (copy) picked.count { id -> id in event.photoIds } else 0,
                            copy = copy,
                        ) { assignTo(event.id) }
                    }
                }
            }
        }
    }
}

@Composable
private fun EventPickRow(event: EventSnapshot, index: Int, alreadyHas: Int, copy: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(indication = null, interactionSource = null, onClick = onClick)
            .heightIn(min = 42.dp)
            .padding(horizontal = PmSpace.xl, vertical = PmSpace.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("%02d".format(index), style = PmType.numeric, color = PmColor.ink3, modifier = Modifier.width(26.dp))
        Text(timeRange(event.startTimeMs, event.endTimeMs), style = PmType.numeric, color = PmColor.ink2, modifier = Modifier.width(96.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (event.description.isBlank()) event.location else "${event.location} · ${event.description}",
                style = PmType.body,
                color = PmColor.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append(event.eventType)
                    append(" · ${event.photoIds.size} 张")
                    // 复制模式下"这个事件已经有本次中的 N 张"必须说出来：否则点了只多出一部分，像失灵。
                    if (copy && alreadyHas > 0) append(" · 已有本次中的 $alreadyHas 张")
                },
                style = PmType.caption,
                color = PmColor.ink3,
                maxLines = 1,
            )
        }
        if (event.photoIds.isEmpty()) Tag("无照片", Tone.DANGER)
    }
}
