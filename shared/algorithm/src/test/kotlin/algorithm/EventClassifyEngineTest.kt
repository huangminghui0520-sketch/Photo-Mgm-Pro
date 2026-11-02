// algorithm/EventClassifyEngineTest.kt —— §6 事件分类模块测试
// 契约（★ 终点归属 + 首条特例，用户定稿）：
//   通用：事件B 照片 = [记录A, 记录B) → 归事件B（终点归属）；
//   首条（交接班）吸收 [dayStart, 第二条记录时间)（交接班到出车之间单独归集到交接班）；
//   末条闭区间；无时间事件进待定区不自动归；单张按时间；§6.3 干预提示
package algorithm

import algorithm.model.Cluster
import algorithm.model.LogEvent
import algorithm.model.Photo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId

class EventClassifyEngineTest {
    private val engine = EventClassifyEngine()
    private val zone = ZoneId.of("Asia/Shanghai")
    private val date = LocalDate.of(2026, 9, 3)
    private fun at(h: Int, m: Int) = date.atTime(h, m).atZone(zone).toInstant().toEpochMilli()
    private fun ev(id: Int, start: Long, end: Long, type: String = "事件$id") =
        LogEvent(id, start, end, listOf(start, end), "K1+000", type, "描述$id")
    private fun photo(id: Long, t: Long?, lat: Double = 23.0, lng: Double = 113.0) =
        Photo(id, "s$id", "img$id.jpg", t, lat, lng)
    private fun cluster(id: Long, t: Long, lat: Double = 23.0, lng: Double = 113.0, outlier: List<Long> = emptyList()) =
        Cluster(listOf(photo(id, t, lat, lng)), t, lat, lng, outlier.map { photo(it, t, lat, lng) })

    // ---- 探针测试（终点归属） ----

    @Test fun `终点归属_记录A到记录B之间的照片归事件B`() {
        // 三事件：E1 记录=08:00，E2 记录=08:15，E3 记录=09:00；照片 08:45 ∈ [08:15,09:00) → 归 E3（事件B）
        val e1 = ev(1, at(8, 0), at(8, 0), "交接班")
        val e2 = ev(2, at(8, 15), at(8, 15), "出车")
        val e3 = ev(3, at(9, 0), at(9, 0), "事件X")
        val c = cluster(11, at(8, 45))
        val r = engine.classify(listOf(e1, e2, e3), listOf(c), emptyList(), date)
        assertTrue(r.eventPhotoMap[3]!!.contains(11L), "08:45 在出车记录与事件X记录之间 → 归事件X（终点归属）")
        assertTrue(r.eventPhotoMap[1]!!.isEmpty())
        assertTrue(r.eventPhotoMap[2]!!.isEmpty())
    }

    /**
     * ★ 2026-10-31 审查回归：事件区间**嵌套**时（长事件包含短事件）不得因排序键错位而归错事件。
     *   findWindow 按"记录（开始）时间升序"推演，但传入的 timed 此前是按 endTime 排序的
     *   （[短事件 08:20, 长事件 09:00]），于是 08:30 的照片被判给记录时间 08:10 的短事件；
     *   08:30 实际落在长事件区间内，应归长事件。
     */
    @Test fun `区间嵌套时照片归区间覆盖它的事件`() {
        val long1 = ev(1, at(8, 0), at(9, 0), "长事件")     // 记录 08:00，结束 09:00（覆盖 08:30）
        val short1 = ev(2, at(8, 10), at(8, 20), "短事件")   // 记录 08:10，结束 08:20（不覆盖 08:30）
        val c = cluster(11, at(8, 30))
        val r = engine.classify(listOf(long1, short1), listOf(c), emptyList(), date)
        assertTrue(r.eventPhotoMap[1]!!.contains(11L), "08:30 落在长事件区间内 → 归长事件（此前误归短事件）")
        assertTrue(r.eventPhotoMap[2]!!.isEmpty())
    }

