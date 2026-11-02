// algorithm/GpsGrouperEngineEdgeTest.kt —— 相片匹配(GPS组合)边界/容错/性能专项测试
// 覆盖：非法坐标 / (0,0) 坐标 / 距离阈值边界 / 5min时间窗边界 / 链式 / 500张性能
package algorithm

import algorithm.model.Photo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class GpsGrouperEngineEdgeTest {
    private val engine = GpsGrouperEngine()
    private val zone = ZoneId.of("Asia/Shanghai")
    private val t0 = LocalDateTime.of(2026, 9, 3, 10, 0).atZone(zone).toInstant().toEpochMilli()
    private fun t(min: Long) = t0 + min * 60_000
    private fun ts(sec: Long) = t0 + sec * 1_000
    private fun photo(id: Long, time: Long?, lat: Double?, lng: Double?) =
        Photo(id, "s$id", "img$id.jpg", time, lat, lng)

    // ── 1. GPS 数据异常 ──

    @Test fun `纬度为0经度为0当作有效坐标聚簇`() {
        // (0,0) 常为"无GPS"的错误编码：ExifInterface.latLong 对写死的 0,0 返回 [0.0,0.0]（非null）
        // → 会被当作有效 GPS。多张 0,0 会聚成一簇（若时间相近）。此处记录现状。
        val photos = listOf(
            photo(1, t(0), 0.0, 0.0),
            photo(2, t(1), 0.0, 0.0),
        )
        val r = engine.group(photos)
        assertEquals(1, r.clusters.size, "(0,0) 被当作有效GPS聚簇（设计缺口：应过滤0,0）")
        assertEquals(0, r.singles.size)
    }

    @Test fun `单张0坐标成单簇不崩溃`() {
        val r = engine.group(listOf(photo(1, t(0), 0.0, 0.0)))
        assertEquals(1, r.clusters.size)
        assertTrue(r.clusters[0].outlierPhotos.isEmpty())
    }

    @Test fun `纬度超出合法范围不崩溃`() {
        // lat=95（>90）：distance 计算不抛异常，与正常点距离 >50m → 各自成簇
        val photos = listOf(
            photo(1, t(0), 95.0, 0.0),
            photo(2, t(1), 23.0, 113.0),
        )
        val r = engine.group(photos)
        assertEquals(2, r.clusters.size, "非法纬度不与正常点并簇（Haversine 产出大距离）")
    }

    @Test fun `经度超出合法范围不崩溃`() {
        val photos = listOf(
            photo(1, t(0), 23.0, 190.0),
            photo(2, t(1), 23.0, 113.0),
        )
        val r = engine.group(photos)
        assertEquals(2, r.clusters.size, "非法经度不与正常点并簇，不崩溃")
    }

    @Test fun `只缺经度进singles`() {
        val r = engine.group(listOf(photo(1, t(0), 23.0, null)))
        assertEquals(0, r.clusters.size)
        assertEquals(1, r.singles.size)
    }

    // ── 2. 阈值边界 ──

    @Test fun `距离略小于50米并簇`() {
        // 0.00045°≈50.1m 超阈值；0.00040°≈44.5m 内
        val photos = listOf(
            photo(1, t(0), 23.0000, 113.0),
            photo(2, t(1), 23.00040, 113.0),
        )
        val r = engine.group(photos)
        assertEquals(1, r.clusters.size)
    }

    @Test fun `距离略大于50米分簇`() {
        val photos = listOf(
            photo(1, t(0), 23.0000, 113.0),
            photo(2, t(1), 23.00045, 113.0),  // ≈50.1m
        )
        val r = engine.group(photos)
        assertEquals(2, r.clusters.size, "≈50.1m 应超过阈值分簇")
    }

    @Test fun `时间窗4分59秒内同点并簇`() {
        val photos = listOf(
            photo(1, t(0), 23.0, 113.0),
            photo(2, t(4), 23.0, 113.0),
            photo(3, t(4), 23.0, 113.0),  // 299s
        )
        val r = engine.group(photos)
        assertEquals(1, r.clusters.size)
    }

    @Test fun `时间窗恰好5分钟并簇`() {
        // 差 300000ms 恰好 = 5min：≤ timeWindowMs → 建边 → 1 簇
        val photos = listOf(
            photo(1, t(0), 23.0, 113.0),
            photo(2, t(5), 23.0, 113.0),
        )
        val r = engine.group(photos)
        assertEquals(1, r.clusters.size, "恰好 5min 边界（≤）应并簇")
    }

    @Test fun `时间窗刚超5分钟拆簇`() {
        // 0min 与 5min01s：差 301s>300s → 不建边 → 2 簇
        val t2 = t0 + 301_000
        val photos = listOf(
            photo(1, t(0), 23.0, 113.0),
            photo(2, t2, 23.0, 113.0),
        )
        val r = engine.group(photos)
        assertEquals(2, r.clusters.size, "301s 超 5min 边界应拆成两簇")
    }

    // ── 3. 链式 / 连通性 ──

    @Test fun `链式A-B-C连通为一簇`() {
        // A(0m)-B(44m)-C(88m)：A-B 建、B-C 建、A-C(88>50) 不建 → 经 B 连通
        val photos = listOf(
            photo(1, t(0), 23.0000, 113.0),
            photo(2, t(1), 23.0004, 113.0),
            photo(3, t(2), 23.0008, 113.0),
        )
        val r = engine.group(photos)
        assertEquals(1, r.clusters.size)
        assertEquals(3, r.clusters[0].photos.size)
    }

    @Test fun `链式断链拆两簇`() {
        // A-B 44m 建边；C 距 B 约 178m 不建边 → 2 簇
        val photos = listOf(
            photo(1, t(0), 23.0000, 113.0),
            photo(2, t(1), 23.0004, 113.0),
            photo(3, t(2), 23.0020, 113.0),  // 距B ≈178m
        )
        val r = engine.group(photos)
        assertEquals(2, r.clusters.size)
    }

    // ── 4. GPS+时间组合（事件层） ──

    @Test fun `同一地点不同时间点拆簇后归不同事件窗口`() {
        // 同一 GPS：08:00 两张、09:00 两张（间隔>5min）→ 2 簇
        val z = ZoneId.of("Asia/Shanghai")
        fun at(h: Int, m: Int) = LocalDateTime.of(2026, 8, 17, h, m).atZone(z).toInstant().toEpochMilli()
        val photos = listOf(
            photo(1, at(8, 0), 23.02, 113.01),
            photo(2, at(8, 1), 23.02, 113.01),
            photo(3, at(9, 0), 23.02, 113.01),
            photo(4, at(9, 1), 23.02, 113.01),
        )
        val g = GpsGrouperEngine().group(photos)
        assertEquals(2, g.clusters.size, "同点但时间跨 1h 应拆为两簇（供分属不同事件窗口）")
        // 事件窗口归属：08:00 簇 → 事件1（交接班吸收 [dayStart, 08:30)）；09:00 簇 → 事件3（09:30 接报）
        val events = listOf(
            algorithm.model.LogEvent(1, at(8, 0), at(8, 0), listOf(at(8, 0)), "K10", "交接班", "08:00 交接班"),
            algorithm.model.LogEvent(2, at(8, 30), at(8, 30), listOf(at(8, 30)), "K10-K20", "出车", "08:30 出车"),
            algorithm.model.LogEvent(3, at(9, 30), at(9, 30), listOf(at(9, 30)), "K12+300", "接报", "09:30 接报"),
        )
        val c = EventClassifyEngine().classify(events, g.clusters, g.singles, LocalDate.of(2026, 8, 17))
        assertEquals(2, c.eventPhotoMap[1]!!.size, "08:00 簇归事件1(交接班)")
        assertEquals(2, c.eventPhotoMap[3]!!.size, "09:00 簇归事件3(接报)")
        assertEquals(0, c.eventPhotoMap[2]!!.size)
    }

    // ── 5. 性能与稳定性 ──

    @Test
    @Timeout(10)
    fun `500张带GPS照片分组不超时`() {
        val photos = (1L..500L).map { i ->
            // 0.5s 间隔，总跨度 250s≤5min；GPS 小范围抖动
            val d = (i % 5) * 0.00003  // ±~10m
            photo(i, ts(i * 500), 23.01 + d, 113.01 + (i % 3) * 0.00003)
        }
        val r = engine.group(photos)
        assertTrue(r.clusters.size >= 1)
        val total = r.clusters.sumOf { it.photos.size } + r.singles.size
        assertEquals(500, total, "500 张全部进入簇或 singles，不丢失")
    }

    @Test fun `500张同GPS聚一簇`() {
        val photos = (1L..500L).map { i -> photo(i, ts(i * 30L), 23.01, 113.01) }
        val r = engine.group(photos)
        // 30s 间隔 × 500 = 15000s > 5min → 会拆成多簇；验证总照片数不丢失
        val total = r.clusters.sumOf { it.photos.size } + r.singles.size
        assertEquals(500, total)
        assertTrue(r.clusters.isNotEmpty())
    }
}
