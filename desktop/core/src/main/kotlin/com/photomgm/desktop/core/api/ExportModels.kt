// desktop/core/api/ExportModels.kt —— 导出请求/进度/结果（UI 与导出实现之间的数据契约）
package com.photomgm.desktop.core.api

import java.nio.file.Path

/**
 * 导出请求。
 *
 * @param outputDir 输出父目录：导出根 `<outputDir>/PhotoMgm_export_yyyyMMdd_HHmmss/` 建在其下
 * @param includeLedger 是否生成 ledger.xlsx（关掉仅用于排障/快速复制照片）
 * @param verifyMode 断点续传的物理校验强度（见 [ResumeVerifyMode]）
 */
data class ExportRequest(
    val outputDir: Path,
    val includeLedger: Boolean = true,
    val verifyMode: ResumeVerifyMode = ResumeVerifyMode.SIZE_AND_MTIME,
)

/**
 * 断点续传的"产物是否还在"校验方式。
 *
 * ★ 之所以默认 [SIZE_AND_MTIME] 而不是 SHA-256：
 *   导出 1000 张、每张 4MB 时，全量重算哈希约 4GB 顺序读，在机械盘上可能比"重新导出"还慢，
 *   而"续传比导出慢"是荒谬的。产物由本程序自己写入，size+mtime 同时变化而内容保持一致的
 *   概率极低（照片是只读素材，不会被我们改写）。
 *   需要法证级校验（如涉密留证场景）时切到 [SIZE_AND_SHA256]。
 */
enum class ResumeVerifyMode {
    /** 文件存在 + 大小一致 + 修改时间一致 → 认为完好（默认，快）。 */
    SIZE_AND_MTIME,

    /** 文件存在 + 大小一致 + SHA-256 一致 → 认为完好（慢，仅强校验场景）。 */
    SIZE_AND_SHA256,
}

/** 导出进度（stage 为中文阶段名，UI 可直接显示）。 */
data class ExportProgress(
    val stage: String,
    val copied: Int,
    val total: Int,
    val currentFile: String = "",
) {
    val fraction: Float
        get() = if (total <= 0) 0f else (copied.toFloat() / total).coerceIn(0f, 1f)
}

/** 单条导出失败明细（相对路径 + 原因，可直接展示/复制）。 */
data class ExportFailure(val relativePath: String, val reason: String)

/**
 * 导出结果。
 *
 * ★ 计数口径（重要，UI 展示与日志 summary 都按这个来）：
 *   [totalTargets] / [copied] / [skipped] / [failed] **只统计照片**，不含台账。
 *   台账是否生成看 [ledgerFile] 是否为 null。
 *
 * ★ 不变式（M5 硬验收）：`skipped == totalTargets - copied - failed`，
 *   即"报告跳过的数量"必须等于"实际没有复制的数量"，不允许出现"提示跳过 N 张、实际一张没复制"。
 */
data class ExportResult(
    val exportDir: Path,
    val ledgerFile: Path?,
    val logFile: Path,
    val manifestFile: Path,
    val totalTargets: Int,
    val copied: Int,
    val skipped: Int,
    val failed: Int,
    val failures: List<ExportFailure> = emptyList(),
    val durationMs: Long = 0,
    val cancelled: Boolean = false,
    /** 是否命中"上次已完整导出"而整体跳过（此时 exportDir 指向既有目录，不新建）。 */
    val resumed: Boolean = false,
    /** 非致命警告（重命名、HEIC 跳过、文件夹名为空等）。 */
    val warnings: List<String> = emptyList(),
)
