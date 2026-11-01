// desktop/ui/window/WindowGeometry.kt —— 窗口尺寸策略（★ 本文件的每个数字都有对应的内业机实测依据）
//
// ── 为什么不能写死 1440×900 / 1024×640 ────────────────────────────────────────
// 内业机主力是 1366×768 笔记本，125% 缩放：
//     1366 / 1.25 = 1092.8 → 1093 dp 宽
//      768 / 1.25 =  614.4 →  614 dp 高
//   减 Windows 任务栏 40 dp → 工作区只有 1093 × 574 dp。
// 所以：
//   · 最小高度 640 **比可用高度还高 66 dp**，窗口下边框直接被屏幕裁掉，拖不动、够不着；
//   · 默认 1440×900 在这台机器上横向超 347 dp、纵向超 326 dp，开出来就出屏。
// 结论：固定值只能当「期望值」，真实尺寸必须按工作区夹取。
//
// ── 原方案 §clamp 公式的致命细节（U1 实现时发现，必须修）─────────────────────
//   原式：初始高 = clamp(900, 540, 574 × 0.9) = clamp(900, 540, 517)
//   但 517 < 540 —— 上下界交叉了。直接写 `v.coerceIn(540f, 517f)` 会抛
//   IllegalArgumentException: Cannot coerce value to an empty range。
//   也就是说：**这条公式会在目标机型上崩在启动阶段**，而不是"开出个 517 高的窗口"。
//   修法（本文件 initialExtent）：下限先跟工作区取 min，再夹取 ——
//       floor = min(minimum, workExtent)      // 极端小屏时最小值也得让位给屏幕
//       cap   = min(desired, round(work × 0.9))
//       result = cap.coerceIn(floor, workExtent)
//   1366@125% 的实际结果因此是 **984 × 540**（高度由最小值兜底，不再是 517），
//   540 ≤ 574，放得下，底部还剩 34 dp。
//
// ── 各档实算（workArea 已扣任务栏 40 dp）────────────────────────────────────
//   1366×768 @100%   work 1366×728  → 1229×655
//   1366×768 @125%   work 1093×574  →  984×540   ← 目标机型
//   1920×1080 @100%  work 1920×1040 → 1440×900   ← 期望值原样落地
//   1920×1080 @125%  work 1536×824  → 1382×742
//   1920×1080 @150%  work 1280×680  → 1152×612
//   残废屏  800×540  work  800×500  →  800×500   （最小值让位，不崩）
//
// ── 与 M6b 的分工 ────────────────────────────────────────────────────────────
// 首次启动（settings.window == null）走「期望值 + 90% 工作区夹取」；
// 第二次起走「恢复用户调过的值」，只做两件事：低于最小值就抬到最小值，超出工作区就居中。
// 注意恢复的是 **dp 而非物理像素**：换到不同缩放的显示器上，dp 值保持不变才符合直觉
// （用户调的是"多大一块地方"，不是"多少颗像素"）。
package com.photomgm.desktop.ui.window

import com.photomgm.desktop.core.api.WindowState
import java.awt.GraphicsEnvironment
import kotlin.math.roundToInt

/** 屏幕/窗口矩形，单位 dp（Compose Desktop 的窗口坐标即 AWT 用户空间像素，与 dp 1:1）。 */
data class ScreenRect(val x: Int, val y: Int, val width: Int, val height: Int) {
    val right: Int get() = x + width
    val bottom: Int get() = y + height
}

object WindowSizing {

    // ── 期望值（不是保证值）────────────────────────────────────────────────
    /** 默认尺寸：1920×1080 上舒展，且不占满屏（留出桌面感）。 */
    const val DESIRED_WIDTH = 1440
    const val DESIRED_HEIGHT = 900

    // ── 下限（硬约束）──────────────────────────────────────────────────────
    /** 最小宽 900：1366@125% 工作区才 1093 dp，1024 再加边框/滚动条就贴边。 */
    const val MIN_WIDTH = 900

    /** 最小高 540：1093×574 下留 34 dp 余量；540 也是三栏可用的底线（见 Workspace）。 */
    const val MIN_HEIGHT = 540

    /** 首次启动最多占工作区的比例。 */
    const val WORK_AREA_FILL = 0.9

    /**
     * 首次启动的单轴尺寸：期望值与工作区 90% 取小，再夹进 [下限, 工作区]。
     *
     * 关键点：`floor` 先跟 `workExtent` 取 min，保证 `coerceIn` 的区间永不为空。
     */
    fun initialExtent(desired: Int, minimum: Int, workExtent: Int): Int {
        if (workExtent <= 0) return maxOf(minimum, 1)
        val floor = minimum.coerceAtMost(workExtent)
        val cap = minOf(desired, (workExtent * WORK_AREA_FILL).roundToInt())
        return cap.coerceIn(floor, workExtent)
    }

    /**
     * 恢复已有尺寸：不做 90% 收缩（那是"猜用户想要多大"），只保证合法。
     * 低于下限抬上去、超出工作区压下来 —— 换显示器后旧尺寸不能把窗口顶出屏幕。
     */
    fun restoreExtent(saved: Int, minimum: Int, workExtent: Int): Int {
        if (workExtent <= 0) return maxOf(minimum, saved)
        val floor = minimum.coerceAtMost(workExtent)
        return saved.coerceIn(floor, workExtent)
    }

    /** 在给定工作区内居中。 */
    fun centered(width: Int, height: Int, work: ScreenRect): ScreenRect = ScreenRect(
        x = work.x + (work.width - width) / 2,
        y = work.y + (work.height - height) / 2,
        width = width,
        height = height,
    )

    /**
     * 解析启动矩形。
     *
     * @param saved 上一次的窗口几何（settings.json 里的 window 字段；首次启动为 null）
     * @param work  主屏工作区（已排除任务栏）
     */
    fun resolve(saved: WindowState?, work: ScreenRect): ScreenRect {
        if (saved == null) {
            val w = initialExtent(DESIRED_WIDTH, MIN_WIDTH, work.width)
            val h = initialExtent(DESIRED_HEIGHT, MIN_HEIGHT, work.height)
            return centered(w, h, work)
        }
        val w = restoreExtent(saved.width, MIN_WIDTH, work.width)
        val h = restoreExtent(saved.height, MIN_HEIGHT, work.height)
        // 位置合法性：必须整窗落在工作区内。显示器拔掉 / 改分辨率 / 换缩放后，
        // 旧坐标可能指向一块不存在的屏幕 —— 此时居中，而不是"记住一个看不见的位置"。
        val fullyVisible = saved.x >= work.x && saved.y >= work.y &&
            saved.x + w <= work.right && saved.y + h <= work.bottom
        return if (fullyVisible) ScreenRect(saved.x, saved.y, w, h) else centered(w, h, work)
    }

    /**
     * 主屏工作区（dp）。
     *
     * `GraphicsEnvironment.maximumWindowBounds` 是 JDK 定义的「可放窗口的最大区域」，
     * Windows 上即扣掉任务栏后的主屏区域；JDK 9+ 起返回值已是**用户空间**（已除过缩放），
     * 与 Compose 的 dp 同单位 —— 这是不做额外换算就能用它的原因。
     * 仍保留兜底：探测失败时退回 1280×720，宁可窗口小一点也不要启动即崩。
     */
    fun primaryWorkArea(): ScreenRect = runCatching {
        val b = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        if (b.width <= 0 || b.height <= 0) ScreenRect(0, 0, 1280, 720)
        else ScreenRect(b.x, b.y, b.width, b.height)
    }.getOrElse { ScreenRect(0, 0, 1280, 720) }
}
