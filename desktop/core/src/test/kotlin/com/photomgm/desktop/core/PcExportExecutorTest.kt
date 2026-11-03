// 导出**执行器**单测：逐条对应方案 §4.5 契约（①产物结构 ②一照多事件 ③重名 ④日志 ⑤续传 ⑥长路径）
//
// ★ T10 的改动只在"怎么摆料"，不在"断言什么"：
//   · 计划不再由执行器自己算，而是先用 `ExportPlanner.build(...)` 造出来再交给它 ——
//     这正是共享层与桌面端的新分工（计划上移、执行留平台）；
//   · `ExportResult` 的路径字段从 `Path` 变不透明 `String`（安卓那边是 content URI），
//     所以断言里多一层 `dir(r)` 把它取回 `Path`。
//
// ★ ③重名 这一条现在**主要**由 `shared/app` 的 `ExportPlannerTest` 保证（它是唯一实现处）。
//   这里保留它，是因为它验证的是**另一件事**：计划里的相对路径真的原样落到了磁盘上
//   （计划写 `IMG_2.jpg`、磁盘写出 `IMG_2.jpg`），而不是"后缀规则算对了"。
package com.photomgm.desktop.core

import algorithm.AppZone
import algorithm.model.LogEvent
import algorithm.model.Photo
import app.api.ExportPlan
import app.api.ExportProgress
import app.api.ExportResult

