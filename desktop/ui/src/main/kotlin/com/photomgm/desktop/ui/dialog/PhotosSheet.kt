// ui/dialog/PhotosSheet.kt —— 事件照片弹窗（复核的主战场）
//
// ★ 为什么"点事件弹窗"比"事件在左、照片在右"好：
//   复核的动作是「这一条对不对」——一次只看一个事件。并排布局里，另一栏永远在
//   争夺注意力，而且宽度被劈成两半，照片只能排 3 列。弹窗把整块宽度给当前事件，
//   一屏能看 6~8 张，且关闭后列表原样还在（没有页面切换、没有滚动位置丢失）。
//
// ★ 结构：单行标题 / 大图 + 右侧事实 / 底部照片条 / 底部操作条。
//   大图回答"这张对不对"，照片条回答"切到哪一张"，事实栏回答"这个事件的背景"。
//
// ★ 信息区压到最薄（v3.3）：弹窗里主角是照片，事实只用来"不用回想"。
//   于是：头部单行、侧栏去掉与头部重复的"时间段/桩号/张数"、槽位行 26、缩略条 92/72、
//   操作条 42 且把"打开原文件"收进大图下方、"关闭"交给头部 ✕。
//   减法口径是 `大图高 = 弹窗高 - 头部 38 - 缩略条 - 操作条 42 - 3 条分隔线`（model/Layout.kt）。
//
// ★ 全部交互只靠鼠标（用户明确要求移除键盘操作）：
//   点缩略图 = 看这张；点缩略图右上角方格 = 把这张加入/移出"待操作"；
//   没勾选时操作对象就是当前这张（model/operationTargets），所以主路径仍是两次点击。
package com.photomgm.desktop.ui.dialog

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import app.api.AppState
import app.api.EventSnapshot
import app.api.PhotoInfo
import com.photomgm.desktop.ui.app.Actions
import com.photomgm.desktop.ui.component.ButtonKind
import com.photomgm.desktop.ui.component.Dash
import com.photomgm.desktop.ui.component.Hairline
import com.photomgm.desktop.ui.component.KeyValue
import com.photomgm.desktop.ui.component.PmButton
import com.photomgm.desktop.ui.component.PmIconButton
import com.photomgm.desktop.ui.component.PmTextField
import com.photomgm.desktop.ui.component.Tag
import com.photomgm.desktop.ui.component.Tone
import com.photomgm.desktop.ui.component.inTopEndZone
import com.photomgm.desktop.ui.component.pmPress
import com.photomgm.desktop.ui.format.hhmm
import com.photomgm.desktop.ui.format.photoMeta
import com.photomgm.desktop.ui.format.timeRange
import com.photomgm.desktop.ui.image.rememberPhotoImage
import com.photomgm.desktop.ui.model.AssignMode
import com.photomgm.desktop.ui.model.CHECK_HIT_DP
import com.photomgm.desktop.ui.model.PHOTOS_ACTION_H
import com.photomgm.desktop.ui.model.PHOTOS_HEADER_H
import com.photomgm.desktop.ui.model.Selection
import com.photomgm.desktop.ui.model.Sheet
import com.photomgm.desktop.ui.model.UiState
import com.photomgm.desktop.ui.model.WorkQueue
import com.photomgm.desktop.ui.model.clickPhotoSelection
import com.photomgm.desktop.ui.model.filterPhotos
import com.photomgm.desktop.ui.model.ledgerByEvent
import com.photomgm.desktop.ui.model.nextPhotoSelection
import com.photomgm.desktop.ui.model.operationTargets
import com.photomgm.desktop.ui.model.photosSheetLayout
import com.photomgm.desktop.ui.model.slotPhotoIds
import com.photomgm.desktop.ui.theme.PmColor
import com.photomgm.desktop.ui.theme.PmRadius
import com.photomgm.desktop.ui.theme.PmSpace
import com.photomgm.desktop.ui.theme.PmType

