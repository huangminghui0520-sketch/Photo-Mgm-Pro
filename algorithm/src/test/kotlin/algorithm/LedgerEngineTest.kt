// algorithm/LedgerEngineTest.kt —— §7 台账模块测试（导出Excel 2.0 方案）
// 契约：每事件一行 / 日期列 yyyy-MM-dd HH:mm（开始时间）/ 结束时间列 yyyy-MM-dd HH:mm（单时间点留空）/ 地点桩号优先 / 日志内容 /
//       照片1=第一张(最早)、照片2=最后一张(最晚)——可用人工标记覆盖，未标记侧回退默认；
//       无拍摄时间的照片不参与最早/最晚选择 / 按 endTime 排序
package algorithm

import algorithm.model.LogEvent
import algorithm.model.Photo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId

class LedgerEngineTest {
    private val engine = LedgerEngine()
    private val zone = ZoneId.of("Asia/Shanghai")
    private val date = LocalDate.of(2026, 9, 3)
    private fun at(h: Int, m: Int) = date.atTime(h, m).atZone(zone).toInstant().toEpochMilli()
    private fun atDate(d: Int, h: Int, m: Int) =
        LocalDate.of(2026, 9, d).atTime(h, m).atZone(zone).toInstant().toEpochMilli()
    private fun ev(id: Int, start: Long?, end: Long?, loc: String = "K138+700M", type: String = "事件$id") =
        LogEvent(id, start, end, listOfNotNull(start, end), loc, type, "描述$id")
    private fun photo(id: Long, t: Long?, src: String = "img$id.jpg") = Photo(id, src, src, t, 23.0, 113.0)
    private fun byId(photos: List<Photo>) = { id: Long -> photos.find { it.id == id } }

    @Test fun `日期列为yyyy-MM-dd HH_mm`() {
        val e = ev(1, at(10, 5), at(10, 30))
        val rows = engine.buildLedger(listOf(e), emptyMap(), byId(emptyList()))
        assertEquals("2026-09-03 10:05", rows[0].dateTime)
        assertEquals("2026-09-03 10:30", rows[0].endTime, "结束时间列=事件结束时间")
    }

    @Test fun `无开始时间日期列为空`() {
        val e = ev(1, null, null)
        val rows = engine.buildLedger(listOf(e), emptyMap(), byId(emptyList()))
        assertEquals("", rows[0].dateTime)
        assertEquals("", rows[0].endTime, "无结束时间时结束时间列为空")
    }

    @Test fun `单时间点事件结束时间为空`() {
        // 单时间点事件 endTimeMs == startTimeMs：结束时间列留空，避免 "08:30 ~ 08:30" 冗余
        val e = ev(1, at(10, 5), at(10, 5))
        val rows = engine.buildLedger(listOf(e), emptyMap(), byId(emptyList()))
        assertEquals("2026-09-03 10:05", rows[0].dateTime)
        assertEquals("", rows[0].endTime)
    }

    @Test fun `照片默认取最早和最晚各一张`() {
        val e = ev(1, at(10, 0), at(10, 30))
        val photos = listOf(photo(1, at(10, 20)), photo(2, at(10, 0)), photo(3, at(10, 10)))
        val rows = engine.buildLedger(listOf(e), mapOf(1 to listOf(1L, 2L, 3L)), byId(photos))
        assertEquals("img2.jpg", rows[0].photo1Ref, "照片1=第一张（最早）")
        assertEquals("img1.jpg", rows[0].photo2Ref, "照片2=最后一张（最晚）")
    }

    @Test fun `单张照片时照片1照片2相同`() {
        val e = ev(1, at(10, 0), at(10, 30))
        val photos = listOf(photo(7, at(10, 10)))
        val rows = engine.buildLedger(listOf(e), mapOf(1 to listOf(7L)), byId(photos))
        assertEquals("img7.jpg", rows[0].photo1Ref)
        assertEquals("img7.jpg", rows[0].photo2Ref)
    }

