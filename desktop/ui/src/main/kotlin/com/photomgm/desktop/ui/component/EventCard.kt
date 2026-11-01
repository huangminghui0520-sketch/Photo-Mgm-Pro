// ui/component/EventCard.kt —— 事件卡片（事件 + 它的照片行）
//
// ★ 这一版把"事件"和"照片"合并成一个可扫视的单元。
//   上一版事件在左列、照片在中间，用户要自己把两者对起来；而 core 里
//   `EventSnapshot.photoIds` 本来就是事件的字段 —— 卡片是数据形状的直接投影。
//
// ★ 卡片里的照片格**不显示文件名**，显示拍摄时刻（08:14）。
//   复核时真正要判断的是"这个事件的时间窗里，照片对不对、有没有漏"，
//   文件名（IMG_20261101_081432.jpg）在那个判断里没有信息量，时刻有。
//   文件名留给弹窗，那里空间够。
//
// ★ 一行放不下就少放几格 + 末位「+N」，不给横向滚动条：
//   一旦可横滑，用户就看不到"这个事件到底几张"，而"几张"正是要判断的东西。
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.photomgm.desktop.core.api.EventSnapshot
import com.photomgm.desktop.core.api.PhotoInfo
import com.photomgm.desktop.ui.format.hhmm
import com.photomgm.desktop.ui.format.timeRange
import com.photomgm.desktop.ui.model.CARD_PAD
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
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val needReview = WorkQueue.needsReview(event)
    val shape = RoundedCornerShape(PmRadius.panel)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(PmColor.surface)
            .border(1.dp, if (hovered) PmColor.lineStrong else PmColor.line, shape)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(CARD_PAD.dp),
    ) {
        CardHeader(
            index = index,
            event = event,
            photoCount = photos.size,
            needReview = needReview,
        )
        Spacer(Modifier.height(PmSpace.m))
        PhotoStrip(photos = photos, slots = slots, tile = tile, maxTiles = maxTiles, event = event)
    }
}

/** 未匹配照片池：固定排在列表末尾，形状与事件卡片一致（它就是"第 N+1 个待处理单元"）。 */
@Composable
fun UnmatchedCard(
    photos: List<PhotoInfo>,
    tile: Int,
    maxTiles: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(PmRadius.panel)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(PmColor.warnSoft.copy(alpha = 0.55f))
            .border(1.dp, if (hovered) PmColor.warn else PmColor.warn.copy(alpha = 0.35f), shape)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(CARD_PAD.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.WarningAmber, contentDescription = null, tint = PmColor.warn, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(PmSpace.s))
            Text("未匹配照片", style = PmType.bodyStrong, color = PmColor.ink)
            Spacer(Modifier.width(PmSpace.m))
            Text(
                "没有归到任何事件，导出时进 99_未分类/",
                style = PmType.caption,
                color = PmColor.ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text("${photos.size} 张", style = PmType.numeric, color = PmColor.warn)
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = PmColor.ink4, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.height(PmSpace.m))
        PhotoStrip(photos = photos, slots = emptyMap(), tile = tile, maxTiles = maxTiles, event = null)
    }
}

@Composable
private fun CardHeader(
    index: Int,
    event: EventSnapshot,
    photoCount: Int,
    needReview: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // 序号固定宽：不因 9→10 而让整列错位（那是表格里最刺眼的一种抖动）
        Text(
            "%02d".format(index),
            style = PmType.numeric,
            color = if (needReview) PmColor.accent else PmColor.ink3,
            modifier = Modifier.width(24.dp),
        )
        Text(
            timeRange(event.startTimeMs, event.endTimeMs),
            style = PmType.numeric,
            color = if (event.isPending) PmColor.warn else PmColor.ink,
            maxLines = 1,
            modifier = Modifier.width(TRANGE_W),
        )
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = PmColor.ink, fontWeight = FontWeight.Medium)) { append(event.location) }
                if (event.description.isNotBlank()) {
                    append("  ")
                    withStyle(SpanStyle(color = PmColor.ink2)) { append(event.description) }
                }
            },
            style = PmType.body,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(PmSpace.m))
        WorkQueue.issueLabels(event).forEach { label ->
            Tag(label, if (label == "无照片") Tone.DANGER else Tone.WARN)
            Spacer(Modifier.width(PmSpace.xs))
        }
        if (photoCount > 0) {
            Text("$photoCount 张", style = PmType.numeric, color = PmColor.ink2)
        }
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = PmColor.ink4, modifier = Modifier.size(16.dp))
    }
}

private val TRANGE_W = 104.dp

@Composable
private fun PhotoStrip(
    photos: List<PhotoInfo>,
    slots: Map<Long, Int>,
    tile: Int,
    maxTiles: Int,
    event: EventSnapshot?,
) {
    if (photos.isEmpty()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(tile.dp * 0.62f)
                .clip(RoundedCornerShape(PmRadius.control))
                .background(PmColor.surface2),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (event?.isPending == true) "待定时间事件：照片不会被自动归入，需要手动指定" else "这个事件没有照片，导出时会生成空文件夹",
                style = PmType.caption,
                color = PmColor.ink3,
            )
        }
        return
    }

    val shown = if (photos.size > maxTiles) maxTiles - 1 else photos.size
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
