// domain/ledger/LedgerProjectionTest.kt —— 台账行投影（T07）
//
// 台账「行」的生成早就在共享层，这一层补的是两端各自加的展示加工。
// 用例重点不是「台账内容对不对」（那是 LedgerEngine 的测试），而是：
//   · 照片列标签的前缀必须与 ZIP 文件夹名同源；
//   · ref 反查不到时的退化规则（只取最后一段，不吐整条路径）；
//   · 空 ref / 空 folderName 两个边界。
package domain.ledger

import algorithm.AppZone
import algorithm.model.LogEvent
import algorithm.model.Photo
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LedgerProjectionTest {

    private fun photo(id: Long, name: String, time: Long? = null) =
        Photo(id = id, sourceRef = "/photos/$name", displayName = name, captureTimeMs = time, latitude = null, longitude = null)

    private fun event(
        id: Int = 1,
        at: LocalDateTime = LocalDateTime.of(2026, 8, 14, 10, 15),
        location: String = "K1+000",
    ) = LogEvent(
        id = id,
        startTimeMs = at.atZone(AppZone.ID).toInstant().toEpochMilli(),
        endTimeMs = at.atZone(AppZone.ID).toInstant().toEpochMilli(),
        timePoints = listOf(at.atZone(AppZone.ID).toInstant().toEpochMilli()),
        location = location,
        eventType = "巡查",
        description = "巡查至$location，检查交安设施",
    )

    @Test
    fun `照片列标签用文件夹名做前缀_与ZIP路径同源`() {
        val e = event()
        val p1 = photo(1L, "IMG_A.jpg", 1_000L)
        val p2 = photo(2L, "IMG_B.jpg", 2_000L)
        val rows = LedgerProjection.buildRows(
            events = listOf(e),
            eventPhotoMap = mapOf(1 to listOf(1L, 2L)),
            photos = listOf(p1, p2),
        )
        assertEquals(1, rows.size)
        val r = rows.single()
        assertTrue(r.folderName.isNotBlank(), "文件夹名来自共享的 numberedFolderNames，不该为空")
        assertEquals("${r.folderName}/IMG_A.jpg", r.photo1Label, "台账照片列前缀必须等于 ZIP 内路径前缀")
        assertEquals("${r.folderName}/IMG_B.jpg", r.photo2Label)
    }

    @Test
    fun `无照片的事件两列都是null`() {
        val rows = LedgerProjection.buildRows(
            events = listOf(event()),
            eventPhotoMap = mapOf(1 to emptyList()),
            photos = emptyList(),
        )
        assertNull(rows.single().photo1Label)
        assertNull(rows.single().photo2Label)
    }

    @Test
    fun `反查不到照片时退化为ref最后一段`() {
        val byRef = emptyMap<String, Photo>()
        assertEquals("01_巡查/IMG_X.jpg", LedgerProjection.labelOf("01_巡查", "/photos/IMG_X.jpg", byRef))
    }

    @Test
    fun `空ref返回null而不是空串`() {
        assertEquals(null, LedgerProjection.labelOf("01_巡查", null, emptyMap()))
        assertEquals(null, LedgerProjection.labelOf("01_巡查", "   ", emptyMap()))
    }

    @Test
    fun `文件夹名为空时只给文件名`() {
        val byRef = mapOf("/photos/IMG_X.jpg" to photo(9L, "IMG_X.jpg"))
        assertEquals("IMG_X.jpg", LedgerProjection.labelOf("", "/photos/IMG_X.jpg", byRef))
    }

    @Test
    fun `重复sourceRef取第一个`() {
        // 现实中 sourceRef 应唯一；万一重复，必须与 PC 的 firstOrNull 语义一致，
        // 否则台账照片名会变成「最后一张」的 displayName，差异极难定位。
        val first = photo(1L, "FIRST.jpg")
        val second = photo(2L, "SECOND.jpg").copy(sourceRef = "/photos/FIRST.jpg")
        val rows = LedgerProjection.buildRows(
            events = listOf(event()),
            eventPhotoMap = mapOf(1 to listOf(1L)),
            photos = listOf(first, second),
        )
        assertEquals("${rows.single().folderName}/FIRST.jpg", rows.single().photo1Label)
    }

    @Test
    fun `多事件按结束时间排序_文件夹序号递增`() {
        // 模板 1 才按日志内容分文件夹；模板 0 按事件类型，同类型会合并成同一个文件夹。
        val e1 = event(1, LocalDateTime.of(2026, 8, 14, 12, 0), "K2+200")
        val e2 = event(2, LocalDateTime.of(2026, 8, 14, 8, 0), "K1+100")
        val rows = LedgerProjection.buildRows(
            events = listOf(e1, e2),
            eventPhotoMap = mapOf(1 to emptyList(), 2 to emptyList()),
            photos = emptyList(),
            namingTemplate = 1,
        )
        assertEquals(2, rows.size)
        assertTrue(rows[0].dateTime.endsWith("08:00"), "台账行顺序 = 结束时间升序，实际首行 ${rows[0].dateTime}")
        val f1 = rows.first { it.location == "K2+200" }.folderName
        val f2 = rows.first { it.location == "K1+100" }.folderName
        assertTrue(f2.startsWith("01_"), "8:00 的事件应排在序号 1，实际：$f2")
        assertTrue(f1.startsWith("02_"), "12:00 的事件应排在序号 2，实际：$f1")
    }

    @Test
    fun `模板零下同类型事件合并到同一文件夹`() {
        // 这是「按事件类型归档」的设计，不是 bug：同一天两条「巡查」共用一个 01_巡查 文件夹。
        val rows = LedgerProjection.buildRows(
            events = listOf(event(1, LocalDateTime.of(2026, 8, 14, 8, 0), "K1+100"),
                event(2, LocalDateTime.of(2026, 8, 14, 12, 0), "K2+200")),
            eventPhotoMap = mapOf(1 to emptyList(), 2 to emptyList()),
            photos = emptyList(),
            namingTemplate = 0,
        )
        assertEquals(rows[0].folderName, rows[1].folderName)
    }

    @Test
    fun `一次投影同时给出原始行与展示行_同序同数`() {
        // T09：写 xlsx 要原始行（带 photo1Ref），UI 预览要展示行（带标签）。
        // 两者必须来自**同一次** buildLedger，否则「预览的台账」与「导出的台账」可能不一致。
        val e = event()
        val p1 = photo(1L, "IMG_A.jpg", 1_000L)
        val result = LedgerProjection.project(
            events = listOf(e),
            eventPhotoMap = mapOf(1 to listOf(1L)),
            photos = listOf(p1),
        )

        assertEquals(1, result.rows.size)
        assertEquals(result.rows.size, result.projected.size)
        assertEquals("/photos/IMG_A.jpg", result.rows.single().photo1Ref, "原始行给写盘用，必须带 ref")
        assertEquals(
            "${result.rows.single().folderName}/IMG_A.jpg",
            result.projected.single().photo1Label,
            "展示行给 UI 用，必须带文件夹前缀",
        )
    }
}
