// app/ui/UnifiedScreen.kt —— 单页纵向流水线
// 布局（自上而下）：
//   ① AppTopBar       巡查日期选择 + 设置按钮（最顶端）
//   ② SourceDirWarning 未设置源目录时的提示（条件渲染）
//   ③ LogInputSection 可编辑日志文本框 + 粘贴导入 + 解析日志（固定）
//   ④ ProgressStrip   2 步进度条，点击跳转分区
//   ⑤ LazyColumn      解析结果：事件卡片列表
//   ⑥ BottomActionBar 底部常驻导出条
// ★ 2026-10-07 UI 重建：
//   - 日期/设置弹窗由 Box+Surface 全屏覆盖层改为 Dialog 平台窗口（真机验证正常机制）
//   - pendingDate 用 epochDay(Long) 保存（LocalDate 不可 Parcelable，真机保存会抛异常）
package com.photomgm.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.photomgm.app.AppViewModel
import kotlinx.coroutines.launch
import java.time.LocalDate

// LazyColumn item 索引（0=日志输入区，1=事件列表）
// ★ 这些常量必须与下面 item 的排列顺序一致；ProgressStrip 的跳转索引也依赖它们。
internal const val IDX_LOG_INPUT = 0
internal const val IDX_LOGS = 1
internal const val IDX_EXPORT = 2

@Composable
fun UnifiedScreen(vm: AppViewModel) {
    val state by vm.state.collectAsState()
    val listState = rememberLazyListState()

    val scope = rememberCoroutineScope()

    // ★ 日期弹窗状态：pendingDate 用 epochDay（Long）保存——
    //   LocalDate 不可 Parcelable，rememberSaveable 在真机 onSaveInstanceState 时抛异常。
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var pendingEpochDay by rememberSaveable { mutableLongStateOf(state.date.toEpochDay()) }

    // 设置弹窗状态
    var showSettings by rememberSaveable { mutableStateOf(false) }

    // 照片管理弹窗状态（事件照片 / 未匹配照片）
    var openSheetEventId by rememberSaveable { mutableStateOf<Int?>(null) }
    var openUnmatchedSheet by rememberSaveable { mutableStateOf(false) }

    // 日志输入区收起态：解析成功后自动收起，把版面让给事件列表
    var logInputCollapsed by rememberSaveable { mutableStateOf(false) }

    // 被动触发兜底
    LaunchedEffect(Unit) { vm.ensurePipeline() }

    // 解析完成 → 自动收起输入区并滚动到事件列表
    val parsedEventCount = state.parsed?.events?.size
    LaunchedEffect(parsedEventCount) {
        if (state.parsed != null) {
            logInputCollapsed = true
            listState.animateScrollToItem(IDX_LOGS)
        }
    }

    fun jumpTo(index: Int) {
        when (index) {
            IDX_LOGS   -> scope.launch { listState.animateScrollToItem(IDX_LOGS) }
            IDX_EXPORT -> scope.launch { listState.animateScrollToItem(IDX_EXPORT) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        // ① 最顶端：左侧「巡查日期」选择器 + 右侧「设置」按钮（固定）
        AppTopBar(
            dateText = state.date.toString(),
            onDateClick = { showDatePicker = true },
            onSettingsClick = { showSettings = true },
        )

        // ② 未设置源目录提示（固定，条件渲染）
        if (state.sourceDirs.isEmpty()) {
            SourceDirWarning(onOpenSettings = { showSettings = true })
        }

        // ③ 流水线进度条（固定，点击跳转分区）
        ProgressStrip(state, onStepClick = ::jumpTo)

        // ④ 可滚动区：日志输入区（解析后收起）→ 事件卡片列表
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().imePadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        ) {
            item(key = "logInput") {
                LogInputSection(
                    logsText = state.logsText,
                    onTextChange = { vm.setLogsText(it) },
                    onParse = { vm.parseLogs() },
                    onClear = { vm.clearLogs() },
                    busy = state.busy,
                    collapsed = logInputCollapsed,
                    onToggleCollapse = { logInputCollapsed = !logInputCollapsed },
                    parseSummary = state.parsed?.let { p ->
                        "已解析 ${p.events.size} 条事件" +
                                if (p.skippedLines.isNotEmpty()) " · 未识别 ${p.skippedLines.size} 行" else ""
                    },
                )
            }

            item(key = "logs") {
                LogSection(
                    vm = vm,
                    onOpenEventSheet = { openSheetEventId = it },
                    onOpenUnmatchedSheet = { openUnmatchedSheet = true },
                )
            }
        }

        // ⑤ 底部常驻操作条：导出始终可见
        // ★ 2026-10-31 审查修复：未匹配计数统一取「有效未匹配」（扣除已人工移入事件的、加入被人工移除的），
        //   与列表页（LogSection）和 ZIP 导出口径一致。此前用 classify.unmatched 原始值，
        //   人工把 3 张照片移入事件后底栏仍显示旧数字，用户据此判断"哪些不入台账"必然误判。
        val effectiveUnmatched = state.classify?.let { vm.effectiveUnmatched(it) } ?: emptyList()
        val gate: ExportGate = when {
            state.sourceDirs.isEmpty() -> ExportGate.NoSourceDir
            state.parsed == null -> ExportGate.NoLogs
            state.classify == null -> ExportGate.NoPhotos
            else -> ExportGate.Ready(
                unmatched = effectiveUnmatched.size,
            )
        }
        BottomActionBar(
            gate = gate,
            ledgerRows = state.ledger?.size,
            pendingEvents = state.classify?.pendingEventIds?.size ?: 0,
            unmatchedPhotos = effectiveUnmatched.size,
            progress = state.exportProgress,
            doneMessage = state.exportMessage,
            exportOk = state.exportOk,
            onExport = { vm.exportZip() },
            onOpenExportResult = if (state.lastExport != null) {
                { vm.openExportedFile() }
            } else null,
            onFix = {
                when (gate) {
                    ExportGate.NoSourceDir, ExportGate.NoPhotos -> showSettings = true
                    ExportGate.NoLogs -> {
                        logInputCollapsed = false
                        scope.launch { listState.animateScrollToItem(IDX_LOG_INPUT) }
                    }
                    is ExportGate.Ready -> Unit
                }
            },
        )
    }

    // ── 日期选择弹窗（Dialog 平台窗口）──
    if (showDatePicker) {
        DatePickerDialog(
            initialDate = LocalDate.ofEpochDay(pendingEpochDay),
            onSelect = { pendingEpochDay = it.toEpochDay() },
            onCancel = { showDatePicker = false },
            onConfirm = {
                vm.setDate(LocalDate.ofEpochDay(pendingEpochDay))
                showDatePicker = false
            },
        )
    }

    // ── 设置弹窗 ──
    if (showSettings) {
        SettingsDialog(
            vm = vm,
            onDismiss = { showSettings = false },
        )
    }

    // ── 事件照片管理弹窗 ──
    openSheetEventId?.let { eventId ->
        val parsed = state.parsed
        val classify = state.classify
        if (parsed != null && classify != null) {
            val event = parsed.events.find { it.id == eventId }
            // ★ 2026-10-24 B1：事件照片弹窗用三态展开后的列表（与导出一致），切换三态即时生效
            val effective = vm.previewEventMap(classify)
            val photoIds = effective[eventId] ?: emptyList()
            val photos = photoIds.mapNotNull { pid -> state.photos.find { it.id == pid } }
            if (event != null) {
                val anomalies = remember(classify) { buildAnomalyMap(classify.interventions) }
                EventPhotosSheet(
                    eventTitle = event.eventType,
                    photos = photos,
                    marked = state.marked[eventId],
                    events = parsed.events,
                    onMove = { ids, target -> vm.movePhotos(ids, target) },
                    onRemoveFromEvent = { ids -> vm.removePhotosFromEvent(ids) },
                    onMark = { photoId, slot -> vm.markLedgerPhoto(eventId, slot, photoId) },
                    onClearMark = { slot -> vm.markLedgerPhoto(eventId, slot, null) },
                    onNotify = { msg -> vm.notify(msg) },
                    onDismiss = { openSheetEventId = null },
                    eventLocation = event.location ?: "",
                    eventTime = fmtEventRange(event.startTimeMs, event.endTimeMs),
                    anomalies = anomalies,
                    readOnly = state.exportProgress != null,
                )
            }
        }
    }

    // ── 未匹配照片管理弹窗 ──
    if (openUnmatchedSheet) {
        val parsed = state.parsed
        val classify = state.classify
        if (parsed != null && classify != null) {
            val unmatched = vm.effectiveUnmatched(classify)
            val photos = unmatched.mapNotNull { pid -> state.photos.find { it.id == pid } }
            UnmatchedPhotosSheet(
                photos = photos,
                events = parsed.events,
                onMove = { ids, target -> vm.movePhotos(ids, target) },
                onDismiss = { openUnmatchedSheet = false },
            )
        }
    }
}

// ──────────────────────────────────────────────────────────────────
// 日期选择弹窗（Dialog 平台窗口 + 自绘日历）
// ──────────────────────────────────────────────────────────────────
@Composable
private fun DatePickerDialog(
    initialDate: LocalDate,
    onSelect: (LocalDate) -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 460.dp)
                .padding(horizontal = 16.dp),
        ) {
            Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                AdaptiveDatePicker(
                    initialDate = initialDate,
                    onDateSelected = onSelect,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onCancel) { Text("取消") }
                    TextButton(onClick = onConfirm) { Text("确定") }
                }
            }
        }
    }
    BackHandler { onCancel() }
}

