// ui/model/Layout.kt —— 窗口宽度 → 三栏几何（纯函数）
//
// ★ 为什么布局要算而不能写死：目标机型的工作区只有 984×540 dp（1366×768 @125%），
//   而开发机是 1536×816 dp。写死的三栏在开发机上好看、在内业机上塌。这里把
//   "多宽 → 几栏 → 每栏多宽 → 照片几列"做成纯函数，界面只读取结果，于是：
//     · 每个宽度档都能被单测覆盖（不需要真的开 14 种窗口）；
//     · 折叠规则是**可解释的**（为什么这里折叠、为什么那里抽屉），不是试出来的。
//
// 分水岭的取舍说明：
//   · 900 dp 以下不摆事件列表（折叠），因为 240 的列表 + 420 的照片区已经 660，
//     再加步骤栏就超了。此时事件切换走照片区顶部的下拉，功能不丢，只是少一次全局浏览。
//   · 1380 dp 才常驻检查器。跨过这条线时照片列数会从 5 回落到 4——这是有意的：
//     用一列照片换掉"每次看详情都要点一下"，复核阶段点击次数是真实成本。
package com.photomgm.desktop.ui.model

import kotlin.math.max

/** 步骤栏宽度。窄窗收窄 8 dp 的意义：那不是视觉微调，是照片区多 8 dp 可能多一列。 */
fun railWidth(contentWidth: Int): Int = if (contentWidth >= 760) 64 else 56

data class ReviewLayout(
    val railW: Int,
    /** 0 = 折叠（事件切换改走照片区顶部下拉）。 */
    val listW: Int,
    val gridW: Int,
    val gridCols: Int,
    /** 0 = 抽屉（点开覆盖在照片区上方，不挤占列数）。 */
    val inspectorW: Int,
    /** 检查器是否为覆盖式抽屉。 */
    val inspectorAsDrawer: Boolean,
)

fun reviewLayout(contentWidth: Int): ReviewLayout {
    val rail = railWidth(contentWidth)
    val list = when {
        contentWidth >= 1380 -> 280
        contentWidth >= 1100 -> 260
        contentWidth >= 900 -> 240
        else -> 0
    }
    val inspector = if (contentWidth >= 1380) 320 else 0
    val dividers = 1 + (if (list > 0) 1 else 0) + (if (inspector > 0) 1 else 0)
    val grid = max(240, contentWidth - rail - list - inspector - dividers)
    return ReviewLayout(
        railW = rail,
        listW = list,
        gridW = grid,
        gridCols = gridColumns(grid),
        inspectorW = inspector,
        inspectorAsDrawer = inspector == 0,
    )
}

/** 缩略图列数：格宽不足 168 就减列，绝不出现横向滚动条（横向滚动会切断缩略图的"整行感知"）。 */
fun gridColumns(gridWidth: Int, minTile: Int = 168, gap: Int = 12): Int {
    val raw = (gridWidth + gap) / (minTile + gap)
    return raw.coerceIn(1, 7)
}

data class InputLayout(
    val railW: Int,
    val contentW: Int,
    /** 素材（日期/源目录）与日志是否左右并排。 */
    val twoPane: Boolean,
    /** 并排时左栏宽度（宽窗给 392 放得下完整路径，窄窗压到 340 保日志区）。 */
    val materialsW: Int,
)

fun inputLayout(contentWidth: Int): InputLayout {
    val rail = railWidth(contentWidth)
    val content = contentWidth - rail - 1
    // ★ 分水岭取 860 而不是 1040：实测 970 dp 的内容宽若上下堆叠，日志区会整个掉到折叠线以下，
    //   用户要先滚动才能粘贴日志——而粘贴日志是这一步的主动作。
    //   并排后左栏 340、日志区 470+，两者同时可见，动作顺序才和界面顺序一致。
    return InputLayout(
        railW = rail,
        contentW = content,
        twoPane = content >= 860,
        materialsW = if (content >= 1100) 392 else 340,
    )
}

data class ExportLayout(
    val railW: Int,
    val contentW: Int,
    /** 0 = 选项抽屉。 */
    val sideW: Int,
    val tableW: Int,
)

fun exportLayout(contentWidth: Int): ExportLayout {
    val rail = railWidth(contentWidth)
    val side = if (contentWidth >= 1180) 320 else 0
    val content = contentWidth - rail - 1
    val table = max(320, content - side - (if (side > 0) 1 else 0))
    return ExportLayout(railW = rail, contentW = content, sideW = side, tableW = table)
}

/** 照片格子在给定列数下的真实宽度（供缩略图请求按需取尺寸）。 */
fun tileWidth(gridWidth: Int, cols: Int, gap: Int = 12, padding: Int = 12): Int {
    val usable = gridWidth - padding * 2 - gap * (max(1, cols) - 1)
    return max(80, usable / max(1, cols))
}
