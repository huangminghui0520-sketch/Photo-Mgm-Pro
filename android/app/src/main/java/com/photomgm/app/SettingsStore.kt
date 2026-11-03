// app/SettingsStore.kt —— 设置持久化的**业务语义层**
// ★ 2026-10-07 新增：状态缓存（事件列表 ParsedLog / 分类 ClassifyResult / 照片 Photo），
//   打开 App 时恢复关闭前的解析与分类结果，无需重跑流水线。
//
// ★ T06（A1）：物理存储拆到 `:data` 的 `PrefsSettingsStorage`。本文件只剩"业务类型 ↔ 存储形状"
//   这一层转换：
//     · LocalDate / DedupKeepMode 的类型转换与默认值；
//     · ParsedLog / ClassifyResult / Photo 的 JSON 编解码（依赖 algorithm.model，属业务模型）；
//     · 派生缓存"可信不可信"的判定（两个版本位：业务时区 + GPS 语义）。
//   构造函数与全部属性名**一字未改** —— 两个迁移测试（logs / zone）是本次搬动的护栏，
//   它们的断言一行不动就必须照样绿。
package com.photomgm.app

import algorithm.AppZone
import algorithm.ClassifyResult
import algorithm.DedupKeepMode
import algorithm.Intervention
import algorithm.Kind
import algorithm.ParsedLog
import algorithm.model.LogEvent
import algorithm.model.Photo
import android.content.Context
import com.photomgm.data.storage.PrefsSettingsStorage
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** 设置项的业务视图：日期 / 多源目录 / 输出目录 / 命名模板 / 日志文本 / 标记 / 状态缓存。 */
class SettingsStore(context: Context) {
    private val storage = PrefsSettingsStorage(context)

    var date: LocalDate
        get() = storage.dateText?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: LocalDate.now(AppZone.ID)
        set(v) {
            storage.dateText = v.toString()
        }

    var sourceDirs: List<String>
        get() = storage.sourceDirs
        set(v) {
            storage.sourceDirs = v
        }

    var outputDir: String?
        get() = storage.outputDir
        set(v) {
            storage.outputDir = v
        }

    var namingTemplate: Int
        get() = storage.namingTemplate ?: 1
        set(v) {
            storage.namingTemplate = v
        }

    /** ★ 2026-10-24 B1：重复照片保留策略（带水印 / 不带水印 / 都保留），默认仅保留带水印。 */
    var dedupKeepMode: DedupKeepMode
        get() = storage.dedupKeepModeName?.let { raw ->
            runCatching { DedupKeepMode.valueOf(raw) }.getOrNull()
        } ?: DedupKeepMode.KEEP_WATERMARKED
        set(v) {
            storage.dedupKeepModeName = v.name
        }

    /**
     * 日志文本（W3 / issue #4）。
     *
     * 存储侧（独立 prefs 文件、旧 key 兼容迁移）见 [PrefsSettingsStorage.logsText]；
     * 本层只是转发。分开的理由：那边是"存哪儿"，这边是"业务上它是什么"。
     */
    var logsText: String
        get() = storage.logsText
        set(v) {
            storage.logsText = v
        }

    /** 人工标记 photoId → 目标事件（§8.3 人工移动） */
    var overrideMap: Map<Long, Int>
        get() = storage.overrideMap
        set(v) {
            storage.overrideMap = v
        }

    /** 台账照片标记：eventId → (照片1, 照片2)；空侧用 null 表示"默认最早+最晚"（§7/§8.2） */
    var markedMap: Map<Int, Pair<Long?, Long?>>
        get() = storage.markedMap
        set(v) {
            storage.markedMap = v
        }

    // ──────────────────────────────────────────────────────────────
    // ★ 2026-10-07 状态缓存：打开 App 恢复关闭前的事件列表 + 分类 + 照片
    //   （JSON 序列化存 SharedPreferences；日期/源目录变更或清空日志时由调用方清除）
    // ──────────────────────────────────────────────────────────────

