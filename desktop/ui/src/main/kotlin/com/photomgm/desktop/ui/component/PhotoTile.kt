// ui/component/PhotoTile.kt —— 照片格
//
// ★ 缩略图属于 U1.3（PcThumbnailProvider）之后再接；这一版**不画假图**。
//   占位块用 id 派生的稳定灰阶 + 扩展名，保证"同一张照片每次都是同一种灰"，
//   这样在缩略图接入前后，用户对"第几行第几列是哪张"的空间记忆不会失效。
//   如果这里用随机彩色块，接入真实缩略图的那天，整屏的视觉地图会全部重排。
package com.photomgm.desktop.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.photomgm.desktop.core.api.PhotoInfo
import com.photomgm.desktop.ui.format.photoMeta
import com.photomgm.desktop.ui.theme.PmColor
import com.photomgm.desktop.ui.theme.PmRadius
import com.photomgm.desktop.ui.theme.PmSpace
import com.photomgm.desktop.ui.theme.PmType

/**
 * 点击 + Ctrl 多选。
 *
 * ★ 不用 material 的 clickable：它会给每个格子叠一层涟漪，而桌面端一次框选可能扫过
 *   二十张照片，涟漪叠在一起会拖慢合成并让人误以为"选中了正在动的那张"。
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.pmPress(onPress: (additive: Boolean) -> Unit): Modifier = this.pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            if (event.type == PointerEventType.Press) {
                onPress(event.keyboardModifiers.isCtrlPressed)
            }
        }
    }
}

@Composable
fun PhotoTile(
    photo: PhotoInfo,
    selected: Boolean,
    ledgerSlot: Int?,
    onClick: (additive: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var hovered by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(PmRadius.card)
    val tone = PmColor.placeholder[(photo.id.hashCode() and 0x7fffffff) % PmColor.placeholder.size]
    val ext = photo.displayName.substringAfterLast('.', "").uppercase().ifEmpty { "IMG" }

    Box(
        modifier = modifier
            .clip(shape)
            .background(if (selected) PmColor.accentSoft else PmColor.surface)
            .border(1.dp, if (selected || hovered) PmColor.accent.copy(alpha = if (selected) 1f else 0.45f) else PmColor.line, shape)
            .pointerHoverIcon(PointerIcon.Hand)
            .pmPress(onClick)
            .padding(PmSpace.s),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
                    .clip(RoundedCornerShape(PmRadius.control))
                    .background(tone),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.Image,
                        contentDescription = null,
                        tint = PmColor.ink4,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.height(PmSpace.xxs))
                    Text(ext, style = PmType.micro, color = PmColor.ink3)
                }

                if (ledgerSlot != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(PmSpace.xs)
                            .clip(RoundedCornerShape(PmRadius.control))
                            .background(PmColor.accent)
                            .padding(horizontal = PmSpace.s, vertical = 1.dp),
                    ) {
                        Text("照片$ledgerSlot", style = PmType.micro, color = PmColor.surface)
                    }
                }

                if (photo.isDerived) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(PmSpace.xs)
                            .clip(RoundedCornerShape(PmRadius.control))
                            .background(PmColor.surface.copy(alpha = 0.92f))
                            .padding(horizontal = PmSpace.s, vertical = 1.dp),
                    ) {
                        Text("派生", style = PmType.micro, color = PmColor.ink3)
                    }
                }

                if (selected) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(PmSpace.xs)
                            .size(18.dp)
                            .clip(RoundedCornerShape(PmRadius.control))
                            .background(PmColor.accent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = "已选中",
                            tint = PmColor.surface,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(PmSpace.s))
            // ★ 必须 fillMaxWidth：否则长文件名会把格子撑得比单元格还宽，
            //   多列时第三列直接被窗口裁掉（984 宽的实拍截图里就是这样）。
            Text(
                photo.displayName,
                style = PmType.caption,
                color = if (selected) PmColor.accent else PmColor.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    photoMeta(photo),
                    style = PmType.caption,
                    color = PmColor.ink3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (!photo.hasGps) {
                    Spacer(Modifier.width(PmSpace.xs))
                    Box(
                        Modifier
                            .size(4.dp)
                            .clip(RoundedCornerShape(PmRadius.pill))
                            .background(PmColor.warn),
                    )
                }
            }
        }
    }
}

/** 空照片区（该事件没有照片 / 未匹配池为空）。 */
@Composable
fun EmptyPhotoArea(text: String, hint: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(PmSpace.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.Image, contentDescription = null, tint = PmColor.ink4, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(PmSpace.m))
        Text(text, style = PmType.bodyStrong, color = PmColor.ink2)
        Spacer(Modifier.height(2.dp))
        Text(hint, style = PmType.caption, color = PmColor.ink3)
    }
}