    @Test fun `边界_照片时间等于本条记录时间归本条事件_补拍回看`() {
        // 照片 08:15 == E2 记录时间 → 补拍回看 0 秒 → 归 E2（2026-10-01 规则变更：不再推给下一事件）
        val e1 = ev(1, at(8, 0), at(8, 0), "交接班")
        val e2 = ev(2, at(8, 15), at(8, 15), "出车")
        val e3 = ev(3, at(9, 0), at(9, 0), "事件X")
        val c = cluster(11, at(8, 15))
        val r = engine.classify(listOf(e1, e2, e3), listOf(c), emptyList(), date)
        assertTrue(r.eventPhotoMap[2]!!.contains(11L), "t == E2 记录时间 → 归本条（补拍回看 0s ≤ backfillMs）")
        assertTrue(r.eventPhotoMap[3]!!.isEmpty())
    }

    // ---- 用户契约：首条交接班吸收到第二条记录 ----

    @Test fun `交接班吸收到出车记录之间的照片`() {
        // 交接班记录 08:00，出车记录 08:15；08:00~08:15 的检车照片 → 归交接班（单独归集）
        val handover = ev(1, at(8, 0), at(8, 0), "交接班")
        val depart = ev(2, at(8, 15), at(8, 15), "出车")
        val c = cluster(11, at(8, 10))
        val r = engine.classify(listOf(handover, depart), listOf(c), emptyList(), date)
        assertTrue(r.eventPhotoMap[1]!!.contains(11L), "交接班到出车之间的照片归交接班")
        assertTrue(r.eventPhotoMap[2]!!.isEmpty())
    }

    @Test fun `首条吸收上班开始到出车记录之间的照片`() {
        // 07:30（早于交接班记录 08:00，≥ 当日 00:00）→ 归交接班
        val handover = ev(1, at(8, 0), at(8, 0), "交接班")
        val depart = ev(2, at(8, 15), at(8, 15), "出车")
        val c = cluster(11, at(7, 30))
        val r = engine.classify(listOf(handover, depart), listOf(c), emptyList(), date)
        assertTrue(r.eventPhotoMap[1]!!.contains(11L), "首条吸收 [00:00, 出车记录)，07:30 归交接班")
    }

    // ---- 扩展 ----

    @Test fun `末条闭区间包含记录后照片`() {
        // 末条（事件X 记录 09:00）之后照片（09:30）→ 归末条
        val e1 = ev(1, at(8, 0), at(8, 0), "交接班")
        val e2 = ev(2, at(8, 15), at(8, 15), "出车")
        val e3 = ev(3, at(9, 0), at(9, 0), "事件X")
        val c = cluster(11, at(9, 30))
        val r = engine.classify(listOf(e1, e2, e3), listOf(c), emptyList(), date)
        assertTrue(r.eventPhotoMap[3]!!.contains(11L), "末条闭区间：t >= 末条记录 → 归末条")
    }

    @Test fun `无GPS单张按时间归属`() {
        val e1 = ev(1, at(8, 0), at(8, 0))
        val e2 = ev(2, at(8, 15), at(8, 15))
        val e3 = ev(3, at(9, 0), at(9, 0))
        val p = photo(21, at(8, 45), 23.0, 113.0)
        val r = engine.classify(listOf(e1, e2, e3), emptyList(), listOf(p), date)
        assertTrue(r.eventPhotoMap[3]!!.contains(21L), "无 GPS 单张按 captureTimeMs 归窗口（08:45 归 E3）")
    }

    @Test fun `无时间照片进未匹配池`() {
        val e1 = ev(1, at(9, 0), at(10, 0))
        val p = photo(21, null)
        val r = engine.classify(listOf(e1), emptyList(), listOf(p), date)
        assertTrue(r.unmatched.contains(21L), "无时间照片不自动归入，进 unmatched 人工处理")
    }

    @Test fun `无时间事件进待定区且照片不自动归入`() {
        val e1 = ev(1, at(9, 0), at(10, 0))
        val pending = LogEvent(3, null, null, emptyList(), "K9+000", "待定", "描述3")
        val c = cluster(11, at(9, 30))   // < E1 记录 10:00 → 归 E1（首条吸收）
        val r = engine.classify(listOf(e1, pending), listOf(c), emptyList(), date)
        assertEquals(listOf(3), r.pendingEventIds, "无时间事件进待定区")
        assertTrue(r.eventPhotoMap[3]!!.isEmpty(), "待定事件照片不自动归入")
        assertTrue(r.eventPhotoMap[1]!!.contains(11L), "照片归有窗口的事件")
    }