/** 竖直滚轮一格映射的横向滚动量（px）；与事件卡片的照片条同口径。 */
private const val SCROLL_FACTOR = 50f

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
    val shown = filterPhotos(photos, ui.photoQuery)
    val event = (target as? Selection.Event)?.let { sel -> state.events.firstOrNull { it.id == sel.id } }
    val slots = if (event != null) slotPhotoIds(ledgerByEvent(state)[event.id], photos) else emptyMap()
    val active = photos.firstOrNull { it.id == ui.activePhotoId } ?: photos.firstOrNull()
    val targets = operationTargets(ui.selectedPhotoIds, active?.id)
    val layout = photosSheetLayout(windowWidth, windowHeight)
    val close = { onUi(ui.copy(sheet = null)) }
    // ★ 点击回调里必须读**最新** ui（Ctrl 多选是按当前勾选集合累积的）。
    //   Strip 里每个格子的 pmPress 只在首次组合时启动，闭包里的 ui 可能是弹窗刚打开那一帧的
    //   —— 直接用它就会"每次只从空集加一张"，看起来像多选坏了。see component/Press.kt。
    val uiNow by rememberUpdatedState(ui)

    SheetHost(onDismiss = close) { dragHandle ->
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
                dragHandle = dragHandle,
                onClose = close,
                onPick = { id ->
                    onUi(
                        ui.copy(
                            sheet = Sheet.Photos(Selection.Event(id)),
                            activePhotoId = null,
                            selectedPhotoIds = emptySet(),
                            selectionAnchor = null,
                            photoQuery = "",
                        ),
                    )
                },
            )
            Hairline()

            Row(Modifier.fillMaxWidth().weight(1f)) {
                Preview(
                    photo = active,
                    onReveal = { active?.let { actions.revealPath(it.sourceRef) } },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
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
                        onResetSlots = {
                            if (event != null) {
                                actions.markLedgerPhoto(event.id, 1, null)
                                actions.markLedgerPhoto(event.id, 2, null)
                            }
                        },
                        // "从本事件移除"只对**多归属**的照片出现：它被复制到了这边，
                        // 移回它的家即可（core 的 movePhotos 会顺带清掉复制关系）。
                        onDropCopy = { photoId, keepEventId ->
                            actions.movePhotosTo(listOf(photoId), keepEventId)
                            onUi(ui.copy(selectedPhotoIds = emptySet(), selectionAnchor = null))
                        },
                    )
                }
            }

            Hairline()
            Strip(
                photos = shown,
                active = active,
                selected = ui.selectedPhotoIds,
                slots = slots,
                tile = layout.stripTile,
                height = layout.stripH,
                emptyText = when {
                    photos.isEmpty() && event == null -> "没有未匹配照片"
                    event != null && event.photoIds.isEmpty() ->
                        "这个事件还没有照片：可从别的事件「移动…」或「复制…」过来"
                    photos.isNotEmpty() -> "没有匹配「${ui.photoQuery.trim()}」的照片"
                    else -> "没有照片"
                },
                // ★ 点图只有两种结果（v3.7 收窄）：
                //   普通点 = 只看这张、且只勾这张；Shift+点 = 从锚点连选到这张。
                //   "切换这一张、保留其它"不在这里 —— 它只由右上角红勾触发（见 Strip 的 onToggle），
                //   这样一次点击永远只可能落到一个分支上，不存在"两个逻辑都跑一遍"的错乱。
                onActivate = { photo, shift ->
                    val now = uiNow
                    // 连选按**屏幕上看到的顺序**算（shown 可能已被搜索过滤）。
                    val res = clickPhotoSelection(
                        selected = now.selectedPhotoIds,
                        ordered = shown.map { it.id },
                        anchor = now.selectionAnchor,
                        id = photo.id,
                        shift = shift,
                    )
                    onUi(
                        now.copy(
                            activePhotoId = photo.id,
                            selectedPhotoIds = res.selected,
                            selectionAnchor = res.anchor,
                        ),
                    )
                },
                onToggle = { photo ->
                    val now = uiNow
                    onUi(
                        now.copy(
                            // ★ 红勾只改"勾了哪几张"，**不动 activePhotoId**（v3.7）。
                            //   之前它顺带把大图切到这张，于是"点红勾勾选第 5 张"会把正在复核的
                            //   第 1 张换掉 —— 用户看到的是"点一下红勾，大图和照片条都跳了"，
                            //   很难判断这次到底改的是勾选还是预览。勾选与预览解耦后，
                            //   "红勾 = 加选/减选"、"点图 = 换预览"各归各的。
                            selectedPhotoIds = nextPhotoSelection(now.selectedPhotoIds, photo.id, additive = true),
                            // 勾选也算"我落到了这一张"，之后的 Shift 连选从这里起。
                            selectionAnchor = photo.id,
                        ),
                    )
                },
            )

            Hairline()
            ActionBar(
                ui = ui,
                onUi = onUi,
                event = event,
                photoCount = photos.size,
                shownCount = shown.size,
                targets = targets,
                onMove = {
                    onUi(ui.copy(sheet = Sheet.Assign(targets, target, AssignMode.MOVE)))
                },
                onCopy = {
                    onUi(ui.copy(sheet = Sheet.Assign(targets, target, AssignMode.COPY)))
                },
                onDetach = {
                    actions.movePhotosTo(targets, null)
                    onUi(ui.copy(selectedPhotoIds = emptySet(), selectionAnchor = null))
                },
            )
        }
    }
}

