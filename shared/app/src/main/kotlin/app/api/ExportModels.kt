// shared/app/api/ExportModels.kt —— 导出计划 / 进度 / 结果（UI ↔ 导出执行器之间的数据契约）
//
// ★ T09 最重要的一处「计划上移、执行留平台」（计划 §T09 D4）：
//   两端导出的**产物形态不同**（桌面=文件夹树，安卓=ZIP），但「哪张照片进哪个文件夹、
//   重名怎么加后缀、文件夹怎么编号」是**同一套业务规则**。此前这段规则写了两份：
//   · 桌面 `PcFolderExporter.buildPlan`（自己算一遍）；
//   · 安卓 `ZipExporter.export` 内部（另算一遍）。
//   两份的注释互相写着「与另一端一致」，靠人肉对拍 —— 这正是要消灭的重复。
//
//   切法：共享层产出 [ExportPlan]（纯数据：相对路径 → 源引用），平台只负责「把这些字节
//   搬到那些路径上」。于是 `ExportPlan` 里**不能有 Path/File/Uri**，否则另端无法实现该端口。
//
// ★ 路径分隔符固定为 `/`：它是 ZIP 条目的语法，也是台账「照片列」标签里的分隔符
//   （`01_护栏损坏/IMG_001.jpg`）。桌面端把它拼到本地路径时自行转换——反过来的话，
//   ZIP 里会出现 `\`，解压后变成文件名的一部分（Windows 之外的解压器不认识它）。
package app.api

import algorithm.model.LedgerRow

/**
 * 导出计划：一次导出要写出的全部相对路径与台账行。
 *
 * 由共享层的导出用例生成，交给平台 [app.port.ExportExecutor] 执行。
 * 计划本身**不含输出位置**（那是平台目标，见 [ExportRequest.target]），也不含文件大小/哈希
 * （那要读盘，属执行侧）。
 *
 * @property folderNames 事件 id → 带序号前缀的文件夹名（如 `01_护栏损坏`）。
 *   与台账照片列的前缀**同源**（都是 `ZipExporter.numberedFolderNames`），
 *   否则会出现「台账说在 01_护栏损坏，磁盘上是 02_标志牌倾斜」的漂移。
 * @property files 文件清单。**顺序即写入顺序**：按事件结束时间升序，未匹配照片最后。
 *   同一相对路径只出现一次；同一文件夹内同一张照片只出现一次。
 * @property ledger 写 xlsx 用的台账行（含 `photo1Ref`/`photo2Ref`）。`includeLedger=false` 时为空。
 * @property ledgerPreview 与 [ledger] 同序同数的展示行（含 `文件夹名/文件名` 标签）。
 *   单独带一份的理由：导出完成后 UI 要显示「这次导出写出的台账」，而导出期间的状态是冻结的，
 *   从计划里直接取比事后重算更不会说谎。
 * @property renames 被重命名的文件（原名 → 相对路径），导出日志要逐条记下来。
 * @property warnings 计划阶段产生的非致命提示（如"事件文件夹名为空，退回编号"）。
 * @property ledgerFileName 台账在产物里的文件名。**由请求带入、不在执行器里写死**：
 *   桌面是文件夹导出，固定 `ledger.xlsx` 最省事；安卓是一次性 ZIP，用户解压后只看到这一个
 *   表格文件，需要带巡查日期与导出时刻（`巡查台账_20260814_131349.xlsx`）才能区分多次导出。
 *   T11 提交② 之前这个差别体现为"安卓 ZIP 里叫巡查台账_…、PC 目录里叫 ledger.xlsx"，
 *   但两边的名字都硬编码在各自执行器里 —— 现在收敛成一个字段，执行器只管按它写。
 */
data class ExportPlan(
    val namingTemplate: Int,
    val includeLedger: Boolean,
    val folderNames: Map<Int, String>,
    val files: List<ExportFile>,
    val unmatchedFolder: String,
    val ledger: List<LedgerRow>,
    val ledgerPreview: List<LedgerRowInfo>,
    val renames: List<ExportRename>,
    val warnings: List<String>,
    val ledgerFileName: String = DEFAULT_LEDGER_NAME,
) {
    /** 照片目标数（**只数照片**，不含台账；与 [ExportResult.totalTargets] 同口径）。 */
    val totalTargets: Int get() = files.size

    /** 是否有可导出的照片。空计划不是「导出成功 0 张」，而是「根本不该开始」。 */
    val hasTargets: Boolean get() = files.isNotEmpty()

    companion object {
        /**
         * 未匹配照片的固定文件夹名。
         *
         * ★ 与 `algorithm.ZipExporter` 里的同名常量必须同值，但那个是 `private`
         *   （`ZipExporter.kt` 的 private companion），共享层引用不到，只能在这里再写一次。
         *   这是**已知的单点重复**，不是疏忽：T14 的门禁会扫「常量重复」，
         *   届时要么把 algorithm 里那个放开、要么让安卓也改用本常量。
         *   在那之前，`ExportPlannerTest` 用字面量 `99_未分类` 断言，改动时至少会红一次。
         */
        const val UNMATCHED_FOLDER: String = "99_未分类"

        /** 台账默认文件名（桌面端；安卓按请求换成带日期的名字，见 [ExportPlan.ledgerFileName]）。 */
        const val DEFAULT_LEDGER_NAME: String = "ledger.xlsx"

        /** 空计划（未分析时导出被阻断，用不到它；测试与默认值用）。 */
        fun empty(namingTemplate: Int = Settings.NAMED_BY_LOG_CONTENT): ExportPlan = ExportPlan(
            namingTemplate = namingTemplate,
            includeLedger = false,
            folderNames = emptyMap(),
            files = emptyList(),
            unmatchedFolder = UNMATCHED_FOLDER,
            ledger = emptyList(),
            ledgerPreview = emptyList(),
            renames = emptyList(),
            warnings = emptyList(),
        )
    }
}