    @Test fun `时间穿插产生提示`() {
        val e1 = ev(1, at(9, 0), at(10, 30))
        val e2 = ev(2, at(10, 0), at(11, 0))   // start(10:00) < 前 end(10:30)
        val r = engine.classify(listOf(e1, e2), emptyList(), emptyList(), date)
        assertTrue(r.interventions.any { it.kind == Kind.TIME_OVERLAP }, "穿插需提示人工核对")
    }

    @Test fun `簇含离群照片产生GPS_FAR提示`() {
        val e1 = ev(1, at(9, 0), at(10, 0))
        val c = cluster(11, at(9, 30), outlier = listOf(12L))
        val r = engine.classify(listOf(e1), listOf(c), emptyList(), date)
        assertTrue(r.interventions.any { it.kind == Kind.GPS_FAR && it.photoIds.contains(12L) },
            "簇内离群照片需 GPS_FAR 提示")
    }

    @Test fun `事件内两簇中心离散产生CLUSTER_SPAN提示`() {
        val e1 = ev(1, at(9, 0), at(10, 0))
        val e2 = ev(2, at(10, 0), at(11, 0))
        val c1 = cluster(11, at(10, 30), 23.0000, 113.0)
        val c2 = cluster(12, at(10, 40), 23.0018, 113.0)   // 距 c1 ≈200m > 75m
        val r = engine.classify(listOf(e1, e2), listOf(c1, c2), emptyList(), date)
        assertTrue(r.interventions.any { it.kind == Kind.CLUSTER_SPAN }, "同一事件下两簇中心超阈值需提示核对")
    }

    @Test fun `自动拆簇产生CLUSTER_SPLIT提示`() {
        val e1 = ev(1, at(9, 0), at(10, 0))
        val r = engine.classify(listOf(e1), emptyList(), emptyList(), date, splitCount = 2)
        assertTrue(r.interventions.any { it.kind == Kind.CLUSTER_SPLIT && it.message.contains("2") },
            "GPS 层自动拆簇次数需 CLUSTER_SPLIT 提示")
    }

    // ---- 2026-10-01 规则改进：补拍回看 + 终点兜底（修复记录后补拍照被推给下一事件） ----

    @Test fun `记录后补拍归原事件`() {
        // E2 记录 08:15、E3 记录 09:00；照片 08:15:30（记录后 30 秒补拍）→ 归 E2（旧规则推给 E3）
        val e1 = ev(1, at(8, 0), at(8, 0), "交接班")
        val e2 = ev(2, at(8, 15), at(8, 15), "出车")
        val e3 = ev(3, at(9, 0), at(9, 0), "事件X")
        val c = cluster(11, at(8, 15) + 30_000)
        val r = engine.classify(listOf(e1, e2, e3), listOf(c), emptyList(), date)
        assertTrue(r.eventPhotoMap[2]!!.contains(11L), "记录后 30 秒补拍归原事件（backfillMs=120s）")
        assertTrue(r.eventPhotoMap[3]!!.isEmpty())
    }

    @Test fun `补拍超过容差归下一事件`() {
        // backfillMs=60s，照片 08:16:30（距 E2 90s > 60s）→ 终点兜底归 E3
        val e1 = ev(1, at(8, 0), at(8, 0), "交接班")
        val e2 = ev(2, at(8, 15), at(8, 15), "出车")
        val e3 = ev(3, at(9, 0), at(9, 0), "事件X")
        val c = cluster(11, at(8, 16) + 30_000)
        val r = engine.classify(listOf(e1, e2, e3), listOf(c), emptyList(), date, backfillMs = 60_000)
        assertTrue(r.eventPhotoMap[3]!!.contains(11L), "补拍超过容差 → 终点兜底归下一事件")
    }

