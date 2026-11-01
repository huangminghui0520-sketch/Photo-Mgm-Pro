// desktop/ui/design/Tokens.kt —— 设计令牌（唯一色值/间距/字号来源）
//
// ★ 来源：docs/UI-REDESIGN.md §2.3–2.5。那份文档里的对比度是**脚本实测**过的
//   （先用 white/black=21.00、#767676/white=4.54、#595959/white=7.00 四个标准值校准工具，
//   再产出结论；初稿手算的三个值就是被这个校准推翻的）。所以这里直接抄值，不重新推导。
//
// ★ 三条硬规则（抄自 UI-REDESIGN §2.3，破坏任何一条都会掉到 WCAG AA 以下）：
//   1. 橙色拆三个角色，不是一个：
//        accentBrand #F0560A —— 画布上只有 3.14:1，**只能**做装饰色块/轨线/≥19sp 大字；
//        accentText  #B94208 —— 任何橙色**文字**用它（4.92:1）；
//        accentFill  #CE4A09 —— 主按钮**填充**（白字落在它上面 4.56:1）。
//   2. 状态角标的底色透明度必须 6%。原型写的 12% 实测只有 4.2:1，不达标。
//   3. 次级文字用实色 #5C6663，不用 rgba(.56)。半透明在 canvas 上勉强达标，
//      落到 surface2 就跌破 4.5:1。
//
// ★ 桌面端与 Android 端的差异（有意为之，不是抄漏）：
//   · 字号**绝对値**整体上调（body 13→14、label 11→12）。手机 35cm、桌面 60cm 观看距离，
//     同一 sp 在桌面上角直径小 40%，直接照搬会让一排 12sp 中文在 1080p 上发虚。
//   · 层级**比例**保持原样（19/15/14/13），跨端看起来仍是同一套语言。
//   · touch.min 48dp 降到 hit.min 36dp：鼠标指针比手指精确，48dp 按钮在桌面上会显得笨重；
//     但 36dp 是下限，低于它连鼠标都要瞄。
package com.photomgm.desktop.ui.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 色彩令牌（浅色；深色见 UI-REDESIGN §2.3，U1 不接，避免引入未验证的一整套派生色）。 */
object PmColor {
    /** 页面底：冷灰绿相，取自巡查场景（护栏灰 / 沥青灰）。 */
    val canvas = Color(0xFFF1F4F0)

    /** 卡片 / 面板底。 */
    val surface = Color(0xFFFBFCFA)

    /** 次级底、按压态、缩略图占位块。 */
    val surface2 = Color(0xFFE7ECE6)

    /** 深色段（顶栏、状态栏）。 */
    val world = Color(0xFF222B29)

    /** 主文字。canvas 上 15.02:1。 */
    val ink = Color(0xFF17201D)

    /** 次级文字（实色，非半透明）。canvas 5.36:1 / surface2 4.96:1。 */
    val dim = Color(0xFF5C6663)

    /** 分隔线 rgba(23,32,29,.10)。 */
    val line = Color(0xFF17201D).copy(alpha = 0.10f)

    /** 强分隔线 rgba(23,32,29,.16)。 */
    val line2 = Color(0xFF17201D).copy(alpha = 0.16f)

    /** 品牌橙：装饰性色块 / 轨线 / 大字专用（3.14:1，不可用于正文）。 */
    val accentBrand = Color(0xFFF0560A)

    /** 强调文字形态。凡 color= 橙色的文字一律用它（4.92:1）。 */
    val accentText = Color(0xFFB94208)

    /** 主操作填充（白字在其上 4.56:1）。 */
    val accentFill = Color(0xFFCE4A09)

    /** 主操作按钮上的文字。 */
    val onAccent = Color(0xFFFFFFFF)

    /** 已分类 / 正常（canvas 5.19:1）。 */
    val ok = Color(0xFF2B7349)

    /** 待复查（canvas 5.15:1）。 */
    val warn = Color(0xFF8D5C17)

    /** 未识别 / 人工干预（canvas 5.25:1）。 */
    val bad = Color(0xFFB6392C)

    /** world 上的次级文字（深色段里不能再用 dim）。 */
    val onWorldDim = Color(0xFFB8C4BF)

    /** 状态角标底色：**6%**，不是 12%（见文件头硬规则 2）。 */
    val okWash = ok.copy(alpha = 0.06f)
    val warnWash = warn.copy(alpha = 0.06f)
    val badWash = bad.copy(alpha = 0.06f)
    val accentWash = accentBrand.copy(alpha = 0.06f)
}

/** 间距令牌：4 / 8 / 14 / 20 / 32。禁止在组件里写裸 dp（如 6.dp、10.dp）。 */
object PmSpace {
    val xs = 4.dp
    val s = 8.dp
    val m = 14.dp
    val l = 20.dp
    val xl = 32.dp
}

/** 形状令牌。 */
object PmRadius {
    /** chip / 输入框 / 小按钮 */
    val control = 8.dp

    /** 事件卡 / 设置卡（对齐原型 --r-card，替代 Material 默认的 20dp） */
    val card = 12.dp

    /** Sheet / 覆盖层（对齐原型 --r-shell） */
    val shell = 20.dp
}

/** 度量令牌。 */
object PmSize {
    /** 顶栏高度。 */
    val topBar = 52.dp

    /** 状态栏高度（桌面常驻，比 Android 底栏矮）。 */
    val statusBar = 30.dp

    /** 鼠标点击最小命中区（桌面下限 36dp，不是 48dp，理由见文件头）。 */
    val hitMin = 36.dp

    /** 缩略图选中边框。 */
    val thumbBorder = 2.5.dp
}

/**
 * 字号阶梯（语义化）。
 *
 * ★ 数字一律用 [numeric]（tnum 等宽）：桩号 K123+000、日期、张数、百分比在列表里
 *   必须纵向对齐，否则扫视时参差感明显。Android 端这一档是缺失的，桌面端补上。
 */
object PmType {
    /** 空状态大标题 */
    val display = TextStyle(fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold)

    /** 分区标题 */
    val headline = TextStyle(fontSize = 19.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold)

    /** 卡片标题 / 列表主行 */
    val title = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)

    /** 正文 */
    val body = TextStyle(fontSize = 14.sp, lineHeight = 21.sp)

    /** 副信息、桩号、时间（桌面 14 起，不再用 12） */
    val bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 20.sp)

    /** 徽章 / chip */
    val label = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)

    /** 等宽数字：桩号、日期、计数、进度 */
    val numeric = TextStyle(fontSize = 14.sp, lineHeight = 21.sp, fontFeatureSettings = "tnum")
}
