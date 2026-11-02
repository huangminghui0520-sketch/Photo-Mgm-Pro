// algorithm/EventClassifyEngine.kt —— §6 事件分类模块
// 窗口规则（2026-10-01 改进：补拍回看 + 终点兜底，替代原纯终点归属）：
//   ① 首条特例：交接班（第一条）吸收 [dayStart, 第二条记录时间) —— 交接班到出车之间的照片单独归集到交接班
//   ② 补拍回看：记录时间 r ≤ t 且 t - r ≤ backfillMs（默认 120s）→ 归该事件（记录时刻之后的补拍照不再推给下一事件）
//   ③ 终点兜底：否则照片归到「记录时间 > 拍摄时间」的第一条日志（先拍后记场景保持不变）
//   ④ 末条闭区间（≥ 末条记录时间 → 末条，含跨日加班次日照片）
// 归属流程（§6.2）：① 簇整体按 representativeTime 归窗口 ② 无GPS单张按时间 ③ 其余 → unmatched
// 人工干预提示（§6.3）：GPS_FAR / CLUSTER_SPLIT / TIME_OVERLAP / CLUSTER_SPAN / NO_PHOTO（提示级，不阻断）
package algorithm

import algorithm.model.Cluster
import algorithm.model.LogEvent
import algorithm.model.Photo
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class Kind { GPS_FAR, CLUSTER_SPLIT, TIME_OVERLAP, CLUSTER_SPAN, NO_PHOTO }

data class Intervention(
    val kind: Kind,
    val message: String,
    val photoIds: List<Long>,
    val relatedEventIds: List<Int> = emptyList(),   // ★ 关联事件 id（用于预览页定位核对）
)

data class ClassifyResult(
    val eventPhotoMap: Map<Int, List<Long>>,   // eventId -> photoIds
    val unmatched: List<Long>,
    val interventions: List<Intervention>,
    val pendingEventIds: List<Int>,            // 待定事件（无时间），照片不自动归入
)

class EventClassifyEngine(private val maxDistanceM: Double = 50.0) {
    companion object {
        /** 补拍回看容差：记录时刻之后 backfillMs 内拍摄的照片仍归该事件（默认 120 秒，设 0 退化为纯终点归属）。 */
        const val DEFAULT_BACKFILL_MS = 120_000L

        /**
         * 相邻事件「时间过近」判定阈值（毫秒，默认 60 秒）。
         *
         * ★ 2026-11-01 由 240 秒下调（原实现借 `2 × backfillMs` 表达）：
         *   现场口径变了。桥底这类多点位取证的停留，同一处点位内的照片间隔通常**不超过 60 秒**，
         *   而两条日志相隔 60 秒以上时，照片按记录时间就能分得开 —— 这时再提示"照片归属易混淆"
         *   只是噪音：一天 8~20 条事件里有一半都顶着一条提示，真正需要核对的那条就沉下去了。
         *   60 秒是把"同一处停留"和"下一条日志"分开的经验分界，不是从 backfillMs 推出来的，
         *   所以这里**独立成常量**，不再跟着补拍容差联动（改补拍容差不应顺带改提示口径）。
         */
        const val NEAR_GAP_MS = 60_000L
    }

