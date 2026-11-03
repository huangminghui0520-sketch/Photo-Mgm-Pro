// app/ui/component/EventCard.kt —— 事件卡片
// ★ 2026-10-07 需求：事件列表统一加边框——正常事件 1dp 轮廓边框；分类不明确（unclear）2dp 红框。
package com.photomgm.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import algorithm.model.Photo

// ──────────────────────────────────────────────────────────────────
// 事件卡片
// ──────────────────────────────────────────────────────────────────
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EventCard(
    eventId: Int,
    title: String,
    time: String,
    location: String,
    description: String,
    photoCount: Int,
    marked: Pair<Long?, Long?>?,
    onOpenSheet: () -> Unit,
    photoThumbs: List<Photo>? = null,
    unclear: Boolean = false,
) {
    val errorColor = MaterialTheme.colorScheme.error
    // ★ 2026-10-07 需求：事件列表统一加边框——正常事件 1dp 轮廓边框；分类不明确（unclear）2dp 红框。
    //   点击边框内任意位置（整个卡片）→ onOpenSheet 直接弹窗查看该事件全部照片。
    val outer = Modifier
        .fillMaxWidth()
        .padding(2.dp)
        .then(
            if (unclear) Modifier.border(2.dp, errorColor, MaterialTheme.shapes.large)
            else Modifier.border(
                1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large
            )
        )

    Card(
        onClick = onOpenSheet,
        modifier = outer,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (unclear) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        "分类不明确 · 需人工复查",
                        Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = if (photoCount == 0) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        "${photoCount}张",
                        Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = if (photoCount == 0) MaterialTheme.colorScheme.onErrorContainer
                        else MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    modifier = Modifier.weight(1f),
                )
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Text(
                        time,
                        Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.padding(start = 2.dp),
            ) {
                Text(
                    "🗺 $location",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(description, style = MaterialTheme.typography.bodySmall)
            }
            if (photoThumbs != null && photoThumbs.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    photoThumbs.take(6).forEach { p ->
                        Thumb(
                            model = p.sourceRef,
                            name = p.displayName,
                            thumbSize = 56.dp,
                            // ★ 2026-10-07 修复：Thumb 内部有 combinedClickable，不传 onClick 会吞掉点击、
                            //   事件不冒泡到卡片 → 点缩略图无法进入照片弹窗。传 onOpenSheet 后点击缩略图同样打开弹窗。
                            onClick = onOpenSheet,
                        )
                    }
                    if (photoThumbs.size > 6) {
                        Box(
                            Modifier
                                .size(56.dp)
                                .background(
                                    MaterialTheme.colorScheme.surfaceContainerHighest,
                                    RoundedCornerShape(12.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "+${photoThumbs.size - 6}",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (marked != null) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(999.dp),
                ) {
                    Text(
                        "已人工标记 · 照片1 ${marked.first?.let { "✓" } ?: "-"} · 照片2 ${marked.second?.let { "✓" } ?: "-"}",
                        Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
    }
}
