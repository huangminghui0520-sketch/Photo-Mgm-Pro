// ui/app/Actions.kt —— 界面能触发的"意图"集合
//
// ★ 这一层存在的理由：阶段组件不应该认识 PhotoMgmCore。
//   组件只发意图（"我要分析""把这三张移到事件 12"），由 AppShell 把这些意图翻译成
//   core 调用。好处很实际：
//     · 组件可以在没有 core 的情况下渲染（--demo、截图、将来的 UI 单测）；
//     · core 的方法名/签名变化只影响 AppShell 一个文件；
//     · 看 Actions 清单就知道这个界面一共能做什么 —— 这张清单就是"用户操作路径"的代码形式。
package com.photomgm.desktop.ui.app

import algorithm.DedupKeepMode
import com.photomgm.desktop.ui.model.Stage
import java.time.LocalDate

class Actions(
    // ── 输入 ──────────────────────────────────────────────────────────────
    val setPatrolDate: (LocalDate) -> Unit = {},
    val addSourceDirs: () -> Unit = {},
    val removeSourceDir: (String) -> Unit = {},
    val chooseOutputDir: () -> Unit = {},
    val revealPath: (String) -> Unit = {},
    val importLogFile: () -> Unit = {},
    val setLogText: (String) -> Unit = {},
    // ── 主流程 ────────────────────────────────────────────────────────────
    val analyze: () -> Unit = {},
    val cancelAnalyze: () -> Unit = {},
    val export: () -> Unit = {},
    val cancelExport: () -> Unit = {},
    // ── 复核 ──────────────────────────────────────────────────────────────
    val movePhotosTo: (photoIds: List<Long>, eventId: Int?) -> Unit = { _, _ -> },
    val markLedgerPhoto: (eventId: Int, slot: Int, photoId: Long?) -> Unit = { _, _, _ -> },
    val resetAdjustments: () -> Unit = {},
    val setDedupMode: (DedupKeepMode) -> Unit = {},
    // ── 结果 ──────────────────────────────────────────────────────────────
    val openLedger: () -> Unit = {},
    val revealExportDir: () -> Unit = {},
    // ── 通用 ──────────────────────────────────────────────────────────────
    val goStage: (Stage) -> Unit = {},
    val clearMessage: () -> Unit = {},
)