    /**
     * @param backfillMs 补拍回看容差（毫秒）
     * @param nearGapMs 相邻事件「时间过近」提示阈值（毫秒，默认 [NEAR_GAP_MS]）
     * @param zone 「巡查日零点」与干预提示里时间文本所用时区。
     *             ★ 必须与解析日志用同一个 zone：解析把挂钟换成 epoch，这里把 epoch 换成
     *             "当日零点"边界与提示文本，两侧不一致会让零点错位（issue #2）。
     */
    fun classify(
        events: List<LogEvent>,
        clusters: List<Cluster>,
        singles: List<Photo>,
        patrolDate: LocalDate,
        splitCount: Int = 0,                    // GPS 层自动拆簇次数 → CLUSTER_SPLIT 提示
        backfillMs: Long = DEFAULT_BACKFILL_MS, // 补拍回看容差（毫秒）
        zone: ZoneId = AppZone.ID,
        nearGapMs: Long = NEAR_GAP_MS,          // 相邻事件"过近"提示阈值（毫秒）
    ): ClassifyResult {
        val sorted = events.sortedBy { it.endTimeMs ?: Long.MAX_VALUE }
        val dayStart = patrolDate.atStartOfDay().atZone(zone).toInstant().toEpochMilli()
        val pending = sorted.filter { it.startTimeMs == null }
        // ★ 2026-10-31 审查修复：findWindow 的窗口划分按「记录（开始）时间升序」推演（其注释亦如此声明），
        //   但上面的 sorted 是按 endTime 排序的——事件区间嵌套/重叠时 endTime 序 ≠ startTime 序，
        //   落在长事件内的照片会被错判给短事件。例：A(08:00~09:00) 包含 B(08:10~08:20)，
        //   按 endTime 排序为 [B, A]，08:30 的照片会被判给 B（正确应属 A 或按最近记录归 A）。
        //   这里对 timed 补一次 startTime 升序即可；sorted 仍用于 pending 划分与 assigned 初始化顺序，
        //   保持原样不动，避免影响既有行为。
        val timed = sorted.filter { it.startTimeMs != null }.sortedBy { it.startTimeMs }

        val assigned = LinkedHashMap<Int, MutableList<Long>>()
        sorted.forEach { assigned[it.id] = mutableListOf() }
        val unmatched = mutableListOf<Long>()
        val interventions = mutableListOf<Intervention>()

        // ① 簇整体归属
        for (c in clusters) {
            val t = c.representativeTimeMs
            if (t == null) { unmatched.addAll(c.photos.map { it.id }); continue }
            val target = findWindow(t, timed, dayStart, backfillMs)
            if (target != null) {
                assigned[target.id]!!.addAll(c.photos.map { it.id })
                if (c.outlierPhotos.isNotEmpty()) interventions.add(
                    Intervention(Kind.GPS_FAR, "该组照片 GPS 定位相差较大，可能属于不同事件", c.outlierPhotos.map { it.id }))
                // ★ 簇内最早/最晚照片跨到不同事件 → 提示（簇可能黏连多个事件）
                if (c.photos.size > 1) {
                    val times = c.photos.mapNotNull { it.captureTimeMs }
                    if (times.isNotEmpty()) {
                        val eFirst = findWindow(times.min(), timed, dayStart, backfillMs)
                        val eLast = findWindow(times.max(), timed, dayStart, backfillMs)
                        if (eFirst != null && eLast != null && eFirst.id != eLast.id) {
                            interventions.add(Intervention(
                                Kind.CLUSTER_SPAN,
                                "该组照片时间跨度跨越事件「${eFirst.eventType} → ${eLast.eventType}」，可能包含多个事件，请核对",
                                c.photos.map { it.id }, listOf(eFirst.id, eLast.id)))
                        }
                    }
                }
            } else unmatched.addAll(c.photos.map { it.id })
        }
        // ② 无 GPS 单张按时间
        for (p in singles) {
            val t = p.captureTimeMs
            if (t == null) { unmatched.add(p.id); continue }
            val target = findWindow(t, timed, dayStart, backfillMs)
            if (target != null) assigned[target.id]!!.add(p.id) else unmatched.add(p.id)
        }
        // 时间穿插提示（后一条 start < 前一条 endTime）+ 间隔过近提示（间隔 < nearGapMs，默认 60s）
        // ★ 增强：消息明确标注穿插的两个事件（编号/类型/时间区间/地点），供预览页人工核对
        for (i in 1 until timed.size) {
            val prev = timed[i - 1]; val cur = timed[i]
            val prevEnd = prev.endTimeMs ?: prev.startTimeMs!!
            val curStart = cur.startTimeMs!!
            val curEnd = cur.endTimeMs ?: cur.startTimeMs!!
            if (curStart < prevEnd) {
                interventions.add(
                    Intervention(
                        Kind.TIME_OVERLAP,
                        "事件时间穿插：${evtInfo(prev, zone)} 与 ${evtInfo(cur, zone)} 时间重叠，请人工核对照片归属",
                        assigned[cur.id]!!,
                        relatedEventIds = listOf(prev.id, cur.id),
                    ))
            } else if (curEnd - prevEnd < nearGapMs) {
                interventions.add(
                    Intervention(
                        Kind.TIME_OVERLAP,
                        "事件时间过近：${evtInfo(prev, zone)} 与 ${evtInfo(cur, zone)} 间隔不足 ${nearGapMs / 1000} 秒，照片归属易混淆，请核对",
                        emptyList(),
                        relatedEventIds = listOf(prev.id, cur.id),
                    ))
            }
        }
        // 事件内多簇中心离散提示（CLUSTER_SPAN）
        // ★ 2026-10-24 B3：同事件聚合为一条（附超距簇对数与涉及照片数），消除同文案重复刷屏
        for (e in timed) {
            val mine = clusters.filter { c -> c.photos.any { assigned[e.id]!!.contains(it.id) } }
            val farPairs = mutableListOf<Pair<Cluster, Cluster>>()
            for (i in 0 until mine.size) for (j in i + 1 until mine.size) {
                if (clusterDistance(mine[i], mine[j]) > maxDistanceM * 1.5) farPairs.add(mine[i] to mine[j])
            }
            if (farPairs.isNotEmpty()) {
                val ids = farPairs.flatMap { it.first.photos + it.second.photos }.map { it.id }.distinct()
                interventions.add(Intervention(
                    Kind.CLUSTER_SPAN,
                    "事件「${e.eventType}」有 ${farPairs.size} 组照片 GPS 跨度大（涉及 ${ids.size} 张），请核对",
                    ids, listOf(e.id)))
            }
        }
        // 自动拆簇提示（§6.3 承诺、§6.4 遗漏 → 完善补回）
        if (splitCount > 0) interventions.add(
            Intervention(Kind.CLUSTER_SPLIT, "已按时间自动拆分 $splitCount 组，请核对", emptyList()))
        // 事件无照片提示（日志提到拍照/取证但照片数为 0 → 可能被误归其他事件）
        for (e in timed) {
            val d = e.description
            if (assigned[e.id]!!.isEmpty() && (d.contains("拍照") || d.contains("取证"))) {
                interventions.add(Intervention(
                    Kind.NO_PHOTO,
                    "事件「${evtInfo(e, zone)}」日志提到拍照/取证但无照片，可能被误归其他事件，请核对",
                    emptyList(), listOf(e.id)))
            }
        }
        // 待定事件提示（人工指定时间后重算）
        if (pending.isNotEmpty()) interventions.add(
            Intervention(
                Kind.TIME_OVERLAP,
                "存在 ${pending.size} 条未记录时间的事件（${pending.map { "事件#${it.id} ${it.eventType}" }.joinToString("、")}），请人工指定时间",
                emptyList(),
                relatedEventIds = pending.map { it.id },
            ))

        return ClassifyResult(
            assigned.mapValues { it.value.toList() },
            unmatched, interventions, pending.map { it.id })
    }