    @Test fun `backfillMs为0退化为纯终点归属`() {
        val e1 = ev(1, at(8, 0), at(8, 0), "交接班")
        val e2 = ev(2, at(8, 15), at(8, 15), "出车")
        val e3 = ev(3, at(9, 0), at(9, 0), "事件X")
        val c = cluster(11, at(8, 15) + 30_000)
        val r = engine.classify(listOf(e1, e2, e3), listOf(c), emptyList(), date, backfillMs = 0)
        assertTrue(r.eventPhotoMap[3]!!.contains(11L), "backfillMs=0 → 纯终点归属（回滚开关）")
        assertTrue(r.eventPhotoMap[2]!!.isEmpty())
    }

    @Test fun `相邻事件间隔过近产生TIME_OVERLAP提示`() {
        // E2 记录 08:15、E3 记录 08:15:30（间隔 30s < 60s）→ 过近提示
        val e1 = ev(1, at(8, 0), at(8, 0), "交接班")
        val e2 = ev(2, at(8, 15), at(8, 15), "出车")
        val e3 = ev(3, at(8, 15) + 30_000, at(8, 15) + 30_000, "事件X")
        val r = engine.classify(listOf(e1, e2, e3), emptyList(), emptyList(), date)
        val hit = r.interventions.firstOrNull { it.kind == Kind.TIME_OVERLAP && it.message.contains("过近") }
            ?: error("相邻事件间隔不足 60 秒需提示人工核对；实际提示=${r.interventions.map { it.message }}")
        assertTrue(hit.message.contains("60 秒"), "提示文案必须写实际阈值（60 秒），不能留旧口径：${hit.message}")
    }

    // ---- 2026-11-01：过近阈值 240s → 60s（桥底场景，同一处停留的照片间隔不超过 60 秒） ----

    @Test fun `间隔180秒不再提示过近`() {
        // 旧口径（2×backfillMs=240s）会提示；新口径（60s）不提示 —— 这正是现场"满屏提示"的来源
        val e1 = ev(1, at(8, 0), at(8, 0), "交接班")
        val e2 = ev(2, at(8, 15), at(8, 15), "出车")
        val e3 = ev(3, at(8, 18), at(8, 18), "事件X")
        val r = engine.classify(listOf(e1, e2, e3), emptyList(), emptyList(), date)
        assertTrue(r.interventions.none { it.message.contains("过近") },
            "相隔 180 秒（>60 秒）不应再报过近：${r.interventions.map { it.message }}")
    }

    @Test fun `间隔恰好60秒不提示_边界取严`() {
        // 阈值语义是"间隔 < 60 秒才提示"：正好 60 秒属于"分得开"，不打扰
        val e1 = ev(1, at(8, 0), at(8, 0), "交接班")
        val e2 = ev(2, at(8, 15), at(8, 15), "出车")
        val e3 = ev(3, at(8, 16), at(8, 16), "事件X")
        val r = engine.classify(listOf(e1, e2, e3), emptyList(), emptyList(), date)
        assertTrue(r.interventions.none { it.message.contains("过近") }, "间隔正好 60 秒不提示")
    }

    @Test fun `同一事件内间隔90秒的照片仍归同一事件`() {
        // 阈值只管"提示"，绝不按照片间隔拆事件：09:00:00 与 09:01:30 都落在 E2 的记录之后
        val e1 = ev(1, at(8, 0), at(8, 0), "交接班")
        val e2 = ev(2, at(9, 0), at(9, 0), "桥底取证")
        val e3 = ev(3, at(9, 30), at(9, 30), "事件X")
        val c = Cluster(
            listOf(photo(11, at(9, 0) + 10_000), photo(12, at(9, 0) + 100_000)),
            at(9, 0) + 10_000, 23.0, 113.0, emptyList())
        val r = engine.classify(listOf(e1, e2, e3), listOf(c), emptyList(), date)
        assertTrue(r.eventPhotoMap[2]!!.containsAll(listOf(11L, 12L)),
            "同一次停留的 90 秒间隔照片必须留在同一事件（阈值只影响提示）")
        assertTrue(r.eventPhotoMap[3]!!.isEmpty())
    }