/**
 * 头部：单行。序号 / 时间段 / 地点 / 问题标签 / 前后切换 / 关闭。
 *
 * ★ 原来这里是两行（第二行是"N 张照片 · 点缩略图切换"）。删掉的理由：
 *   那句提示和操作条左边的文案说的是同一件事，而头部每多一行，大图就少 18 dp。
 * ★ 这一行同时是**拖动区**（[dragHandle]）：整条头都能拖，按钮照样能点 ——
 *   拖动只有"按下后移动超过阈值"才开始，静止的点击仍然落到 ✕ / ‹ › 上。
 */
@Composable
private fun Header(
    state: AppState,
    event: EventSnapshot?,
    target: Selection,
    dragHandle: Modifier,
    onClose: () -> Unit,
    onPick: (Int) -> Unit,
) {
    val index = event?.let { state.events.indexOfFirst { e -> e.id == it.id } + 1 } ?: 0
    val total = state.events.size

    Row(
        Modifier
            .fillMaxWidth()
            .height(PHOTOS_HEADER_H.dp)
            .then(dragHandle)
            .padding(start = PmSpace.l, end = PmSpace.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (event != null) {
            Text("事件 %02d / %02d".format(index, total), style = PmType.label, color = PmColor.ink3)
            Spacer(Modifier.width(PmSpace.m))
            Text(
                timeRange(event.startTimeMs, event.endTimeMs),
                style = PmType.numeric,
                color = if (event.isPending) PmColor.warn else PmColor.ink2,
                maxLines = 1,
            )
            Spacer(Modifier.width(PmSpace.m))
        }
        Text(
            // 弹窗标题只放地点：时间在左边、描述在侧栏，三处重复只会挤掉宽度。
            if (event != null) event.location.ifBlank { "（无桩号）" } else WorkQueue.titleOf(state, target),
            style = PmType.title,
            color = PmColor.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(PmSpace.m))
        (event?.let { WorkQueue.issueLabels(it) } ?: emptyList()).forEach { label ->
            Tag(label, Tone.NEUTRAL)
            Spacer(Modifier.width(PmSpace.xs))
        }
        Spacer(Modifier.weight(1f))
        if (event != null) {
            val prev = state.events.getOrNull(index - 2)
            val next = state.events.getOrNull(index)
            PmIconButton(Icons.Filled.ChevronLeft, "上一事件", { prev?.let { onPick(it.id) } }, enabled = prev != null, size = 26.dp)
            PmIconButton(Icons.Filled.ChevronRight, "下一事件", { next?.let { onPick(it.id) } }, enabled = next != null, size = 26.dp)
            Spacer(Modifier.width(PmSpace.xs))
        }
        PmIconButton(Icons.Filled.Close, "关闭", onClose, size = 26.dp)
    }
}

/**
 * 大图区：画布底 + 居中 4:3 + 一行"文件名 · 参数 + 打开原文件"。
 *
 * ★ 图永远是 `fillMaxHeight` + 4:3：让高度先吃满，宽度由比例反推 —— 这样窗口变宽时
 *   图的面积只增不减，而不会被拉成一条横贯弹窗的扁片（原图是 4:3 的相机照片）。
 * ★ "打开原文件"从操作条挪到这里：它是"这一张"的动作，长在图下面才不用回头找。
 */
@Composable
private fun Preview(photo: PhotoInfo?, onReveal: () -> Unit, modifier: Modifier) {
    Column(modifier.background(PmColor.stage).padding(horizontal = PmSpace.l, vertical = PmSpace.m)) {
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            if (photo == null) {
                Text("没有可显示的照片", style = PmType.body, color = PmColor.ink3)
            } else {
                val tone = PmColor.placeholder[(photo.id.hashCode() and 0x7fffffff) % PmColor.placeholder.size]
                // 大图按 1400 px 取：弹窗高度上限 720 dp，2× 缩放下也不会发虚，又不至于解码原图。
                val image = rememberPhotoImage(photo.sourceRef, maxEdge = 1400)
                Box(
                    Modifier
                        .fillMaxHeight()
                        .aspectRatio(4f / 3f)
                        .clip(RoundedCornerShape(PmRadius.card))
                        .background(tone)
                        .border(1.dp, PmColor.lineStrong, RoundedCornerShape(PmRadius.card)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (image != null) {
                        // Fit 而不是 Crop：复核要判断"这张对不对"，先得看到整张，不能裁掉边角。
                        Image(
                            bitmap = image,
                            contentDescription = photo.displayName,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.matchParentSize(),
                        )
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Filled.Image, contentDescription = null, tint = PmColor.ink4, modifier = Modifier.size(34.dp))
                            Spacer(Modifier.height(PmSpace.s))
                            Text(
                                photo.displayName.substringAfterLast('.', "").uppercase().ifEmpty { "IMG" },
                                style = PmType.label,
                                color = PmColor.ink3,
                            )
                            Spacer(Modifier.height(PmSpace.xs))
                            Text("正在读取 / 该格式暂不支持预览", style = PmType.micro, color = PmColor.ink4)
                        }
                    }
                }
            }
        }
        if (photo != null) {
            Spacer(Modifier.height(PmSpace.xs))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = PmColor.ink)) { append(photo.displayName) }
                        append("   ")
                        withStyle(SpanStyle(color = PmColor.ink3, fontSize = 11.sp)) { append(photoMeta(photo)) }
                    },
                    style = PmType.body,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(PmSpace.s))
                PmButton("打开原文件", onReveal, kind = ButtonKind.GHOST, icon = Icons.Filled.OpenInNew, height = 26.dp)
            }
        }
    }
}