    /**
     * 窗口归属（2026-10-01 改进：补拍回看 + 终点兜底）：
     *   - 首条（交接班）吸收 [dayStart, 第二条记录时间)：交接班到出车之间的照片单独归集到交接班
     *   - 补拍回看：最近的记录时间 r ≤ t 且 t - r ≤ backfillMs → 归该事件（记录时刻之后的补拍照归回原事件，
     *     修复「记录后 1 秒~几分钟的取证照被推给下一事件」的错位；backfillMs=0 退化为纯终点归属）
     *   - 终点兜底：照片归到「记录时间 > 拍摄时间」的第一条日志（先拍后记场景保持原行为）
     *   - 末条闭区间：t ≥ 末条记录时间 → 末条（含跨日加班次日照片）
     *   - t < dayStart（异常早）→ null（unmatched）
     */
    private fun findWindow(t: Long, events: List<LogEvent>, dayStart: Long, backfillMs: Long): LogEvent? {
        if (t < dayStart) return null
        // ★ 2026-10-31 崩溃修复：timed 为空（日志无可识别时间的事件）时，事件列表空 → events.last() 抛
        //   NoSuchElementException，在 parseLogs 协程内无 try/catch → 应用直接关闭。
        //   语义：无可归属事件 → 照片进未匹配池（与"待定事件人工指定时间后重算"一致），而非崩溃。
        if (events.isEmpty()) return null
        val second = events.getOrNull(1)?.let { it.endTimeMs ?: it.startTimeMs!! }
        if (second != null && t < second) return events[0]
        // 补拍回看：记录时间 ≤ t 的最近一条事件（events 已按记录时间升序）
        var last: Pair<LogEvent, Long>? = null
        for (e in events) {
            val r = e.endTimeMs ?: e.startTimeMs!!
            if (r <= t) {
                last = e to r
            } else {
                val lb = last
                if (lb != null && t - lb.second <= backfillMs) return lb.first
                return e   // 终点兜底：第一条「记录时间 > 拍摄时间」的日志
            }
        }
        // 末条闭区间：全部记录时间 ≤ t
        val lb = last
        return if (lb != null && t - lb.second <= backfillMs) lb.first else events.last()
    }

    /** 事件摘要（编号/类型/时间区间/地点），用于干预提示明确标注核对对象。 */
    private fun evtInfo(e: LogEvent, zone: ZoneId): String {
        val fmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
        fun t(ms: Long?): String = ms?.let {
            java.time.Instant.ofEpochMilli(it).atZone(zone).format(fmt)
        } ?: "无时间"
        val loc = e.location?.takeIf { it.isNotBlank() } ?: "未定位"
        return "事件#${e.id} ${e.eventType} ${t(e.startTimeMs)}~${t(e.endTimeMs)} $loc"
    }

    /** 两簇中心距离（哈弗辛）；任一中心缺失 → MAX_VALUE（提示跨度大）。 */
    private fun clusterDistance(a: Cluster, b: Cluster): Double {
        val lat1 = a.centerLat ?: return Double.MAX_VALUE
        val lng1 = a.centerLng ?: return Double.MAX_VALUE
        val lat2 = b.centerLat ?: return Double.MAX_VALUE
        val lng2 = b.centerLng ?: return Double.MAX_VALUE
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        return R * 2 * atan2(sqrt(h), sqrt(1 - h))
    }
}
