// app/ui/dialog/PhotosSheet.kt —— 单事件照片管理弹窗 + 未匹配照片管理弹窗
package com.photomgm.app.ui.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.api.EventSnapshot
import app.api.PhotoInfo
import com.photomgm.app.ui.EventViewer
import com.photomgm.app.ui.PhotoAnomaly
import com.photomgm.app.ui.component.PhotoGrid
import com.photomgm.app.ui.component.PhotoTile
import com.photomgm.app.ui.model.LongSetSaver

// ──────────────────────────────────────────────────────────────────
// 单事件照片管理弹窗：Dialog + 置顶操作栏 + 3 列网格（点击多选）
// ──────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EventPhotosSheet(
    eventTitle: String,
    photos: List<PhotoInfo>,
    marked: Pair<Long?, Long?>?,
    events: List<EventSnapshot>,
    onMove: (List<Long>, Int) -> Unit,
    onRemoveFromEvent: (List<Long>) -> Unit,
    onMark: (Long, Int) -> Unit,
    onClearMark: (Int) -> Unit,
    onNotify: (String) -> Unit,
    onDismiss: () -> Unit,
    eventLocation: String = "",
    eventTime: String = "",
    anomalies: Map<Long, PhotoAnomaly> = emptyMap(),
    readOnly: Boolean = false,
) {
    var selected by rememberSaveable(stateSaver = LongSetSaver) { mutableStateOf(emptySet()) }
    var moveDialogOpen by rememberSaveable { mutableStateOf(false) }
    var markTarget by remember { mutableStateOf<Long?>(null) }
    var confirmRemoveOpen by rememberSaveable { mutableStateOf(false) }
    var viewerIndex by remember { mutableStateOf<Int?>(null) }
    val screenMaxH = (LocalConfiguration.current.screenHeightDp * 0.8f).dp

    val selectionMode = selected.isNotEmpty()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 600.dp)
                .padding(horizontal = 16.dp)
                .heightIn(max = screenMaxH)
                .imePadding(),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(vertical = 14.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (selectionMode) "已选 ${selected.size} 张"
                        else "$eventTitle · ${photos.size} 张",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    if (selectionMode) {
                        TextButton(onClick = { selected = emptySet() }) { Text("取消选择") }
                    } else {
                        TextButton(onClick = onDismiss) { Text("关闭") }
                    }
                }
                if (!selectionMode && (eventTime.isNotBlank() || eventLocation.isNotBlank())) {
                    Text(
                        listOf(eventTime, eventLocation).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
                if (selectionMode) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            onClick = { if (selected.isNotEmpty()) moveDialogOpen = true },
                            enabled = !readOnly,
                            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                        ) { Text("移动到…") }
                        Spacer(Modifier.width(6.dp))
                        OutlinedButton(
                            onClick = { if (selected.isNotEmpty()) confirmRemoveOpen = true },
                            enabled = !readOnly,
                            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                        ) { Text("移出事件", color = MaterialTheme.colorScheme.error) }
                        Spacer(Modifier.width(6.dp))
                        OutlinedButton(
                            onClick = {
                                if (selected.size == 1) markTarget = selected.first()
                                else onNotify("台账标记请仅选择一张照片")
                            },
                            enabled = !readOnly,
                            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                        ) { Text("设为台账") }
                    }
                }
                if (photos.isEmpty()) {
                    Text(
                        "该事件暂无照片",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                } else {
                    PhotoGrid(
                        photos = photos,
                        selected = selected,
                        marked = marked,
                        anomalies = anomalies,
                        onTap = { p, isSel ->
                            if (selectionMode) {
                                selected = if (isSel) selected - p.id else selected + p.id
                            } else {
                                viewerIndex = photos.indexOf(p).coerceAtLeast(0)
                            }
                        },
                        onLongPress = { p ->
                            if (!selectionMode) selected = setOf(p.id)
                        },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                }
                Text(
                    if (selectionMode) "点按照片切换选中 · 「取消选择」退出多选"
                    else "点按照片查看大图 · 长按进入多选 · 移出仅回到未匹配池，不删源文件",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                )
                if (marked != null && !selectionMode) {
                    Row(Modifier.padding(horizontal = 20.dp)) {
                        TextButton(onClick = { onClearMark(1) }) { Text("照片1恢复默认") }
                        TextButton(onClick = { onClearMark(2) }) { Text("照片2恢复默认") }
                    }
                }
            }
        }
    }

    // ── 全屏查看器 ──
    viewerIndex?.let { idx ->
        EventViewer(
            photos = photos,
            initialIndex = idx,
            anomalies = anomalies,
            locationText = eventLocation,
            onSetLedger = { photo -> markTarget = photo.id },
            onDismiss = { viewerIndex = null },
        )
    }

    // 移动目标弹窗（带搜索）
    if (moveDialogOpen) {
        MoveTargetDialogWithSearch(
            title = "移动 ${selected.size} 张照片到事件",
            events = events,
            onConfirm = { target ->
                onMove(selected.toList(), target)
                selected = emptySet()
                moveDialogOpen = false
            },
            onDismiss = { moveDialogOpen = false },
        )
    }
    // 台账照片标记（单选）
    markTarget?.let { pid ->
        MarkLedgerDialog(
            onSlot1 = { onMark(pid, 1); markTarget = null },
            onSlot2 = { onMark(pid, 2); markTarget = null },
            onDismiss = { markTarget = null },
        )
    }
    // 移除确认
    if (confirmRemoveOpen) {
        AlertDialog(
            onDismissRequest = { confirmRemoveOpen = false },
            title = { Text("从当前事件移除") },
            text = { Text("将把选中的 ${selected.size} 张照片移出「$eventTitle」，回到未匹配照片池。\n不会删除源文件。") },
            confirmButton = {
                TextButton({
                    onRemoveFromEvent(selected.toList())
                    selected = emptySet()
                    confirmRemoveOpen = false
                }) { Text("移出事件") }
            },
            dismissButton = { TextButton({ confirmRemoveOpen = false }) { Text("取消") } },
        )
    }
}

