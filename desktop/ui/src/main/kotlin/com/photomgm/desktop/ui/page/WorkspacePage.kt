// ui/page/WorkspacePage.kt —— 唯一的页面
//
// ★ 页面从上到下的五段，每一段回答一个问题，且顺序与用户动作顺序一致：
//     ① 日志条     —— 这一天要处理什么？（解析成功后自动收成一行）
//     ② 结果条     —— 上一次导出成功了吗？（仅导出后出现，可收起）
//     ③ 工具条     —— 我现在要看哪一批事件？（筛选 / 搜索 / 去重口径）
//     ④ 事件卡片   —— 每个事件一行标题 + 一行照片，一屏能看到四五个
//     ⑤ 状态栏（在 AppShell）—— 数据口径
//
// ★ 为什么"所有按钮放在同一页"是对的，而不是"省了导航"这么简单：
//   复核的动作是「扫一眼卡片 → 不对劲的点开 → 改 → 关掉 → 继续扫」。
//   上一版把它拆成"事件列表页 / 导出页 / 结果页"，用户每次导出前都要离开列表，
//   导出后又要回来找刚才看到的那一条 —— 单页把这条来回路径彻底删掉了。
package com.photomgm.desktop.ui.page

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import algorithm.DedupKeepMode
import app.api.AppState
import com.photomgm.desktop.ui.app.Actions
import com.photomgm.desktop.ui.component.ButtonKind
import com.photomgm.desktop.ui.component.EmptyBlock
import com.photomgm.desktop.ui.component.EventCard
import com.photomgm.desktop.ui.component.FilterChip
import com.photomgm.desktop.ui.component.Hairline
import com.photomgm.desktop.ui.component.InlineNotice
import com.photomgm.desktop.ui.component.PmButton
import com.photomgm.desktop.ui.component.PmTextField
import com.photomgm.desktop.ui.component.Segmented
import com.photomgm.desktop.ui.component.Tone
import com.photomgm.desktop.ui.component.UnmatchedCard
import com.photomgm.desktop.ui.model.AssignMode
import com.photomgm.desktop.ui.model.PAGE_PAD
import com.photomgm.desktop.ui.model.ReviewFilter
import com.photomgm.desktop.ui.model.Selection
import com.photomgm.desktop.ui.model.Sheet
import com.photomgm.desktop.ui.model.UiState
import com.photomgm.desktop.ui.model.exportKey
import com.photomgm.desktop.ui.model.WorkQueue
import com.photomgm.desktop.ui.model.ledgerByEvent
import com.photomgm.desktop.ui.model.maxTiles
import com.photomgm.desktop.ui.model.resultToShow
import com.photomgm.desktop.ui.model.slotPhotoIds
import com.photomgm.desktop.ui.model.tileSize
import com.photomgm.desktop.ui.theme.PmColor
import com.photomgm.desktop.ui.theme.PmSpace
import com.photomgm.desktop.ui.theme.PmType

