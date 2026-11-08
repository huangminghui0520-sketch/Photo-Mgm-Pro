// ui/component/EventCard.kt —— 事件卡片（事件 + 它的照片行）
//
// ★ 这一版把"事件"和"照片"合并成一个可扫视的单元。
//   上一版事件在左列、照片在中间，用户要自己把两者对起来；而 core 里
//   `EventSnapshot.photoIds` 本来就是事件的字段 —— 卡片是数据形状的直接投影。
//
// ★ 为什么是卡片、不是表格（评估结论，写在这里免得下次又推翻）：
//   ① 每个事件的主体信息是"一段文字 + 一组数量可变的照片"，表格的行高会被 0~5 张照片撑成锯齿，
//      卡片天然容纳可变内容；
//   ② 复核的动作是"整体看这个事件对不对"，不是"跨行比较同一个字段"——后者才需要表格的排序/列固定；
//   ③ 一天 8~20 个事件，不需要分页、列宽拖拽、批量勾选这些表格能力。
//   ④ 代价是"同一字段上下对齐"要靠固定列宽，所以卡片头部做成三列固定栅格（CARD_INDEX_W /
//      CARD_TIME_W / CARD_COUNT_W），照片条左边缘与序号列对齐 —— 卡片的容器 + 表格的栅格。
//
// ★ "展示统一"落在**栅格**上，不落在"高度必须完全相等"上：
//   头部行高固定 30、三列宽度固定、照片条永远从同一个左边缘起 —— 于是卡片之间可以上下对齐着扫；
//   而卡片高度随内容（有无照片）变化，因为把无照片的占位条撑到 80 dp，一屏就少看一个事件。
//   540 dp 高的内业机上一屏能多看半张卡片，比"两列高度完全相等"更值。
//
// ★ 卡片里的照片格**不显示文件名**，显示拍摄时刻（08:14）。
//   复核时真正要判断的是"这个事件的时间窗里，照片对不对、有没有漏"，
//   文件名（IMG_20261101_081432.jpg）在那个判断里没有信息量，时刻有。
//
// ★ 描边语义（三条边各有各的意思，不许混用）：
//     红色边   —— 这张卡片**已经挂了照片**（has photos）。扫一列卡片时，"有没有图上"是最先要
//                 判断的事，用色相而不是再加一个标签去表达；红是项目里唯一的"实心信号色"。
//     强调边   —— 需要人处理（待确认：有 hint / 无照片 / 待定时间）。没有照片的卡片才用它，
//                 因为"无照片"本身也是待确认的一种，两者不会同屏争夺同一条边。
//     暖色底+边 —— 未匹配池（它不是事件，是整个列表的"第 N+1 个待处理单元"）。
//   优先级：未匹配 > 有照片 > 待确认 > 悬停 > 静止。有照片的卡片即使待确认也走红边，
//   但它的序号仍用强调色、问题标签仍在 —— 待确认的信息没有丢，只是不再抢描边。
// ★ 缩略图上的两个动作（移动 / 复制）藏在**悬停覆盖层**里，不常驻显示：
//   ① 一张 72~96 dp 的格子若常驻两个按钮，照片本身就没地方了，而"这是哪张照片"
//      （时刻）正是卡片要传达的第一件事；
//   ② 复核时鼠标扫过一列卡片，常驻按钮会让人误以为"这张正在被操作"；
//   ③ 悬停才出现，等于把"可以动手"和"正在看"分成两个时刻，一次点击只承担一件事：
//      点格子 = 打开这个事件（看），点悬停层里的按钮 = 对这一张动手。
//   代价是"不知道这里有入口"。弥补方式是这里的按钮带**文字**（不只图标）：
//   图标在 72 dp 的格子里可以猜，但"移动"和"复制"不是能靠形状区分的动词。
// ★ 未匹配池的缩略图只给"移动"：复制的前提是"它有家"，未匹配照片没有家，
//   core 也直接拒绝复制（见 Feedbacl.rejectReason 里的同一条理由）。
package com.photomgm.desktop.ui.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import app.api.EventSnapshot
import app.api.PhotoInfo
import com.photomgm.desktop.ui.format.hhmm
import com.photomgm.desktop.ui.format.timeRange
import com.photomgm.desktop.ui.image.rememberPhotoImage
import com.photomgm.desktop.ui.model.AssignMode
import com.photomgm.desktop.ui.model.CARD_COUNT_W
import com.photomgm.desktop.ui.model.CARD_HEADER_H
import com.photomgm.desktop.ui.model.CARD_INDEX_W
import com.photomgm.desktop.ui.model.CARD_PAD
import com.photomgm.desktop.ui.model.CARD_TIME_W
import com.photomgm.desktop.ui.model.CardEdge
import com.photomgm.desktop.ui.model.EMPTY_STRIP_H
import com.photomgm.desktop.ui.model.TILE_GAP
import com.photomgm.desktop.ui.model.WorkQueue
import com.photomgm.desktop.ui.model.cardEdge
import com.photomgm.desktop.ui.theme.PmColor
import com.photomgm.desktop.ui.theme.PmRadius
import com.photomgm.desktop.ui.theme.PmSpace
import com.photomgm.desktop.ui.theme.PmType

