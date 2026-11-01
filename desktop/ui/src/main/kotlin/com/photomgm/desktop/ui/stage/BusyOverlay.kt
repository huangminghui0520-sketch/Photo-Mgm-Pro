// ui/stage/BusyOverlay.kt —— 分析/导出进行中
//
// ★ 为什么不是"进度条挂在状态栏"：分析和导出期间**核心会拒绝写操作**
//   （analyze 在 EXPORTING 时直接返回、movePhotos 同理），如果界面还留着可点的按钮，
//   用户点下去没反应，会以为程序卡死。所以覆盖主内容区，只留一个「取消」。
// ★ 取消的语义必须写在界面上：
//   分析取消 = 丢弃这一轮（重新分析从零开始）；
//   导出取消 = **保留已复制的文件**，下次导出自动续传（manifest 在导出根里）。
//   这两句不一样，不能合并成"取消"两个字。
package com.photomgm.desktop.ui.stage

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.photomgm.desktop.core.api.AppState
import com.photomgm.desktop.ui.component.ButtonKind
import com.photomgm.desktop.ui.component.PmButton
import com.photomgm.desktop.ui.model.Busy
import com.photomgm.desktop.ui.theme.PmColor
import com.photomgm.desktop.ui.theme.PmRadius
import com.photomgm.desktop.ui.theme.PmSpace
import com.photomgm.desktop.ui.theme.PmType

@Composable
fun BusyOverlay(busy: Busy, state: AppState, onCancel: () -> Unit) {
    val stageText = when (busy) {
        Busy.ANALYZE -> state.progress?.stage ?: "准备中"
        Busy.EXPORT -> state.exportProgress?.stage ?: "准备导出"
    }
    val fraction = when (busy) {
        Busy.ANALYZE -> state.progress?.fraction ?: 0f
        Busy.EXPORT -> state.exportProgress?.fraction ?: 0f
    }
    val counter = when (busy) {
        Busy.ANALYZE -> state.progress?.let { p -> if (p.total > 0) "${p.current} / ${p.total}" else "" } ?: ""
        Busy.EXPORT -> state.exportProgress?.let { p -> if (p.total > 0) "${p.copied} / ${p.total}" else "" } ?: ""
    }

    Box(
        Modifier.fillMaxSize().background(PmColor.canvas.copy(alpha = 0.97f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(460.dp)
                .clip(RoundedCornerShape(PmRadius.panel))
                .background(PmColor.surface)
                .padding(PmSpace.xl),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(busy.label, style = PmType.title, color = PmColor.ink)
                Spacer(Modifier.weight(1f))
                if (counter.isNotEmpty()) {
                    Text(counter, style = PmType.numeric, color = PmColor.ink2)
                }
            }
            Spacer(Modifier.height(PmSpace.m))

            // 自己画进度条：M3 的 LinearProgressIndicator 在不同版本里参数名不一致，
            // 而这里只需要"填充比例"这一件事，自绘更可控。
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(PmRadius.pill))
                    .background(PmColor.surface2),
            ) {
                if (fraction > 0f) {
                    Box(
                        Modifier
                            .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                            .height(6.dp)
                            .clip(RoundedCornerShape(PmRadius.pill))
                            .background(PmColor.accent),
                    )
                } else {
                    // 无总量（解析日志 / 扫描目录阶段）：不确定进度，用浅色整条提示"在跑"。
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(PmRadius.pill))
                            .background(PmColor.accentSoft),
                    )
                }
            }

            Spacer(Modifier.height(PmSpace.m))
            Text(stageText, style = PmType.body, color = PmColor.ink2)

            val detail = when (busy) {
                Busy.ANALYZE -> "读 EXIF 与 GPS 分组时最慢；这段时间界面不做其他写入。"
                Busy.EXPORT -> state.exportProgress?.currentFile?.takeIf { it.isNotBlank() } ?: "正在准备目标目录"
            }
            Text(
                detail,
                style = PmType.caption,
                color = PmColor.ink3,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(PmSpace.l))
            Text(
                when (busy) {
                    Busy.ANALYZE -> "取消会丢弃这一轮结果，重新分析从零开始。"
                    Busy.EXPORT -> "取消会在下一个文件边界停下，已复制的文件保留，下次导出自动续传。"
                },
                style = PmType.caption,
                color = PmColor.ink3,
            )
            Spacer(Modifier.height(PmSpace.m))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PmButton("取消", onCancel, kind = ButtonKind.SECONDARY, icon = Icons.Filled.Close)
                Spacer(Modifier.weight(1f))
                Text("请勿关闭窗口", style = PmType.caption, color = PmColor.ink3)
            }
        }
    }
}