@Composable
fun WorkspacePage(
    state: AppState,
    ui: UiState,
    onUi: (UiState) -> Unit,
    actions: Actions,
    contentWidth: Int,
    /** 日期字段在窗口里的位置（上交给根覆盖层定位日历浮层，见 AppShell）。 */
    onDateAnchor: (IntRect) -> Unit,
) {
    val stats = remember(state) { WorkQueue.stats(state) }
    val byId = remember(state.photos) { WorkQueue.byId(state) }
    val ledger = remember(state.ledger, state.events) { ledgerByEvent(state) }
    val visible = remember(state, ui.filter, ui.query) { WorkQueue.filter(state, ui.filter, ui.query) }
    val tile = tileSize(contentWidth)
    val maxTiles = maxTiles(contentWidth, tile)

    fun open(target: Selection, firstPhoto: Long?) = onUi(
        ui.copy(
            sheet = Sheet.Photos(target),
            activePhotoId = firstPhoto,
            selectedPhotoIds = emptySet(),
            // 新事件 = 新的一段：旧锚点留着会让第一次 Shift 从上一个事件的照片开始连选。
            selectionAnchor = null,
        ),
    )

    Column(Modifier.fillMaxSize()) {
        LogBar(state, ui, onUi, actions, onDateAnchor)

        resultToShow(ui, state)?.let { result ->
            ResultBanner(
                result = result,
                onDismiss = { onUi(ui.copy(dismissedResultKey = state.exportKey)) },
                actions = actions,
            )
        }

        Toolbar(state, ui, onUi, stats, actions)

        if (state.events.isEmpty() && state.unmatchedPhotoIds.isEmpty()) {
            EmptyState(state)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = PAGE_PAD.dp,
                    end = PAGE_PAD.dp,
                    top = PmSpace.l,
                    bottom = PmSpace.xl,
                ),
                verticalArrangement = Arrangement.spacedBy(PmSpace.m),
            ) {
                items(visible, key = { it.id }) { event ->
                    val photos = WorkQueue.photosOfEvent(event, byId)
                    EventCard(
                        index = state.events.indexOfFirst { it.id == event.id } + 1,
                        event = event,
                        photos = photos,
                        slots = slotPhotoIds(ledger[event.id], photos),
                        tile = tile,
                        maxTiles = maxTiles,
                        // 打开时预览最早的那张：缩略条与卡片都按拍摄时间升序，
                        // "第一张"应当是时间上的第一张，而不是 core 列表里的第一张。
                        onClick = { open(Selection.Event(event.id), photos.firstOrNull()?.id) },
                        // 卡片里单张照片的"移动/复制"：复用照片弹窗用的那个目标选择层，
                        // 只是这里的对象固定为这一张。 from 必须是它现在所在的事件 ——
                        // 目标列表要据此把"它自己所在的事件"排除掉（不能把照片移到它正在的地方）。
                        onAssignPhoto = { photoId, mode ->
                            onUi(
                                ui.copy(
                                    sheet = Sheet.Assign(
                                        photoIds = listOf(photoId),
                                        from = Selection.Event(event.id),
                                        mode = mode,
                                    ),
                                    selectedPhotoIds = emptySet(),
                                ),
                            )
                        },
                    )
                }

                // 未匹配池固定排在最后：它是"第 N+1 个待处理单元"，不是垃圾桶。
                // UNMATCHED 档显示未匹配卡片；NEEDS_REVIEW/NO_PHOTO/PENDING 三档不显示（收口语义）
                if (state.unmatchedPhotoIds.isNotEmpty() &&
                    (ui.filter == ReviewFilter.ALL || ui.filter == ReviewFilter.UNMATCHED)) {
                    item(key = "unmatched") {
                        UnmatchedCard(
                            photos = state.unmatchedPhotos,
                            tile = tile,
                            maxTiles = maxTiles,
                            onClick = { open(Selection.Unmatched, state.unmatchedPhotoIds.firstOrNull()) },
                            // 未匹配照片只能"移到某个事件"：它没有家，复制会让它同时出现在两个地方。
                            onMovePhoto = { photoId ->
                                onUi(
                                    ui.copy(
                                        sheet = Sheet.Assign(
                                            photoIds = listOf(photoId),
                                            from = Selection.Unmatched,
                                            mode = AssignMode.MOVE,
                                        ),
                                        selectedPhotoIds = emptySet(),
                                    ),
                                )
                            },
                        )
                    }
                }

                // UNMATCHED 且池非空时，未匹配卡片已渲染，不再显示空态
                if (visible.isEmpty() && ui.filter != ReviewFilter.ALL &&
                    !(ui.filter == ReviewFilter.UNMATCHED && state.unmatchedPhotoIds.isNotEmpty())) {
                    item(key = "empty-filter") {
                        EmptyBlock(
                            icon = Icons.Filled.Search,
                            title = "「${ui.filter.label}」这一档是空的",
                            body = "切回「全部」可以看到 ${stats.events} 个事件。",
                            action = {
                                PmButton(
                                    "显示全部",
                                    { onUi(ui.copy(filter = ReviewFilter.ALL)) },
                                    kind = ButtonKind.SECONDARY,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Toolbar(
    state: AppState,
    ui: UiState,
    onUi: (UiState) -> Unit,
    stats: WorkQueue.Stats,
    actions: Actions,
) {
    if (state.events.isEmpty() && state.unmatchedPhotoIds.isEmpty()) return
    var draft by remember(ui.query) { mutableStateOf(ui.query) }

    Column(Modifier.fillMaxWidth().background(PmColor.surface)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = PmSpace.xl, vertical = PmSpace.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ReviewFilter.entries.forEach { f ->
                FilterChip(
                    label = f.label,
                    count = countOf(f, stats),
                    selected = ui.filter == f,
                    onClick = { onUi(ui.copy(filter = f)) },
                    tone = if (f == ReviewFilter.ALL) Tone.NEUTRAL else Tone.WARN,
                )
                Spacer(Modifier.width(PmSpace.xs))
            }

            Spacer(Modifier.weight(1f))

            PmTextField(
                value = draft,
                onValueChange = {
                    draft = it
                    onUi(ui.copy(query = it))
                },
                modifier = Modifier.width(180.dp),
                placeholder = "搜索地点 / 描述",
            )
            Spacer(Modifier.width(PmSpace.m))
            // ★ 去重模式是**视图参数**（core 同步重算，不重新扫描），所以放在工具条上、不进设置。
            Segmented(
                options = listOf("全部保留" to "", "仅水印" to "", "仅原图" to ""),
                selectedIndex = when (state.dedupMode) {
                    DedupKeepMode.KEEP_BOTH -> 0
                    DedupKeepMode.KEEP_WATERMARKED -> 1
                    DedupKeepMode.KEEP_PLAIN -> 2
                },
                onSelect = { index ->
                    actions.setDedupMode(
                        when (index) {
                            0 -> DedupKeepMode.KEEP_BOTH
                            1 -> DedupKeepMode.KEEP_WATERMARKED
                            else -> DedupKeepMode.KEEP_PLAIN
                        },
                    )
                },
            )
        }
        // 工具条与列表之间的这条线不是装饰：没有它，白色工具条和浅灰列表之间只靠背景差一档区分，
        // 125% 缩放的笔记本屏上几乎看不出来，"我在看的是筛选结果"这层关系就丢了。
        Hairline()
    }
}

private fun countOf(f: ReviewFilter, stats: WorkQueue.Stats): Int = when (f) {
    ReviewFilter.ALL -> stats.events
    ReviewFilter.NEEDS_REVIEW -> stats.needReview
    ReviewFilter.NO_PHOTO -> stats.noPhoto
    ReviewFilter.PENDING -> stats.pending
    ReviewFilter.UNMATCHED -> stats.unmatched
}

@Composable
private fun EmptyState(state: AppState) {
    val hasInput = state.logText.isNotBlank() || state.settings.sourceDirs.isNotEmpty()
    Column(
        Modifier.fillMaxSize().padding(PAGE_PAD.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 空状态限宽 520：通栏一段说明文字在大屏上会拉到 1400 dp 宽，眼睛要来回扫。
        Column(Modifier.widthIn(max = 520.dp)) {
            EmptyBlock(
                icon = Icons.Filled.Description,
                title = if (hasInput) "还没有开始分析" else "解析后，事件会一条条排在下面",
                body = if (hasInput) {
                    "日志已就绪：点上方「开始分析」，会依次解析日志 → 扫描源目录 → 读 EXIF → 按 GPS 与时间分组。"
                } else {
                    "日志里的「时间 + 桩号 + 病害」会被拆成事件，每个事件配一张卡片，卡片里是它对应的照片。点卡片可以放大看、整理照片。"
                },
            )
            if (state.settings.sourceDirs.isEmpty()) {
                Spacer(Modifier.height(PmSpace.m))
                InlineNotice(
                    tone = Tone.WARN,
                    text = "还没选源目录：分析只能解析日志，匹配不到照片。源目录在右上角「设置」里加。",
                )
            }
        }
    }
}