/** 竖直滚轮一格映射的横向滚动量（px）；只取 scrollDelta.y，不影响水平滚轮的原生行为。 */
private const val SCROLL_FACTOR = 50f

@Composable
fun EventCard(
    index: Int,
    event: EventSnapshot,
    photos: List<PhotoInfo>,
    slots: Map<Long, Int>,
    tile: Int,
    maxTiles: Int,
    onClick: () -> Unit,
    /** 对卡片里某张照片动手（移动到另一个事件 / 复制到另一个事件）。 */
    onAssignPhoto: (photoId: Long, mode: AssignMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val needReview = WorkQueue.needsReview(event)
    CardFrame(
        onClick = onClick,
        modifier = modifier,
        // 待确认的卡片换强调色描边：不新增色块、不改栅格，但在一列白卡片里能一眼挑出来。
        accent = needReview,
        hasPhotos = photos.isNotEmpty(),
    ) {
        CardHeader(
            index = index,
            time = timeRange(event.startTimeMs, event.endTimeMs),
            timeTone = if (event.isPending) PmColor.warn else PmColor.ink,
            title = event.location,
            detail = event.description,
            indexAccent = needReview,
            tags = { WorkQueue.issueLabels(event).forEach { label ->
                // 红边现在表示"有照片"，所以"无照片"不再用红色：同一张卡片里红框配红字，
                // 两种红互相抵消，反而谁也读不出来。无照片是"缺口"，用中性标签 + 占位行说。
                Tag(label, Tone.NEUTRAL)
                Spacer(Modifier.width(PmSpace.xs))
            } },
            photoCount = photos.size,
        )
        Spacer(Modifier.height(PmSpace.m))
        PhotoStrip(
            photos = photos,
            slots = slots,
            tile = tile,
            maxTiles = maxTiles,
            event = event,
            canCopy = true,
            onAssign = { photo, mode -> onAssignPhoto(photo.id, mode) },
        )
    }
}

/** 未匹配照片池：固定排在列表末尾，形状与事件卡片完全一致（它就是"第 N+1 个待处理单元"）。 */
@Composable
fun UnmatchedCard(
    photos: List<PhotoInfo>,
    tile: Int,
    maxTiles: Int,
    onClick: () -> Unit,
    /** 未匹配照片只能"移动"（它没有归属事件，复制没有意义），所以这里只有一个方向。 */
    onMovePhoto: (photoId: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    CardFrame(onClick = onClick, modifier = modifier, accent = true, warm = true) {
        CardHeader(
            index = null,
            time = null,
            timeTone = PmColor.warn,
            title = "未匹配照片",
            detail = "没有归到任何事件，导出时进 99_未分类/",
            indexAccent = true,
            leading = {
                Icon(Icons.Filled.WarningAmber, contentDescription = null, tint = PmColor.warn, modifier = Modifier.size(14.dp))
            },
            photoCount = photos.size,
        )
        Spacer(Modifier.height(PmSpace.m))
        PhotoStrip(
            photos = photos,
            slots = emptyMap(),
            tile = tile,
            maxTiles = maxTiles,
            event = null,
            canCopy = false,
            onAssign = { photo, _ -> onMovePhoto(photo.id) },
        )
    }
}

/**
 * 卡片底色：悬停时深一档，移出即恢复；纯函数，颜色规则可被单测钉住。
 *
 * 描边加实（见 CardFrame 的 borderTone）是既有的悬停表达，这里只叠一层底色，
 * 两者说同一件事：鼠标现在停在这张卡上。不改尺寸、不加边框、不动栅格。
 * warm（未匹配池）不换成 surface2 —— 那会把"暖色底+边 = 未匹配"洗成一张普通灰卡
 * （见文件头"描边语义"），所以只把原色 warnSoft 加深，色相不变。
 */
internal fun eventCardBackground(isHovered: Boolean, warm: Boolean): Color =
    when {
        warm && isHovered -> PmColor.warnSoft.copy(alpha = 1.00f)
        warm -> PmColor.warnSoft.copy(alpha = 0.45f)
        isHovered -> PmColor.stage
        else -> PmColor.surface
    }

/**
 * 卡片外壳：白底 + 1 dp 描边 + 统一内边距。
 *
 * `hasPhotos` = 这个单元已经挂了照片 → 红边（见文件头"描边语义"）；
 * `accent`    = 需要人处理（待确认），在无照片时用强调色描边；
 * `warm`      = 未匹配池专用的暖底（它不是一个"事件"，用底色区分，而不是加一个标题前缀）。
 */
@Composable
private fun CardFrame(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    warm: Boolean = false,
    hasPhotos: Boolean = false,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(PmRadius.panel)
    // 谁抢到这条边由 model.cardEdge 决定（纯函数、有单测）；这里只负责上色。
    // 悬停不改变"这条边说什么"，只把它加实一档 —— 否则鼠标一过，红边变强调边，
    // 用户会以为卡片的状态变了。
    val borderTone = when (cardEdge(hasPhotos = hasPhotos, review = accent, unmatched = warm)) {
        CardEdge.UNMATCHED -> if (hovered) PmColor.warn else PmColor.warn.copy(alpha = 0.35f)
        CardEdge.PHOTOS -> if (hovered) PmColor.danger else PmColor.danger.copy(alpha = 0.72f)
        CardEdge.REVIEW -> if (hovered) PmColor.accent.copy(alpha = 0.75f) else PmColor.accent.copy(alpha = 0.28f)
        CardEdge.REST -> if (hovered) PmColor.lineStrong else PmColor.line
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(eventCardBackground(hovered, warm))
            .border(1.dp, borderTone, shape)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(CARD_PAD.dp),
    ) {
        content()
    }
}

/**
 * 卡片头部：一行五段的三列固定栅格。
 *
 *   [序号 26] [时间 96] [地点 + 描述 (weight 1)] [标签] [张数 56] [›]
 *
 * 序号与时间**永远占位**（未匹配池用空占位而不是省略），否则同一列卡片的地点会左右错开。
 */
@Composable
private fun CardHeader(
    index: Int?,
    time: String?,
    timeTone: androidx.compose.ui.graphics.Color,
    title: String,
    detail: String,
    indexAccent: Boolean,
    photoCount: Int,
    tags: @Composable () -> Unit = {},
    leading: (@Composable () -> Unit)? = null,
) {
    Row(Modifier.height(CARD_HEADER_H.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(CARD_INDEX_W.dp), contentAlignment = Alignment.CenterStart) {
            if (leading != null) {
                leading()
            } else if (index != null) {
                Text(
                    "%02d".format(index),
                    style = PmType.numeric,
                    color = if (indexAccent) PmColor.accent else PmColor.ink3,
                )
            }
        }
        Box(Modifier.width(CARD_TIME_W.dp)) {
            if (time != null) {
                Text(time, style = PmType.numeric, color = timeTone, maxLines = 1)
            }
        }
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = PmColor.ink, fontWeight = FontWeight.Medium)) { append(title) }
                if (detail.isNotBlank()) {
                    append("  ")
                    withStyle(SpanStyle(color = PmColor.ink2)) { append(detail) }
                }
            },
            style = PmType.body,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(PmSpace.m))
        tags()
        // 张数：固定宽 + 右对齐，数字的个位永远在同一竖线上。
        Text(
            if (photoCount > 0) "$photoCount 张" else "",
            style = PmType.numeric,
            color = if (photoCount == 0) PmColor.ink4 else PmColor.ink2,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(CARD_COUNT_W.dp),
        )
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = PmColor.ink4, modifier = Modifier.size(16.dp))
    }
}

