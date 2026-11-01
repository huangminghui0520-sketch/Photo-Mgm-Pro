// ui/theme/Tokens.kt —— 设计令牌
//
// ★ 这一版是按「运行逻辑」反推出来的，不是按视觉喜好调出来的：
//   1. 界面里 80% 的像素是文字与表格（事件地点/描述/桩号/文件名/统计），
//      所以底色必须是浅色高对比；照片区单独用更深的画布色，让缩略图浮出来。
//   2. 只有一个强调色（赭橙）。它在业务里只承担两件事：**当前选中**、**主动作**。
//      不做第二强调色——颜色一多，"哪个是现在该点的"就消失了。
//   3. 语义色（成功/警告/危险）只用于**状态**，不用于装饰。hints 是业务信号（GPS 偏远/
//      时间重叠/无照片），必须能被一眼区分于普通文本，否则复核时会漏。
//
// 对比度全部为脚本实测值（相对亮度按 WCAG 2.1 公式），标注在每项后面。
// 规则：正文/标签 ≥ 4.5:1；非文本装饰（分隔线、占位块）不受此约束，但不得承载信息。
package com.photomgm.desktop.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object PmColor {
    /** 窗口底色（内容区之外）。 */
    val canvas = Color(0xFFF4F5F6)

    /** 卡片/面板底色。 */
    val surface = Color(0xFFFFFFFF)

    /** 次级面板（表格表头、输入框内衬、选中条的底）。 */
    val surface2 = Color(0xFFEFF1F3)

    /** 照片画布：比 canvas 深一档，缩略图的白边才有边界感。 */
    val stage = Color(0xFFE8EAEC)

    val line = Color(0xFFDFE3E7)
    val lineStrong = Color(0xFFC7CDD3)

    /** 主文本 17.81:1 / 16.31:1。 */
    val ink = Color(0xFF15181C)

    /** 次文本 8.18:1。 */
    val ink2 = Color(0xFF495057)

    /** 三级文本（时间戳、辅助说明）5.73:1；在 canvas 上 5.25:1；在 surface2 上 5.06:1。 */
    val ink3 = Color(0xFF5F6771)

    /** 仅用于非文本：占位块、禁用图标、分隔装饰。2.61:1 —— **禁止用于文字**。 */
    val ink4 = Color(0xFF9AA1A9)

    /** 强调色：白字压在上面 5.71:1；压在 accentSoft 上 5.03:1。 */
    val accent = Color(0xFFB4400F)
    val accentHover = Color(0xFF98350B)   // 白字 7.37:1
    val accentSoft = Color(0xFFFBEEE8)

    val ok = Color(0xFF157F3D)            // 5.08:1
    val okSoft = Color(0xFFEAF5EE)
    val warn = Color(0xFFA85B00)          // 5.05:1
    val warnSoft = Color(0xFFFDF3E3)
    val danger = Color(0xFFB3261E)        // 6.54:1
    val dangerSoft = Color(0xFFFCEDEC)
    val info = Color(0xFF1B4FD8)          // 6.65:1
    val infoSoft = Color(0xFFEBF0FD)

    /** 照片占位块的底色候选（按 id 稳定取色，保证同一张照片每次都是同一种灰）。 */
    val placeholder = listOf(
        Color(0xFFDDE1E5),
        Color(0xFFD9DEE3),
        Color(0xFFE1DFDA),
        Color(0xFFD8DFDC),
    )
}

/**
 * 间距：4 的倍数为主，保留 2/6 两个半档。
 *
 * ★ 桌面端密度：13 号字配 8dp 内边距，一屏能多放两行事件——内业机的 984×540
 *   不允许 16dp 起步的移动端留白。
 */
object PmSpace {
    val xxs = 2.dp
    val xs = 4.dp
    val s = 6.dp
    val m = 8.dp
    val l = 12.dp
    val xl = 16.dp
    val xxl = 24.dp
    val xxxl = 32.dp
}

object PmRadius {
    val control = 4.dp
    val card = 8.dp
    val panel = 10.dp
    val pill = 999.dp
}

object PmSize {
    /** 顶栏高度。 */
    val topBar = 52.dp
    /** 状态栏高度。 */
    val statusBar = 26.dp
    /** 控件高度：低于 30dp 的点击目标在 125% 缩放下实际只有 ~21 物理像素，误点明显。 */
    val control = 30.dp
    val controlLarge = 34.dp
}

/**
 * 字号阶梯（桌面绝对值，不用 sp 缩放系数）。
 *
 * ★ 为什么标题只有 15~18：这是工具不是网站。窗口宽度 900 时，22 号标题会
 *   把路径挤断行；而视觉层级靠**字重 + 颜色 + 分隔线**已足够区分。
 */
object PmType {
    val display = TextStyle(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
    val title = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
    val subtitle = TextStyle(fontSize = 13.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
    val body = TextStyle(fontSize = 13.sp, lineHeight = 20.sp)
    val bodyStrong = TextStyle(fontSize = 13.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
    val label = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
    val caption = TextStyle(fontSize = 11.sp, lineHeight = 15.sp)
    val micro = TextStyle(fontSize = 10.sp, lineHeight = 13.sp, fontWeight = FontWeight.Medium)

    /** 数字（统计、计数、进度）：等宽数字，避免"7/30"变成"7/3O"那种参差。 */
    val numeric = TextStyle(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Medium,
        fontFeatureSettings = "tnum",
    )
    val numericLarge = TextStyle(
        fontSize = 22.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.SemiBold,
        fontFeatureSettings = "tnum",
    )
}
