// algorithm/ZipExporterTest.kt —— §7.4 ZIP 导出测试
package algorithm

import algorithm.model.LogEvent
import algorithm.model.Photo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.ZipInputStream

class ZipExporterTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val date = LocalDate.of(2026, 9, 3)
    private fun at(h: Int, m: Int) = date.atTime(h, m).atZone(zone).toInstant().toEpochMilli()
    private fun ev(id: Int, start: Long?, end: Long?, loc: String = "K138+700M", type: String = "交安设施巡查") =
        LogEvent(id, start, end, listOfNotNull(start, end), loc, type, "描述$id")

    @TempDir lateinit var tmp: File

    private fun makePhoto(id: Long, name: String, content: String): Pair<Photo, File> {
        val f = File(tmp, name)
        f.writeText(content)
        return Photo(id, f.absolutePath, name, at(8, 30), 23.0, 113.0) to f
    }

    private fun zipNames(zip: File): List<String> {
        val names = mutableListOf<String>()
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            while (true) { val e = z.nextEntry ?: break; names += e.name; z.closeEntry() }
        }
        return names
    }

    private fun zipContent(zip: File, path: String): String {
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.name == path) return z.readBytes().toString(Charsets.UTF_8)
                z.closeEntry()
            }
        }
        error("not found: $path")
    }

    @Test fun `模板一文件夹名=事件类型`() {
        val (p1, _) = makePhoto(1, "a.jpg", "AAA")
        val (p2, _) = makePhoto(2, "b.jpg", "BBB")
        val e = ev(1, at(8, 30), at(10, 0))
        val zip = File(tmp, "out1.zip")
        ZipExporter().export(listOf(e), mapOf(1 to listOf(1L, 2L)), emptyList(),
            { id -> if (id == 1L) p1 else p2 }, 0, zip, null) { _, _ -> }
        val names = zipNames(zip)
        // ★ P2-4：文件夹带序号前缀
        assertTrue("01_交安设施巡查/a.jpg" in names)
        assertTrue("01_交安设施巡查/b.jpg" in names)
    }

    @Test fun `模板二=巡查日志内容`() {
        val (p1, _) = makePhoto(1, "a.jpg", "AAA")
        val e = ev(1, at(8, 30), at(10, 0))
        val zip = File(tmp, "out2.zip")
        ZipExporter().export(listOf(e), mapOf(1 to listOf(1L)), emptyList(),
            { p1 }, 1, zip, null) { _, _ -> }
        assertEquals(listOf("01_描述1/a.jpg"), zipNames(zip))
    }

    @Test fun `模板二日志内容截断与换行合并`() {
        val (p1, _) = makePhoto(1, "a.jpg", "AAA")
        val e = ev(1, at(8, 30), at(10, 0)).copy(
            description = "发现路面障碍物并已清理\n现场通知养护单位跟进处理"
        )
        val zip = File(tmp, "out3.zip")
        ZipExporter().export(listOf(e), mapOf(1 to listOf(1L)), emptyList(),
            { p1 }, 1, zip, null) { _, _ -> }
        // 换行合并为空格；描述 23 字符 < 50 上限，不截断；不再含月日前缀
        assertEquals(
            "01_发现路面障碍物并已清理 现场通知养护单位跟进处理/a.jpg",
            zipNames(zip).first()
        )
    }

    @Test fun `模板二去除开头时间前缀`() {
        val (p1, _) = makePhoto(1, "a.jpg", "AAA")
        val e = ev(1, at(8, 30), at(10, 0)).copy(
            description = "8时30分 到达上行K138+700M处，检查交安设施"
        )
        val zip = File(tmp, "outTime.zip")
        ZipExporter().export(listOf(e), mapOf(1 to listOf(1L)), emptyList(),
            { p1 }, 1, zip, null) { _, _ -> }
        // 开头 "8时30分 " 被去除
        assertEquals(
            "01_到达上行K138+700M处，检查交安设施/a.jpg",
            zipNames(zip).first()
        )
    }

    @Test fun `extraFiles写入ZIP且跳过断点续传`() {
        val (p1, _) = makePhoto(1, "a.jpg", "AAA")
        val e = ev(1, at(8, 30), at(10, 0))
        val zip = File(tmp, "outExtra.zip")
        val state = File(tmp, "export_state_extra.json")
        val extra = mapOf("20260903.xlsx" to "XLSX-BYTES-123".toByteArray())
        // 首次：照片 + Excel 都写入
        val r1 = ZipExporter().export(listOf(e), mapOf(1 to listOf(1L)), emptyList(),
            { p1 }, 0, zip, state, extraFiles = extra) { _, _ -> }
        assertEquals(2, r1.copied)
        val names = zipNames(zip)
        assertTrue("01_交安设施巡查/a.jpg" in names)
        assertTrue("20260903.xlsx" in names)
        assertEquals("XLSX-BYTES-123", zipContent(zip, "20260903.xlsx"))
        // 再次导出（带 extraFiles）：不得整包跳过（保证台账最新）
        val r2 = ZipExporter().export(listOf(e), mapOf(1 to listOf(1L)), emptyList(),
            { p1 }, 0, zip, state, extraFiles = extra) { _, _ -> }
        assertEquals(0, r2.skippedFromState)
        assertEquals(2, r2.copied)
    }

    @Test fun `未匹配照片进未分类文件夹`() {
        val (p1, _) = makePhoto(1, "a.jpg", "AAA")
        val (pu, _) = makePhoto(9, "u.jpg", "UUU")
        val e = ev(1, at(8, 30), at(10, 0))
        val zip = File(tmp, "out4.zip")
        ZipExporter().export(listOf(e), mapOf(1 to listOf(1L)), listOf(9L),
            { id -> if (id == 1L) p1 else pu }, 0, zip, null) { _, _ -> }
        assertTrue("99_未分类/u.jpg" in zipNames(zip))
    }

    @Test fun `重名照片自动加序号`() {
        val (p1, _) = makePhoto(1, "a.jpg", "AAA")
        val (p2, _) = makePhoto(2, "a.jpg", "BBB")
        val e = ev(1, at(8, 30), at(10, 0))
        val zip = File(tmp, "out5.zip")
        ZipExporter().export(listOf(e), mapOf(1 to listOf(1L, 2L)), emptyList(),
            { id -> if (id == 1L) p1 else p2 }, 0, zip, null) { _, _ -> }
        val names = zipNames(zip)
        assertTrue("01_交安设施巡查/a.jpg" in names)
        assertTrue("01_交安设施巡查/a_1.jpg" in names)
    }

    @Test fun `文件内容正确流式复制`() {
        val (p1, _) = makePhoto(1, "a.jpg", "HELLO-CONTENT-123")
        val e = ev(1, at(8, 30), at(10, 0))
        val zip = File(tmp, "out6.zip")
        ZipExporter().export(listOf(e), mapOf(1 to listOf(1L)), emptyList(),
            { p1 }, 0, zip, null) { _, _ -> }
        assertEquals("HELLO-CONTENT-123", zipContent(zip, "01_交安设施巡查/a.jpg"))
    }

    @Test fun `进度回调触发且末值=1`() {
        val (p1, _) = makePhoto(1, "a.jpg", "A")
        val e = ev(1, at(8, 30), at(10, 0))
        val zip = File(tmp, "out7.zip")
        val progresses = mutableListOf<Float>()
        ZipExporter().export(listOf(e), mapOf(1 to listOf(1L)), emptyList(),
            { p1 }, 0, zip, null) { f, _ -> progresses += f }
        assertTrue(progresses.isNotEmpty())
        assertEquals(1f, progresses.last())
    }

    @Test fun `断点续传上次全部完成后跳过`() {
        val (p1, _) = makePhoto(1, "a.jpg", "A")
        val e = ev(1, at(8, 30), at(10, 0))
        val zip = File(tmp, "out8.zip")
        val state = File(tmp, "export_state.json")
        // 首次导出（写 state）
        val r1 = ZipExporter().export(listOf(e), mapOf(1 to listOf(1L)), emptyList(),
            { p1 }, 0, zip, state) { _, _ -> }
        assertEquals(1, r1.copied)
        // 再次导出：跳过
        val r2 = ZipExporter().export(listOf(e), mapOf(1 to listOf(1L)), emptyList(),
            { p1 }, 0, zip, state) { _, _ -> }
        assertEquals(1, r2.skippedFromState)
        assertEquals(0, r2.copied)
    }

    @Test fun `无state文件不跳过`() {
        val (p1, _) = makePhoto(1, "a.jpg", "A")
        val e = ev(1, at(8, 30), at(10, 0))
        val zip = File(tmp, "out9.zip")
        val r = ZipExporter().export(listOf(e), mapOf(1 to listOf(1L)), emptyList(),
            { p1 }, 0, zip, null) { _, _ -> }
        assertEquals(1, r.copied)
        assertEquals(0, r.skippedFromState)
    }

    @Test fun `非法文件不中断计入失败`() {
        val e = ev(1, at(8, 30), at(10, 0))
        // photo sourceRef 指向不存在文件
        val missing = Photo(1, File(tmp, "nope.jpg").absolutePath, "nope.jpg", at(8, 30), null, null)
        val zip = File(tmp, "out10.zip")
        val r = ZipExporter().export(listOf(e), mapOf(1 to listOf(1L)), emptyList(),
            { missing }, 0, zip, null) { _, _ -> }
        assertEquals(1, r.failed)
    }

    @Test fun `失败照片记录明细_含路径与原因`() {
        // 回归 P0-3：打包失败时 failedDetails 记录 ZIP 内路径 + 原因，界面可展示缺了哪些照片
        val e = ev(1, at(8, 30), at(10, 0))
        val missing = Photo(1, File(tmp, "nope.jpg").absolutePath, "nope.jpg", at(8, 30), null, null)
        val zip = File(tmp, "outDetail.zip")
        val r = ZipExporter().export(listOf(e), mapOf(1 to listOf(1L)), emptyList(),
            { missing }, 0, zip, null) { _, _ -> }
        assertEquals(1, r.failed)
        assertEquals(1, r.failedDetails.size)
        assertTrue(r.failedDetails[0].contains("nope.jpg"), "明细应含失败文件路径: ${r.failedDetails[0]}")
        assertTrue(r.failedDetails[0].contains("文件不存在"), "明细应含失败原因: ${r.failedDetails[0]}")
    }

    @Test fun `失败明细不影响成功导出`() {
        val (p1, _) = makePhoto(1, "a.jpg", "A")
        val e = ev(1, at(8, 30), at(10, 0))
        val zip = File(tmp, "outOkDetail.zip")
        val r = ZipExporter().export(listOf(e), mapOf(1 to listOf(1L)), emptyList(),
            { p1 }, 0, zip, null) { _, _ -> }
        assertEquals(1, r.copied)
        assertEquals(0, r.failed)
        assertTrue(r.failedDetails.isEmpty(), "全部成功时明细应为空")
    }

    @Test fun `断点续传目标集合变化必须重新打包`() {
        // 回归：旧逻辑 targets ⊆ completed 即整包跳过 → 用户移走照片后 ZIP 仍含旧照片；
        // 修复后集合不完全一致必须重新打包
        val (p1, _) = makePhoto(1, "a.jpg", "A")
        val (p2, _) = makePhoto(2, "b.jpg", "B")
        val e = ev(1, at(8, 30), at(10, 0))
        val zip = File(tmp, "out11.zip")
        val state = File(tmp, "export_state2.json")
        // 首次导出 {A,B} → state 记录 {A,B}
        val r1 = ZipExporter().export(listOf(e), mapOf(1 to listOf(1L, 2L)), emptyList(),
            { id -> if (id == 1L) p1 else p2 }, 0, zip, state) { _, _ -> }
        assertEquals(2, r1.copied)
        // 用户移走 b.jpg → targets={A}（⊂ completed）→ 不得误跳过，必须重新打包
        val r2 = ZipExporter().export(listOf(e), mapOf(1 to listOf(1L)), emptyList(),
            { p1 }, 0, zip, state) { _, _ -> }
        assertEquals(0, r2.skippedFromState, "集合变化（移走照片）后不得整包跳过")
        assertEquals(1, r2.copied, "必须重新打包出新 ZIP")
        assertTrue("b.jpg" !in zipNames(zip), "新 ZIP 不得再含被移走的照片")
    }

    @Test fun `台账失败后不得误跳过_上次带台账`() {
        // 回归 P0-1：上次带台账导出（state extra=true），本次台账生成失败（extraFiles 为空）
        // 且照片集合一致 → 修复前误判"已完成"整包跳过，输出目录不刷新；
        // 修复后必须重新打包（新 ZIP 不含台账，与界面提示一致）
        val (p1, _) = makePhoto(1, "a.jpg", "A")
        val e = ev(1, at(8, 30), at(10, 0))
        val zip = File(tmp, "outLedgerFail.zip")
        val state = File(tmp, "export_state_ledgerfail.json")
        val extra = mapOf("20260903.xlsx" to "XLSX-BYTES".toByteArray())
        // 首次：带台账导出
        val r1 = ZipExporter().export(listOf(e), mapOf(1 to listOf(1L)), emptyList(),
            { p1 }, 0, zip, state, extraFiles = extra) { _, _ -> }
        assertEquals(2, r1.copied)
        // 本次台账失败：extraFiles 为空，照片集合未变
        val r2 = ZipExporter().export(listOf(e), mapOf(1 to listOf(1L)), emptyList(),
            { p1 }, 0, zip, state) { _, _ -> }
        assertEquals(0, r2.skippedFromState, "上次带台账、本次台账失败后不得整包跳过")
        assertEquals(1, r2.copied, "必须重新打包出新 ZIP")
        assertTrue("20260903.xlsx" !in zipNames(zip), "新 ZIP 不得含台账（本次未生成）")
    }

    @Test fun `旧格式state无extra字段保守按带台账处理`() {
        // 旧版本 state 只有 completed 字段：无 extra 时保守按"带台账"处理 → 不跳过，
        // 避免台账失败时误跳过（升级后第一次）
        val (p1, _) = makePhoto(1, "a.jpg", "A")
        val e = ev(1, at(8, 30), at(10, 0))
        val zip = File(tmp, "outOldState.zip")
        val state = File(tmp, "export_state_old.json")
        state.writeText("""{"completed":["交安设施巡查/a.jpg"]}""")
        val r = ZipExporter().export(listOf(e), mapOf(1 to listOf(1L)), emptyList(),
            { p1 }, 0, zip, state) { _, _ -> }
        assertEquals(0, r.skippedFromState)
        assertEquals(1, r.copied)
    }

    @Test fun `文件夹名非法字符替换为下划线`() {
        val (p1, _) = makePhoto(1, "a.jpg", "AAA")
        val e = ev(1, at(8, 30), at(10, 0), loc = "K1+000", type = "桥下/施工:测试")
        val zip = File(tmp, "outsanitize.zip")
        ZipExporter().export(listOf(e), mapOf(1 to listOf(1L)), emptyList(),
            { p1 }, 0, zip, null) { _, _ -> }
        // Windows 非法字符 / : 替换为 _
        assertTrue("01_桥下_施工_测试/a.jpg" in zipNames(zip))
    }

    @Test fun `断点续传_路径含特殊字符仍正确跳过`() {
        // 回归 P2-3：原手写正则 `"completed":\s*\[(.*?)\]` 在路径含 `]` 时提前截断、
        // 含 `,` 时 split 拆碎 → completed 集合解析错误 → 断点续传误判"集合不等"而重新打包。
        // 文件夹名（事件类型 sanitize 不替换 `]`/`,`）与文件名均构造特殊字符。
        val (p1, _) = makePhoto(1, "a],c.jpg", "A")
        val e = ev(1, at(8, 30), at(10, 0), type = "交安]设施,巡检")
        val zip = File(tmp, "outSpecial.zip")
        val state = File(tmp, "export_state_special.json")
        // 首次导出（写 state）
        val r1 = ZipExporter().export(listOf(e), mapOf(1 to listOf(1L)), emptyList(),
            { p1 }, 0, zip, state) { _, _ -> }
        assertEquals(1, r1.copied)
        // 同集合再次导出 → 必须整包跳过（旧解析在 `]` 处截断 → 误判集合不等 → 重新打包）
        val r2 = ZipExporter().export(listOf(e), mapOf(1 to listOf(1L)), emptyList(),
            { p1 }, 0, zip, state) { _, _ -> }
        assertEquals(1, r2.skippedFromState, "路径含 ] / , 时断点续传仍应正确跳过")
        assertEquals(0, r2.copied)
    }

    @Test fun `文件夹按结束时间排序并带序号前缀`() {
        // 回归 P2-4：events 乱序传入 → ZIP 条目按结束时间升序、序号 01/02/03、未分类 99_ 固定最后
        val (p1, _) = makePhoto(1, "a.jpg", "A")
        val (p2, _) = makePhoto(2, "b.jpg", "B")
        val (p3, _) = makePhoto(3, "c.jpg", "C")
        val (pu, _) = makePhoto(9, "u.jpg", "U")
        val eLate = ev(1, at(13, 0), at(14, 0))   // 结束 14:00 → 第 3
        val eEarly = ev(2, at(8, 0), at(9, 0))    // 结束 09:00 → 第 1
        val eMid = ev(3, at(10, 0), at(11, 0))    // 结束 11:00 → 第 2
        val zip = File(tmp, "outOrder.zip")
        ZipExporter().export(listOf(eLate, eEarly, eMid),
            mapOf(1 to listOf(1L), 2 to listOf(2L), 3 to listOf(3L)), listOf(9L),
            { id -> when (id) { 1L -> p1; 2L -> p2; 3L -> p3; else -> pu } }, 1, zip, null) { _, _ -> }
        assertEquals(
            listOf("01_描述2/b.jpg", "02_描述3/c.jpg", "03_描述1/a.jpg", "99_未分类/u.jpg"),
            zipNames(zip)
        )
    }

    @Test fun `模板0同类型事件合并文件夹只编一次号`() {
        // P2-4：同一事件类型多事件 → 合并为一个文件夹，序号取组内最早事件（不重复编号）
        val (p1, _) = makePhoto(1, "a.jpg", "A")
        val (p2, _) = makePhoto(2, "b.jpg", "B")
        val e1 = ev(1, at(8, 0), at(9, 0), type = "交通事故处置")
        val e2 = ev(2, at(10, 0), at(11, 0), type = "交通事故处置")
        val zip = File(tmp, "outMerge.zip")
        ZipExporter().export(listOf(e1, e2), mapOf(1 to listOf(1L), 2 to listOf(2L)), emptyList(),
            { id -> if (id == 1L) p1 else p2 }, 0, zip, null) { _, _ -> }
        assertEquals(
            listOf("01_交通事故处置/a.jpg", "01_交通事故处置/b.jpg"),
            zipNames(zip)
        )
    }
}
