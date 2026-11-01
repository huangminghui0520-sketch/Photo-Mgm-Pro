// ui/component/Press.kt —— 无涟漪的点按
//
// ★ 为什么不用 material 的 clickable：它会给每个格子叠一层涟漪，而一次加选可能连点十张照片，
//   涟漪叠在一起既拖慢合成，也让人误以为"正在动的是刚点的那张"。
//   桌面端的选择动作需要的是"边框立刻变"，不是"水波纹扩散"。
//
// ★ 这里**没有**修饰键参数：本项目已移除全部键盘操作（用户明确要求），
//   多选改由缩略图上的勾选框承担（见 PhotosSheet 的 Strip），一次点击只做一件事。
package com.photomgm.desktop.ui.component

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput

/** 按下即回调（不等待抬起）：照片格要的是"点下去边框就变"。 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.pmPress(onPress: () -> Unit): Modifier = this.pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            // 被上层（例如缩略图角上的勾选框）消费掉的事件不再触发：
            // 否则点勾选框会顺带把这张照片设成"当前"，一次点击做两件事。
            if (event.type == PointerEventType.Press && event.changes.none { it.isConsumed }) onPress()
        }
    }
}