/**
 * 照片条：一行等高的格子，**可横向滚动**；末位是「+N」，标出首屏之外还有几张。
 *
 * ★ 为什么改成可滚动：照片多于首屏格数时，旧版只留「+N」把剩下的藏掉，
 *   复核"这个事件到底几张、有没有漏"时看不到具体是哪几张；
 *   滚动让每一张都能翻到，而「+N」仍然回答"一共比首屏多几张"。
 * ★ 滚动条只在这条真能滚时才画：装得下时画一条满长的灰条，会让人以为右边还有东西。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PhotoStrip(
    photos: List<PhotoInfo>,
    slots: Map<Long, Int>,
    tile: Int,
    maxTiles: Int,
    event: EventSnapshot?,
    /** 能否复制：未匹配照片没有归属事件，只给"移动"。 */
    canCopy: Boolean,
    onAssign: (PhotoInfo, AssignMode) -> Unit,
) {
    if (photos.isEmpty()) {
        // 无照片：一条 40 dp 的占位行，与照片条同一左边缘、同一圆角、同一底色体系——
        // 只在"高度"上比有照片的卡片矮，因为空占位没有信息，不该占一张缩略图的面积。
        val pending = event?.isPending == true
        Box(
            Modifier
                .fillMaxWidth()
                .height(EMPTY_STRIP_H.dp)
                .clip(RoundedCornerShape(PmRadius.control))
                .background(PmColor.surface2.copy(alpha = 0.6f)),
            contentAlignment = Alignment.CenterStart,
        ) {
            // 这里不再重复"无照片"三个字：卡片右上角已经有标签，占位行只说"接下来会发生什么"。
            Text(
                if (pending) {
                    "待定时间事件：照片不会自动归入，需在照片弹窗里手动指定"
                } else {
                    "导出时会生成一个空文件夹；点卡片可以把照片移进来"
                },
                style = PmType.caption,
                color = if (pending) PmColor.warn else PmColor.ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = PmSpace.l),
            )
        }
        return
    }

    // 全部照片都进列表；末位「+N」= 比首屏格数多出来的张数（滚到末尾才看到）。
    val overflow = (photos.size - maxTiles).coerceAtLeast(0)
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxWidth()) {
        LazyRow(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .height(tile.dp)
                .onPointerEvent(PointerEventType.Scroll) { event ->
                    val delta = event.changes.first().scrollDelta.y
                    if (delta != 0f) {
                        // 只在当前方向还滚得动时接管滚轮：滚到头就放行，让父级 LazyColumn 继续竖直滚动。
                        val canScroll = if (delta > 0) listState.canScrollForward else listState.canScrollBackward
                        if (canScroll) {
                            scope.launch { listState.scrollBy(delta * SCROLL_FACTOR) }
                            event.changes.forEach { it.consume() }
                        }
                    }
                },
            horizontalArrangement = Arrangement.spacedBy(TILE_GAP.dp),
        ) {
            items(photos, key = { it.id }) { photo ->
                PhotoThumb(photo, slots[photo.id], tile, canCopy, { mode -> onAssign(photo, mode) })
            }
            if (overflow > 0) {
                item(key = "overflow") { OverflowTile(overflow, tile) }
            }
        }
        // 叠在缩略图上沿而不是另起一行：不改变格子尺寸与卡片高度，滚起来才出现。
        if (listState.canScrollBackward || listState.canScrollForward) {
            HorizontalScrollbar(
                adapter = rememberScrollbarAdapter(listState),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = PmSpace.m)
                    .height(6.dp),
            )
        }
    }
}

