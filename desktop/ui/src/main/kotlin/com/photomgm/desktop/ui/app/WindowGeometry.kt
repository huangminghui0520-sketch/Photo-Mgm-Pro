// ui/app/WindowGeometry.kt —— 窗口尺寸策略（纯函数，可单测）
//
// ★ 目标机型：1366×768 @125% 的内业笔记本。它的工作区只有 1093×574（dp），
//   比"默认 1440×900"小得多，也比"最小 1280×800"小得多。
//   所以窗口尺寸不能写死，必须**按工作区夹取**：
//
//     下限 = min(最小值, 工作区)         ← 这一步是防崩的关键
//     上限 = max(下限, 工作区 × 0.9)
//     尺寸 = 默认值.coerceIn(下限, 上限)
//
// ★ 为什么要先跟工作区取 min：若工作区高 574，最小值 540，0.9×574=517，
//   直接 coerceIn(540, 517) 会抛 IllegalArgumentException（上下界交叉）——
//   在目标机型上表现为"程序打不开"，比窗口出屏严重得多。
//   取 min 之后上限被抬到 540，结果 540，仍小于工作区，窗口放得下。
//
// ★ 存的是 dp 不是物理像素：125% 缩放下 1 dp = 1.25 px，存像素会在不同缩放的机器上
//   恢复出一个奇怪的大小。Compose 的 WindowState 本身就是 dp，直接存取即可。
package com.photomgm.desktop.ui.app

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object WindowGeometry {

    const val DEFAULT_WIDTH = 1440
    const val DEFAULT_HEIGHT = 900
    const val MIN_WIDTH = 900
    const val MIN_HEIGHT = 540
    const val MAX_RATIO = 0.9f

    data class Area(val x: Int, val y: Int, val width: Int, val height: Int)

    data class Bounds(val x: Int, val y: Int, val width: Int, val height: Int)

    /** 首次启动：按工作区自适应，居中。 */
    fun initial(area: Area, saved: Bounds? = null): Bounds {
        val minW = min(MIN_WIDTH, area.width)
        val minH = min(MIN_HEIGHT, area.height)

        if (saved != null) return clamp(saved, area)

        val capW = max(minW, (area.width * MAX_RATIO).roundToInt()).coerceAtMost(area.width)
        val capH = max(minH, (area.height * MAX_RATIO).roundToInt()).coerceAtMost(area.height)
        val w = DEFAULT_WIDTH.coerceIn(minW, capW)
        val h = DEFAULT_HEIGHT.coerceIn(minH, capH)
        return Bounds(
            x = area.x + (area.width - w) / 2,
            y = area.y + (area.height - h) / 2,
            width = w,
            height = h,
        )
    }

    /**
     * 恢复记忆尺寸：尺寸夹进工作区，位置再夹一次，保证窗口标题栏一定可抓。
     *
     * ★ 只夹不丢：若上次是在 4K 屏上关的窗口，这次接到 1366 笔记本上，
     *   位置会很离谱。夹到工作区内，用户看到的是"窗口在屏幕里"，而不是"程序丢了"。
     */
    fun clamp(saved: Bounds, area: Area): Bounds {
        val minW = min(MIN_WIDTH, area.width)
        val minH = min(MIN_HEIGHT, area.height)
        val w = saved.width.coerceIn(minW, area.width)
        val h = saved.height.coerceIn(minH, area.height)
        val maxX = area.x + max(0, area.width - w)
        val maxY = area.y + max(0, area.height - h)
        return Bounds(
            x = saved.x.coerceIn(area.x, maxX),
            y = saved.y.coerceIn(area.y, maxY),
            width = w,
            height = h,
        )
    }

    /** 记忆值 → 恢复时的 Bounds（宽高为 0 视为无效记忆）。 */
    fun fromSaved(x: Int, y: Int, width: Int, height: Int): Bounds? =
        if (width <= 0 || height <= 0) null else Bounds(x, y, width, height)
}
