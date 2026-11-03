// app/ui/component/Thumb.kt —— 缩略图主体（方形圆角 · 2.5dp 选中边框 · 圆形对勾叠层 · 槽号标记）
// ★ 2026-10-07 UI 重建：功能等价。
package com.photomgm.app.ui.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import algorithm.model.Photo
import com.photomgm.app.ThumbnailSpec

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
// 缩略图单元格：8dp 安全边距 + 2.5dp 主色边框 + 圆形对勾叠层 + 底部标准 Checkbox
// ──────────────────────────────────────────────────────────────────
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ThumbCell(
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
