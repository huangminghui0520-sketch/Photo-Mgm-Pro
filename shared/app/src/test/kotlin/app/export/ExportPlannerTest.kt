// ExportPlannerTest —— 导出计划的命名与清单规则（T09-C3）
//
// 这些规则此前在两端各写一份（桌面 `PcFolderExporter.buildPlan`、安卓 `ZipExporter.export`），
// 靠注释互相声明一致。这里逐条钉住，任何一端将来想"顺手优化"都会先红一次。
package app.export

import algorithm.model.LogEvent
import algorithm.model.Photo
import app.api.ExportPlan
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExportPlannerTest {

    private val zone: ZoneId = algorithm.AppZone.ID

    private fun at(hour: Int, minute: Int): Long =
        LocalDateTime.of(2026, 8, 14, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun event(id: Int, hour: Int, type: String, location: String = "K$id", description: String = "$type 描述") =
        LogEvent(
            id = id,
            startTimeMs = at(hour, 0),
            endTimeMs = at(hour, 0),
            timePoints = listOf(at(hour, 0)),
            location = location,
            eventType = type,
            description = description,
        )

    private fun photo(id: Long, name: String, dir: String = "/photos") = Photo(
        id = id,
        sourceRef = "$dir/$name",
        displayName = name,
        captureTimeMs = at(10, 15),
        latitude = 30.25,
        longitude = 120.5,
    )

    private val e1 = event(1, 8, "交接班")
    private val e2 = event(2, 10, "护栏损坏", location = "K138+700")
    private val e3 = event(3, 12, "标志牌倾斜", location = "K138+800")

    private fun build(
        events: List<LogEvent> = listOf(e1, e2, e3),
        map: Map<Int, List<Long>>,
        unmatched: List<Long> = emptyList(),
        photos: List<Photo>,
        template: Int = 0,
        includeLedger: Boolean = true,
    ) = ExportPlanner.build(
        events = events,
        eventPhotoMap = map,
        unmatched = unmatched,
        photos = photos,
        namingTemplate = template,
        includeLedger = includeLedger,
        zone = zone,
    )

    @Test
    fun `文件夹按结束时间编号_事件无照片不建文件夹`() {
        val a = photo(10, "IMG_A.jpg")
        val b = photo(11, "IMG_B.jpg")
        val plan = build(map = mapOf(2 to listOf(10L), 3 to listOf(11L)), photos = listOf(a, b))

        assertEquals("02_护栏损坏", plan.folderNames[2], "序号按时间位次，08:00 的交接班占了 01")
        assertEquals("03_标志牌倾斜", plan.folderNames[3])
        assertEquals(
            listOf("02_护栏损坏/IMG_A.jpg", "03_标志牌倾斜/IMG_B.jpg"),
            plan.files.map { it.relativePath },
        )
        assertTrue(plan.files.none { it.relativePath.contains("交接班") }, "无照片的事件不建文件夹")
    }

    @Test
    fun `同一照片被两个事件引用时各存一份`() {
        val a = photo(10, "IMG_A.jpg")
        val plan = build(map = mapOf(2 to listOf(10L), 3 to listOf(10L)), photos = listOf(a))

        assertEquals(2, plan.totalTargets, "禁止「全导出只存一份」：两个文件夹各一份")
        assertEquals(listOf("/photos/IMG_A.jpg"), plan.files.map { it.sourceRef }.distinct())
        assertEquals(2, plan.files.map { it.relativePath }.distinct().size)
    }

    @Test
    fun `重名从 _2 开始且插在扩展名前`() {
        val a = photo(10, "IMG_001.jpg")
        val b = photo(11, "IMG_001.jpg", dir = "/other")
        val plan = build(map = mapOf(2 to listOf(10L, 11L)), photos = listOf(a, b))

        assertEquals(
            listOf("02_护栏损坏/IMG_001.jpg", "02_护栏损坏/IMG_001_2.jpg"),
            plan.files.map { it.relativePath },
        )
        assertEquals(1, plan.renames.size)
        assertEquals("IMG_001.jpg", plan.renames.single().originalName)
        assertEquals("02_护栏损坏/IMG_001_2.jpg", plan.renames.single().toRelativePath)
    }

    @Test
    fun `无扩展名的重名不拼尾随点`() {
        assertEquals("IMG_001_2", ExportPlanner.suffixed("IMG_001", 2))
        assertEquals("IMG_001_2.jpg", ExportPlanner.suffixed("IMG_001.jpg", 2))
        // 点号在首位的隐藏文件不算"扩展名分隔符"，后缀整体追加在末尾
        assertEquals(".gitignore_2", ExportPlanner.suffixed(".gitignore", 2))
    }

    @Test
    fun `同一事件内同一张照片列两次只写一份`() {
        val a = photo(10, "IMG_A.jpg")
        val plan = build(map = mapOf(2 to listOf(10L, 10L)), photos = listOf(a))
        assertEquals(1, plan.totalTargets)
    }

    @Test
    fun `未匹配照片进99_未分类`() {
        val a = photo(10, "IMG_A.jpg")
        val plan = build(map = emptyMap(), unmatched = listOf(10L), photos = listOf(a))

        assertEquals(ExportPlan.UNMATCHED_FOLDER, plan.unmatchedFolder)
        assertEquals(listOf("99_未分类/IMG_A.jpg"), plan.files.map { it.relativePath })
    }

    @Test
    fun `没有 sourceRef 的照片被跳过而不是写进计划`() {
        val broken = Photo(id = 10, sourceRef = "", displayName = "IMG_A.jpg", captureTimeMs = null, latitude = null, longitude = null)
        val plan = build(map = mapOf(2 to listOf(10L)), photos = listOf(broken))
        assertTrue(plan.files.isEmpty())
        assertFalse(plan.hasTargets)
    }

    @Test
    fun `模板 0 下同类型事件共用一个文件夹`() {
        val e2b = event(4, 13, "护栏损坏", location = "K139+000")
        val a = photo(10, "IMG_A.jpg")
        val b = photo(11, "IMG_B.jpg")
        val plan = build(
            events = listOf(e1, e2, e3, e2b),
            map = mapOf(2 to listOf(10L), 4 to listOf(11L)),
            photos = listOf(a, b),
        )

        assertEquals("02_护栏损坏", plan.folderNames[2])
        assertEquals("02_护栏损坏", plan.folderNames[4], "同类型事件合并到同一文件夹，序号只取首次出现")
        assertEquals(
            listOf("02_护栏损坏/IMG_A.jpg", "02_护栏损坏/IMG_B.jpg"),
            plan.files.map { it.relativePath },
        )
    }

    @Test
    fun `台账原始行与展示行同序同数且展示行带文件夹前缀`() {
        val a = photo(10, "IMG_A.jpg")
        val plan = build(map = mapOf(2 to listOf(10L)), photos = listOf(a))

        assertEquals(3, plan.ledger.size, "每个事件一行台账")
        assertEquals(plan.ledger.size, plan.ledgerPreview.size)
        val row = plan.ledgerPreview.first { it.location == "K138+700" }
        assertEquals("02_护栏损坏/IMG_A.jpg", row.photo1Label)
    }

    @Test
    fun `不生成台账时两类行都为空`() {
        val a = photo(10, "IMG_A.jpg")
        val plan = build(map = mapOf(2 to listOf(10L)), photos = listOf(a), includeLedger = false)
        assertFalse(plan.includeLedger)
        assertTrue(plan.ledger.isEmpty())
        assertTrue(plan.ledgerPreview.isEmpty())
        assertEquals(1, plan.totalTargets, "不生成台账不影响照片清单")
    }
}
