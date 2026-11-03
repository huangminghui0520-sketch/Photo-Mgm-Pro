// data/storage/PrefsSettingsStorage.kt —— 设置项的**物理存储**（SharedPreferences）
//
// ★ T06（A1）：从 `app/SettingsStore.kt` 中拆出的"介质段"。
//
// 为什么拆、拆到哪一刀为止 —— 这个边界不是随手切的，判据是「换掉存储介质时哪部分要跟着改」：
//
//   · **本文件管"存成什么形状"**：prefs 文件名、key 名、值类型（String / StringSet / Int）、
//     以及那些把业务映射压成字符串的编码规则（`42=1;43=2`、`1=42,`）。
//     换 SharedPreferences → DataStore / SQLite 时，**这些全部要重写**，所以它们同属介质层。
//   · **`app/SettingsStore` 管"业务类型"**：LocalDate 解析、DedupKeepMode 枚举往返、
//     ParsedLog/ClassifyResult/Photo 的 JSON 编解码、缓存版本位判定。
//     换介质时这些一行都不用动，所以留在 `:app`。
//
// 于是本文件**不认识任何业务类型**：没有 LocalDate、没有 algorithm.model、没有 JSON。
// 只认 String / Int / 字符串集合，以及两个 Map 的字符串编码。
package com.photomgm.data.storage

import android.content.Context
import android.content.SharedPreferences

/**
 * SharedPreferences 轻量持久化的介质层：日期 / 多源目录 / 输出目录 / 命名模板 / 日志文本 /
 * 标记 / 状态缓存原文。
 *
 * 所有属性都是"存进去什么就是什么"：**不做类型转换、不做有效性判断、不解释版本位**。
 * 例如 [dateText] 原样返回当初 [set] 进去的 ISO 字符串，是不是合法日期由调用方判断。
 */