    /** 事件列表缓存（ParsedLog JSON）。 */
    var parsedCache: ParsedLog?
        get() = if (!cacheUsable()) null else storage.parsedCacheRaw?.let { raw ->
            runCatching { parseParsed(raw) }.getOrNull()
        }
        set(v) {
            storage.parsedCacheRaw = v?.let { serializeParsed(it) }
        }

    /** 分类结果缓存（ClassifyResult JSON）。 */
    var classifyCache: ClassifyResult?
        get() = if (!cacheUsable()) null else storage.classifyCacheRaw?.let { raw ->
            runCatching { parseClassify(raw) }.getOrNull()
        }
        set(v) {
            storage.classifyCacheRaw = v?.let { serializeClassify(it) }
        }

    /** 照片元数据缓存（Photo[] JSON；分类的 photoId 需映射回 Photo 供导出/查看器使用）。 */
    var photosCache: List<Photo>
        get() = if (!cacheUsable()) emptyList() else storage.photosCacheRaw?.let { raw ->
            runCatching { parsePhotos(raw) }.getOrNull()
        } ?: emptyList()
        set(v) {
            storage.photosCacheRaw = serializePhotos(v)
        }

    /** 粗筛数量缓存（恢复后 ensurePipeline 视为已粗筛，跳过重扫）。 */
    var coarseCountCache: Int?
        get() = if (!cacheUsable()) null else storage.coarseCount
        set(v) {
            storage.coarseCount = v
        }

    /**
     * 记录"这批派生缓存是在什么语义假设下算出来的"（时区 + GPS 读取能力两个版本位）。
     *
     * ★ issue #2 一次性迁移的版本位：上面的四个派生缓存里存的是 **epoch 毫秒**，历史上是用
     *   「当时设备的默认时区」把日志/EXIF 里的本地挂钟时间解释出来的。若当时设备不在 +08:00，
     *   这些 epoch 就是错的，而且**无法从 epoch 反推原始挂钟**（信息已丢失）。
     *   处置不是"换算"，而是**丢弃 + 用日志原文在 [AppZone.ID] 下重算**：
     *   读侧发现版本位与当前业务时区不一致 → 当作没有缓存（返回 null / 空列表），
     *   流水线随即重跑并调用本方法把版本位刷新成新值。
     *
     * ★ P0（2026-11-02）追加第二个版本位 [GEO_CACHE_VERSION]：
     *   旧版本 APK 缺 `ACCESS_MEDIA_LOCATION`，读到的 `latitude/longitude` 恒为 null——那是
     *   **系统剥离的结果**，不是照片本来没有定位。修好权限后这些 null 仍躺在 cache_photos 里，
     *   而"打开 App 从缓存恢复"不会重读 EXIF，于是修复对老用户完全无效。
     *   故用 [GEO_CACHE_VERSION] 一次性作废旧缓存：旧包没写过这个位 → 读不到 → 全量重算。
     *   注意这里**故意不用运行时权限状态**做版本位，而是用静态常量：权限状态在 init 时未必已定
     *   （对话框还没回来），拿它当判据会让"刚授予"的用户反复丢缓存。补授权限后的口径由
     *   [AppViewModel.onMediaPermissionsResult] 提示用户重新解析，语义更清楚。
     *
     * 受影响数据（PR 里逐项列出）：
     *   - cache_parsed / cache_classify / cache_photos / cache_coarse_count：全部丢弃重算；
     *   - date / source_dirs / output_dir / naming_template / dedup_keep_mode / logs_text：
     *     **原样保留**（不是时间派生数据）；
     *   - photo_override / photo_marked：**原样保留** —— 它们的键是 MediaStore `_ID`，
     *     重扫后 ID 不变，所以人工移动与台账标记不会丢。
     *
     * 回滚方案：revert 引入本方法的提交即可。版本位不匹配只影响"要不要用缓存"，
     *   不会删除任何用户数据；回滚后缓存会按旧时区重新生成，App 仍可用。
     */
    fun markCacheVersion() {
        storage.markCacheVersion(AppZone.ID.id, GEO_CACHE_VERSION)
    }