    @Test fun `地点与日志内容取自事件`() {
        val e = ev(1, at(10, 0), at(10, 30), loc = "K138+700M收费站")
        val rows = engine.buildLedger(listOf(e), emptyMap(), byId(emptyList()))
        assertEquals("K138+700M收费站", rows[0].location)
        assertEquals("描述1", rows[0].description)
    }

    @Test fun `按endTime升序输出`() {
        val e1 = ev(1, at(9, 0), at(10, 0))
        val e2 = ev(2, at(10, 0), at(11, 0))
        val e3 = ev(3, at(8, 0), at(9, 0))
        val rows = engine.buildLedger(listOf(e1, e2, e3), emptyMap(), byId(emptyList()))
        assertEquals("2026-09-03 08:00", rows[0].dateTime)
        assertEquals("2026-09-03 09:00", rows[1].dateTime)
        assertEquals("2026-09-03 10:00", rows[2].dateTime)
    }

    @Test fun `无照片事件照片列为空`() {
        val e = ev(1, at(10, 0), at(10, 30))
        val rows = engine.buildLedger(listOf(e), mapOf(1 to listOf(99L)), byId(emptyList()))
        assertNull(rows[0].photo1Ref)
        assertNull(rows[0].photo2Ref)
    }

    @Test fun `跨日事件日期按实际开始日期`() {
        // 09-03 23:30 开始，09-04 01:00 结束 → 日期列显示 09-03 23:30，结束时间显示 09-04 01:00
        val e = ev(1, atDate(3, 23, 30), atDate(4, 1, 0))
        val rows = engine.buildLedger(listOf(e), emptyMap(), byId(emptyList()))
        assertEquals("2026-09-03 23:30", rows[0].dateTime)
        assertEquals("2026-09-04 01:00", rows[0].endTime, "跨日事件结束时间按实际日期+1")
    }

    @Test fun `folderName与ZIP文件夹命名一致`() {
        // 回归 P1-3/P2-4：namingTemplate=0 → 事件类型；=1 → 巡查日志内容；均带序号前缀（与 ZipExporter 同规则）
        val e = ev(1, at(10, 5), at(10, 30), type = "交通事故处置")
        val rows0 = engine.buildLedger(listOf(e), emptyMap(), byId(emptyList()), namingTemplate = 0)
        assertEquals("01_交通事故处置", rows0[0].folderName)
        val rows1 = engine.buildLedger(listOf(e), emptyMap(), byId(emptyList()), namingTemplate = 1)
        assertEquals("01_描述1", rows1[0].folderName)
    }

    // ──────────────────────────────────────────────────────────────
    // 人工台账标记（2026-10-07 接通）
    // 语义：标记优先；某一侧为 null 表示该侧回退默认（最早/最晚）
    // ──────────────────────────────────────────────────────────────

    @Test fun `人工标记两侧都生效`() {
        val e = ev(1, at(10, 0), at(10, 30))
        // 时间顺序：2 最早、1 中间、3 最晚
        val photos = listOf(photo(1, at(10, 10)), photo(2, at(10, 0)), photo(3, at(10, 20)))
        val map = mapOf(1 to listOf(1L, 2L, 3L))
        // 人工指定反过来：照片1=3（最晚）、照片2=2（最早）
        val rows = engine.buildLedger(listOf(e), map, byId(photos), mapOf(1 to (3L to 2L)))
        assertEquals("img3.jpg", rows[0].photo1Ref, "照片1 应取人工标记")
        assertEquals("img2.jpg", rows[0].photo2Ref, "照片2 应取人工标记")
    }