    @Test fun `日志间隔90秒的照片按记录时间拆到各自事件`() {
        // 桥底 08:00 拍完、08:01:30 在下一点位拍 → 两条日志相隔 90 秒，照片各归各家
        val e1 = ev(1, at(8, 0), at(8, 0), "桥底取证")
        val e2 = ev(2, at(8, 1) + 30_000, at(8, 1) + 30_000, "护栏取证")
        val a = photo(11, at(8, 0) + 5_000)
        val b = photo(12, at(8, 1) + 35_000)
        val r = engine.classify(listOf(e1, e2), emptyList(), listOf(a, b), date)
        assertTrue(r.eventPhotoMap[1]!!.contains(11L), "08:00:05 → 事件1")
        assertTrue(r.eventPhotoMap[2]!!.contains(12L), "08:01:35 → 事件2（不被 120s 补拍容差拽回事件1）")
        assertTrue(r.interventions.none { it.message.contains("过近") }, "间隔 90 秒不提示过近")
    }

    @Test fun `日志提到拍照但无照片产生NO_PHOTO提示`() {
        val e1 = ev(1, at(9, 0), at(10, 0), "取证事件")
            .copy(description = "发现标牌损坏，已拍照取证")
        val r = engine.classify(listOf(e1), emptyList(), emptyList(), date)
        assertTrue(r.interventions.any { it.kind == Kind.NO_PHOTO && it.relatedEventIds.contains(1) },
            "日志提到拍照/取证但照片数为 0 需提示人工核对")
    }

    @Test fun `簇内照片跨越两个事件产生CLUSTER_SPAN提示`() {
        // E2 记录 09:30、E3 记录 10:00；簇照片 09:31（归 E2）与 09:59:30（归 E3）→ 跨事件提示
        val e1 = ev(1, at(9, 0), at(9, 0), "事件A")
        val e2 = ev(2, at(9, 30), at(9, 30), "事件B")
        val e3 = ev(3, at(10, 0), at(10, 0), "事件C")
        val c = Cluster(
            listOf(photo(11, at(9, 31)), photo(12, at(9, 59) + 30_000)),
            at(9, 31), 23.0, 113.0, emptyList())
        val r = engine.classify(listOf(e1, e2, e3), listOf(c), emptyList(), date)
        assertTrue(r.interventions.any {
            it.kind == Kind.CLUSTER_SPAN && it.photoIds.containsAll(listOf(11L, 12L))
        }, "簇内最早/最晚照片跨不同事件需提示核对")
    }

    // ---- 2026-10-31 崩溃回归：日志无可识别时间（全部待定事件）+ 带时间照片 → 不崩溃、照片进未匹配 ----

    @Test fun `全部事件无时间_带时间簇照片_不崩溃_进未匹配`() {
        // 真实崩溃场景：timed 为空 → findWindow 空列表 events.last() 抛 NoSuchElementException（parseLogs 协程内未捕获 → 应用关闭）
        val pending1 = LogEvent(1, null, null, emptyList(), "K1+000", "待定1", "无时间事件1")
        val pending2 = LogEvent(2, null, null, emptyList(), "K2+000", "待定2", "无时间事件2")
        val c = cluster(11, at(9, 30))   // 带 GPS + 精确时间
        val r = engine.classify(listOf(pending1, pending2), listOf(c), emptyList(), date)
        assertEquals(listOf(1, 2), r.pendingEventIds, "全部事件进待定区")
        assertTrue(r.unmatched.contains(11L), "无归属事件 → 簇照片进未匹配池，不崩溃")
    }

    @Test fun `全部事件无时间_无GPS单张带时间_不崩溃_进未匹配`() {
        val pending = LogEvent(1, null, null, emptyList(), "K1+000", "待定", "无时间事件")
        val s = photo(21, at(9, 30), 23.0, 113.0)
        val r = engine.classify(listOf(pending), emptyList(), listOf(s), date)
        assertTrue(r.unmatched.contains(21L), "无归属事件 → 单张照片进未匹配池，不崩溃")
    }
}