    /**
     * 派生缓存是否可用：时区与 GPS 语义两个版本位**都**要对上（全有或全无）。
     *
     * 判定留在业务层而不是介质层的理由：`AppZone.ID` 与 [GEO_CACHE_VERSION] 都是**业务语义**
     * （"按北京时间解释挂钟" / "这批 GPS 是在申请了 ACCESS_MEDIA_LOCATION 之后读的"），
     * 存储层不该知道它们是什么，只负责把两个字符串原样存下来。
     */
    private fun cacheUsable(): Boolean =
        storage.cacheZoneVersion() == AppZone.ID.id && storage.cacheGeoVersion() == GEO_CACHE_VERSION

    /** 解析时刻的日志文本快照：用于判断"日志是否被修改"——未修改时点解析只清人工移动。 */
    var parsedLogsText: String
        get() = storage.parsedLogsText
        set(v) {
            storage.parsedLogsText = v
        }

    /** 清空全部状态缓存（日期/源目录变更、清空日志时调用）。 */
    fun clearStateCache() {
        storage.clearDerivedCaches()
        // 清空后版本位与新写入保持一致，避免"刚清完又被判为旧版本"的反复失效
        markCacheVersion()
    }

    // ── JSON 序列化（Android 内置 org.json，零新依赖） ──
    //  留在本层而不是介质层：结构体是 algorithm.model 的类型，属业务模型；
    //  换存储介质（SharedPreferences → DataStore/SQLite）时这套编解码一行都不用改。

    private fun serializeParsed(p: ParsedLog): String {
        val o = JSONObject()
        val evs = JSONArray()
        p.events.forEach { evs.put(serializeEvent(it)) }
        o.put("events", evs)
        o.put("skippedLines", JSONArray(p.skippedLines))
        o.put("inspectors", p.inspectors ?: JSONObject.NULL)
        o.put("recorder", p.recorder ?: JSONObject.NULL)
        o.put("vehicle", p.vehicle ?: JSONObject.NULL)
        o.put("notices", JSONArray(p.notices))
        return o.toString()
    }

