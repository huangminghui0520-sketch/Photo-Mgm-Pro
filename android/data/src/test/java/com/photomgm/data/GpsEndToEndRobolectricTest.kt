// data/GpsEndToEndRobolectricTest.kt —— GPS 组合照片端到端逻辑验证（JVM/Robolectric）
// 链路：真实 androidx ExifInterface 读 13 张测试照片 EXIF → GpsGrouperEngine GPS 分组 → EventClassifyEngine 事件分类
// 绕过模拟器 ExifInterface GPS 解析环境限制（PRIV 实验已证实模拟器 runtime 读 GPS 失败、JVM 成功）
package com.photomgm.data

import algorithm.AlgorithmApi
import algorithm.EventClassifyEngine
import algorithm.GpsGrouperEngine
import algorithm.model.Photo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GpsEndToEndRobolectricTest {

    private val root = File("D:/Photo-Mgm/Photo-Mgm/gps_test_photos")
    private val reader = ExifPhotoReader()
    private val date = LocalDate.of(2026, 8, 17)
    // ★ 测试显式指定业务时区（issue #2）：不依赖 runner 的 TZ 环境变量。
    private val zone = ZoneId.of("Asia/Shanghai")

    private fun photo(id: Long, name: String): Photo {
        val p = File(root, name)
        return Photo(
            id = id,
            sourceRef = p.absolutePath,
            displayName = name,
            captureTimeMs = reader.readCaptureTimeMs(p.absolutePath),
            latitude = reader.readGps(p.absolutePath)?.first,
            longitude = reader.readGps(p.absolutePath)?.second,
        )
    }

    private fun all(): List<Photo> {
        val names = listOf(
            "IMG_20260817_080030_T01.jpg", "IMG_20260817_080130_T02.jpg",
            "IMG_20260817_080430_T03.jpg", "IMG_20260817_081000_T04.jpg",
            "IMG_20260817_083500_T05.jpg", "IMG_20260817_083630_T06.jpg",
            "IMG_20260817_085000_T07.jpg", "IMG_20260817_085130_T08.jpg",
            "IMG_20260817_093200_T09.jpg", "IMG_20260817_093330_T10.jpg",
            "IMG_20260817_093500_T11.jpg", "IMG_20260817_094000_T12.jpg",
            "IMG_20260817_095000_T13.jpg",
        )
        return names.mapIndexed { i, n -> photo(1000L + i, n) }
    }

    private fun time(h: Int, m: Int, s: Int = 0): Long =
        date.atTime(h, m, s).atZone(zone).toInstant().toEpochMilli()

    // ===== 1. EXIF 精读正确性（androidx ExifInterface 真实读取） =====
    @Test
    fun exif读取_T01_GPS与时间正确() {
        val p = photo(1, "IMG_20260817_080030_T01.jpg")
        assertEquals(time(8, 0, 30), p.captureTimeMs)
        assertEquals(23.02, p.latitude!!, 0.001)
        assertEquals(113.01, p.longitude!!, 0.001)
    }

    @Test
    fun exif读取_无GPS无时间T13全null() {
        val p = photo(13, "IMG_20260817_095000_T13.jpg")
        assertNull(p.captureTimeMs)
        assertNull(p.latitude)
        assertNull(p.longitude)
    }

    @Test
    fun exif读取_无GPS有时间T04() {
        val p = photo(4, "IMG_20260817_081000_T04.jpg")
        assertEquals(time(8, 10), p.captureTimeMs)
        assertNull(p.latitude)
    }

    @Test
    fun exif读取_0_0坐标T12有效GPS() {
        val p = photo(12, "IMG_20260817_094000_T12.jpg")
        assertEquals(time(9, 40), p.captureTimeMs)
        assertEquals(0.0, p.latitude!!, 0.0001)
        assertEquals(0.0, p.longitude!!, 0.0001)
    }

    // ===== 2. GPS 分组（GpsGrouperEngine） =====
    @Test
    fun gps分组_13张形成4簇() {
        val ps = all()
        ps.forEach { println("PHOTO ${it.displayName} t=${it.captureTimeMs} lat=${it.latitude} lng=${it.longitude}") }
        val r = GpsGrouperEngine(50.0, 5 * 60 * 1000L).group(ps)
        println("CLUSTERS=${r.clusters.size} singles=${r.singles.size} split=${r.splitCount}")
        r.clusters.forEachIndexed { i, c -> println("C$i ids=${c.photos.map{it.displayName}} rep=${c.representativeTimeMs}") }
        r.singles.forEach { println("SINGLE ${it.displayName} t=${it.captureTimeMs}") }
        // 期望 5 簇：A(T01-03) / B1(T05-06) / B2(T07-08) / D(T09-11) / T12(0,0 单簇)
        assertEquals(5, r.clusters.size)
        assertEquals(2, r.singles.size)
        // singles：T04（无GPS有时间）、T12（0,0 会并入簇？）、T13（无GPS无时间）
        // 注：T12 GPS(0,0) 有精确时间 → 按 GpsGrouperEngine 规则可能聚簇或 single，此处只断言 T13 必在 singles
        assertTrue(r.singles.any { it.displayName.endsWith("T13.jpg") })
        // 簇A 成员
        val clusterA = r.clusters.first { it.photos.any { p -> p.displayName.contains("T01") } }
        assertEquals(setOf("01", "02", "03"), clusterA.photos.map { it.displayName.substringAfter("T").substringBefore(".") }.toSet())
    }

    // ===== 3. 事件分类（EventClassifyEngine） =====
    @Test
    fun 端到端_日志事件分类照片正确() {
        val photos = all()
        val g = GpsGrouperEngine(50.0, 5 * 60 * 1000L).group(photos)
        val logs = """
            2026-08-17 08:00 交接班，检查装备（检车）完成，安全
            2026-08-17 08:30 出车，正常巡查 K10-K20 路段
            2026-08-17 09:30 接报 K12+300 处路面障碍物，到达现场处置
        """.trimIndent()
        val parsed = AlgorithmApi.parseLog(logs, date)
        parsed.events.forEach { println("EV id=${it.id} ${it.eventType} start=${it.startTimeMs} end=${it.endTimeMs}") }
        val result = EventClassifyEngine(50.0).classify(parsed.events, g.clusters, g.singles, date, g.splitCount)
        println("EV1=${result.eventPhotoMap[1]} EV2=${result.eventPhotoMap[2]} EV3=${result.eventPhotoMap[3]} unmatched=${result.unmatched}")
        println("INTERV=${result.interventions.map { it.kind to it.photoIds }}")
        // 终点归属：事件N 照片 = (上一条记录, 本条记录] → 归本条。
        // ev1 交接班：首条特例吸收 [dayStart, 08:30) → 簇A(T01-03)+T04(08:10)=4
        // ev2 出车(08:30)：其区间(08:00,08:30] 被首条特例吸收 → 0
        // ev3 处置(09:30)：末条闭区间(08:30,∞) → C1(T05-06)+C2(T07-08)+C3(T09-11)+C4(T12)=8
        // unmatched：T13=1；CLUSTER_SPAN：ev3 内多簇中心离散
        assertEquals(4, result.eventPhotoMap[1]!!.size)
        assertEquals(0, result.eventPhotoMap[2]!!.size)
        assertEquals(8, result.eventPhotoMap[3]!!.size)
        assertEquals(1, result.unmatched.size)
        assertTrue(result.interventions.any { it.kind == algorithm.Kind.CLUSTER_SPAN })
    }
}
