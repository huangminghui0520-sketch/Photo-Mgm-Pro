// desktop/ui/workspace/WorkspaceScreen.kt —— U1 三栏骨架
//
// 这一版的目标不是"好看"，是**证明三栏在 900 dp 下真的排得开、且吃的是真实 AppState**。
// 内容还是骨架（缩略图在 U1.3、交互在 U2–U4），但每一块都绑到了核心的真实字段上：
//   左栏 → settings.sourceDirs / state.parsed / settings.namingTemplate / settings.dedupMode
//   中栏 → state.photos（含 isDerived 角标）
//   右栏 → state.events[].hints / photoIds
//   状态栏 → state.status / progress / message
// 所以"骨架能显示 AppState 真实数据"这条 U1 验收，是能拿日志证明的，不是口头承诺。
//
// ★ 布局规则全部来自 Workspace.metrics()，本文件不写任何 if (width < N) 的判断。
//   理由：尺寸决策要能被单测覆盖，散在 Composable 里就只能人工拖窗口验证。
package com.photomgm.desktop.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import algorithm.DedupKeepMode
import com.photomgm.desktop.core.api.AppState
import com.photomgm.desktop.core.api.CoreStatus
import com.photomgm.desktop.core.api.EventSnapshot
import com.photomgm.desktop.core.api.PhotoInfo
import com.photomgm.desktop.ui.design.PmColor
import com.photomgm.desktop.ui.design.PmRadius
import com.photomgm.desktop.ui.design.PmSize
import com.photomgm.desktop.ui.design.PmSpace
import com.photomgm.desktop.ui.design.PmType
import com.photomgm.desktop.ui.layout.Workspace
import com.photomgm.desktop.ui.layout.WorkspaceMetrics
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ★ 业务时区：AppState 的时间字段是 epoch 毫秒，其注释明确"由 UI 自行按 AppZone.ID 格式化"。
//   这里用的是 api 契约里指定的常量，不是越层去调 logic/platform。
private val ZONE: ZoneId = algorithm.AppZone.ID
private val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

@Composable
fun WorkspaceScreen(
    state: AppState,
    onToggleRail: () -> Unit,
    /** 打印列数/栏宽等开发信息。默认关闭 —— 这些数字对巡查员没有意义，只是走查时的标尺。 */
    debugLayout: Boolean = false,
) {
    // 用户偏好"想展开"；实际是否展开由宽度裁决（Workspace.metrics）。
    var railRequested by remember { mutableStateOf(true) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(PmColor.canvas)) {
        val widthDp = maxWidth.value.toInt()
        val metrics = Workspace.metrics(widthDp, railRequested)

        // 首帧与每次尺寸变化都打一行，用来证明"这个宽度下确实排成了这样"。
        LaunchedEffect(metrics) { println("[UI] layout $metrics") }

        Column(Modifier.fillMaxSize()) {
            TopBar(
                state = state,
                metrics = metrics,
                onToggleRail = {
                    railRequested = !metrics.railExpanded
                    onToggleRail()
                },
            )

            Row(Modifier.fillMaxWidth().weight(1f)) {
                SourceRail(state, metrics)

                VDivider()

                PhotoGridArea(state, metrics, debugLayout, Modifier.weight(1f))

                if (metrics.detailWidth > 0) {
                    VDivider()
                    EventPanel(state, metrics)
                }
            }

            StatusBar(state)
        }
    }
}

// ────────────────────────────────────────────────────────────── 顶栏

@Composable
private fun TopBar(state: AppState, metrics: WorkspaceMetrics, onToggleRail: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(PmSize.topBar).background(PmColor.world)
            .padding(horizontal = PmSpace.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PmSpace.s),
    ) {
        RailToggle(metrics, onToggleRail)

        // 应用名已在系统标题栏上，这里再写一遍等于浪费一整行高度。
        // 顶栏空间用来放"这一屏在处理哪一天"，这才是巡查员实际要确认的信息。
        Text("巡查日期", style = PmType.label, color = PmColor.onWorldDim)
        Text(
            state.settings.patrolDate.toString(),
            style = PmType.numeric.copy(color = Color.White),
        )

        StatusChip(state.status)

        Spacer(Modifier.weight(1f))

        // 计数用等宽数字：切换日期时数字不左右跳。
        Text(
            "事件 ${state.events.size}　照片 ${state.photos.size}　未匹配 ${state.unmatchedPhotoIds.size}",
            style = PmType.numeric,
            color = PmColor.onWorldDim,
        )
    }
}