    private fun parseParsed(raw: String): ParsedLog {
        val o = JSONObject(raw)
        val evs = o.getJSONArray("events")
        val events = (0 until evs.length()).map { parseEvent(evs.getJSONObject(it)) }
        val skipped = o.optJSONArray("skippedLines")?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        } ?: emptyList()
        val notices = o.optJSONArray("notices")?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        } ?: emptyList()
        return ParsedLog(
            events = events,
            skippedLines = skipped,
            inspectors = if (o.isNull("inspectors")) null else o.getString("inspectors"),
            recorder = if (o.isNull("recorder")) null else o.getString("recorder"),
            vehicle = if (o.isNull("vehicle")) null else o.getString("vehicle"),
            notices = notices,
        )
    }

    private fun serializeEvent(e: LogEvent): JSONObject {
        val o = JSONObject()
        o.put("id", e.id)
        o.put("startTimeMs", e.startTimeMs ?: JSONObject.NULL)
        o.put("endTimeMs", e.endTimeMs ?: JSONObject.NULL)
        o.put("timePoints", JSONArray(e.timePoints))
        o.put("location", e.location)
        o.put("eventType", e.eventType)
        o.put("description", e.description)
        return o
    }

    private fun parseEvent(o: JSONObject): LogEvent = LogEvent(
        id = o.getInt("id"),
        startTimeMs = if (o.isNull("startTimeMs")) null else o.getLong("startTimeMs"),
        endTimeMs = if (o.isNull("endTimeMs")) null else o.getLong("endTimeMs"),
        timePoints = o.optJSONArray("timePoints")?.let { arr ->
            (0 until arr.length()).map { arr.getLong(it) }
        } ?: emptyList(),
        location = o.optString("location", ""),
        eventType = o.getString("eventType"),
        description = o.getString("description"),
    )

    private fun serializePhoto(p: Photo): JSONObject {
        val o = JSONObject()
        o.put("id", p.id)
        o.put("sourceRef", p.sourceRef)
        o.put("displayName", p.displayName)
        o.put("captureTimeMs", p.captureTimeMs ?: JSONObject.NULL)
        o.put("latitude", p.latitude ?: JSONObject.NULL)
        o.put("longitude", p.longitude ?: JSONObject.NULL)
        return o
    }

    private fun parsePhoto(o: JSONObject): Photo = Photo(
        id = o.getLong("id"),
        sourceRef = o.getString("sourceRef"),
        displayName = o.getString("displayName"),
        captureTimeMs = if (o.isNull("captureTimeMs")) null else o.getLong("captureTimeMs"),
        latitude = if (o.isNull("latitude")) null else o.getDouble("latitude"),
        longitude = if (o.isNull("longitude")) null else o.getDouble("longitude"),
    )

    private fun serializePhotos(photos: List<Photo>): String {
        val arr = JSONArray()
        photos.forEach { arr.put(serializePhoto(it)) }
        return arr.toString()
    }

    private fun parsePhotos(raw: String): List<Photo> {
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { parsePhoto(arr.getJSONObject(it)) }
    }

    private fun serializeClassify(c: ClassifyResult): String {
        val o = JSONObject()
        val epm = JSONObject()
        c.eventPhotoMap.forEach { (k, v) -> epm.put(k.toString(), JSONArray(v)) }
        o.put("eventPhotoMap", epm)
        o.put("unmatched", JSONArray(c.unmatched))
        val ivs = JSONArray()
        c.interventions.forEach { ivs.put(serializeIntervention(it)) }
        o.put("interventions", ivs)
        o.put("pendingEventIds", JSONArray(c.pendingEventIds))
        return o.toString()
    }

    private fun parseClassify(raw: String): ClassifyResult {
        val o = JSONObject(raw)
        val epm = o.getJSONObject("eventPhotoMap")
        val map = LinkedHashMap<Int, List<Long>>()
        val keys = epm.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val arr = epm.getJSONArray(k)
            map[k.toInt()] = (0 until arr.length()).map { arr.getLong(it) }
        }
        val unmatched = o.getJSONArray("unmatched").let { arr ->
            (0 until arr.length()).map { arr.getLong(it) }
        }
        val ivs = o.optJSONArray("interventions")?.let { arr ->
            (0 until arr.length()).map { parseIntervention(arr.getJSONObject(it)) }
        } ?: emptyList()
        val pending = o.optJSONArray("pendingEventIds")?.let { arr ->
            (0 until arr.length()).map { arr.getInt(it) }
        } ?: emptyList()
        return ClassifyResult(map, unmatched, ivs, pending)
    }

    private fun serializeIntervention(i: Intervention): JSONObject {
        val o = JSONObject()
        o.put("kind", i.kind.name)
        o.put("message", i.message)
        o.put("photoIds", JSONArray(i.photoIds))
        o.put("relatedEventIds", JSONArray(i.relatedEventIds))
        return o
    }

    private fun parseIntervention(o: JSONObject): Intervention = Intervention(
        kind = Kind.valueOf(o.getString("kind")),
        message = o.getString("message"),
        photoIds = o.getJSONArray("photoIds").let { arr ->
            (0 until arr.length()).map { arr.getLong(it) }
        },
        relatedEventIds = o.optJSONArray("relatedEventIds")?.let { arr ->
            (0 until arr.length()).map { arr.getInt(it) }
        } ?: emptyList(),
    )

    private companion object {
        /**
         * GPS 语义版本：`media-location-v1` = 已声明并申请 `ACCESS_MEDIA_LOCATION` 之后读出的结果。
         * 旧版本 APK 没写这个位（读不到 GPS，却把 `latitude=null` 当真值缓存了下来）→ 一律作废重算。
         * 将来若再改 GPS 读取方式（如改用 `MediaStore.setRequireOriginal`），改这里的值即可让旧缓存失效。
         *
         * 定义放业务层而非介质层：它描述的是"这批 GPS 是怎么读出来的"，存储层只把它当不透明字符串。
         */
        const val GEO_CACHE_VERSION = "media-location-v1"
    }
}