/**
 * 计划中的一个文件。
 *
 * @property relativePath 相对导出根（或 ZIP 根）的路径，分隔符为 `/`。
 * @property sourceRef 源照片引用（桌面=物理路径；安卓=content URI）。执行器按它读字节。
 * @property photoId 来源照片 id，仅用于排障与去重断言（执行器不需要它）。
 */
data class ExportFile(
    val relativePath: String,
    val sourceRef: String,
    val photoId: Long,
)

/** 一次重命名（同一文件夹内重名，第一个后缀是 `_2`，不是 `_1`）。 */
data class ExportRename(
    val originalName: String,
    val toRelativePath: String,
)

/**
 * 导出请求。
 *
 * @property target **平台输出目标**，共享层不解释其语法：桌面端是输出父目录的绝对路径，
 *   安卓端是用户选定的树 URI（或应用私有目录路径）。执行器负责把它变成可写的目的地。
 * @property includeLedger 是否生成台账（关掉仅用于排障/快速复制照片）。
 * @property ledgerName 台账文件名（见 [ExportPlan.ledgerFileName]）。两端默认值不同：
 *   桌面留空即用 `ledger.xlsx`，安卓传入带日期的名字。
 */
data class ExportRequest(
    val target: String,
    val includeLedger: Boolean = true,
    val ledgerName: String = ExportPlan.DEFAULT_LEDGER_NAME,
)

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
 * ★ 不变式（硬验收）：`skipped == totalTargets - copied - failed`，
 *   即「报告跳过的数量」必须等于「实际没有复制的数量」，
 *   不允许出现「提示跳过 N 张、实际一张没复制」。
 *
 * ★ 路径字段一律是**不透明字符串**（桌面=绝对路径，安卓=content URI 或私有目录路径）。
 *   共享层只负责把它们原样交回 UI，不解析、不拼接、不做 `File` 运算。
 *
 * ★ [logFile] / [manifestFile] 允许是**空串**，语义是"本端不产出该文件"：
 *   安卓导出是一次性 ZIP，没有跨次续传，也就没有 manifest；它仍然写 export.log，
 *   但写在应用私有目录（不进 ZIP），所以 logFile 有值。空串而不是 null 是既有契约
 *   （字段非空、已有调用方直接拼字符串显示），改成可空会波及 PC 侧所有展示点。
 */
data class ExportResult(
    val exportDir: String,
    val ledgerFile: String?,
    val logFile: String,
    val manifestFile: String,
    val totalTargets: Int,
    val copied: Int,
    val skipped: Int,
    val failed: Int,
    val failures: List<ExportFailure> = emptyList(),
    val durationMs: Long = 0,
    val cancelled: Boolean = false,
    /** 是否命中「上次已完整导出」而整体跳过（此时 exportDir 指向既有目录，不新建）。 */
    val resumed: Boolean = false,
    /** 非致命警告（重命名、HEIC 跳过、文件夹名为空等）。 */
    val warnings: List<String> = emptyList(),
)

/**
 * 完成提示语（四种结局，文案**两端必须一致** —— 用户靠它判断"能不能直接用"）。
 *
 * ★ 取消排在第一位：取消的导出**可能已经写出了一部分**，若先说"导出完成"，
 *   用户会拿一个不全的目录去交付。
 *
 * ★ T11 提交② 从 `ExportUseCase` 的私有方法提成 `ExportResult` 的扩展：安卓侧要把结果
 *   投影成自己的底栏文案，而 `ExportUseCase` 是共享层的**实例**、安卓只拿到结果对象。
 *   做成扩展而不是让 ViewModel 自己拼一遍，是因为"文案分几种结局"属于界面契约，
 *   两份实现必然在某个分支上说得不一样（例如取消时一份说"已取消"、另一份说"未完成"）。
 */
fun ExportResult.userMessage(): String = when {
    cancelled -> "导出已取消：已导出 $copied 个文件，可稍后续传"
    resumed -> "上次导出的产物校验一致（$skipped 个文件），本次未重复复制"
    failed > 0 -> "导出完成但有 $failed 个失败，详见 export.log"
    else -> "导出完成：$copied 个文件 → $exportDir"
}
