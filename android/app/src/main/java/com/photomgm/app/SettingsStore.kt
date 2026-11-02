// app/SettingsStore.kt —— 设置持久化（§8.4 自动保存：设置即存）
// ★ 2026-10-07 新增：状态缓存（事件列表 ParsedLog / 分类 ClassifyResult / 照片 Photo），
//   打开 App 时恢复关闭前的解析与分类结果，无需重跑流水线。
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
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** SharedPreferences 轻量持久化：日期 / 多源目录 / 输出目录 / 命名模板 / 日志文本 / 标记 / 状态缓存。 */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("photo_mgm_settings", Context.MODE_PRIVATE)

    /**
     * 日志文本专用 prefs（W3 / issue #4）。
     * 独立文件的意义：`apply()` 全量重写的是**本文件**的 XML，本文件只有日志这一个 key，
     * 所以写日志的代价与主 prefs 里 MB 级的照片缓存彻底解耦。
     */
    private val logsPrefs = context.getSharedPreferences("photo_mgm_logs", Context.MODE_PRIVATE)

    var date: LocalDate
        get() = prefs.getString(KEY_DATE, null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: LocalDate.now(AppZone.ID)
        set(v) = prefs.edit().putString(KEY_DATE, v.toString()).apply()

    var sourceDirs: List<String>
        get() = prefs.getStringSet(KEY_DIRS, emptySet())?.toList() ?: emptyList()
        set(v) = prefs.edit().putStringSet(KEY_DIRS, v.toSet()).apply()

    var outputDir: String?
        get() = prefs.getString(KEY_OUTPUT, null)
        set(v) = prefs.edit().putString(KEY_OUTPUT, v).apply()

    var namingTemplate: Int
        get() = prefs.getInt(KEY_TEMPLATE, 1)
        set(v) = prefs.edit().putInt(KEY_TEMPLATE, v).apply()

    /** ★ 2026-10-24 B1：重复照片保留策略（带水印 / 不带水印 / 都保留），默认仅保留带水印。 */
    var dedupKeepMode: DedupKeepMode
        get() = prefs.getString(KEY_DEDUP_MODE, null)?.let { raw ->
            runCatching { DedupKeepMode.valueOf(raw) }.getOrNull()
        } ?: DedupKeepMode.KEEP_WATERMARKED
        set(v) = prefs.edit().putString(KEY_DEDUP_MODE, v.name).apply()

    /**
     * 日志文本（W3 / issue #4）。
     *
     * ★ 单独存一个 prefs 文件（[logsPrefs]），不与主 prefs 混放：主 prefs 的 XML 里含
     *   MB 级的 `cache_photos`，而 SharedPreferences 的 `apply()` 是**全量重写整个 XML**。
     *   以前日志文本存在主 prefs 里，输入框每敲一个字符就触发一次 MB 级写盘 —— 卡顿 + 闪存磨损。
     *   隔离到独立文件后，写日志只重写「只有这一个 key」的小文件；再叠加
     *   [LogsTextStore] 的防抖，连打时只落一次盘。
     *
     * 读侧兼容旧数据：新版 key 缺失时回落到主 prefs 的旧 [KEY_LOGS]，读到就**迁移**到新文件
     * 并把旧 key 从主 prefs 删除（一次性瘦身，之后主 XML 不再被日志文本撑大）。
     */
    var logsText: String
        get() {
            logsPrefs.getString(KEY_LOGS_TEXT, null)?.let { return it }
            val legacy = prefs.getString(KEY_LOGS, null) ?: return ""
            logsPrefs.edit().putString(KEY_LOGS_TEXT, legacy).apply()
            prefs.edit().remove(KEY_LOGS).apply()
            return legacy
        }
        set(v) = logsPrefs.edit().putString(KEY_LOGS_TEXT, v).apply()

    /** 人工标记 photoId → 目标事件（§8.3 人工移动） */
    var overrideMap: Map<Long, Int>
        get() = prefs.getString(KEY_OVERRIDE, null)?.let { raw ->
            runCatching {
                raw.split(';').mapNotNull { seg ->
                    val kv = seg.split('=')
                    if (kv.size == 2) kv[0].toLong() to kv[1].toInt() else null
                }.toMap()
            }.getOrElse { emptyMap() }
        } ?: emptyMap()
        set(v) = prefs.edit()
            .putString(KEY_OVERRIDE, v.entries.joinToString(";") { "${it.key}=${it.value}" }).apply()

    /** 台账照片标记：eventId → (照片1, 照片2)；空侧用 null 表示"默认最早+最晚"（§7/§8.2） */
    var markedMap: Map<Int, Pair<Long?, Long?>>
        get() = prefs.getString(KEY_MARKED, null)?.let { raw ->
            runCatching {
                raw.split(';').mapNotNull { seg ->
                    val kv = seg.split('=')
                    if (kv.size != 2) return@mapNotNull null
                    val ids = kv[1].split(',')
                    kv[0].toInt() to (ids.getOrNull(0)?.toLongOrNull() to ids.getOrNull(1)?.toLongOrNull())
                }.toMap()
            }.getOrElse { emptyMap() }
        } ?: emptyMap()
        set(v) = prefs.edit().putString(
            KEY_MARKED,
            v.entries.joinToString(";") { (k, p) -> "${k}=${p.first ?: ""},${p.second ?: ""}" }
        ).apply()

    // ──────────────────────────────────────────────────────────────
    // ★ 2026-10-07 状态缓存：打开 App 恢复关闭前的事件列表 + 分类 + 照片
    //   （JSON 序列化存 SharedPreferences；日期/源目录变更或清空日志时由调用方清除）
    // ──────────────────────────────────────────────────────────────

    /** 事件列表缓存（ParsedLog JSON）。 */
    var parsedCache: ParsedLog?
        get() = if (!cacheZoneMatches()) null else prefs.getString(KEY_PARSED_CACHE, null)?.let { raw ->
            runCatching { parseParsed(raw) }.getOrNull()
        }
        set(v) = prefs.edit()
            .putString(KEY_PARSED_CACHE, v?.let { serializeParsed(it) }).apply()

    /** 分类结果缓存（ClassifyResult JSON）。 */
    var classifyCache: ClassifyResult?
        get() = if (!cacheZoneMatches()) null else prefs.getString(KEY_CLASSIFY_CACHE, null)?.let { raw ->
            runCatching { parseClassify(raw) }.getOrNull()
        }
        set(v) = prefs.edit()
            .putString(KEY_CLASSIFY_CACHE, v?.let { serializeClassify(it) }).apply()

    /** 照片元数据缓存（Photo[] JSON；分类的 photoId 需映射回 Photo 供导出/查看器使用）。 */
    var photosCache: List<Photo>
        get() = if (!cacheZoneMatches()) emptyList() else prefs.getString(KEY_PHOTOS_CACHE, null)?.let { raw ->
            runCatching { parsePhotos(raw) }.getOrNull()
        } ?: emptyList()
        set(v) = prefs.edit().putString(KEY_PHOTOS_CACHE, serializePhotos(v)).apply()

    /** 粗筛数量缓存（恢复后 ensurePipeline 视为已粗筛，跳过重扫）。 */
    var coarseCountCache: Int?
        get() {
            if (!cacheZoneMatches()) return null
            return if (prefs.contains(KEY_COARSE_CACHE)) prefs.getInt(KEY_COARSE_CACHE, 0) else null
        }
        set(v) {
            if (v == null) prefs.edit().remove(KEY_COARSE_CACHE).apply()
            else prefs.edit().putInt(KEY_COARSE_CACHE, v).apply()
        }

    /**
     * 记录"当前派生缓存是按哪个业务时区算出来的"。
     *
     * ★ issue #2 一次性迁移的版本位：上面的四个派生缓存里存的是 **epoch 毫秒**，历史上是用
     *   「当时设备的默认时区」把日志/EXIF 里的本地挂钟时间解释出来的。若当时设备不在 +08:00，
     *   这些 epoch 就是错的，而且**无法从 epoch 反推原始挂钟**（信息已丢失）。
     *   处置不是"换算"，而是**丢弃 + 用日志原文在 [AppZone.ID] 下重算**：
     *   读侧发现版本位与当前业务时区不一致 → 当作没有缓存（返回 null / 空列表），
     *   流水线随即重跑并调用本方法把版本位刷新成新值。
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
    fun markCacheZone() {
        prefs.edit().putString(KEY_CACHE_ZONE, AppZone.ID.id).apply()
    }

    /** 派生缓存是否按当前业务时区算出（版本位缺失 = 旧版本数据 → 不可信）。 */
    private fun cacheZoneMatches(): Boolean = prefs.getString(KEY_CACHE_ZONE, null) == AppZone.ID.id

    /** 解析时刻的日志文本快照：用于判断"日志是否被修改"——未修改时点解析只清人工移动。 */
    var parsedLogsText: String
        get() = prefs.getString(KEY_PARSED_LOGS_TEXT, "") ?: ""
        set(v) = prefs.edit().putString(KEY_PARSED_LOGS_TEXT, v).apply()

    /** 清空全部状态缓存（日期/源目录变更、清空日志时调用）。 */
    fun clearStateCache() {
        parsedCache = null
        classifyCache = null
        photosCache = emptyList()
        coarseCountCache = null
        parsedLogsText = ""
        // 清空后版本位与新写入保持一致，避免"刚清完又被判为旧版本"的反复失效
        markCacheZone()
    }

    // ── JSON 序列化（Android 内置 org.json，零新依赖） ──

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
        const val KEY_DATE = "date"
        const val KEY_DIRS = "source_dirs"
        const val KEY_OUTPUT = "output_dir"
        const val KEY_TEMPLATE = "naming_template"
        const val KEY_DEDUP_MODE = "dedup_keep_mode"
        /** 旧位置（主 prefs，MB 级 XML）：仅用于读侧兼容迁移，见 [logsText]。 */
        const val KEY_LOGS = "logs_text"
        /** 新位置（[logsPrefs] 独立文件内的 key）。 */
        const val KEY_LOGS_TEXT = "logs_text"
        const val KEY_OVERRIDE = "photo_override"
        const val KEY_MARKED = "photo_marked"
        const val KEY_PARSED_CACHE = "cache_parsed"
        const val KEY_CLASSIFY_CACHE = "cache_classify"
        const val KEY_PHOTOS_CACHE = "cache_photos"
        const val KEY_COARSE_CACHE = "cache_coarse_count"
        const val KEY_PARSED_LOGS_TEXT = "cache_parsed_logs_text"
        /** 派生缓存所用的业务时区版本位（issue #2 迁移用，见 [markCacheZone]）。 */
        const val KEY_CACHE_ZONE = "cache_zone"
    }
}
