// ui/component/Press.kt —— 无涟漪的点按 + **显式区域判定**
//
// ★ 为什么不用 material 的 clickable：它会给每个格子叠一层涟漪，而一次多选
//   可能连点十张照片，涟漪叠在一起既拖慢合成，也让人误以为"正在动的是刚点的那张"。
//   桌面端的选择动作需要的是"边框立刻变"，不是"水波纹扩散"。
//
// ★ 把 Shift 是否按下一起交出去（连选）。
//   Ctrl 不再由这里交出去：缩略图的"加选/切换"已经收归**右上角红勾**唯一入口
//   （v3.7 的决定，见 PhotosSheet 的 Strip）。多留一个 Ctrl 分支，就等于多留一条
//   "修饰键读错 → 勾选集合错乱"的路 —— 而这是现场报上来的第一个问题。
//
// ★ 区域判定必须由**几何**决定，不能靠"子节点有没有消费事件"来猜（v3.7 修的真事故）：
//   旧实现是 `event.changes.none { it.isConsumed }`，指望右上角红勾的 clickable
//   先把 press 消费掉。但 clickable 只在**抬起**时才确定这是一次 tap，
//   按下阶段并不保证消费；主力事件（Main pass）又是子先父后，
//   于是"点红勾"经常同时触发格子本体的点击 —— 一次点击做两件事，勾选被后到的
//   单选逻辑覆盖，看起来就是"点红勾没用 / 选不中第三张"。
//   现在改成：调用方给出 [accept]（像素坐标 + 格子尺寸 → 这块区域归不归我），
//   红勾所在的那块直角区域在几何上就被排除，父层根本不会把这次按下当成"点格子"。
//
// ★ 必须用 rememberUpdatedState 转发（修复「Ctrl+点只能选两张」）：
//   pointerInput(Unit) 的 block 只在首次进入组合时启动一次，之后既不重启也不换 lambda。
//   如果直接读入参 onPress，它永远是**第一次组合时**那个闭包 —— 而那个闭包里的 ui
//   还是弹窗刚打开时的 selectedPhotoIds（空集）。于是第三次点击算出来的下一组
//   依旧基于空集，看起来就像"最多只能选两张 / 新点的这张把旧的顶掉了"。
//   rememberUpdatedState 让 block 每次读到的都是最新闭包，状态才能累积。
package com.photomgm.desktop.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize

/** 按一下时键盘上按着什么。 */
data class PressModifiers(
    /** Shift：从上一个锚点连选到这一项。 */
    val shift: Boolean = false,
)

/**
 * 按下即回调（不等待抬起），并交出 Shift 状态（连选）。
 *
 * @param accept 这块像素是否归本组件（默认全都归）。入参是 `(按下点, 本组件尺寸)`，
 *               **单位是像素**（PointerInputScope 的坐标系），调用方自己把 dp 换成 px。
 *               返回 false 的按下既不改选择、也不改预览，等于"这一下不归我"。
 * @param onPress 命中且未被下层消费时的回调。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Modifier.pmPress(
    accept: ((Offset, IntSize) -> Boolean)? = null,
    onPress: (PressModifiers) -> Unit,
): Modifier {
    val latest by rememberUpdatedState(onPress)
    val guard by rememberUpdatedState(accept)
    return this.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.type != PointerEventType.Press) continue
                val change = event.changes.firstOrNull() ?: continue
                // 已经被更上层的控件（例如红勾）吃掉：不重复处理。
                if (change.isConsumed) continue
                // 几何上不属于本组件的那一块（红勾命中区）：直接忽略。
                val inMyArea = guard?.invoke(change.position, size) ?: true
                if (!inMyArea) continue
                val keys = event.keyboardModifiers
                latest(PressModifiers(shift = keys.isShiftPressed))
            }
        }
    }
}

/**
 * 命中区判定：像素点是否落在**右上角**边长为 [checkPx] 的正方形里。
 *
 * ★ 抽成纯函数：红勾的命中区是"点击判定错乱"的核心，
 *   它必须有单测（见 PhotoHitZoneTest），而不是只能靠人手点。
 *
 * ★ 半开区间（右/下边界不含）：这块区域正好 `checkPx × checkPx` 个像素，
 *   与格子本体严格互补、零重叠 —— "一次点击只做一件事"才有可验证的边界。
 */
fun inTopEndZone(position: Offset, size: IntSize, checkPx: Int): Boolean =
    size.width > 0 && size.height > 0 &&
        position.x >= size.width - checkPx && position.x < size.width &&
        position.y >= 0 && position.y < checkPx
