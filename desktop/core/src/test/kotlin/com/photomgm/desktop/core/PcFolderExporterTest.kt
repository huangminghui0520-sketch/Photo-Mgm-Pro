// 导出器单测：逐条对应方案 §4.5 契约（①产物结构 ②一照多事件 ③重名 ④日志 ⑤续传 ⑥长路径）
package com.photomgm.desktop.core

import algorithm.AppZone
import algorithm.model.LogEvent
import algorithm.model.Photo
import com.photomgm.desktop.core.api.ExportProgress
import com.photomgm.desktop.core.api.ExportResult
import com.photomgm.desktop.core.api.ResumeVerifyMode
import com.photomgm.desktop.core.export.ExportBlockedException
import com.photomgm.desktop.core.export.PcFolderExporter
import com.photomgm.desktop.core.infra.Json
import com.photomgm.desktop.core.infra.JsonValue
import com.photomgm.desktop.core.infra.asObj
import com.photomgm.desktop.core.infra.longOrNull
import com.photomgm.desktop.core.infra.objOrNull
import com.photomgm.desktop.core.infra.strOrNull
import com.photomgm.desktop.core.platform.PcImageEmbedder
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PcFolderExporterTest {

    @TempDir
    lateinit var tmp: Path

    private val zone = AppZone.ID
    private val fixedClock: Clock =
        Clock.fixed(Instant.parse("2026-08-14T02:15:00Z"), zone) // 北京时间 10:15:00

    private val exporter = PcFolderExporter(
        zone = zone,
        imageEmbedder = { ref -> ref?.let { PcImageEmbedder().embed(it) } },
        clock = fixedClock,
    )

    private fun jpeg(name: String, w: Int = 320, h: Int = 240): Path {
        val p = tmp.resolve("photos/$name")
        TestImages.writeJpeg(p, w, h)
        return p
    }

    private fun input(
        out: Path,
        events: List<LogEvent>,
        map: Map<Int, List<Long>>,
        photos: Map<Long, Photo>,
        unmatched: List<Long> = emptyList(),
        template: Int = 0,
        includeLedger: Boolean = true,
        onProgress: (ExportProgress) -> Unit = {},
        isCancelled: () -> Boolean = { false },
        verifyMode: ResumeVerifyMode = ResumeVerifyMode.SIZE_AND_MTIME,
    ) = PcFolderExporter.Input(
        outputParent = out,
        events = events,
        eventPhotoIds = map,
        unmatchedIds = unmatched,
        photoById = { photos[it] },
        namingTemplate = template,
        includeLedger = includeLedger,
        verifyMode = verifyMode,
        onProgress = onProgress,
        isCancelled = isCancelled,
    )

    // ── ① 产物结构 ────────────────────────────────────────────────────────

    @Test
    fun `产物结构符合契约`() {
        val p1 = jpeg("IMG_1.jpg")
        val p2 = jpeg("IMG_2.jpg")
        val p3 = jpeg("IMG_3.jpg")
        val photos = mapOf(
            1L to Fixtures.photo(1, p1),
            2L to Fixtures.photo(2, p2),
            3L to Fixtures.photo(3, p3),
        )
        val events = listOf(
            Fixtures.event(1, 10, 0, "护栏损坏", "K138+700", "发现护栏损坏"),
            Fixtures.event(2, 12, 0, "标志牌倾斜", "K138+800", "发现标志牌倾斜"),
        )
        val out = tmp.resolve("out")
        val r = exporter.export(input(out, events, mapOf(1 to listOf(1L, 2L), 2 to listOf(3L)), photos))

        assertTrue(r.exportDir.name.startsWith(PcFolderExporter.EXPORT_DIR_PREFIX))
        assertTrue(Regex("""PhotoMgm_export_\d{8}_\d{6}""").matches(r.exportDir.name), "目录名时间戳不含冒号")
        assertTrue(Files.isRegularFile(r.exportDir.resolve("ledger.xlsx")))
        assertTrue(Files.isRegularFile(r.exportDir.resolve("export.log")))
        assertTrue(Files.isRegularFile(r.exportDir.resolve("export-manifest.json")))

        val dirs = Files.list(r.exportDir).use { s ->
            s.filter { Files.isDirectory(it) }.map { it.fileName.toString() }.sorted().toList()
        }
        assertEquals(listOf("01_护栏损坏", "02_标志牌倾斜"), dirs)
        assertEquals(2, Files.list(r.exportDir.resolve("01_护栏损坏")).use { it.count() }.toInt())
        assertEquals(1, Files.list(r.exportDir.resolve("02_标志牌倾斜")).use { it.count() }.toInt())

        assertEquals(3, r.totalTargets)
        assertEquals(0, r.skipped)
        assertEquals(0, r.failed)
        assertEquals(3, r.copied, "3 张照片（台账不计入 copied）")
    }

    @Test
    fun `未匹配照片落入 99_未分类`() {
        val p1 = jpeg("IMG_1.jpg")
        val p2 = jpeg("IMG_9.jpg")
        val photos = mapOf(1L to Fixtures.photo(1, p1), 9L to Fixtures.photo(9, p2))
        val events = listOf(Fixtures.event(1, 10, 0, "护栏损坏", "K1", "x"))
        val out = tmp.resolve("out")
        val r = exporter.export(input(out, events, mapOf(1 to listOf(1L)), photos, unmatched = listOf(9L)))
        assertTrue(Files.isDirectory(r.exportDir.resolve("99_未分类")))
        assertEquals(1, Files.list(r.exportDir.resolve("99_未分类")).use { it.count() }.toInt())
    }

    // ── ② 一照多事件各存一份 ──────────────────────────────────────────────

    @Test
    fun `同一张照片被两个事件引用时各存一份`() {
        val p1 = jpeg("IMG_1.jpg")
        val photos = mapOf(1L to Fixtures.photo(1, p1))
        val events = listOf(
            Fixtures.event(1, 10, 0, "护栏损坏", "K1", "x"),
            Fixtures.event(2, 12, 0, "标志牌倾斜", "K2", "y"),
        )
        val out = tmp.resolve("out")
        val r = exporter.export(input(out, events, mapOf(1 to listOf(1L), 2 to listOf(1L)), photos))

        assertEquals(1, Files.list(r.exportDir.resolve("01_护栏损坏")).use { it.count() }.toInt())
        assertEquals(1, Files.list(r.exportDir.resolve("02_标志牌倾斜")).use { it.count() }.toInt())
    }

    // ── ③ 重名 ────────────────────────────────────────────────────────────

    @Test
    fun `同文件夹内重名加序号且无扩展名不产生尾随点`() {
        val a = jpeg("x1.jpg")
        val b = jpeg("x2.jpg")
        val c = jpeg("x3.jpg")
        val d = jpeg("x4.jpg")
        val photos = mapOf(
            1L to Fixtures.photo(1, a, displayName = "IMG.jpg"),
            2L to Fixtures.photo(2, b, displayName = "IMG.jpg"),
            3L to Fixtures.photo(3, c, displayName = "NOEXT"),
            4L to Fixtures.photo(4, d, displayName = "NOEXT"),
        )
        val events = listOf(Fixtures.event(1, 10, 0, "护栏损坏", "K1", "x"))
        val out = tmp.resolve("out")
        val r = exporter.export(input(out, events, mapOf(1 to listOf(1L, 2L, 3L, 4L)), photos))

        val names = Files.list(r.exportDir.resolve("01_护栏损坏")).use { s ->
            s.map { it.fileName.toString() }.sorted().toList()
        }
        assertEquals(listOf("IMG.jpg", "IMG_2.jpg", "NOEXT", "NOEXT_2"), names)
        assertTrue(names.none { it.endsWith(".") }, "不得出现尾随点文件名（Windows 会报文件名无效）")
    }

    // ── ④ export.log ──────────────────────────────────────────────────────

    @Test
    fun `export_log 是合法 JSONL 且末行含 summary`() {
        val p1 = jpeg("IMG_1.jpg")
        val photos = mapOf(1L to Fixtures.photo(1, p1))
        val events = listOf(Fixtures.event(1, 10, 0, "护栏损坏", "K1", "发现护栏损坏"))
        val out = tmp.resolve("out")
        val r = exporter.export(input(out, events, mapOf(1 to listOf(1L)), photos))

        val lines = Files.readAllLines(r.logFile, Charsets.UTF_8).filter { it.isNotBlank() }
        assertTrue(lines.size >= 3, "至少 export_start + copy + export_end")
        val parsed: List<JsonValue.Obj> = lines.map {
            val v = Json.parse(it)
            assertNotNull(v, "每行都必须是合法 JSON：$it")
            requireNotNull(v.asObj()) { "每行必须是 JSON 对象：$it" }
        }
        assertTrue(parsed.any { it.strOrNull("event") == "export_start" }, "实际日志：\n" + lines.joinToString("\n"))
        assertTrue(parsed.any { it.strOrNull("event") == "copy" }, "实际日志：\n" + lines.joinToString("\n"))
        val end = assertNotNull(parsed.lastOrNull { it.strOrNull("event") == "export_end" })
        val summary = assertNotNull(end.objOrNull("summary"))
        assertEquals(1, summary.longOrNull("total")?.toInt())
        assertEquals(0, summary.longOrNull("failed")?.toInt())
        // 编码：无 BOM
        val head = Files.readAllBytes(r.logFile).take(3)
        assertTrue(!(head.size == 3 && head[0] == 0xEF.toByte()), "export.log 不得带 BOM")
    }

    // ── ⑤ 续传 ────────────────────────────────────────────────────────────

    @Test
    fun `同一批数据重复导出命中续传`() {
        val p1 = jpeg("IMG_1.jpg")
        val p2 = jpeg("IMG_2.jpg")
        val photos = mapOf(1L to Fixtures.photo(1, p1), 2L to Fixtures.photo(2, p2))
        val events = listOf(Fixtures.event(1, 10, 0, "护栏损坏", "K1", "x"))
        val out = tmp.resolve("out")

        val first = exporter.export(input(out, events, mapOf(1 to listOf(1L, 2L)), photos))
        assertEquals(2, first.copied, "本次复制 2 张照片（台账不计入 copied）")
        assertTrue(!first.resumed)

        val second = exporter.export(input(out, events, mapOf(1 to listOf(1L, 2L)), photos))
        assertTrue(second.resumed, "同批产物完整时应命中续传")
        assertEquals(first.exportDir, second.exportDir, "续传不应新建目录")
        assertEquals(2, second.skipped)
        assertEquals(0, second.copied)
        assertEquals(2, second.totalTargets - second.copied - second.failed, "跳过数 == 未复制数")
    }

    @Test
    fun `产物被删后必须重新复制`() {
        val p1 = jpeg("IMG_1.jpg")
        val p2 = jpeg("IMG_2.jpg")
        val photos = mapOf(1L to Fixtures.photo(1, p1), 2L to Fixtures.photo(2, p2))
        val events = listOf(Fixtures.event(1, 10, 0, "护栏损坏", "K1", "x"))
        val out = tmp.resolve("out")

        val first = exporter.export(input(out, events, mapOf(1 to listOf(1L, 2L)), photos))
        val victim = Files.list(first.exportDir.resolve("01_护栏损坏")).use { s -> s.findFirst().get() }
        Files.delete(victim)

        val second = exporter.export(input(out, events, mapOf(1 to listOf(1L, 2L)), photos))
        assertTrue(!second.resumed, "产物缺失时不得报「已完成」")
        assertEquals(2, second.copied, "重新导出应复制 2 张照片")
        assertEquals(0, second.skipped)
        assertTrue(second.copied >= 2)
    }

    @Test
    fun `台账内容变化时不得命中续传`() {
        val p1 = jpeg("IMG_1.jpg")
        val photos = mapOf(1L to Fixtures.photo(1, p1))
        val events = listOf(Fixtures.event(1, 10, 0, "护栏损坏", "K1", "x"))
        val out = tmp.resolve("out")

        exporter.export(input(out, events, mapOf(1 to listOf(1L)), photos))
        // 换模板 → 文件夹名变化 → 台账指纹变化 → 必须重导
        val second = exporter.export(input(out, events, mapOf(1 to listOf(1L)), photos, template = 1))
        assertTrue(!second.resumed)
    }

    // ── ⑥ 长路径 ──────────────────────────────────────────────────────────

    @Test
    fun `超长目标路径在导出前被明确阻断`() {
        val p1 = jpeg("IMG_1.jpg")
        val photos = mapOf(1L to Fixtures.photo(1, p1))
        val events = listOf(Fixtures.event(1, 10, 0, "护栏损坏", "K1", "x"))

        var deep = tmp
        while (deep.toString().length < 235) {
            deep = deep.resolve("很长的目录名称_0123456789")
        }
        val e = assertFailsWith<ExportBlockedException> {
            exporter.export(input(deep, events, mapOf(1 to listOf(1L)), photos))
        }
        assertTrue(e.message!!.contains("超过"))
        assertTrue(e.offendingPaths.isNotEmpty())
    }

    // ── 台账一致性 ────────────────────────────────────────────────────────

    @Test
    fun `台账照片列前缀等于实际文件夹名且嵌入真实图片`() {
        val p1 = jpeg("IMG_1.jpg", 800, 600)
        val p2 = jpeg("IMG_2.jpg", 800, 600)
        val photos = mapOf(1L to Fixtures.photo(1, p1, captureTimeMs = Fixtures.at(10, 5)),
            2L to Fixtures.photo(2, p2, captureTimeMs = Fixtures.at(10, 10)))
        val events = listOf(Fixtures.event(1, 10, 0, "护栏损坏", "K138+700", "发现护栏损坏", 10, 20))
        val out = tmp.resolve("out")
        val r = exporter.export(input(out, events, mapOf(1 to listOf(1L, 2L)), photos))

        val ledger = assertNotNull(r.ledgerFile)
        XSSFWorkbook(Files.newInputStream(ledger)).use { wb ->
            val sheet = wb.getSheetAt(0)
            val row = assertNotNull(sheet.getRow(1))
            val p1Label = row.getCell(5)?.stringCellValue ?: ""
            val p2Label = row.getCell(6)?.stringCellValue ?: ""
            val folder = "01_护栏损坏"
            assertTrue(p1Label.startsWith("$folder/"), "照片1 前缀应为实际文件夹名，实际=$p1Label")
            assertTrue(p2Label.startsWith("$folder/"), "照片2 前缀应为实际文件夹名，实际=$p2Label")
            // 前缀指向的文件夹必须真实存在，且其中的文件名与列内文件名一致
            val fileInFolder = p1Label.removePrefix("$folder/")
            assertTrue(Files.isRegularFile(r.exportDir.resolve(folder).resolve(fileInFolder)),
                "台账里的 $p1Label 在磁盘上不存在")
            assertTrue(wb.allPictures.isNotEmpty(), "台账应嵌入图片（imageLoader 应被真正调用）")
        }
    }

    @Test
    fun `取消导出后写入不完整清单且不命中续传`() {
        val p1 = jpeg("IMG_1.jpg")
        val p2 = jpeg("IMG_2.jpg")
        val photos = mapOf(1L to Fixtures.photo(1, p1), 2L to Fixtures.photo(2, p2))
        val events = listOf(Fixtures.event(1, 10, 0, "护栏损坏", "K1", "x"))
        val out = tmp.resolve("out")

        var seen = 0
        val r = exporter.export(
            input(
                out, events, mapOf(1 to listOf(1L, 2L)), photos,
                onProgress = { seen++ },
                isCancelled = { seen >= 1 },
            ),
        )
        assertTrue(r.cancelled)
        assertEquals(r.totalTargets - r.copied - r.failed, r.skipped, "取消后：跳过数必须等于未复制数")

        // 取消留下的清单 complete=false → 下次必须重导
        val again = exporter.export(input(out, events, mapOf(1 to listOf(1L, 2L)), photos))
        assertTrue(!again.resumed)
    }
}
