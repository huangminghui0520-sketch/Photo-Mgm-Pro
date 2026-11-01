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
package com.photomgm.desktop.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.photomgm.desktop.core.api.EventSnapshot
import com.photomgm.desktop.core.api.PhotoInfo
import com.photomgm.desktop.ui.format.hhmm
import com.photomgm.desktop.ui.format.timeRange
import com.photomgm.desktop.ui.model.CARD_COUNT_W
import com.photomgm.desktop.ui.model.CARD_HEADER_H
import com.photomgm.desktop.ui.model.CARD_INDEX_W
import com.photomgm.desktop.ui.model.CARD_PAD
import com.photomgm.desktop.ui.model.CARD_TIME_W
import com.photomgm.desktop.ui.model.EMPTY_STRIP_H
import com.photomgm.desktop.ui.model.TILE_GAP
import com.photomgm.desktop.ui.model.WorkQueue
import com.photomgm.desktop.ui.theme.PmColor
import com.photomgm.desktop.ui.theme.PmRadius
import com.photomgm.desktop.ui.theme.PmSpace
import com.photomgm.desktop.ui.theme.PmType

@Composable
fun EventCard(
    index: Int,
    event: EventSnapshot,
    photos: List<PhotoInfo>,
    slots: Map<Long, Int>,
    tile: Int,
    maxTiles: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val needReview = WorkQueue.needsReview(event)
    CardFrame(
        onClick = onClick,
        modifier = modifier,
        // 待确认的卡片换强调色描边：不新增色块、不改栅格，但在一列白卡片里能一眼挑出来。
        accent = needReview,
    ) {
        CardHeader(
            index = index,
            time = timeRange(event.startTimeMs, event.endTimeMs),
            timeTone = if (event.isPending) PmColor.warn else PmColor.ink,
            title = event.location,
            detail = event.description,
            indexAccent = needReview,
            tags = { WorkQueue.issueLabels(event).forEach { label ->
                Tag(label, if (label == "无照片") Tone.DANGER else Tone.WARN)
                Spacer(Modifier.width(PmSpace.xs))
            } },
            photoCount = photos.size,
        )
        Spacer(Modifier.height(PmSpace.m))
        PhotoStrip(photos = photos, slots = slots, tile = tile, maxTiles = maxTiles, event = event)
    }
}

/** 未匹配照片池：固定排在列表末尾，形状与事件卡片完全一致（它就是"第 N+1 个待处理单元"）。 */
@Composable
fun UnmatchedCard(
    photos: List<PhotoInfo>,
    tile: Int,
    maxTiles: Int,
    onClick: () -> Unit,
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
        PhotoStrip(photos = photos, slots = emptyMap(), tile = tile, maxTiles = maxTiles, event = null)
    }
}

/**
 * 卡片外壳：白底 + 1 dp 描边 + 统一内边距。
 *
 * `accent` = 需要人处理（待确认 / 未匹配），把描边换成强调色的一层淡色；
 * `warm` = 未匹配池专用的暖底（它不是一个"事件"，用底色区分，而不是加一个标题前缀）。
 */
@Composable
private fun CardFrame(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    warm: Boolean = false,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(PmRadius.panel)
    val borderTone = when {
        warm && hovered -> PmColor.warn
        warm -> PmColor.warn.copy(alpha = 0.35f)
        accent && hovered -> PmColor.accent.copy(alpha = 0.75f)
        accent -> PmColor.accent.copy(alpha = 0.28f)
        hovered -> PmColor.lineStrong
        else -> PmColor.line
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (warm) PmColor.warnSoft.copy(alpha = 0.55f) else PmColor.surface)
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
            color = if (photoCount == 0) PmColor.danger else PmColor.ink2,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(CARD_COUNT_W.dp),
        )
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = PmColor.ink4, modifier = Modifier.size(16.dp))
    }
}

/**
 * 照片条：一行等高的格子，放不下就末位「+N」，绝不横向滚动
 * （一旦可横滑，用户就看不到"这个事件到底几张"，而"几张"正是要判断的东西）。
 */
@Composable
private fun PhotoStrip(
    photos: List<PhotoInfo>,
    slots: Map<Long, Int>,
    tile: Int,
    maxTiles: Int,
    event: EventSnapshot?,
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

    // 放不下时少显示一格、末位留给「+N」；maxTiles 至少为 1（真实宽度下 ≥7），这里只做防御。
    val shown = if (photos.size > maxTiles) (maxTiles - 1).coerceAtLeast(1) else photos.size
    val overflow = photos.size - shown

    Row(horizontalArrangement = Arrangement.spacedBy(TILE_GAP.dp)) {
        photos.take(shown).forEach { photo ->
            PhotoThumb(photo, slots[photo.id], tile)
        }
        if (overflow > 0) OverflowTile(overflow, tile)
    }
}

@Composable
private fun PhotoThumb(photo: PhotoInfo, slot: Int?, tile: Int) {
    val tone = PmColor.placeholder[(photo.id.hashCode() and 0x7fffffff) % PmColor.placeholder.size]
    Box(
        Modifier
            .size(tile.dp)
            .clip(RoundedCornerShape(PmRadius.control))
            .background(tone),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            photo.captureTimeMs?.let { hhmm(it) } ?: "无时间",
            style = PmType.micro,
            color = if (photo.captureTimeMs == null) PmColor.warn else PmColor.ink2,
        )
        if (slot != null) {
            Box(
                Modifier
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
                    .align(Alignment.BottomEnd)
                    .padding(3.dp)
                    .size(5.dp)
                    .clip(RoundedCornerShape(PmRadius.pill))
                    .background(PmColor.ink3),
            )
        }
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
