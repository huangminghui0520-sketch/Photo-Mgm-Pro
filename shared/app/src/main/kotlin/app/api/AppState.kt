// shared/app/api/AppState.kt —— 供 UI 消费的不可变状态快照（★ UI 与逻辑的唯一数据契约）
//
// 设计原则（T09 从桌面端原样上移，逐条仍然成立）：
//   1. 全量不可变 data class：UI 拿到一个快照就能渲染整屏，不需要回读核心内部对象；
//   2. 只放「数据」，不放「控件/颜色/字号」：文案由核心产出（术语与另一端一致），
//      但如何展示（图标/颜色/排列）完全由 UI 决定；
//   3. 时间统一用 epoch 毫秒（captureTimeMs / startTimeMs），UI 自行按业务时区格式化——
//      核心不替 UI 决定显示格式，也避免「核心格式化 → UI 想改格式就要改核心」的耦合。
//
// ★ T09 上移时的两处取舍：
//   · `Settings` 里的 `window` 字段被剥离（桌面独有，见 Settings.kt 文件头说明）；
//   · `lastExport` 的类型换成平台无关的 [ExportResult]（路径字段改不透明字符串）。
//     安卓端原本用 `lastExport: String?` 表示「可打开的那一个文件」，现在统一成结果对象：
//     要看导出目录/台账/日志各自在哪，不必再靠字符串约定去猜。
package app.api

import algorithm.DedupKeepMode

/** 核心运行状态。UI 可据此决定按钮可用性与光标（等待态）。 */
enum class CoreStatus {
    /** 初始态：未导入日志。 */
    IDLE,

    /** 正在扫描照片 / 读 EXIF / 分类（同一流水线，细分见 [AppState.progress]）。 */
    ANALYZING,

    /** 分析完成，可预览、可调整、可导出。 */
    READY,

    /** 正在导出。 */
    EXPORTING,

    /** 上一次操作失败（错误详情见 [AppState.error]）。 */
    FAILED,

    /** 已关闭。 */
    CLOSED,
}

/** 分析进度（stage 是给人看的中文阶段名，UI 可直接显示，也可忽略）。 */
data class Progress(
    val stage: String,
    val current: Int = 0,
    val total: Int = 0,
) {
    val fraction: Float
        get() = if (total <= 0) 0f else (current.toFloat() / total).coerceIn(0f, 1f)
}

/** 照片（UI 渲染网格用的最小信息集；不含缩略图字节——缩略图由 UI 侧按 sourceRef 自行加载）。 */
data class PhotoInfo(
    val id: Long,
    val displayName: String,
    val sourceRef: String,
    val captureTimeMs: Long?,
    val latitude: Double?,
    val longitude: Double?,
    /**
     * 是否派生副本（`_original`/`_compressed`/`_optimized`/`_缩略` 等，清单唯一真值见
     * `algorithm.DerivedNames`）。UI 据此在缩略图右上角画一个 5dp 白点（EventCard）。
     *
     * ★ 措辞教训：这里原本写「无水印备份角标」，但白点是**派生副本**标记，不专指 `_original`。
     *   这句错注释让 T04 的判断一度走偏（按「无水印备份」去论证，方向全错）。
     *   改注释时请对照 EventCard 的实际渲染，别凭名字猜。
     */
    val isDerived: Boolean,
) {
    val hasGps: Boolean get() = latitude != null && longitude != null
}

/** 事件（台账一行 + 该事件当前照片；photoIds 已含人工移动与三态去重展开）。 */
data class EventSnapshot(
    val id: Int,
    val eventType: String,
    val location: String,
    val description: String,
    val startTimeMs: Long?,
    val endTimeMs: Long?,
    val photoIds: List<Long>,
    /** 无时间事件（待定事件）：照片不自动归入，需人工指定。 */
    val isPending: Boolean,
    /** 人工干预提示文案（GPS 偏远 / 拆簇 / 时间重叠 / 簇跨度过大 / 事件无照片）。 */
    val hints: List<String>,
)

