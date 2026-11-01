// ui/component/Press.kt —— 无涟漪的点按
//
// ★ 为什么不用 material 的 clickable：它会给每个格子叠一层涟漪，而一次 Ctrl 多选
//   可能连点十张照片，涟漪叠在一起既拖慢合成，也让人误以为"正在动的是刚点的那张"。
//   桌面端的选择动作需要的是"边框立刻变"，不是"水波纹扩散"。
package com.photomgm.desktop.ui.component

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.pointerInput

/** 按下即回调，并把 Ctrl 是否按下一起交出去（多选）。 */
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