    @Test fun `仅标记照片1时照片2回退默认最晚`() {
        val e = ev(1, at(10, 0), at(10, 30))
        val photos = listOf(photo(1, at(10, 10)), photo(2, at(10, 0)), photo(3, at(10, 20)))
        val map = mapOf(1 to listOf(1L, 2L, 3L))
        // 只标记照片1=1；照片2 未标记(null) → 应为默认最晚(3)
        val rows = engine.buildLedger(listOf(e), map, byId(photos), mapOf(1 to (1L to null)))
        assertEquals("img1.jpg", rows[0].photo1Ref, "照片1 用标记")
        assertEquals("img3.jpg", rows[0].photo2Ref, "照片2 未标记 → 回退默认最晚")
    }

    @Test fun `仅标记照片2时照片1回退默认最早`() {
        val e = ev(1, at(10, 0), at(10, 30))
        val photos = listOf(photo(1, at(10, 10)), photo(2, at(10, 0)), photo(3, at(10, 20)))
        val map = mapOf(1 to listOf(1L, 2L, 3L))
        val rows = engine.buildLedger(listOf(e), map, byId(photos), mapOf(1 to (null to 1L)))
        assertEquals("img2.jpg", rows[0].photo1Ref, "照片1 未标记 → 回退默认最早")
        assertEquals("img1.jpg", rows[0].photo2Ref, "照片2 用标记")
    }

    @Test fun `标记的id不属于该事件时忽略并回退默认`() {
        val e = ev(1, at(10, 0), at(10, 30))
        val photos = listOf(photo(1, at(10, 10)), photo(2, at(10, 0)), photo(3, at(10, 20)))
        val map = mapOf(1 to listOf(1L, 2L, 3L))
        // 标记了一个不在本事件内的 id（如已被移动到别处）→ 忽略，用默认
        val rows = engine.buildLedger(listOf(e), map, byId(photos), mapOf(1 to (99L to 98L)))
        assertEquals("img2.jpg", rows[0].photo1Ref, "失效标记应回退默认最早")
        assertEquals("img3.jpg", rows[0].photo2Ref, "失效标记应回退默认最晚")
    }

    @Test fun `未提供标记时行为与自动一致`() {
        val e = ev(1, at(10, 0), at(10, 30))
        val photos = listOf(photo(1, at(10, 10)), photo(2, at(10, 0)))
        val map = mapOf(1 to listOf(1L, 2L))
        val rows = engine.buildLedger(listOf(e), map, byId(photos))   // 用默认参数
        assertEquals("img2.jpg", rows[0].photo1Ref)
        assertEquals("img1.jpg", rows[0].photo2Ref)
    }

    // ──────────────────────────────────────────────────────────────
    // 拍摄时间为 null 的照片（2026-10-07 修订）
    // 语义：时间未知无法定位时序，不参与"最早/最晚"；全部无时间时才退回列表顺序
    // ──────────────────────────────────────────────────────────────

    @Test fun `无拍摄时间的照片不会被当成最晚`() {
        val e = ev(1, at(10, 0), at(10, 30))
        // id=9 无拍摄时间，且在列表最后；若按旧实现(Long.MAX_VALUE)它会被当成"最晚"
        val photos = listOf(photo(1, at(10, 10)), photo(2, at(10, 0)), photo(9, null))
        val rows = engine.buildLedger(listOf(e), mapOf(1 to listOf(1L, 2L, 9L)), byId(photos))
        assertEquals("img2.jpg", rows[0].photo1Ref, "照片1 = 有时间里最早的")
        assertEquals("img1.jpg", rows[0].photo2Ref, "照片2 = 有时间里最晚的，不应是无时间的 id=9")
    }

    @Test fun `全部照片都无拍摄时间时退回列表顺序`() {
        val e = ev(1, at(10, 0), at(10, 30))
        val photos = listOf(photo(5, null), photo(6, null), photo(7, null))
        val rows = engine.buildLedger(listOf(e), mapOf(1 to listOf(5L, 6L, 7L)), byId(photos))
        assertEquals("img5.jpg", rows[0].photo1Ref, "全无时间 → 列表首张")
        assertEquals("img7.jpg", rows[0].photo2Ref, "全无时间 → 列表末张")
    }
}
