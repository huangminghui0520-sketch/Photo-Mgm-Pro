// ui/model/Layout.kt —— 宽度 → 尺寸（纯函数，无 Compose 依赖）
//
// ★ 单页结构下要算的东西只有三样：
//     ① 事件卡片里一个照片格多大、一行放几张（放不下就少放几张，绝不横向滚动）；
//     ② 弹窗多大（窗口本身可能只有 900×540，弹窗必须按窗口算，不能写死）；
//     ③ 照片弹窗里"大图 + 侧栏 + 缩略条"怎么分。
//   全部做成纯函数，于是每个宽度档都能被单测覆盖，不用真的开 14 种窗口去试。
//
// ★ 为什么不做横向滚动：卡片里的照片条一旦可横滑，用户就看不到"这个事件到底有几张"，
//   而"有几张"正是复核时要判断的东西。宁可少显示几格 + 用「+N」标出来，也不给横向滚动条。
package com.photomgm.desktop.ui.model

import kotlin.math.max

/** 页面左右留白（卡片与窗口边缘的距离）。 */
const val PAGE_PAD = 16

/** 卡片内边距。 */
const val CARD_PAD = 14

/** 卡片内照片格的间距。 */
const val TILE_GAP = 8

/**
 * 卡片头部栅格：序号列 / 时间列 / 数量列，三列都是固定宽。
 *
 * ★ 卡片式布局最大的风险是"每张卡片各排各的"：序号 1 位数、时间有没有跨零点、地点长短不一，
 *   头部的字段就会各自漂移，扫一眼全是锯齿。固定这三列，等于把表格的列对齐搬进卡片里，
 *   于是既有卡片的"一个事件一个单元"，又有表格的"同一字段上下对齐"。
 * ★ 序号 26 是按两位数字 + 内边距取的；时间列 96 刚好放 `08:15–08:30`。
 */
const val CARD_INDEX_W = 26
const val CARD_TIME_W = 96

/** 卡片右侧"张数"列的固定宽（右对齐用）：两位数字 + "张"。 */
const val CARD_COUNT_W = 56

/** 卡片头部行高（序号/时间/标题/标签/张数都在这一行里垂直居中）。 */
const val CARD_HEADER_H = 30

/** 无照片时的占位行高度（不像素级等于照片格：空占位没有信息，不该占一张缩略图的面积）。 */
const val EMPTY_STRIP_H = 40

/** 有照片的卡片高度：内边距 + 头部 + 间距 + 照片条。 */
fun cardHeight(tile: Int): Int = CARD_PAD * 2 + CARD_HEADER_H + 8 + tile

/** 无照片的卡片高度（占位行固定 40）。两个函数一起用来核对"栅格一致、高度随内容"。 */
fun emptyCardHeight(): Int = CARD_PAD * 2 + CARD_HEADER_H + 8 + EMPTY_STRIP_H

/**
 * 卡片内照片格边长。
 *
 * 四个档位的取法：把"一屏能看到几个事件"作为约束反推。
 * 540 dp 高的窗口里，卡片高度 = 标题行 40 + 照片行 + 内边距；格太大就只剩两张卡片可看，
 * 复核时需要频繁滚动，反而更慢。96/80 是实测下"格内时刻读得清 + 一屏 3~4 张卡片"的交点。
 * 缩略图接入后这个值会被真实图片重新校准（届时格内显示的是图，不是时刻）。
 */
fun tileSize(contentWidth: Int): Int = when {
    contentWidth >= 1300 -> 96
    contentWidth >= 1080 -> 88
    contentWidth >= 880 -> 80
    else -> 72
}

/** 卡片内一行最多放几个照片格（含「+N」占位也算一格）。 */
fun maxTiles(contentWidth: Int, tile: Int = tileSize(contentWidth)): Int {
    val usable = contentWidth - PAGE_PAD * 2 - CARD_PAD * 2
    val fit = (usable + TILE_GAP) / (tile + TILE_GAP)
    return fit.coerceIn(1, 10)
}

