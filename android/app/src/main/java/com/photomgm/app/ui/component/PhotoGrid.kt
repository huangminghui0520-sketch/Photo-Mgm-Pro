// app/ui/component/PhotoGrid.kt —— 照片网格（3 列）与瓦片单元
package com.photomgm.app.ui.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import app.api.PhotoInfo
import com.photomgm.app.ThumbnailSpec
import com.photomgm.app.ui.PhotoAnomaly
import com.photomgm.app.ui.model.tileTimeFmt
import java.time.Instant

// ──────────────────────────────────────────────────────────────────
// 弹窗网格单元格
// 手势：浏览模式 单击=查看大图 / 长按=多选；多选模式 单击=切换选中
// ──────────────────────────────────────────────────────────────────
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PhotoTile(
    p: PhotoInfo,
    selected: Boolean,
    selectionMode: Boolean,
    markedSlot: Int?,
    anomaly: PhotoAnomaly?,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .then(
                when {
                    selected -> Modifier.border(
                        width = 3.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(10.dp),
                    )
                    anomaly != null -> Modifier.border(
                        width = 2.dp,
                        color = MaterialTheme.colorScheme.error,
                        shape = RoundedCornerShape(10.dp),
                    )
                    else -> Modifier
                }
            )
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .combinedClickable(onClick = onTap, onLongClick = onLongPress),
    ) {
        // ★ W5(#3)：照片网格同样走 ThumbnailSpec，与预加载共用尺寸与内存缓存键
        val ctx = LocalContext.current
        val thumbReq = remember(ctx, p.sourceRef) { ThumbnailSpec.request(ctx, p.sourceRef) }
        AsyncImage(
            model = thumbReq,
            contentDescription = p.displayName,
            modifier = Modifier.fillMaxSize(),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        )
        if (selected) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
            Icon(
                Icons.Filled.Check, null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(22.dp),
            )
        }
        if (anomaly != null && !selected) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp),
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.error,
            ) {
                Row(
                    Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Warning, anomaly.detail,
                        Modifier.size(10.dp),
                        tint = MaterialTheme.colorScheme.onError,
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(
                        anomaly.label,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = MaterialTheme.colorScheme.onError,
                    )
                }
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
        p.captureTimeMs?.let { ms ->
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 5.dp, bottom = 5.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            ) {
                Text(
                    tileTimeFmt.format(
                        Instant.ofEpochMilli(ms).atZone(algorithm.AppZone.ID)
                    ),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = Color.White,
                )
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────
// 照片网格（3 列）
// ──────────────────────────────────────────────────────────────────
@Composable
internal fun PhotoGrid(
    photos: List<PhotoInfo>,
    selected: Set<Long>,
    marked: Pair<Long?, Long?>?,
    anomalies: Map<Long, PhotoAnomaly>,
    onTap: (PhotoInfo, Boolean) -> Unit,
    onLongPress: (PhotoInfo) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        gridItems(photos, key = { it.id }) { p ->
            val isSel = p.id in selected
            val slot = when {
                marked?.first == p.id -> 1
                marked?.second == p.id -> 2
                else -> null
            }
            PhotoTile(
                p = p,
                selected = isSel,
                selectionMode = selected.isNotEmpty(),
                markedSlot = slot,
                anomaly = anomalies[p.id],
                onTap = { onTap(p, isSel) },
                onLongPress = { onLongPress(p) },
            )
        }
    }
}
