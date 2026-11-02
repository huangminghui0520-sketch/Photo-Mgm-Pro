// algorithm/AlgorithmApi.kt —— §2.2 统一算法入口
// UI / PC 只调用此入口；核心内部零 Android 依赖，输入输出均为纯数据。
package algorithm

import algorithm.model.Cluster
import algorithm.model.GpsGroupResult
import algorithm.model.LedgerRow
import algorithm.model.LogEvent
import algorithm.model.Photo
import java.time.LocalDate
import java.time.ZoneId

object AlgorithmApi {
    /** 日志解析：输入日志文本 + 巡查日期，输出 ParsedLog（§3.4）。
     *
     *  @param zone 把日志里的「本地挂钟时间」解释成绝对时刻所用的时区。
     *              默认 [AppZone.ID]（北京时间，issue #2 产品决策）；生产调用点显式传参，
     *              **不要**依赖设备默认时区——否则跨时区运行时台账日期列会整体偏移。 */
    fun parseLog(text: String, date: LocalDate, zone: ZoneId = AppZone.ID): ParsedLog =
        LogParserEngine.parse(text, date, zone)

    /** GPS 分组（§5）：输入已精读 EXIF 的照片，输出 簇 + 无GPS/无时间 singles + 拆簇次数。 */
    fun gpsGroup(
        photos: List<Photo>,
        maxDistanceM: Double = 50.0,
        timeWindowMs: Long = 5 * 60 * 1000,
    ): GpsGroupResult = GpsGrouperEngine(maxDistanceM, timeWindowMs).group(photos)

    /** 事件分类（§6）：簇 + 单张 按窗口归事件，输出 归属表/未匹配/干预提示/待定事件。
     *  @param backfillMs 补拍回看容差（毫秒，默认 120s）：记录时刻之后 backfillMs 内拍摄的照片仍归该事件。
     *  @param zone 计算「巡查日零点」与干预提示里时间文本所用的时区。
     *              ★ 必须与 [parseLog] 用同一个 zone，否则事件边界与时区各偏一半
     *              （parsed 的 epoch 按 A 时区算、dayStart 按 B 时区算，零点会错位）。 */
    fun classify(
        events: List<LogEvent>,
        clusters: List<Cluster>,
        singles: List<Photo>,
        patrolDate: LocalDate,
        splitCount: Int = 0,
        maxDistanceM: Double = 50.0,
        backfillMs: Long = EventClassifyEngine.DEFAULT_BACKFILL_MS,
        zone: ZoneId = AppZone.ID,
    ): ClassifyResult = EventClassifyEngine(maxDistanceM)
        .classify(events, clusters, singles, patrolDate, splitCount, backfillMs, zone)

    /** 台账生成（§7）：事件 + 分类结果 → 每事件一行（日期/结束时间/地点/日志内容/照片第一张+最后一张）。
     *  @param marked 人工台账标记：eventId -> (照片1, 照片2)；某一侧为 null 表示该侧用默认（最早/最晚）
     *  @param namingTemplate 事件文件夹命名模板（P1-3：照片列"文件夹名/文件名"前缀，与 ZIP 分类一致）
     *  @param zone 把 epoch 渲染成「yyyy-MM-dd HH:mm」所用时区；与 [parseLog] 保持一致。 */
    fun buildLedger(
        events: List<LogEvent>,
        eventPhotoMap: Map<Int, List<Long>>,
        photoById: (Long) -> Photo?,
        marked: Map<Int, Pair<Long?, Long?>> = emptyMap(),
        namingTemplate: Int = 0,
        zone: ZoneId = AppZone.ID,
    ): List<LedgerRow> =
        LedgerEngine(zone).buildLedger(events, eventPhotoMap, photoById, marked, namingTemplate)

    /** Excel 写入（§7.3）：LedgerRow → .xlsx 字节流（纯 Kotlin OOXML，可移植 PC）。
     *  @param imageLoader 照片引用 → 图片字节（JPEG/PNG），嵌入台账照片列；返回 null/缺省 则照片列仅写文件名。 */
    fun exportLedgerXlsx(
        rows: List<LedgerRow>,
        out: java.io.OutputStream,
        imageLoader: (String?) -> ByteArray? = { null },
    ): Unit = XlsxWriter.write(rows, out, imageLoader)

    /** ZIP 导出（§7.4）：按事件分类文件夹打包，命名模板二选一，未匹配→未分类；
     *  extraFiles 额外写入 ZIP（如台账 Excel），非空时跳过断点续传。 */
    fun exportZip(
        events: List<LogEvent>,
        eventPhotoMap: Map<Int, List<Long>>,
        unmatched: List<Long>,
        photoById: (Long) -> Photo?,
        namingTemplate: Int,
        outFile: java.io.File,
        stateFile: java.io.File?,
        extraFiles: Map<String, ByteArray> = emptyMap(),
        zone: ZoneId = AppZone.ID,
        onProgress: (Float, String) -> Unit,
    ): ZipExporter.Result = ZipExporter(zone).export(events, eventPhotoMap, unmatched, photoById,
        namingTemplate, outFile, stateFile, extraFiles, onProgress)
}