@Composable
private fun RailToggle(metrics: WorkspaceMetrics, onToggle: () -> Unit) {
    val enabled = metrics.railCanExpand
    Box(
        modifier = Modifier
            .size(PmSize.hitMin)
            .clip(RoundedCornerShape(PmRadius.control))
            .background(if (enabled) PmColor.accentWash else Color.Transparent)
            .then(if (enabled) Modifier.flatClick(onToggle) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = when {
                !metrics.railExpanded -> Icons.Filled.Menu
                else -> Icons.Filled.KeyboardArrowLeft
            },
            contentDescription = if (metrics.railExpanded) "折叠左栏" else "展开左栏",
            tint = if (enabled) Color.White else PmColor.onWorldDim.copy(alpha = 0.4f),
            modifier = Modifier.size(20.dp),
        )
    }
}

// ────────────────────────────────────────────────────────────── 左栏

@Composable
private fun SourceRail(state: AppState, metrics: WorkspaceMetrics) {
    Column(
        modifier = Modifier.width(metrics.railWidth.dp).fillMaxHeight()
            .background(PmColor.surface)
            .padding(horizontal = if (metrics.railExpanded) PmSpace.m else PmSpace.xs),
        verticalArrangement = Arrangement.spacedBy(PmSpace.m),
    ) {
        Spacer(Modifier.height(PmSpace.xs))

        if (!metrics.railExpanded) {
            // 折叠态：只剩图标轨。点击展开按钮在顶栏，这里不做交互，避免两种入口抢同一个状态。
            CollapsedRailMarker()
            return@Column
        }

        RailSection("源目录", "${state.settings.sourceDirs.size} 个") {
            if (state.settings.sourceDirs.isEmpty()) {
                EmptyHint("未选择源目录")
            } else {
                state.settings.sourceDirs.forEach { dir ->
                    Text(
                        dir,
                        style = PmType.bodySmall,
                        color = PmColor.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        RailSection("巡查日志", if (state.logText.isBlank()) "空" else "${state.logText.lineSequence().count()} 行") {
            val parsed = state.parsed
            if (parsed == null) {
                EmptyHint("未导入日志")
            } else {
                KeyValue("解析事件", "${parsed.eventCount}")
                KeyValue("跳过行数", "${parsed.skippedLineCount}")
                parsed.inspectors?.let { KeyValue("巡查人员", it) }
                parsed.recorder?.let { KeyValue("记录人", it) }
                parsed.vehicle?.let { KeyValue("车牌", it) }
                parsed.notices.forEach {
                    Text(it, style = PmType.bodySmall, color = PmColor.warn)
                }
            }
        }

        RailSection("设置", null) {
            KeyValue("命名模板", if (state.settings.namingTemplate == 0) "事件类型" else "月日+日志内容")
            KeyValue("去重策略", dedupLabel(state))
            KeyValue("巡查日期", state.settings.patrolDate.toString())
        }
    }
}

@Composable
private fun CollapsedRailMarker() {
    // 56 dp 图标轨：U2/U3 会把源/日志/设置各挂一个入口；U1 只放占位锚点，
    // 让"折叠后左栏还在"这件事在视觉上成立（否则中间栏会突然贴到屏幕左边）。
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PmSpace.s),
    ) {
        listOf("源", "志", "设").forEach { glyph ->
            Box(
                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(PmRadius.control))
                    .background(PmColor.surface2),
                contentAlignment = Alignment.Center,
            ) {
                Text(glyph, style = PmType.label, color = PmColor.dim)
            }
        }
    }
}

@Composable
private fun RailSection(title: String, trailing: String?, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(PmSpace.xs)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = PmType.label, color = PmColor.dim)
            Spacer(Modifier.weight(1f))
            trailing?.let { Text(it, style = PmType.label, color = PmColor.dim) }
        }
        content()
    }
}

