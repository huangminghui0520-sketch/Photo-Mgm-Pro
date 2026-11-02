// app/ui/PreviewScreen.kt —— 事件照片相关组件
// 内容：FilterMode · EventCard · Thumb · PhotoTile · PhotoGrid
//       EventPhotosSheet（事件照片弹窗）· UnmatchedPhotosSheet（未匹配照片）
//       MoveTargetDialogWithSearch · MarkLedgerDialog · EventViewer 入口
// ★ 2026-10-07 UI 重建：功能等价；弹窗统一走 Dialog 平台窗口（真机验证正常机制）。
package com.photomgm.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import algorithm.model.LogEvent
import algorithm.model.Photo
import com.photomgm.app.AppViewModel
import com.photomgm.app.ThumbnailSpec
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

/** 预览区状态筛选模式 */
internal enum class FilterMode { ALL, NORMAL, PENDING, UNMATCHED }

// ──────────────────────────────────────────────────────────────────
// 自定义 Saver：Set<Long> → 可保存的基础类型列表
// ──────────────────────────────────────────────────────────────────
private val LongSetSaver: Saver<Set<Long>, List<Long>> = Saver(
    save = { it.toList() },
    restore = { it.toSet() },
)

// ──────────────────────────────────────────────────────────────────
// 移动目标对话框：Dialog + Surface + Column（避免 AlertDialog 嵌套滚动冲突）
// ──────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoveTargetDialog(
    title: String,
    events: List<LogEvent>,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var target by rememberSaveable { mutableStateOf(events.firstOrNull()?.id ?: -1) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.padding(top = 18.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                )
                Column(
                    Modifier
                        .fillMaxWidth()
                        .height(380.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    events.forEach { e ->
                        val sel = target == e.id
                        Surface(
                            onClick = { target = e.id },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                            shape = MaterialTheme.shapes.medium,
                            color = when {
                                sel -> MaterialTheme.colorScheme.primaryContainer
                                else -> Color.Transparent
                            },
                        ) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 6.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = sel, onClick = { target = e.id })
                                Column(
                                    Modifier.weight(1f).padding(end = 4.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            e.eventType,
                                            style = MaterialTheme.typography.titleSmall.copy(
                                                fontWeight = FontWeight.SemiBold
                                            ),
                                            color = if (sel) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurface,
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Surface(
                                            color = MaterialTheme.colorScheme.secondaryContainer,
                                            shape = RoundedCornerShape(6.dp),
                                        ) {
                                            Text(
                                                fmtRange(e.startTimeMs, e.endTimeMs),
                                                Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            )
                                        }
                                    }
                                    Text(
                                        "🗺 ${e.location}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        e.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (sel) MaterialTheme.colorScheme.onPrimaryContainer
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Spacer(Modifier.width(6.dp))
                    Button(onClick = { onConfirm(target) }, enabled = target != -1) {
                        Text("确认移动")
                    }
                }
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────
// 标记台账照片对话框：图标按钮 1 / 2
// ──────────────────────────────────────────────────────────────────
@Composable
private fun MarkLedgerDialog(
    onSlot1: () -> Unit,
    onSlot2: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "标记台账照片",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.SemiBold
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "为该事件选择一张照片作为台账记录：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = onSlot1,
                        Modifier.weight(1f),
                        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                        colors = ButtonDefaults.filledTonalButtonColors(),
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.primary,
                            shape = CircleShape,
                        ) {
                            Text(
                                "1",
                                Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.SemiBold
                                ),
                            )
                        }
                        Text(" 设为照片 1（最早）")
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = onSlot2,
                        Modifier.weight(1f),
                        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                        colors = ButtonDefaults.filledTonalButtonColors(),
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.tertiary,
                            shape = CircleShape,
                        ) {
                            Text(
                                "2",
                                Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                color = MaterialTheme.colorScheme.onTertiary,
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.SemiBold
                                ),
                            )
                        }
                        Text(" 设为照片 2（最晚）")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}

// ──────────────────────────────────────────────────────────────────
// 缩略图单元格：8dp 安全边距 + 2.5dp 主色边框 + 圆形对勾叠层 + 底部标准 Checkbox
// ──────────────────────────────────────────────────────────────────
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThumbCell(
    p: Photo,
    selected: Boolean,
    onToggle: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.padding(4.dp),
    ) {
        Thumb(
            p.sourceRef, p.displayName,
            selected = selected,
            onClick = onClick,
            onLongClick = onLongClick,
        )
        Row(
            Modifier.heightIn(min = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.Checkbox(
                checked = selected,
                onCheckedChange = { onToggle() },
            )
        }
    }
}