// ──────────────────────────────────────────────────────────────────
// 未匹配照片管理弹窗：Dialog + 置顶操作栏 + 3 列网格点击多选 + 批量移动
// ──────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UnmatchedPhotosSheet(
    photos: List<PhotoInfo>,
    events: List<EventSnapshot>,
    onMove: (List<Long>, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by rememberSaveable(stateSaver = LongSetSaver) { mutableStateOf(emptySet()) }
    var moveDialogOpen by rememberSaveable { mutableStateOf(false) }
    val screenMaxH = (LocalConfiguration.current.screenHeightDp * 0.8f).dp

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 600.dp)
                .padding(horizontal = 16.dp)
                .heightIn(max = screenMaxH)
                .imePadding(),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(vertical = 14.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "未匹配照片 · ${photos.size} 张",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "已选 ${selected.size}",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = { selected = emptySet() },
                        enabled = selected.isNotEmpty(),
                    ) { Text("清空") }
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = { if (selected.isNotEmpty()) moveDialogOpen = true },
                        enabled = selected.isNotEmpty(),
                        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                    ) { Text("批量移动") }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "点击照片 = 选中/取消（多选）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (photos.isEmpty()) {
                    Text(
                        "暂无未匹配照片",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        gridItems(photos, key = { it.id }) { p ->
                            val isSel = p.id in selected
                            PhotoTile(
                                p = p,
                                selected = isSel,
                                selectionMode = selected.isNotEmpty(),
                                markedSlot = null,
                                anomaly = null,
                                onTap = {
                                    selected = if (isSel) selected - p.id else selected + p.id
                                },
                                onLongPress = {
                                    if (!isSel) selected = selected + p.id
                                },
                            )
                        }
                    }
                }
                Text(
                    "批量移动后照片归入对应事件；不删除源文件",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                )
            }
        }
    }

    if (moveDialogOpen) {
        MoveTargetDialogWithSearch(
            title = "移动 ${selected.size} 张照片到事件",
            events = events,
            onConfirm = { target ->
                onMove(selected.toList(), target)
                selected = emptySet()
                moveDialogOpen = false
            },
            onDismiss = { moveDialogOpen = false },
        )
    }
}
