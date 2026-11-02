// algorithm/GpsGrouperEngineTest.kt —— §5 GPS 分组模块测试
// 契约（§5.1）：50m 聚簇 / 5min 拆簇 / 簇代表=最早 / 中心=GPS 均值 / 离群>1.5×50m / 无GPS·无时间进 singles
package algorithm

import algorithm.model.Photo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneId

class GpsGrouperEngineTest {
    private val engine = GpsGrouperEngine()
    private val zone = ZoneId.of("Asia/Shanghai")
    private val t0 = LocalDateTime.of(2026, 9, 3, 10, 0).atZone(zone).toInstant().toEpochMilli()
    private fun t(min: Long) = t0 + min * 60_000
    private fun ts(sec: Long) = t0 + sec * 1_000     // 秒级时间（用于紧凑簇内）
    private fun photo(id: Long, time: Long?, lat: Double?, lng: Double?) =
        Photo(id, "s$id", "img$id.jpg", time, lat, lng)
    private val p1 = 23.0000 to 113.0000
    private val p2 = 23.0004 to 113.0000   // ≈44m（50m 内）
    private val p3 = 23.0018 to 113.0000   // ≈200m（>50m）

    // ---- 探针测试 ----

    @Test fun `无GPS或无时间的照片进singles不丢失`() {
        val photos = listOf(
            photo(1, t(0), p1.first, p1.second),          // 有效
            photo(2, t(1), null, p1.second),              // 无 GPS
            photo(3, null, p1.first, p1.second),          // 无时间
        )
        val r = engine.group(photos)
        assertEquals(1, r.clusters.size)
        assertEquals(listOf("img2.jpg", "img3.jpg"), r.singles.map { it.displayName }, "无GPS/无时间照片不得丢失")
    }

    @Test fun `同点时间跨度超5分钟自动拆簇`() {
        // 10:00-10:02 建边、10:02-10:06(4min) 建边、10:06-10:08 建边 → 一个基础簇，但跨度 8min>5min → 拆成两簇
        val photos = listOf(
            photo(1, t(0), p1.first, p1.second),
            photo(2, t(2), p1.first, p1.second),
            photo(3, t(6), p1.first, p1.second),
            photo(4, t(8), p1.first, p1.second),
        )
        val r = engine.group(photos)
        assertEquals(2, r.clusters.size, "簇内时间跨度>5min 必须自动拆簇，避免误并")
        assertEquals(listOf(1L, 2L), r.clusters[0].photos.map { it.id })
        assertEquals(listOf(3L, 4L), r.clusters[1].photos.map { it.id })
    }

    // ---- 扩展 ----

    @Test fun `同点5分钟内并簇且代表时间为最早`() {
        val photos = listOf(
            photo(1, t(4), p1.first, p1.second),
            photo(2, t(0), p1.first, p1.second),
            photo(3, t(2), p1.first, p1.second),
        )
        val r = engine.group(photos)
        assertEquals(1, r.clusters.size)
        assertEquals(3, r.clusters[0].photos.size)
        assertEquals(t(0), r.clusters[0].representativeTimeMs, "簇代表时间=簇内最早拍摄")
    }

    @Test fun `相距超过50米分簇`() {
        val photos = listOf(
            photo(1, t(0), p1.first, p1.second),
            photo(2, t(1), p3.first, p3.second),   // ≈200m
        )
        val r = engine.group(photos)
        assertEquals(2, r.clusters.size)
    }