import app.export.ExportPlanner
import com.photomgm.desktop.core.adapter.PcExportExecutor
import com.photomgm.desktop.core.platform.PcImageEmbedder
import app.api.ExportBlockedException
import app.infra.Json
import app.infra.JsonValue
import app.infra.asObj
import app.infra.longOrNull
import app.infra.objOrNull
import app.infra.strOrNull
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PcExportExecutorTest {

    @TempDir
    lateinit var tmp: Path

    private val zone = AppZone.ID
    private val fixedClock: Clock =
        Clock.fixed(Instant.parse("2026-08-14T02:15:00Z"), zone) // 北京时间 10:15:00

    private val executor = PcExportExecutor(
        zone = zone,
        imageEncoder = { ref -> PcImageEmbedder().embed(ref) },
        clock = fixedClock,
    )

    /** `ExportResult` 的路径是不透明字符串（安卓那边是 URI），断言前先取回本地 `Path`。 */
    private fun dir(r: ExportResult): Path = Paths.get(r.exportDir)

    private fun jpeg(name: String, w: Int = 320, h: Int = 240): Path {
        val p = tmp.resolve("photos/$name")
        TestImages.writeJpeg(p, w, h)
        return p
    }

    /** 造计划（T10：这段以前是执行器内部 `buildPlan` 的活，现在由共享层产出）。 */
    private fun planOf(
        events: List<LogEvent>,
        map: Map<Int, List<Long>>,
        photos: Map<Long, Photo>,
        unmatched: List<Long> = emptyList(),
        template: Int = 0,
        includeLedger: Boolean = true,
    ): ExportPlan = ExportPlanner.build(
        events = events,
        eventPhotoMap = map,
        unmatched = unmatched,
        photos = photos.toSortedMap().values.toList(),
        namingTemplate = template,
        includeLedger = includeLedger,
        zone = zone,
    )

    /** 跑一次导出（同步；进度与取消回调由调用方给）。 */
    private fun run(
        out: Path,
        plan: ExportPlan,
        onProgress: (ExportProgress) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): ExportResult = executor.execute(
        plan = plan,
        target = out.toAbsolutePath().toString(),
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
        val r = run(out, planOf(events, mapOf(1 to listOf(1L, 2L), 2 to listOf(3L)), photos))

        // 2026-11-02：目录名由 PhotoMgm_export_yyyyMMdd_HHmmss 改为 月日_时分秒（如 1101_203200）
        assertTrue(PcExportExecutor.isExportDirName(dir(r).name), "目录名应为 月日_时分秒：${dir(r).name}")
        assertTrue(Regex("""\d{4}_\d{6}""").matches(dir(r).name), "目录名应只含 月日_时分秒：${dir(r).name}")
        assertTrue(!dir(r).name.startsWith("PhotoMgm_export_"), "不应再带旧前缀")
        assertTrue(!dir(r).name.contains(":"), "目录名时间戳不含冒号")
        assertTrue(Files.isRegularFile(dir(r).resolve("ledger.xlsx")))
        assertTrue(Files.isRegularFile(dir(r).resolve("export.log")))
        assertTrue(Files.isRegularFile(dir(r).resolve("export-manifest.json")))

        val dirs = Files.list(dir(r)).use { s ->
            s.filter { Files.isDirectory(it) }.map { it.fileName.toString() }.sorted().toList()
        }
        assertEquals(listOf("01_护栏损坏", "02_标志牌倾斜"), dirs)
        assertEquals(2, Files.list(dir(r).resolve("01_护栏损坏")).use { it.count() }.toInt())
        assertEquals(1, Files.list(dir(r).resolve("02_标志牌倾斜")).use { it.count() }.toInt())

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
        val r = run(out, planOf(events, mapOf(1 to listOf(1L)), photos, unmatched = listOf(9L)))
        assertTrue(Files.isDirectory(dir(r).resolve("99_未分类")))
        assertEquals(1, Files.list(dir(r).resolve("99_未分类")).use { it.count() }.toInt())
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
        val r = run(out, planOf(events, mapOf(1 to listOf(1L), 2 to listOf(1L)), photos))

        assertEquals(1, Files.list(dir(r).resolve("01_护栏损坏")).use { it.count() }.toInt())
        assertEquals(1, Files.list(dir(r).resolve("02_标志牌倾斜")).use { it.count() }.toInt())
    }

    // ── ③ 重名（计划的相对路径必须原样落盘） ────────────────────────────────

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
        val plan = planOf(events, mapOf(1 to listOf(1L, 2L, 3L, 4L)), photos)
        // 计划里已经定好名字（唯一实现处是 ExportPlanner），执行器不许改
        assertEquals(
            listOf("01_护栏损坏/IMG.jpg", "01_护栏损坏/IMG_2.jpg", "01_护栏损坏/NOEXT", "01_护栏损坏/NOEXT_2"),
            plan.files.map { it.relativePath },
        )
        val r = run(out, plan)

        val names = Files.list(dir(r).resolve("01_护栏损坏")).use { s ->
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
        val r = run(out, planOf(events, mapOf(1 to listOf(1L)), photos))

        val logFile = Paths.get(r.logFile)
        val lines = Files.readAllLines(logFile, Charsets.UTF_8).filter { it.isNotBlank() }
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
        val head = Files.readAllBytes(logFile).take(3)
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

        val first = run(out, planOf(events, mapOf(1 to listOf(1L, 2L)), photos))
        assertEquals(2, first.copied, "本次复制 2 张照片（台账不计入 copied）")
        assertTrue(!first.resumed)

        val second = run(out, planOf(events, mapOf(1 to listOf(1L, 2L)), photos))
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

        val first = run(out, planOf(events, mapOf(1 to listOf(1L, 2L)), photos))
        val victim = Files.list(dir(first).resolve("01_护栏损坏")).use { s -> s.findFirst().get() }
        Files.delete(victim)

        val second = run(out, planOf(events, mapOf(1 to listOf(1L, 2L)), photos))
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

        run(out, planOf(events, mapOf(1 to listOf(1L)), photos))
        // 换模板 → 文件夹名变化 → 台账指纹变化 → 必须重导
        val second = run(out, planOf(events, mapOf(1 to listOf(1L)), photos, template = 1))
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
            run(deep, planOf(events, mapOf(1 to listOf(1L)), photos))
        }
        assertTrue(e.message!!.contains("超过"))
        assertTrue(e.offendingPaths.isNotEmpty())
    }

    // ── 台账一致性 ────────────────────────────────────────────────────────

    @Test
    fun `台账照片列在嵌入成功时留空且照片落在对应文件夹`() {
        val p1 = jpeg("IMG_1.jpg", 800, 600)
        val p2 = jpeg("IMG_2.jpg", 800, 600)
        val photos = mapOf(
            1L to Fixtures.photo(1, p1, captureTimeMs = Fixtures.at(10, 5)),
            2L to Fixtures.photo(2, p2, captureTimeMs = Fixtures.at(10, 10)),
        )
        val events = listOf(Fixtures.event(1, 10, 0, "护栏损坏", "K138+700", "发现护栏损坏", 10, 20))
        val out = tmp.resolve("out")
        val r = run(out, planOf(events, mapOf(1 to listOf(1L, 2L)), photos))

        val ledger = Paths.get(assertNotNull(r.ledgerFile))
        XSSFWorkbook(Files.newInputStream(ledger)).use { wb ->
            val sheet = wb.getSheetAt(0)
            val row = assertNotNull(sheet.getRow(1))
            assertTrue(wb.allPictures.isNotEmpty(), "台账应嵌入图片（imageLoader 应被真正调用）")
            // ★ 2026-11-02：照片已嵌进格子 → 照片下方不再写任何文字（原先写 "01_护栏损坏/IMG_1.jpg"）
            assertEquals("", row.getCell(5)?.stringCellValue ?: "", "照片1 嵌入后单元格应留空")
            assertEquals("", row.getCell(6)?.stringCellValue ?: "", "照片2 嵌入后单元格应留空")
        }
        // 台账不再写文件夹名，那么"照片落在哪个文件夹"这件事必须靠磁盘结构本身对得上
        val folder = "01_护栏损坏"
        val inFolder = Files.list(dir(r).resolve(folder)).use { s ->
            s.filter { Files.isRegularFile(it) }.map { it.fileName.toString() }.sorted().toList()
        }
        assertEquals(listOf("IMG_1.jpg", "IMG_2.jpg"), inFolder, "事件文件夹里应正好是这两张原图")
    }

    @Test
    fun `取消导出后写入不完整清单且不命中续传`() {
        val p1 = jpeg("IMG_1.jpg")
        val p2 = jpeg("IMG_2.jpg")
        val photos = mapOf(1L to Fixtures.photo(1, p1), 2L to Fixtures.photo(2, p2))
        val events = listOf(Fixtures.event(1, 10, 0, "护栏损坏", "K1", "x"))
        val out = tmp.resolve("out")

        var seen = 0
        val r = run(
            out,
            planOf(events, mapOf(1 to listOf(1L, 2L)), photos),
            onProgress = { seen++ },
            isCancelled = { seen >= 1 },
        )
        assertTrue(r.cancelled)
        assertEquals(r.totalTargets - r.copied - r.failed, r.skipped, "取消后：跳过数必须等于未复制数")

        // 取消留下的清单 complete=false → 下次必须重导
        val again = run(out, planOf(events, mapOf(1 to listOf(1L, 2L)), photos))
        assertTrue(!again.resumed)
    }
}
