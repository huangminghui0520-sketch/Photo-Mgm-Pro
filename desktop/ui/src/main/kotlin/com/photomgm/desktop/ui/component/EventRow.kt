// ui/component/EventRow.kt —— 事件行（复核阶段的绝对主体）
//
// ★ 为什么事件是行、照片是格：一个事件 = 台账一行 = 导出的一个文件夹（NN_事件/）。
//   照片只是这个事件的附属物。旧的"照片网格 + 事件侧栏"把两者并列，
//   每次复核都要在脑内做一次 join；这里把 join 做进结构里：选中事件 → 它的照片。
//
// 行内信息优先级（空间紧张时从上往下砍）：
//   ① 事件编号 + 类型（决定这是哪一类病害）
//   ② 时间（决定它和相邻事件是不是同一趟）
//   ③ 地点（桩号）
//   ④ 照片数 / 问题角标（决定要不要点进去）
//   ⑤ 描述（最长，最先被截断）
package com.photomgm.desktop.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.photomgm.desktop.core.api.EventSnapshot
import com.photomgm.desktop.ui.format.timeRange
import com.photomgm.desktop.ui.model.WorkQueue
import com.photomgm.desktop.ui.theme.PmColor
import com.photomgm.desktop.ui.theme.PmRadius
import com.photomgm.desktop.ui.theme.PmSpace
import com.photomgm.desktop.ui.theme.PmType

@Composable
fun EventRow(
    index: Int,
    event: EventSnapshot,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var hovered by remember { mutableStateOf(false) }
    val issues = WorkQueue.issueLabels(event)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                when {
                    selected -> PmColor.accentSoft
                    hovered -> PmColor.surface2
                    else -> PmColor.surface
                },
            )
            .pmPress { onClick() }
            .padding(start = PmSpace.m, end = PmSpace.m, top = PmSpace.s, bottom = PmSpace.s),
    ) {
        // 左侧选中条：2dp 定宽，选中时才上色。不改变布局宽度，行不会因选中而抖动。
        Box(
            Modifier
                .width(2.dp)
                .height(56.dp)
                .clip(RoundedCornerShape(PmRadius.pill))
                .background(if (selected) PmColor.accent else PmColor.surface),
        )
        Spacer(Modifier.width(PmSpace.m))

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    index.toString().padStart(2, '0'),
                    style = PmType.numeric,
                    color = if (selected) PmColor.accent else PmColor.ink3,
                )
                Spacer(Modifier.width(PmSpace.s))
                Text(
                    event.eventType.ifBlank { "未分类" },
                    style = PmType.bodyStrong,
                    color = PmColor.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "${event.photoIds.size} 张",
                    style = PmType.numeric,
                    color = if (event.photoIds.isEmpty()) PmColor.warn else PmColor.ink3,
                )
            }

            Spacer(Modifier.height(2.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(timeRange(event.startTimeMs, event.endTimeMs), style = PmType.numeric, color = PmColor.ink2)
                Spacer(Modifier.width(PmSpace.s))
                Text(
                    event.location.ifBlank { "（无桩号）" },
                    style = PmType.caption,
                    color = PmColor.ink2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }

            if (issues.isNotEmpty()) {
                Spacer(Modifier.height(PmSpace.xs))
                Row {
                    issues.forEach { label ->
                        val tone = when {
                            label.startsWith("无照片") -> Tone.WARN
                            label.startsWith("无时间") -> Tone.DANGER
                            else -> Tone.INFO
                        }
                        Tag(label, tone)
                        Spacer(Modifier.width(PmSpace.xs))
                    }
                }
            } else if (event.description.isNotBlank()) {
                Spacer(Modifier.height(PmSpace.xxs))
                Text(
                    event.description,
                    style = PmType.caption,
                    color = PmColor.ink3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 未匹配池在列表里的固定行（它不是事件，但必须和事件同列可达，否则照片会被忘记）。 */
@Composable
fun UnmatchedRow(count: Int, selected: Boolean, onClick: () -> Unit) {
    var hovered by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) PmColor.accentSoft else if (hovered) PmColor.surface2 else PmColor.surface)
            .pmPress { onClick() }
            .padding(start = PmSpace.m, end = PmSpace.m, top = PmSpace.s, bottom = PmSpace.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(2.dp)
                .height(36.dp)
                .clip(RoundedCornerShape(PmRadius.pill))
                .background(if (selected) PmColor.accent else PmColor.surface),
        )
        Spacer(Modifier.width(PmSpace.m))
        Column(Modifier.weight(1f)) {
            Text("未匹配照片", style = PmType.bodyStrong, color = PmColor.ink)
            Text(
                if (count == 0) "全部照片都已归属事件" else "$count 张没有归属，导出时进 99_未分类",
                style = PmType.caption,
                color = if (count == 0) PmColor.ink3 else PmColor.warn,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(count.toString(), style = PmType.numeric, color = if (count == 0) PmColor.ink3 else PmColor.warn)
    }
}
