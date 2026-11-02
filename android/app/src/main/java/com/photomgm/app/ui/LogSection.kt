// app/ui/LogSection.kt —— 日志解析结果区（事件卡片列表）
// 内容：解析汇总条 → 筛选条 → 事件卡片列表 → 未匹配照片池入口
// ★ 2026-10-07 UI 重建：功能等价。
package com.photomgm.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.photomgm.app.AppViewModel
import java.time.Instant
import java.time.format.DateTimeFormatter

// ──────────────────────────────────────────────────────────────────
// 日志解析结果区：解析汇总 + 筛选 + 事件卡片列表 + 未匹配池入口
// ──────────────────────────────────────────────────────────────────
@Composable
fun LogSection(
    vm: AppViewModel,
    onOpenEventSheet: (Int) -> Unit,
    onOpenUnmatchedSheet: () -> Unit,
) {
    val state by vm.state.collectAsState()
    var filterMode by rememberSaveable { mutableStateOf(FilterMode.ALL) }

    // 尚未解析：不渲染任何内容（输入区已给出引导）
    val parsed = state.parsed ?: return

    val hasSkip = parsed.skippedLines.isNotEmpty()

    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ── 解析汇总条 ──
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (hasSkip) MaterialTheme.colorScheme.tertiaryContainer
                else MaterialTheme.colorScheme.primaryContainer,
            ),
            shape = MaterialTheme.shapes.large,
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.ReceiptLong, null, Modifier.size(20.dp),
                        tint = if (hasSkip) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        " 解析出 ${parsed.events.size} 条事件" +
                                if (hasSkip) " · 未识别 ${parsed.skippedLines.size} 行" else "",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = if (hasSkip) MaterialTheme.colorScheme.onTertiaryContainer
                        else MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                parsed.inspectors?.let {
                    Text(
                        "巡查人员：$it",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (hasSkip) MaterialTheme.colorScheme.onTertiaryContainer
                        else MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                parsed.vehicle?.let {
                    Text(
                        "车牌：$it",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (hasSkip) MaterialTheme.colorScheme.onTertiaryContainer
                        else MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }

        if (hasSkip) {
            AssistChip(
                onClick = {},
                leadingIcon = {
                    Icon(
                        Icons.Filled.Error, null, Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
                label = {
                    Text(
                        "未识别行 ${parsed.skippedLines.size}（请在上方文本框修正后重新解析）",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                },
            )
        }

        // ── 分类结果：事件卡片列表 ──
        val classify = state.classify
        if (classify == null) {
            Text(
                "照片尚未就绪，事件卡片暂无法显示照片。" +
                        "请在设置中确认已选择照片目录，且所选日期下有照片。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            // ★ 2026-10-24 B1：预览用三态展开后的事件照片表（含人工移动），与导出一致；
            //   remember key 含 dedupKeepMode —— 三态切换即时重建卡片照片数与缩略图
            // ★ 2026-10-31 审查修复：key 必须含 state.marked —— expandedEventMap 会把 marked 展平为
            //   keepAlways（三态优先级最高，能把被折叠掉的照片重新纳入结果）。此前 key 只有
            //   classify / overrideMap / dedupKeepMode，在弹窗里「设为台账」后 marked 变了而这三者都没变，
            //   remember 返回旧 Map → 卡片照片数与缩略图停留在标记前，与弹窗内实时算出的视图不一致。
            val effective = remember(classify, state.overrideMap, state.dedupKeepMode, state.marked) {
                vm.previewEventMap(classify)
            }
            val effectiveUnmatched = remember(classify, state.overrideMap) { vm.effectiveUnmatched(classify) }
            val photosById = remember(state.photos) { state.photos.associateBy { it.id } }
            val pendingEvents = parsed.events.filter { it.id in classify.pendingEventIds }
            val normalEvents = parsed.events.filter { it.id !in classify.pendingEventIds }

            // 筛选条
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChip(
                    selected = filterMode == FilterMode.ALL,
                    onClick = { filterMode = FilterMode.ALL },
                    label = { Text("全部 ${parsed.events.size}") },
                )
                FilterChip(
                    selected = filterMode == FilterMode.NORMAL,
                    onClick = { filterMode = FilterMode.NORMAL },
                    label = { Text("正常 ${normalEvents.size}") },
                )
                FilterChip(
                    selected = filterMode == FilterMode.PENDING,
                    onClick = { filterMode = FilterMode.PENDING },
                    label = { Text("待复查 ${pendingEvents.size}") },
                )
                FilterChip(
                    selected = filterMode == FilterMode.UNMATCHED,
                    onClick = { filterMode = FilterMode.UNMATCHED },
                    label = { Text("未匹配 ${effectiveUnmatched.size}") },
                )
            }

            // 待复查事件区
            if (pendingEvents.isNotEmpty() &&
                (filterMode == FilterMode.ALL || filterMode == FilterMode.PENDING)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "待复查事件（${pendingEvents.size}）",
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    pendingEvents.forEach { e ->
                        val photoIds = effective[e.id] ?: emptyList()
                        EventCard(
                            eventId = e.id,
                            title = e.eventType,
                            time = fmtRange(e.startTimeMs, e.endTimeMs),
                            location = e.location ?: "",
                            description = e.description ?: "",
                            photoCount = photoIds.size,
                            marked = state.marked[e.id],
                            onOpenSheet = { onOpenEventSheet(e.id) },
                            photoThumbs = photoIds.mapNotNull { photosById[it] },
                            // ★ 分类不明确 → 整卡红框提示
                            unclear = true,
                        )
                    }
                }
            }

            // 正常事件区
            if (filterMode == FilterMode.ALL || filterMode == FilterMode.NORMAL) {
                if (normalEvents.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (filterMode == FilterMode.ALL && pendingEvents.isNotEmpty()) {
                            Text(
                                "正常事件（${normalEvents.size}）",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        normalEvents.forEach { e ->
                            val photoIds = effective[e.id] ?: emptyList()
                            EventCard(
                                eventId = e.id,
                                title = e.eventType,
                                time = fmtRange(e.startTimeMs, e.endTimeMs),
                                location = e.location ?: "",
                                description = e.description ?: "",
                                photoCount = photoIds.size,
                                marked = state.marked[e.id],
                                onOpenSheet = { onOpenEventSheet(e.id) },
                                photoThumbs = photoIds.mapNotNull { photosById[it] },
                            )
                        }
                    }
                } else {
                    Text(
                        "无正常事件",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }

            // 未匹配照片池入口
            if (effectiveUnmatched.isNotEmpty() &&
                (filterMode == FilterMode.ALL || filterMode == FilterMode.UNMATCHED)
            ) {
                Card(
                    onClick = onOpenUnmatchedSheet,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                ) {
                    Column(
                        Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.PhotoLibrary, null, Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                " 未匹配照片（${effectiveUnmatched.size}）",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.SemiBold
                                ),
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "点击管理 ›",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Text(
                            "3 列浏览 / 点击多选 / 批量移动到对应事件",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private val tf = DateTimeFormatter.ofPattern("HH:mm")
private fun hm(ms: Long?): String =
    ms?.let {
        Instant.ofEpochMilli(it).atZone(algorithm.AppZone.ID).format(tf)
    } ?: "无时间"

private fun fmtRange(start: Long?, end: Long?): String =
    if (start != null && end != null) "${hm(start)}～${hm(end)}" else "无时间"