class PrefsSettingsStorage(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_MAIN, Context.MODE_PRIVATE)

    /**
     * 日志文本专用 prefs（W3 / issue #4）。
     * 独立文件的意义：`apply()` 全量重写的是**本文件**的 XML，本文件只有日志这一个 key，
     * 所以写日志的代价与主 prefs 里 MB 级的照片缓存彻底解耦。
     */
    private val logsPrefs: SharedPreferences =
        context.getSharedPreferences(PREFS_LOGS, Context.MODE_PRIVATE)

    // ── 标量设置 ──────────────────────────────────────────────

    /** 巡查日期，ISO-8601（如 `2026-09-03`）；未设置 = null（调用方回落到"今天"）。 */
    var dateText: String?
        get() = prefs.getString(KEY_DATE, null)
        set(v) = prefs.edit().putString(KEY_DATE, v).apply()

    /**
     * 多源目录。
     *
     * ⚠ 已知缺陷（T06 搬运时保留原行为，未修）：底层是 `StringSet`，**顺序不保证**。
     *   原来是 `.toList()` 直接给出 HashSet 的迭代顺序，同一组目录在不同设备/JVM 上可能不同序。
     *   目前没有任何逻辑依赖这个顺序（只是"要扫哪些目录"的集合），故不在归位任务里顺手改语义；
     *   若将来要按用户添加先后展示，必须换成有序存储 —— 改这里即可，与调用方无关。
     */
    var sourceDirs: List<String>
        get() = prefs.getStringSet(KEY_DIRS, emptySet())?.toList() ?: emptyList()
        set(v) = prefs.edit().putStringSet(KEY_DIRS, v.toSet()).apply()

    /** 输出目录（SAF tree uri 或物理路径）；未设置 = null。 */
    var outputDir: String?
        get() = prefs.getString(KEY_OUTPUT, null)
        set(v) = prefs.edit().putString(KEY_OUTPUT, v).apply()

    /** 命名模板档位（1/2/3…）；未设置 = null（调用方回落默认档）。 */
    var namingTemplate: Int?
        get() = if (prefs.contains(KEY_TEMPLATE)) prefs.getInt(KEY_TEMPLATE, 1) else null
        set(v) {
            if (v == null) prefs.edit().remove(KEY_TEMPLATE).apply()
            else prefs.edit().putInt(KEY_TEMPLATE, v).apply()
        }

    /** 重复照片保留策略的**枚举名**（如 `KEEP_WATERMARKED`）；未设置 = null。不做 `valueOf`。 */
    var dedupKeepModeName: String?
        get() = prefs.getString(KEY_DEDUP_MODE, null)
        set(v) = prefs.edit().putString(KEY_DEDUP_MODE, v).apply()

    /**
     * 日志文本（W3 / issue #4）。
     *
     * 读侧兼容旧数据：新版 key 缺失时回落到主 prefs 的旧 [KEY_LOGS]，读到就**迁移**到新文件
     * 并把旧 key 从主 prefs 删除（一次性瘦身，之后主 XML 不再被日志文本撑大）。
     *
     * 这段迁移放在介质层而不是业务层：它整件事就是"把一段字符串从一个 prefs 文件搬到另一个"，
     * 不涉及任何业务解释。放在这里还有一个好处 —— 将来换掉 SharedPreferences 时，
     * 迁移的出口只在本文件，不会漏在半路。
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

    // ── 人工标记（业务映射 ↔ 字符串编码） ─────────────────────

    /**
     * 人工标记 photoId → 目标事件（§8.3 人工移动）。
     *
     * 编码：`42=1;43=2`（分号分隔条目、等号分隔键值）。解析失败或段数不为 2 的条目**静默丢弃**
     * —— 单个坏条目不应让全部人工移动失效。这是编码规则的一部分，故与 [overrideMapText] 同层。
     */
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

    /**
     * 台账照片标记：eventId → (照片1, 照片2)；空侧用 null 表示"默认最早+最晚"（§7/§8.2）。
     *
     * 编码：`1=42,`（逗号两侧分别为"照片1 / 照片2"的 id，空串 = 该侧为 null）。
     * 注意空串必须解成 null 而不是 0L —— 0 会变成一个合法但不存在的光片 id。
     */
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

    // ── 状态缓存（原文） ──────────────────────────────────────
    //  本层只搬字符串/整数，**不解析**：缓存是否可信由调用方的版本位判定决定（见 cache*Version）。

    /** 事件列表缓存原文（ParsedLog JSON）。 */
    var parsedCacheRaw: String?
        get() = prefs.getString(KEY_PARSED_CACHE, null)
        set(v) {
            if (v == null) prefs.edit().remove(KEY_PARSED_CACHE).apply()
            else prefs.edit().putString(KEY_PARSED_CACHE, v).apply()
        }

    /** 分类结果缓存原文（ClassifyResult JSON）。 */
    var classifyCacheRaw: String?
        get() = prefs.getString(KEY_CLASSIFY_CACHE, null)
        set(v) {
            if (v == null) prefs.edit().remove(KEY_CLASSIFY_CACHE).apply()
            else prefs.edit().putString(KEY_CLASSIFY_CACHE, v).apply()
        }

    /** 照片元数据缓存原文（Photo[] JSON）。 */
    var photosCacheRaw: String?
        get() = prefs.getString(KEY_PHOTOS_CACHE, null)
        set(v) {
            if (v == null) prefs.edit().remove(KEY_PHOTOS_CACHE).apply()
            else prefs.edit().putString(KEY_PHOTOS_CACHE, v).apply()
        }

    /** 粗筛数量；未设置 = null（= 没有缓存，流水线要重扫）。 */
    var coarseCount: Int?
        get() = if (prefs.contains(KEY_COARSE_CACHE)) prefs.getInt(KEY_COARSE_CACHE, 0) else null
        set(v) {
            if (v == null) prefs.edit().remove(KEY_COARSE_CACHE).apply()
            else prefs.edit().putInt(KEY_COARSE_CACHE, v).apply()
        }

    /** 解析时刻的日志文本快照：用于判断"日志是否被修改"——未修改时点解析只清人工移动。 */
    var parsedLogsText: String
        get() = prefs.getString(KEY_PARSED_LOGS_TEXT, "") ?: ""
        set(v) = prefs.edit().putString(KEY_PARSED_LOGS_TEXT, v).apply()

    // ── 缓存版本位（只存取，不判定） ──────────────────────────

    /** 派生缓存所用的业务时区版本位（issue #2 迁移用）；未写过 = null（= 旧版本数据）。 */
    fun cacheZoneVersion(): String? = prefs.getString(KEY_CACHE_ZONE, null)

    /** 派生缓存所用的「GPS 语义版本」位（★ P0 修复用）；未写过 = null。 */
    fun cacheGeoVersion(): String? = prefs.getString(KEY_CACHE_GEO, null)

    /**
     * 写入两个版本位。
     *
     * 两个值都由调用方给（业务时区 id、GPS 语义版本串）—— 本层不认它们是什么意思，
     * 只负责"两个 key 一次写完"。一次 `apply()` 而不是两次：版本位必须同时生效，
     * 否则中途被打断会留下"时区对、GPS 不对"的半旧状态，读侧虽然仍判不可用（全有或全无），
     * 但会让排障时看到的现场自相矛盾。
     */
    fun markCacheVersion(zoneId: String, geoVersion: String) {
        prefs.edit()
            .putString(KEY_CACHE_ZONE, zoneId)
            .putString(KEY_CACHE_GEO, geoVersion)
            .apply()
    }

    /** 清空四份派生缓存 + 日志快照（用户数据：日期/目录/模板/标记/日志文本，一个都不动）。 */
    fun clearDerivedCaches() {
        prefs.edit()
            .remove(KEY_PARSED_CACHE)
            .remove(KEY_CLASSIFY_CACHE)
            .remove(KEY_PHOTOS_CACHE)
            .remove(KEY_COARSE_CACHE)
            .remove(KEY_PARSED_LOGS_TEXT)
            .apply()
    }

    private companion object {
        const val PREFS_MAIN = "photo_mgm_settings"
        const val PREFS_LOGS = "photo_mgm_logs"

        const val KEY_DATE = "date"
        const val KEY_DIRS = "source_dirs"
        const val KEY_OUTPUT = "output_dir"
        const val KEY_TEMPLATE = "naming_template"
        const val KEY_DEDUP_MODE = "dedup_keep_mode"
        /** 旧位置（主 prefs，MB 级 XML）：仅用于读侧兼容迁移，见 [logsText]。 */
        const val KEY_LOGS = "logs_text"
        /** 新位置（[logsPrefs] 独立文件内的 key）。与 [KEY_LOGS] 同名是历史原因，两者文件不同。 */
        const val KEY_LOGS_TEXT = "logs_text"
        const val KEY_OVERRIDE = "photo_override"
        const val KEY_MARKED = "photo_marked"
        const val KEY_PARSED_CACHE = "cache_parsed"
        const val KEY_CLASSIFY_CACHE = "cache_classify"
        const val KEY_PHOTOS_CACHE = "cache_photos"
        const val KEY_COARSE_CACHE = "cache_coarse_count"
        const val KEY_PARSED_LOGS_TEXT = "cache_parsed_logs_text"
        const val KEY_CACHE_ZONE = "cache_zone"
        const val KEY_CACHE_GEO = "cache_geo_version"
    }
}