/** 卡片内照片条实际宽度（供缩略图按需取尺寸；单测里用它验证不越界）。 */
fun stripWidth(tile: Int, count: Int): Int = tile * count + TILE_GAP * (max(1, count) - 1)

/**
 * 弹窗尺寸。
 *
 * ★ 必须按窗口算：900×540 的内业机上，一个"设计稿里 960×640"的弹窗会超出屏幕下沿，
 *   用户连底部的操作按钮都点不到。这里上下各留 24~28 dp，保证弹窗永远落在窗口内。
 */
fun dialogWidth(windowWidth: Int): Int = (windowWidth - 56).coerceIn(640, 1040)

fun dialogHeight(windowHeight: Int): Int = (windowHeight - 48).coerceIn(420, 720)

data class PhotosSheetLayout(
    val width: Int,
    val height: Int,
    /** 右侧信息栏宽；0 = 窄窗改为上下叠（信息折到缩略条下面）。 */
    val sideW: Int,
    /** 底部缩略条高度。 */
    val stripH: Int,
    val stripTile: Int,
)

/**
 * 照片弹窗三条固定高的里两条（第三条是会随窗口变的缩略条）。
 *
 * ★ 抽成常量而不是散在 Composable 的 padding 里：大图能有多大，是
 *   `height - 头部 - 缩略条 - 操作条 - 3 条分隔线`，这个减法必须能被单测核对，
 *   否则"给照片留更大空间"只能靠眼睛量。
 */
const val PHOTOS_HEADER_H = 38
const val PHOTOS_ACTION_H = 42

/**
 * 缩略图右上角勾选框的命中区边长（dp）。
 *
 * ★ 视觉框 16、命中区 24：手指/鼠标不必对准 16 dp 的小方块。
 *   这个数值同时被两处使用 —— 红勾自己的手势区，以及格子手势的"这块不归我"豁免区。
 *   两处必须是同一个常量，否则豁免区比命中区小一圈时，边缘那几像素仍会同时触发两个逻辑。
 */
const val CHECK_HIT_DP = 24

/** 大图区可用高度（扣掉头部、缩略条、操作条与三条 1 dp 分隔线）。 */
fun previewHeight(sheet: PhotosSheetLayout): Int =
    sheet.height - PHOTOS_HEADER_H - sheet.stripH - PHOTOS_ACTION_H - 3

fun photosSheetLayout(windowWidth: Int, windowHeight: Int): PhotosSheetLayout {
    val w = dialogWidth(windowWidth)
    val h = dialogHeight(windowHeight)
    // 侧栏：台账两格 + 类型 + 提示 + 巡查日志内容。
    // ★ 从 240 提到 300（v3.7）：日志原文一行就是一条完整记录（"10时00分 接报…事故…"），
    //   240 宽下每行只能放 12 个字，一条记录要折 4~5 行，读起来反而比不看更累。
    //   这 60 dp 是从大图那里让出来的，取舍是"复核事件内容"比"大图再宽一点"更常用。
    val side = if (w >= 720) 300 else 0
    // 弹窗矮的时候缩略条要压：它只是"切换照片"的入口，大图才是主体。
    val stripH = if (h >= 560) 92 else 72
    val stripTile = if (h >= 560) 64 else 52
    return PhotosSheetLayout(w, h, side, stripH, stripTile)
}

/** 照片弹窗底部缩略条能放几个（一行，不滚动）。 */
fun stripColumns(sheet: PhotosSheetLayout): Int {
    val usable = sheet.width - (if (sheet.sideW > 0) sheet.sideW else 0) - 24 * 2
    return ((usable + TILE_GAP) / (sheet.stripTile + TILE_GAP)).coerceIn(1, 12)
}

/** 移动弹窗里事件列表的可见行数（用于估算高度，不参与布局硬约束）。 */
fun moveListRows(windowHeight: Int): Int = (dialogHeight(windowHeight) - 200).coerceAtLeast(120) / 44