/**
 * 右侧事实栏（宽 240）。
 *
 * ★ 只放"头部没有的"和"要动手的"：台账两格、多归属、类型、待确认提示、一句"改动立即生效"。
 *   时间段 / 桩号 / 张数原来在这里各占一行，全部与头部重复，删掉后侧栏矮了 ~60 dp，
 *   而侧栏越矮、大图越高（两者在同一行里争高度）。
 */
@Composable
private fun SidePanel(
    state: AppState,
    event: EventSnapshot?,
    target: Selection,
    photos: List<PhotoInfo>,
    slots: Map<Long, Int>,
    active: PhotoInfo?,
    modifier: Modifier,
    onMark: (Int) -> Unit,
    onResetSlots: () -> Unit,
    onDropCopy: (photoId: Long, keepEventId: Int) -> Unit,
) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(PmSpace.l)) {
        if (event == null) {
            Text("未匹配照片", style = PmType.title, color = PmColor.ink)
            Spacer(Modifier.height(PmSpace.xs))
            Text(
                "这些照片在文件名日期上属于巡查日，但没有匹配到任何事件的时间窗与位置。可以：\n" +
                    "· 选中后「移动到其他事件…」放进正确的事件；\n" +
                    "· 不管它，导出时会统一放进 99_未分类/。",
                style = PmType.caption,
                color = PmColor.ink3,
            )
            return@Column
        }

        // ── 巡查日志内容（v3.7 新增）────────────────────────────────────────
        // 复核时真正要回答的是"这条记录说的和这些照片对得上吗"，
        // 而原来弹窗里只有类型/桩号/时间段这些**解析结果**，看不到记录本身。
        // 日志原文放在最上面：它是判断依据，先读它，再看照片和台账格。
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(PmRadius.control))
                .background(PmColor.surface2)
                .padding(PmSpace.m),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("巡查日志内容", style = PmType.label, color = PmColor.ink2)
                Spacer(Modifier.weight(1f))
                Tag("原始记录", Tone.NEUTRAL)
            }
            Spacer(Modifier.height(PmSpace.xs))
            val content = event.description.trim()
            Text(
                content.ifEmpty { "（这条记录没有文字内容，只有时间与照片）" },
                style = PmType.body,
                // 空内容用 ink4 弱化，避免被当成"日志里真写了这句话"。
                color = if (content.isEmpty()) PmColor.ink4 else PmColor.ink,
            )
        }

        Spacer(Modifier.height(PmSpace.m))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("台账里的两张", style = PmType.label, color = PmColor.ink2)
            Spacer(Modifier.weight(1f))
            if (slots.isNotEmpty()) {
                PmButton("恢复自动", onResetSlots, kind = ButtonKind.GHOST, height = 24.dp)
            }
        }
        Spacer(Modifier.height(PmSpace.xs))
        SlotRow(1, photos.firstOrNull { slots[it.id] == 1 }, active, onMark)
        Spacer(Modifier.height(PmSpace.xs))
        SlotRow(2, photos.firstOrNull { slots[it.id] == 2 }, active, onMark)

        // 多归属：这张照片同时出现在几个事件里。只在这张真的属于多个事件时才出现 ——
        // 它是"复制"的直接后果，用户需要在这里看到"另一份在哪、怎么取消"。
        val owners = active?.let { WorkQueue.eventsOfPhoto(state, it.id) } ?: emptyList()
        if (owners.size > 1) {
            Spacer(Modifier.height(PmSpace.m))
            Text("本次归属（${owners.size} 处）", style = PmType.label, color = PmColor.ink2)
            Spacer(Modifier.height(2.dp))
            owners.forEach { eventId ->
                val here = eventId == event.id
                Row(Modifier.height(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "事件 %02d".format(WorkQueue.indexOf(state, eventId)),
                        style = PmType.caption,
                        color = if (here) PmColor.ink else PmColor.ink2,
                        modifier = Modifier.width(52.dp),
                    )
                    Text(
                        state.events.firstOrNull { it.id == eventId }?.location ?: "",
                        style = PmType.caption,
                        color = PmColor.ink3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (here) {
                        Tag("当前", Tone.NEUTRAL)
                    } else {
                        PmButton(
                            "移除本份",
                            { active?.let { onDropCopy(it.id, eventId) } },
                            kind = ButtonKind.GHOST,
                            height = 22.dp,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(PmSpace.m))
        Dash()
        Spacer(Modifier.height(PmSpace.m))

        KeyValue("类型", event.eventType.ifBlank { "未分类" })

        if (event.hints.isNotEmpty()) {
            Spacer(Modifier.height(PmSpace.m))
            Text("需要确认", style = PmType.label, color = PmColor.warn)
            Spacer(Modifier.height(2.dp))
            event.hints.forEach { hint ->
                Text("· $hint", style = PmType.caption, color = PmColor.ink3, modifier = Modifier.padding(vertical = 1.dp))
            }
        }

        Spacer(Modifier.height(PmSpace.l))
        Text("改动立即生效，不需要重新分析。", style = PmType.caption, color = PmColor.ink3)
    }
}

/** 台账槽位行：26 dp 高（原来 34），一屏侧栏能同时看到两格 + 类型 + 提示。 */
@Composable
private fun SlotRow(slot: Int, photo: PhotoInfo?, active: PhotoInfo?, onMark: (Int) -> Unit) {
    Row(Modifier.height(26.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("第 $slot 格", style = PmType.micro, color = PmColor.ink3, modifier = Modifier.width(34.dp))
        Box(
            Modifier
                .size(26.dp)
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
            height = 24.dp,
        )
    }
}

/**
 * 缩略条。
 *
 * ★ "选"的两个落点，语义严格分开（v3.7 定稿）：
 *   · 点格子本体 —— 只看这张、只勾这张；`Shift`+点 = 从锚点连选一段；
 *   · 点右上角红勾 —— **唯一**的"切换这一张、保留其它"入口。
 *   Ctrl+点图 的加选已经删掉：它和红勾是同一件事的两个入口，而两个入口在手势层
 *   无法可靠区分（按下阶段谁都可能先拿到这次 press），现场表现就是"点红勾/连点几次
 *   勾选自己乱跳"。现在点图永远只做单选或连选，勾选集合只可能被红勾改，行为可预测。
 *   红勾本身也够用：不知道快捷键的人点它、习惯 Ctrl 的人点它也得到同一个结果。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Strip(
    photos: List<PhotoInfo>,
    active: PhotoInfo?,
    selected: Set<Long>,
    slots: Map<Long, Int>,
    tile: Int,
    height: Int,
    emptyText: String,
    onActivate: (PhotoInfo, shift: Boolean) -> Unit,
    onToggle: (PhotoInfo) -> Unit,
) {
    if (photos.isEmpty()) {
        Box(Modifier.fillMaxWidth().height(height.dp).padding(horizontal = PmSpace.l), contentAlignment = Alignment.CenterStart) {
            Text(emptyText, style = PmType.caption, color = PmColor.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        return
    }
    val density = LocalDensity.current
    val checkPx = with(density) { CHECK_HIT_DP.dp.roundToPx() }
    // 红勾回调同样要读最新闭包：pointerInput 的 block 只在首次组合启动（见 component/Press.kt）。
    val toggleNow by rememberUpdatedState(onToggle)
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    LazyRow(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .height(height.dp)
            .onPointerEvent(PointerEventType.Scroll) { event ->
                val delta = event.changes.first().scrollDelta.y
                if (delta != 0f) {
                    // 只在当前方向还滚得动时接管滚轮：滚到头就放行，让父级继续滚动。
                    val canScroll = if (delta > 0) listState.canScrollForward else listState.canScrollBackward
                    if (canScroll) {
                        scope.launch { listState.scrollBy(delta * SCROLL_FACTOR) }
                        event.changes.forEach { it.consume() }
                    }
                }
            },
        contentPadding = PaddingValues(horizontal = PmSpace.l, vertical = PmSpace.s),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(photos, key = { it.id }) { photo ->
            val isActive = photo.id == active?.id
            val isSelected = selected.contains(photo.id)
            val tone = PmColor.placeholder[(photo.id.hashCode() and 0x7fffffff) % PmColor.placeholder.size]
            val px = with(density) { tile.dp.roundToPx() }
            val image = rememberPhotoImage(photo.sourceRef, maxEdge = (px * 2).coerceAtLeast(64))
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
                    // ★ 红勾那 24×24 的直角区域**不归格子**：点在它上面时这里直接不响应，
                    //   由下面的红勾自己处理。旧实现靠"子节点消费了没"来猜，按下阶段
                    //   并不保证消费 → 一次点击同时跑单选和切换，勾选被覆盖（现场事故）。
                    .pmPress(accept = { pos, size -> !inTopEndZone(pos, size, checkPx) }) { mods ->
                        onActivate(photo, mods.shift)
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (image != null) {
                    Image(
                        bitmap = image,
                        contentDescription = photo.displayName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize(),
                    )
                }
                // 时刻：深色胶囊压在右下角（图上居中放灰字是读不出来的）。
                val time = photo.captureTimeMs?.let { hhmm(it) }
                if (time != null) {
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(3.dp)
                            .clip(RoundedCornerShape(PmRadius.control))
                            .background(Color.Black.copy(alpha = 0.58f))
                            .padding(horizontal = 3.dp, vertical = 1.dp),
                    ) {
                        Text(time, style = PmType.micro, color = Color.White)
                    }
                } else if (image == null) {
                    Text("无时间", style = PmType.micro, color = PmColor.warn)
                }
                val slot = slots[photo.id]
                if (slot != null) {
                    Box(
                        // 左下角：右上角被勾选框占了，左上角留给"时刻居中"。
                        Modifier.align(Alignment.BottomStart).padding(3.dp)
                            .clip(RoundedCornerShape(PmRadius.control))
                            .background(PmColor.accent)
                            .padding(horizontal = 4.dp),
                    ) {
                        Text("照$slot", style = PmType.micro, color = PmColor.surface)
                    }
                }
                // 勾选框：24 dp 命中区（框本身 16）压在右上角，是**唯一的加选入口**。
                //
                // ★ 不用 clickable 而用 awaitEachGesture：clickable 在按下阶段不保证 consume，
                //   父层（格子本体）就可能先把它当成一次"点图"。这里 down 到手立刻 consume，
                //   配合格子的几何豁免，两道保险锁住"点红勾只改勾选、不动预览"。
                // ★ 抬起必须落在同一块区域（waitForUpOrCancellation 会因移出而返回 null）：
                //   按下后拖走再松手等于放弃这次勾选，与 Windows 复选框一致。
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(CHECK_HIT_DP.dp)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .pointerInput(photo.id) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                down.consume()
                                val up = waitForUpOrCancellation()
                                if (up != null) {
                                    up.consume()
                                    toggleNow(photo)
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(16.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (isSelected) PmColor.accent else PmColor.surface.copy(alpha = 0.92f))
                            .border(
                                1.dp,
                                if (isSelected) PmColor.accent else PmColor.ink3.copy(alpha = 0.65f),
                                RoundedCornerShape(4.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isSelected) {
                            Icon(Icons.Filled.Check, contentDescription = "已勾选", tint = PmColor.surface, modifier = Modifier.size(11.dp))
                        }
                    }
                }
            }
        }
    }
}

/**
 * 操作条：左边"当前口径 + 搜索"，右边三个动作。
 *
 * ★ 三个动作都是"对这几张照片做什么"，作用对象由 [operationTargets] 决定（勾了用勾的，没勾用当前）。
 * ★ "移出到未匹配"只在事件视图出现：未匹配池里的照片本来就在那儿，再移一次没有意义。
 */
@Composable
private fun ActionBar(
    ui: UiState,
    onUi: (UiState) -> Unit,
    event: EventSnapshot?,
    photoCount: Int,
    shownCount: Int,
    targets: List<Long>,
    onMove: () -> Unit,
    onCopy: () -> Unit,
    onDetach: () -> Unit,
) {
    var draft by remember(ui.photoQuery) { mutableStateOf(ui.photoQuery) }
    val querying = ui.photoQuery.isNotBlank()
    val enabled = targets.isNotEmpty()

    Row(
        Modifier
            .fillMaxWidth()
            .height(PHOTOS_ACTION_H.dp)
            .padding(horizontal = PmSpace.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            when {
                // 只有真多选（≥2）时"已勾选 N 张"才有信息量：单张时它就是"当前这张"，
                // 而普通点击本来就会重置成一张，那句话会一直挂着当噪音。
                ui.selectedPhotoIds.size > 1 -> "已勾选 ${ui.selectedPhotoIds.size} 张"
                photoCount > 0 -> "$photoCount 张 · 点图预览 · Shift+点 连选 · 右上角红勾加选"
                else -> "无照片"
            },
            style = PmType.caption,
            color = if (ui.selectedPhotoIds.size > 1) PmColor.accent else PmColor.ink3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(PmSpace.m))
        Icon(Icons.Filled.Search, contentDescription = null, tint = PmColor.ink4, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(PmSpace.xs))
        PmTextField(
            value = draft,
            onValueChange = {
                draft = it
                onUi(ui.copy(photoQuery = it))
            },
            modifier = Modifier.width(148.dp),
            placeholder = "搜文件名 / 时刻",
            height = 26.dp,
            textStyle = PmType.caption,
        )
        if (querying) {
            Spacer(Modifier.width(PmSpace.xs))
            Text("$shownCount/$photoCount", style = PmType.caption, color = PmColor.ink3)
            Spacer(Modifier.width(PmSpace.xs))
            PmButton("清除", { draft = ""; onUi(ui.copy(photoQuery = "")) }, kind = ButtonKind.GHOST, height = 24.dp)
        }

        Spacer(Modifier.weight(1f))
        PmButton("移动到…", onMove, kind = ButtonKind.SECONDARY, enabled = enabled, height = 28.dp)
        Spacer(Modifier.width(PmSpace.xs))
        PmButton("复制到…", onCopy, kind = ButtonKind.GHOST, enabled = enabled && event != null, height = 28.dp)
        if (event != null) {
            Spacer(Modifier.width(PmSpace.xs))
            // 勾了多张时，"移出"和"移动"是两件事：移出＝这些照片不属于任何事件。
            PmButton("移出到未匹配", onDetach, kind = ButtonKind.GHOST, enabled = enabled, height = 28.dp)
        }
    }
}