// ──────────────────────────────────────────────────────────────────
// 事件卡片
// ──────────────────────────────────────────────────────────────────
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EventCard(
    eventId: Int,
    title: String,
    time: String,
    location: String,
    description: String,
    photoCount: Int,
    marked: Pair<Long?, Long?>?,
    onOpenSheet: () -> Unit,
    photoThumbs: List<Photo>? = null,
    unclear: Boolean = false,
) {
    val errorColor = MaterialTheme.colorScheme.error
    // ★ 2026-10-07 需求：事件列表统一加边框——正常事件 1dp 轮廓边框；分类不明确（unclear）2dp 红框。
    //   点击边框内任意位置（整个卡片）→ onOpenSheet 直接弹窗查看该事件全部照片。
    val outer = Modifier
        .fillMaxWidth()
        .padding(2.dp)
        .then(
            if (unclear) Modifier.border(2.dp, errorColor, MaterialTheme.shapes.large)
            else Modifier.border(
                1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large
            )
        )

    Card(
        onClick = onOpenSheet,
        modifier = outer,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (unclear) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        "分类不明确 · 需人工复查",
                        Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = if (photoCount == 0) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        "${photoCount}张",
                        Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = if (photoCount == 0) MaterialTheme.colorScheme.onErrorContainer
                        else MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    modifier = Modifier.weight(1f),
                )
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Text(
                        time,
                        Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.padding(start = 2.dp),
            ) {
                Text(
                    "🗺 $location",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(description, style = MaterialTheme.typography.bodySmall)
            }
            if (photoThumbs != null && photoThumbs.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    photoThumbs.take(6).forEach { p ->
                        Thumb(
                            model = p.sourceRef,
                            name = p.displayName,
                            thumbSize = 56.dp,
                            // ★ 2026-10-07 修复：Thumb 内部有 combinedClickable，不传 onClick 会吞掉点击、
                            //   事件不冒泡到卡片 → 点缩略图无法进入照片弹窗。传 onOpenSheet 后点击缩略图同样打开弹窗。
                            onClick = onOpenSheet,
                        )
                    }
                    if (photoThumbs.size > 6) {
                        Box(
                            Modifier
                                .size(56.dp)
                                .background(
                                    MaterialTheme.colorScheme.surfaceContainerHighest,
                                    RoundedCornerShape(12.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "+${photoThumbs.size - 6}",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (marked != null) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(999.dp),
                ) {
                    Text(
                        "已人工标记 · 照片1 ${marked.first?.let { "✓" } ?: "-"} · 照片2 ${marked.second?.let { "✓" } ?: "-"}",
                        Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────
// Thumb：缩略图主体（方形圆角 · 2.5dp 选中边框 · 圆形对勾叠层 · 槽号标记）
// ──────────────────────────────────────────────────────────────────
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Thumb(
    model: String,
    name: String,
    selected: Boolean = false,
    markedSlot: Int? = null,
    thumbSize: Dp = 80.dp,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
) {
    val primary = MaterialTheme.colorScheme.primary
    // ★ W5(#3)：tile 与预加载共用 ThumbnailSpec（同尺寸 + 同缓存键），否则界面命中不到预加载结果
    //   LocalContext.current 是 @Composable 读，remember 的 calculation 禁 composable 调用，故先取出来
    val ctx = LocalContext.current
    val thumbReq = remember(ctx, model) { ThumbnailSpec.request(ctx, model) }
    Box(
        Modifier
            .padding(4.dp)
            .size(thumbSize)
            .aspectRatio(1f)
            .then(
                if (selected) Modifier.border(
                    width = 2.5.dp,
                    color = primary,
                    shape = RoundedCornerShape(14.dp),
                )
                else Modifier
            )
            .clip(RoundedCornerShape(if (selected) 11.dp else 14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        AsyncImage(
            model = thumbReq,
            contentDescription = name,
            modifier = Modifier.fillMaxSize(),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        )
        if (selected) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 6.dp, end = 6.dp)
                    .size(20.dp)
                    .background(primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Check, null,
                    Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
        if (markedSlot != null) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 6.dp, bottom = 6.dp)
                    .size(20.dp)
                    .background(
                        color = if (markedSlot == 1) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.tertiary,
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    markedSlot.toString(),
                    color = if (markedSlot == 1) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onTertiary,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                )
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────
// 时间格式化
// ──────────────────────────────────────────────────────────────────
private val fmt = DateTimeFormatter.ofPattern("HH:mm")
private fun fmtRange(start: Long?, end: Long?): String {
    val zone = algorithm.AppZone.ID
    val s = start?.let { Instant.ofEpochMilli(it).atZone(zone).format(fmt) } ?: return "无时间"
    val e = end ?: return s
    if (e == start) return s
    return "$s～${Instant.ofEpochMilli(e).atZone(zone).format(fmt)}"
}

// ──────────────────────────────────────────────────────────────────
// 单事件照片管理弹窗：Dialog + 置顶操作栏 + 3 列网格（点击多选）
// ──────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EventPhotosSheet(
    eventTitle: String,
    photos: List<Photo>,
    marked: Pair<Long?, Long?>?,
    events: List<LogEvent>,
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
// 目标事件选择弹窗（批量移动）：Dialog + 置顶搜索框 + 事件列表（点击即移动）
// ──────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoveTargetDialogWithSearch(
    title: String,
    events: List<LogEvent>,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var debouncedQuery by remember { mutableStateOf("") }
    LaunchedEffect(query) {
        if (query != debouncedQuery) {
            delay(300)
            debouncedQuery = query
        }
    }
    val filtered = remember(debouncedQuery, events) {
        val q = debouncedQuery.trim()
        if (q.isEmpty()) events
        else events.filter { e ->
            e.eventType.contains(q, ignoreCase = true) ||
            e.id.toString() == q ||
            e.location?.contains(q, ignoreCase = true) == true ||
            e.description?.contains(q, ignoreCase = true) == true
        }
    }
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
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text("搜索：事件名称 / 编号 / 桩号·收费站 / 描述") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Filled.Close, "清空搜索")
                            }
                        }
                    },
                    singleLine = true,
                    shape = MaterialTheme.shapes.large,
                )
                if (filtered.isEmpty()) {
                    Text(
                        "无匹配事件",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(filtered, key = { it.id }) { e ->
                            Surface(
                                onClick = { onConfirm(e.id) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.secondaryContainer,
                            ) {
                                Column(
                                    Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            e.eventType,
                                            style = MaterialTheme.typography.titleSmall.copy(
                                                fontWeight = FontWeight.SemiBold
                                            ),
                                            modifier = Modifier.weight(1f),
                                        )
                                        Surface(
                                            color = MaterialTheme.colorScheme.tertiaryContainer,
                                            shape = RoundedCornerShape(6.dp),
                                        ) {
                                            Text(
                                                fmtRange(e.startTimeMs, e.endTimeMs),
                                                Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                            )
                                        }
                                    }
                                    Text(
                                        "编号 ${e.id} · 🗺 ${e.location}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        e.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 2,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                }
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────
// 弹窗网格单元格
// 手势：浏览模式 单击=查看大图 / 长按=多选；多选模式 单击=切换选中
// ──────────────────────────────────────────────────────────────────
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoTile(
    p: Photo,
    selected: Boolean,
    selectionMode: Boolean,
    markedSlot: Int?,
    anomaly: PhotoAnomaly?,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .then(
                when {
                    selected -> Modifier.border(
                        width = 3.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(10.dp),
                    )
                    anomaly != null -> Modifier.border(
                        width = 2.dp,
                        color = MaterialTheme.colorScheme.error,
                        shape = RoundedCornerShape(10.dp),
                    )
                    else -> Modifier
                }
            )
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .combinedClickable(onClick = onTap, onLongClick = onLongPress),
    ) {
        // ★ W5(#3)：照片网格同样走 ThumbnailSpec，与预加载共用尺寸与内存缓存键
        val ctx = LocalContext.current
        val thumbReq = remember(ctx, p.sourceRef) { ThumbnailSpec.request(ctx, p.sourceRef) }
        AsyncImage(
            model = thumbReq,
            contentDescription = p.displayName,
            modifier = Modifier.fillMaxSize(),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        )
        if (selected) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
            Icon(
                Icons.Filled.Check, null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(22.dp),
            )
        }
        if (anomaly != null && !selected) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp),
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.error,
            ) {
                Row(
                    Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Warning, anomaly.detail,
                        Modifier.size(10.dp),
                        tint = MaterialTheme.colorScheme.onError,
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(
                        anomaly.label,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = MaterialTheme.colorScheme.onError,
                    )
                }
            }
        }
        if (markedSlot != null) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 6.dp, bottom = 6.dp)
                    .size(20.dp)
                    .background(
                        color = if (markedSlot == 1) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.tertiary,
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    markedSlot.toString(),
                    color = if (markedSlot == 1) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onTertiary,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                )
            }
        }
        p.captureTimeMs?.let { ms ->
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 5.dp, bottom = 5.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            ) {
                Text(
                    tileTimeFmt.format(
                        Instant.ofEpochMilli(ms).atZone(algorithm.AppZone.ID)
                    ),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = Color.White,
                )
            }
        }
    }
}

private val tileTimeFmt = DateTimeFormatter.ofPattern("HH:mm")

// ──────────────────────────────────────────────────────────────────
// 照片网格（3 列）
// ──────────────────────────────────────────────────────────────────
@Composable
internal fun PhotoGrid(
    photos: List<Photo>,
    selected: Set<Long>,
    marked: Pair<Long?, Long?>?,
    anomalies: Map<Long, PhotoAnomaly>,
    onTap: (Photo, Boolean) -> Unit,
    onLongPress: (Photo) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        gridItems(photos, key = { it.id }) { p ->
            val isSel = p.id in selected
            val slot = when {
                marked?.first == p.id -> 1
                marked?.second == p.id -> 2
                else -> null
            }
            PhotoTile(
                p = p,
                selected = isSel,
                selectionMode = selected.isNotEmpty(),
                markedSlot = slot,
                anomaly = anomalies[p.id],
                onTap = { onTap(p, isSel) },
                onLongPress = { onLongPress(p) },
            )
        }
    }
}

// ──────────────────────────────────────────────────────────────────
// 未匹配照片管理弹窗：Dialog + 置顶操作栏 + 3 列网格点击多选 + 批量移动
// ──────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UnmatchedPhotosSheet(
    photos: List<Photo>,
    events: List<LogEvent>,
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