@Composable
private fun KeyValue(key: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(PmSpace.xs)) {
        Text(key, style = PmType.bodySmall, color = PmColor.dim, modifier = Modifier.width(68.dp))
        Text(value, style = PmType.bodySmall, color = PmColor.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(text, style = PmType.bodySmall, color = PmColor.dim)
}

// ────────────────────────────────────────────────────────────── 中栏：照片网格

@Composable
private fun PhotoGridArea(
    state: AppState,
    metrics: WorkspaceMetrics,
    debugLayout: Boolean,
    modifier: Modifier,
) {
    Column(modifier.fillMaxHeight()) {
        SectionHeader(
            title = "照片",
            count = state.photos.size,
            note = if (debugLayout) "${metrics.gridColumns} 列 · 中栏 ${metrics.gridWidth} dp" else null,
        )

        if (state.photos.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(PmSpace.s)) {
                    Text("还没有照片", style = PmType.headline, color = PmColor.ink)
                    Text(
                        "在左栏选择源目录并导入巡查日志后开始分析",
                        style = PmType.bodySmall,
                        color = PmColor.dim,
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(metrics.gridColumns),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(PmSpace.m),
                horizontalArrangement = Arrangement.spacedBy(PmSpace.xs),
                verticalArrangement = Arrangement.spacedBy(PmSpace.xs),
            ) {
                gridItems(state.photos, key = { it.id }) { photo -> PhotoTile(photo) }
            }
        }
    }
}

@Composable
private fun PhotoTile(photo: PhotoInfo) {
    Column {
        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f)
                .clip(RoundedCornerShape(PmRadius.control))
                .background(PmColor.surface2)
                .then(if (photo.isDerived) Modifier.border(2.dp, PmColor.warn, RoundedCornerShape(PmRadius.control)) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            // 缩略图在 U1.3 接 PcThumbnailProvider（受控加载 + LRU + 可取消）。
            // 现在显示文件名，是为了让格子"看起来是照片卡"而不是一块碎掉的图。
            Text(
                photo.displayName,
                style = PmType.bodySmall,
                color = PmColor.dim,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(PmSpace.s),
            )
        }
        Spacer(Modifier.height(PmSpace.xs))
        Text(
            captureTimeText(photo),
            style = PmType.numeric.copy(fontSize = PmType.bodySmall.fontSize),
            color = if (photo.hasGps) PmColor.ink else PmColor.dim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun captureTimeText(photo: PhotoInfo): String {
    val ms = photo.captureTimeMs ?: return if (photo.hasGps) "无时间" else "无时间 · 无 GPS"
    val t = Instant.ofEpochMilli(ms).atZone(ZONE)
    return if (photo.hasGps) t.format(HHMM) else "${t.format(HHMM)} · 无 GPS"
}

// ────────────────────────────────────────────────────────────── 右栏：事件

@Composable
private fun EventPanel(state: AppState, metrics: WorkspaceMetrics) {
    Column(Modifier.width(metrics.detailWidth.dp).fillMaxHeight().background(PmColor.surface)) {
        SectionHeader(title = "事件", count = state.events.size, note = null, padding = PmSpace.m)

        if (state.events.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无事件", style = PmType.bodySmall, color = PmColor.dim)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = PmSpace.m, end = PmSpace.m, bottom = PmSpace.m),
                verticalArrangement = Arrangement.spacedBy(PmSpace.s),
            ) {
                items(state.events, key = { it.id }) { event -> EventCard(event) }
            }
        }
    }
}

@Composable
private fun EventCard(event: EventSnapshot) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(PmRadius.card))
            .background(PmColor.canvas)
            .padding(PmSpace.m),
        verticalArrangement = Arrangement.spacedBy(PmSpace.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PmSpace.s)) {
            SeqBadge(event.id)
            Text(event.eventType, style = PmType.title, color = PmColor.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.weight(1f))
            CountBadge(event.photoIds.size)
        }

        Text(event.location, style = PmType.bodySmall, color = PmColor.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)

        Row(horizontalArrangement = Arrangement.spacedBy(PmSpace.xs)) {
            Text(timeRange(event), style = PmType.numeric.copy(fontSize = PmType.bodySmall.fontSize), color = PmColor.dim)
        }

        if (event.isPending) {
            Text("待定事件（无时间）· 需人工指定照片", style = PmType.label, color = PmColor.warn)
        }
        event.hints.forEach { hint ->
            Text(hint, style = PmType.label, color = PmColor.warn, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun timeRange(event: EventSnapshot): String {
    fun fmt(ms: Long?): String = ms?.let { Instant.ofEpochMilli(it).atZone(ZONE).format(HHMM) } ?: "--:--"
    // 起点等于终点时只写一次：日志里同一分钟内的两条记录会产生 08:30 – 08:30，
    // 读起来像"持续了零分钟"，实际是"这一分钟内完成"。
    return if (event.endTimeMs == null || event.startTimeMs == event.endTimeMs) {
        fmt(event.startTimeMs).takeIf { event.startTimeMs != null } ?: "--:--"
    } else {
        "${fmt(event.startTimeMs)} – ${fmt(event.endTimeMs)}"
    }
}

@Composable
private fun SeqBadge(id: Int) {
    Box(
        modifier = Modifier.size(22.dp).clip(RoundedCornerShape(PmRadius.control)).background(PmColor.accentWash),
        contentAlignment = Alignment.Center,
    ) {
        Text("$id", style = PmType.numeric.copy(fontSize = PmType.label.fontSize), color = PmColor.accentText)
    }
}

@Composable
private fun CountBadge(count: Int) {
    Box(
        modifier = Modifier.clip(RoundedCornerShape(PmRadius.control))
            .background(if (count > 0) PmColor.okWash else PmColor.warnWash)
            .padding(horizontal = PmSpace.s, vertical = 2.dp),
    ) {
        Text(
            if (count > 0) "$count 张" else "无照片",
            style = PmType.label,
            color = if (count > 0) PmColor.ok else PmColor.warn,
        )
    }
}

// ────────────────────────────────────────────────────────────── 状态栏

@Composable
private fun StatusBar(state: AppState) {
    Row(
        modifier = Modifier.fillMaxWidth().height(PmSize.statusBar).background(PmColor.world)
            .padding(horizontal = PmSpace.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PmSpace.m),
    ) {
        val p = state.progress
        val text = when {
            state.error != null -> state.error!!
            state.message != null -> state.message!!
            p != null -> "${p.stage} ${p.current}/${p.total}"
            state.status == CoreStatus.IDLE -> "就绪 · 等待导入日志"
            state.status == CoreStatus.READY -> "分析完成 · 可预览与导出"
            else -> state.status.name
        }
        Text(text, style = PmType.bodySmall, color = PmColor.onWorldDim, maxLines = 1, overflow = TextOverflow.Ellipsis)

        Spacer(Modifier.weight(1f))

        state.analysis?.let { a ->
            Text(
                "匹配 ${a.matchedCount}/${a.photoCount}　无 GPS ${a.noGpsCount}　HEIC ${a.heicCount}",
                style = PmType.numeric.copy(fontSize = PmType.bodySmall.fontSize),
                color = PmColor.onWorldDim,
            )
        }
    }
}

// ────────────────────────────────────────────────────────────── 通用件

@Composable
private fun SectionHeader(title: String, count: Int, note: String?, padding: Dp = PmSpace.m) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = padding, vertical = PmSpace.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PmSpace.s),
    ) {
        Text(title, style = PmType.title, color = PmColor.ink)
        Text("$count", style = PmType.numeric, color = PmColor.dim)
        Spacer(Modifier.weight(1f))
        note?.let { Text(it, style = PmType.label, color = PmColor.dim) }
    }
}