    @Test fun `距簇中心超过阈值识别为离群`() {
        // 离群=簇内但与 GPS 均值中心 >1.5×50m(75m)：
        // 8 张锚在 23.0000 + 1 张 23.00040(距A≈44.5m 建边) + 1 张 23.00084
        //   23.00084 距 23.00040≈49m(≤50m 建边)，距中心 (0.00040+0.00084)/10=0.000124 → ≈79.7m(>75m) → 离群
        // 注意 0.00045°≈50.1m 会刚超 50m 阈值不建边，需留余量
        // 时间用 30s 间隔（10 张总跨度 4.5min ≤5min，不触发拆簇）
        val photos = buildList {
            repeat(8) { add(photo(it + 1L, ts(it * 30L), 23.0000, 113.0)) }
            add(photo(9, ts(8 * 30L), 23.00040, 113.0))
            add(photo(10, ts(9 * 30L), 23.00084, 113.0))
        }
        val r = engine.group(photos)
        assertEquals(1, r.clusters.size, "10 张应在 5min 窗口内并为一簇")
        assertEquals(listOf(10L), r.clusters[0].outlierPhotos.map { it.id }, "离群(>1.5×50m)须进入 outlierPhotos")
    }

    @Test fun `单张有效照片成单簇`() {
        val r = engine.group(listOf(photo(1, t(0), p1.first, p1.second)))
        assertEquals(1, r.clusters.size)
        assertTrue(r.clusters[0].outlierPhotos.isEmpty())
    }

    @Test fun `空输入返回空结果`() {
        val r = engine.group(emptyList())
        assertTrue(r.clusters.isEmpty())
        assertTrue(r.singles.isEmpty())
    }

    // ---- 探针测试：审计未覆盖的高风险契约 ----

    @Test fun `阈值参数化100米聚簇`() {
        // 0.00072°≈80m：maxDistanceM=100 应并簇（错误实现若固定 50m 会误分 2 簇）
        val e = GpsGrouperEngine(maxDistanceM = 100.0)
        val photos = listOf(
            photo(1, t(0), 23.0000, 113.0),
            photo(2, t(1), 23.00072, 113.0),
        )
        val r = e.group(photos)
        assertEquals(1, r.clusters.size, "maxDistanceM=100m 时相距 80m 应并为一簇")
    }

    @Test fun `簇内距中心62米不离群`() {
        // 3 张锚 23.0000 + 1 张 23.00040(距A≈44m 建边) + 1 张 23.00080(距B≈44m 建边)
        // 中心=(0.00040+0.00080)/5=0.00024，23.00080 距中心 0.00056°≈62m < 75m → 不离群
        val photos = buildList {
            repeat(3) { add(photo(it + 1L, ts(it * 30L), 23.0000, 113.0)) }
            add(photo(4, ts(3 * 30L), 23.00040, 113.0))
            add(photo(5, ts(4 * 30L), 23.00080, 113.0))
        }
        val r = engine.group(photos)
        assertEquals(1, r.clusters.size)
        assertTrue(r.clusters[0].outlierPhotos.isEmpty(), "距中心 62m(<75m)不得误判为离群")
    }

    @Test fun `链式连通同一簇`() {
        // A(0m)-B(44m)-C(89m)：A-B 建边、B-C 建边、A-C 不建(89>50)，经 B 连通 → 1 簇
        val photos = listOf(
            photo(1, t(0), 23.0000, 113.0),
            photo(2, t(1), 23.0004, 113.0),
            photo(3, t(2), 23.0008, 113.0),
        )
        val r = engine.group(photos)
        assertEquals(1, r.clusters.size, "链式(逐跳≤50m)应连通为一簇，而非仅相邻合并")
    }

    @Test fun `跨日簇代表时间为最早完整时间戳`() {
        // 23:58 与次日 00:02 同 GPS、差 4min≤5min → 1 簇，rep=23:58（含完整日期）
        val tLate = LocalDateTime.of(2026, 9, 3, 23, 58).atZone(zone).toInstant().toEpochMilli()
        val tNext = LocalDateTime.of(2026, 9, 4, 0, 2).atZone(zone).toInstant().toEpochMilli()
        val photos = listOf(
            photo(1, tNext, 23.0, 113.0),
            photo(2, tLate, 23.0, 113.0),
        )
        val r = engine.group(photos)
        assertEquals(1, r.clusters.size)
        assertEquals(tLate, r.clusters[0].representativeTimeMs, "跨日簇代表时间=最早 23:58 完整时间戳")
    }
}
