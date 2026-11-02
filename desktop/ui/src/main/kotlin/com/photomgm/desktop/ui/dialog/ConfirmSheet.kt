// ui/dialog/ConfirmSheet.kt —— 重新分析确认
//
// ★ 唯一需要二次确认的动作。理由：core 没有撤销栈，重新分析会**丢弃全部人工调整**
//   （移到事件的照片回到自动分类、指定的台账照片回到默认）。这不是"再点一次就好"的代价，
//   而是几十次手工核对白做。所以宁可多一次点击，也不给它一个后悔不了的结果。
package com.photomgm.desktop.ui.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.photomgm.desktop.core.api.AppState
import com.photomgm.desktop.ui.app.Actions
import com.photomgm.desktop.ui.component.ButtonKind
import com.photomgm.desktop.ui.component.PmButton
import com.photomgm.desktop.ui.component.Tone
import com.photomgm.desktop.ui.component.fg
import com.photomgm.desktop.ui.model.UiState
import com.photomgm.desktop.ui.theme.PmColor
import com.photomgm.desktop.ui.theme.PmRadius
import com.photomgm.desktop.ui.theme.PmSpace
import com.photomgm.desktop.ui.theme.PmType

@Composable
fun ConfirmReanalyzeSheet(
    state: AppState,
    ui: UiState,
    onUi: (UiState) -> Unit,
    actions: Actions,
) {
    val moved = state.events.sumOf { it.photoIds.size } - state.analysis?.matchedCount.let { it ?: 0 }
    SheetHost(onDismiss = { onUi(ui.copy(sheet = null)) }) { dragHandle ->
        Column(
            Modifier
                .width(420.dp)
                .then(dragHandle)
                .clip(RoundedCornerShape(PmRadius.panel))
                .background(PmColor.surface)
                .padding(PmSpace.xl),
        ) {
            Text("重新分析这一天的照片？", style = PmType.title, color = PmColor.ink)
            Spacer(Modifier.height(PmSpace.s))
            Text(
                "会重新解析日志、重扫源目录、重读 EXIF，并按 GPS 与时间重新分组。" +
                    "手动移动的照片和指定的台账照片会被丢弃，回到自动分类结果。",
                style = PmType.body,
                color = PmColor.ink2,
            )
            if (moved > 0) {
                Spacer(Modifier.height(PmSpace.m))
                Text(
                    "当前有 $moved 张照片的位置与自动结果不同。",
                    style = PmType.caption,
                    color = Tone.WARN.fg(),
                )
            }
            Spacer(Modifier.height(PmSpace.m))
            Text(
                "只想还原分类、不重扫目录的话，用「设置 → 恢复最初分类」更快。",
                style = PmType.caption,
                color = PmColor.ink3,
            )
            Spacer(Modifier.height(PmSpace.xl))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                PmButton("取消", { onUi(ui.copy(sheet = null)) }, kind = ButtonKind.SECONDARY)
                Spacer(Modifier.width(PmSpace.m))
                PmButton(
                    "重新分析",
                    {
                        onUi(ui.copy(sheet = null))
                        actions.analyze()
                    },
                    kind = ButtonKind.PRIMARY,
                )
            }
        }
    }
}