@Composable
private fun StatusChip(status: CoreStatus) {
    val (label, color) = when (status) {
        CoreStatus.IDLE -> "待导入" to PmColor.onWorldDim
        CoreStatus.ANALYZING -> "分析中" to PmColor.accentBrand
        CoreStatus.READY -> "已就绪" to PmColor.onWorldDim
        CoreStatus.EXPORTING -> "导出中" to PmColor.accentBrand
        CoreStatus.FAILED -> "失败" to PmColor.bad
        CoreStatus.CLOSED -> "已关闭" to PmColor.onWorldDim
    }
    Box(
        modifier = Modifier.clip(RoundedCornerShape(PmRadius.control))
            .background(color.copy(alpha = 0.18f))
            .padding(horizontal = PmSpace.s, vertical = 2.dp),
    ) {
        Text(label, style = PmType.label, color = Color.White)
    }
}

@Composable
private fun VDivider() {
    Box(Modifier.width(Workspace.DIVIDER.dp).fillMaxHeight().background(PmColor.line))
}

private fun dedupLabel(state: AppState): String = when (state.dedupMode) {
    DedupKeepMode.KEEP_WATERMARKED -> "仅带水印"
    DedupKeepMode.KEEP_PLAIN -> "仅不带水印"
    DedupKeepMode.KEEP_BOTH -> "两者都保留"
}

/** 无涟漪点击：桌面用鼠标，且 motionVoice=still 明确禁止非必要动效。 */
private fun Modifier.flatClick(onClick: () -> Unit): Modifier =
    this.composed {
        val source = remember { MutableInteractionSource() }
        clickable(interactionSource = source, indication = null, onClick = onClick)
    }