// ──────────────────────────────────────────────────────────────────
// 分类异常 → 照片角标映射
// ──────────────────────────────────────────────────────────────────
private fun buildAnomalyMap(
    interventions: List<algorithm.Intervention>,
): Map<Long, PhotoAnomaly> {
    val out = LinkedHashMap<Long, PhotoAnomaly>()
    for (i in interventions) {
        val label = when (i.kind) {
            algorithm.Kind.GPS_FAR -> "定位远"
            algorithm.Kind.CLUSTER_SPLIT -> "已拆簇"
            algorithm.Kind.TIME_OVERLAP -> "时间重叠"
            algorithm.Kind.CLUSTER_SPAN -> "跨度大"
            algorithm.Kind.NO_PHOTO -> "缺照片"
        }
        for (pid in i.photoIds) {
            out.putIfAbsent(pid, PhotoAnomaly(label = label, detail = i.message))
        }
    }
    return out
}

private val eventTimeFmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm")

private fun fmtEventRange(start: Long?, end: Long?): String {
    val zone = algorithm.AppZone.ID
    val s = start?.let {
        java.time.Instant.ofEpochMilli(it).atZone(zone).format(eventTimeFmt)
    } ?: return ""
    val e = end ?: return s
    if (e == start) return s
    return "$s～${java.time.Instant.ofEpochMilli(e).atZone(zone).format(eventTimeFmt)}"
}

// ──────────────────────────────────────────────────────────────────
// 未设置源目录提示
// ──────────────────────────────────────────────────────────────────
@Composable
private fun SourceDirWarning(onOpenSettings: () -> Unit) {
    Card(
        onClick = onOpenSettings,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Warning, null,
                Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "未设置源目录",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    "无法读取该日期的照片。点此选择照片目录",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Text(
                "去设置 ›",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}