@Composable
private fun PhotoThumb(
    photo: PhotoInfo,
    slot: Int?,
    tile: Int,
    canCopy: Boolean,
    onAssign: (AssignMode) -> Unit,
) {
    val tone = PmColor.placeholder[(photo.id.hashCode() and 0x7fffffff) % PmColor.placeholder.size]
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    // 按格子实际像素取图（×2 供 150%+ 缩放时仍然锐利）；命中缓存时是同步返回的。
    val px = with(LocalDensity.current) { tile.dp.roundToPx() }
    val image = rememberPhotoImage(photo.sourceRef, maxEdge = (px * 2).coerceAtLeast(64))
    Box(
        Modifier
            .size(tile.dp)
            .clip(RoundedCornerShape(PmRadius.control))
            .background(tone)
            // hoverable（不是 clickable）：格子本身不承担点击 —— 点它是"打开这个事件"，
            //   那是整张卡片 CardFrame 的职责。这里只需要知道鼠标在不在它上面。
            .hoverable(interaction),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = photo.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        } else {
            // 还没解码完 / 解不出来（HEIC、文件被移走）：退回落色块，格子里不留空白。
            Text(
                photo.displayName.substringAfterLast('.', "").uppercase().ifEmpty { "IMG" },
                style = PmType.micro,
                color = PmColor.ink3,
            )
        }
        // 时刻压在图上：深色胶囊 + 白字，浅色照片/深色照片都读得清（原来是浅底上居中一行灰字）。
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
        }
        if (slot != null) {
            Box(
                Modifier
                    // 角标放左上：左下/右下已经给了时刻与派生点，图上只有两个角是"干净"的。
                    .align(Alignment.TopStart)
                    .padding(3.dp)
                    .clip(RoundedCornerShape(PmRadius.control))
                    .background(PmColor.accent)
                    .padding(horizontal = 4.dp),
            ) {
                Text("照$slot", style = PmType.micro, color = PmColor.surface)
            }
        }
        if (photo.isDerived) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(3.dp)
                    .size(5.dp)
                    .clip(RoundedCornerShape(PmRadius.pill))
                    .background(Color.White.copy(alpha = 0.9f)),
            )
        }
        if (hovered) {
            ThumbActions(canCopy = canCopy, onAssign = onAssign)
        }
    }
}

