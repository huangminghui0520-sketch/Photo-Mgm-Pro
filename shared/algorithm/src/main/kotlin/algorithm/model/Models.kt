// algorithm/model/Models.kt
// §2.3 核心数据模型 —— 纯 Kotlin / 纯数据类，禁止任何 Android 依赖（§2.4 纯度红线）
package algorithm.model

/**
 * 一条巡查日志事件。
 * @param id 1-based，按 endTimeMs 升序编号（var：LogParserEngine 去重后需重新编号，文档 §2.3 为 val、§3.4 需修改，以可执行代码为准取 var）
 * @param startTimeMs 最早时间点（含日期，跨日已 +1 天）
 * @param endTimeMs 最晚时间点（单时间点 = startTime）
 * @param timePoints 本条日志全部时间点（按文本出现顺序，含跨日回绕）
 * @param location 地点（桩号优先），无地点为 ""
 * @param eventType 事件类型（EventTypeClassifier 输出）
 * @param description 日志原文，如实输出（不脱敏）
 */
data class LogEvent(
    var id: Int,
    val startTimeMs: Long?,
    val endTimeMs: Long?,
    val timePoints: List<Long>,
    val location: String,
    val eventType: String,
    val description: String,
)

/** 照片（data 层注入核心的纯数据形态；Android=content uri，PC=文件路径） */
data class Photo(
    val id: Long,
    val sourceRef: String,
    val displayName: String,
    val captureTimeMs: Long?,    // 精确时间（含日期）
    val latitude: Double?,
    val longitude: Double?,
)

/** GPS 聚簇结果 */
data class Cluster(
    val photos: List<Photo>,
    val representativeTimeMs: Long?,   // 簇内最早拍摄时间
    val centerLat: Double?,
    val centerLng: Double?,
    val outlierPhotos: List<Photo>,    // 与簇中心距离 > 1.5×50m 的离群照片
)

/**
 * GPS 分组结果。
 * @param clusters 正常簇（有 GPS + 精确时间）
 * @param singles  无 GPS / 无精确时间的照片（不丢弃，供 §6.2② 按时间归事件 / 预览页人工处理）
 * @param splitCount 自动拆簇次数（基础簇时间跨度 >5min 被拆出的额外组数，供 §6.3 CLUSTER_SPLIT 提示）
 */
data class GpsGroupResult(
    val clusters: List<Cluster>,
    val singles: List<Photo>,
    val splitCount: Int = 0,
)

/** 台账行（每事件一行，导出Excel 2.0 方案 §7） */
data class LedgerRow(
    val dateTime: String,        // "yyyy-MM-dd HH:mm"（事件开始时间，含巡查日期）
    val endTime: String = "",    // "yyyy-MM-dd HH:mm"（事件结束时间；无结束时间或单时间点事件为空）
    val location: String,        // 完整位置描述（桩号优先）
    val description: String,     // 日志内容
    val photo1Ref: String?,      // 事件第一张（最早）照片
    val photo2Ref: String?,      // 事件最后一张（最晚）照片
    val folderName: String = "", // ZIP 内该事件的分类文件夹名（2026-10-18 P1-3：照片列前缀，消除跨文件夹同名歧义）
)