/** 台账行（导出预览用；字段与 XlsxWriter 实际写出的列一一对应）。 */
data class LedgerRowInfo(
    val dateTime: String,
    val endTime: String,
    val location: String,
    val description: String,
    /** 照片 1 的展示标签（形如 `01_护栏损坏/IMG_001.jpg`；无照片为 null）。 */
    val photo1Label: String?,
    val photo2Label: String?,
    val folderName: String,
)

/** 日志解析摘要（UI 顶部提示用）。 */
data class ParsedSummary(
    val eventCount: Int,
    val skippedLineCount: Int,
    val inspectors: String?,
    val recorder: String?,
    val vehicle: String?,
    val notices: List<String>,
)

/**
 * 分析摘要（扫描/精读/分类的统计口径，用于状态栏与排障）。
 *
 * @property heicCount HEIC/HEIF 张数。★ T09 统一了这个口径：此前只有桌面端统计它，
 *   而 HEIC 在台账里**嵌不进图片**（Excel 不支持，见 `XlsxWriter` 的格式白名单）——
 *   这是「导出的台账里有些格子只有文件名」的唯一原因，安卓端也需要这句话才能解释现象。
 */
data class AnalysisSummary(
    val candidateCount: Int,
    val photoCount: Int,
    val clusterCount: Int,
    val unmatchedCount: Int,
    val mergedDerivedGroups: Int,
    val heicCount: Int,
    val noGpsCount: Int,
) {
    val matchedCount: Int get() = photoCount - unmatchedCount
}

/**
 * 全局状态快照。
 *
 * ★ UI 接入方式（唯一推荐路径）：
 * ```
 * val state by core.state.collectAsState()      // Compose: collectAsState
 * core.state.addListener { ... }                // 其他 UI 框架
 * ```
 */
data class AppState(
    val settings: Settings,
    val status: CoreStatus = CoreStatus.IDLE,
    val progress: Progress? = null,
    /** 日志原文（UI 文本框双向绑定用：改动后调用核心的 setLogText）。 */
    val logText: String = "",
    val parsed: ParsedSummary? = null,
    val analysis: AnalysisSummary? = null,
    val events: List<EventSnapshot> = emptyList(),
    /** 全部照片（含未匹配）；UI 用 id 与 events/unmatchedPhotoIds 关联。 */
    val photos: List<PhotoInfo> = emptyList(),
    /** 事件 id → 照片 id（已含人工移动 + 三态展开），UI 事件卡直接消费。 */
    val eventPhotoIds: Map<Int, List<Long>> = emptyMap(),
    /** 未匹配照片 id（已扣除被移入事件的；含被人工从事件移除的）。 */
    val unmatchedPhotoIds: List<Long> = emptyList(),
    val ledger: List<LedgerRowInfo> = emptyList(),
    val dedupMode: DedupKeepMode = DedupKeepMode.KEEP_WATERMARKED,
    val lastExport: ExportResult? = null,
    /** 导出进度（status == EXPORTING 时非空）。 */
    val exportProgress: ExportProgress? = null,
    /** 一句话提示（成功/警告），UI 决定用 Snackbar 还是状态栏；消费后调用 clearMessage。 */
    val message: String? = null,
    /** 错误详情（可复制给工程师排障）。 */
    val error: String? = null,
) {
    /** 是否允许触发导出（UI 据此禁用按钮，避免「点了才报错」）。 */
    val canExport: Boolean
        get() = status == CoreStatus.READY && settings.outputDir != null && events.isNotEmpty()

    /** 未匹配照片实体（UI 未匹配池直接渲染）。 */
    val unmatchedPhotos: List<PhotoInfo>
        get() {
            if (unmatchedPhotoIds.isEmpty()) return emptyList()
            val byId = photos.associateBy { it.id }
            return unmatchedPhotoIds.mapNotNull { byId[it] }
        }
}