/**
 * 缩略图的悬停操作层：蒙一层半透明深底，里面是「移动」「复制」两个按钮。
 *
 * ★ 为什么要蒙底：格子本身的背景是一组浅色占位色，白底黑字的按钮压在上面会看不清边界，
 *   用户不确定"我点下去点的是哪个动词"。加深色底等于临时把这张格子变成一个操作台。
 * ★ 按钮必须带文字：15 dp 图标 + 10 sp 文字竖排。只给图标的话，"两只箭头=移动、两张纸=复制"
 *   这种约定在一个 72 dp 的格子里是猜的，猜错一次就是一次误移动（移动改的是归属关系）。
 */
@Composable
private fun BoxScope.ThumbActions(canCopy: Boolean, onAssign: (AssignMode) -> Unit) {
    Row(
        Modifier
            .matchParentSize()
            .background(PmColor.ink.copy(alpha = 0.66f)),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ThumbAction(Icons.Filled.SwapHoriz, "移动") { onAssign(AssignMode.MOVE) }
        if (canCopy) {
            ThumbAction(Icons.Filled.ContentCopy, "复制") { onAssign(AssignMode.COPY) }
        }
    }
}

@Composable
private fun ThumbAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Column(
        Modifier
            .width(38.dp)
            .clip(RoundedCornerShape(PmRadius.control))
            .background(if (hovered) Color.White.copy(alpha = 0.18f) else Color.Transparent)
            .pointerHoverIcon(PointerIcon.Hand)
            // ★ 这里的 clickable 会消费掉按下事件，所以点按钮不会顺带打开整个事件的照片弹窗。
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(15.dp))
        Spacer(Modifier.height(2.dp))
        Text(label, style = PmType.micro, color = Color.White, maxLines = 1)
    }
}

@Composable
private fun OverflowTile(count: Int, tile: Int) {
    Box(
        Modifier
            .size(tile.dp)
            .clip(RoundedCornerShape(PmRadius.control))
            .background(PmColor.surface2)
            .border(1.dp, PmColor.line, RoundedCornerShape(PmRadius.control)),
        contentAlignment = Alignment.Center,
    ) {
        Text("+$count", style = PmType.numeric, color = PmColor.ink3)
    }
}
