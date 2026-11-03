// shared/app 契约层单测（T09-C1）
//
// 这一组测的是「契约自己的语义」，不测编排：
//   · 设置值的清洗口径（非法模板值不能把导出目录名变成 `0_`）；
//   · 状态快照的派生属性（canExport / unmatchedPhotos）——它们是 UI 的唯一判据，
//     写错了表现为「按钮可点但点了报错」「未匹配池少几张」，而不是崩溃；
//   · 日志文本解码三步顺序（BOM / UTF-8 / GBK 回落）——两端曾各自实现，
//     这组用例是「同一份日志两端解出同一段文字」的机器化保证；
//   · 端口默认实现的取值（NOOP 外壳、空计划）。
//
// ★ 全部纯 JVM：不依赖 Robolectric / AWT / 真机（C5 硬要求）。
package app.api

import app.infra.decodeText
import app.port.ShellLauncher
import app.port.ShellOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContractsTest {

    // ── Settings ─────────────────────────────────────────────────────────

    @Test
    fun `命名模板非法值回落默认而不是原样保留`() {
        assertEquals(Settings.NAMED_BY_LOG_CONTENT, Settings.sanitizeNamingTemplate(7))
        assertEquals(Settings.NAMED_BY_LOG_CONTENT, Settings.sanitizeNamingTemplate(-1))
        assertEquals(Settings.NAMED_BY_EVENT_TYPE, Settings.sanitizeNamingTemplate(0))
        assertEquals(Settings.NAMED_BY_LOG_CONTENT, Settings.sanitizeNamingTemplate(1))
    }

    @Test
    fun `默认设置是未选源目录与未设输出目录`() {
        val s = Settings()
        assertTrue(s.sourceDirs.isEmpty())
        assertNull(s.outputDir)
        // 默认模板必须是「巡查日志内容」（1），与两端既有持久化口径一致
        assertEquals(1, s.namingTemplate)
    }

    // ── AppState 派生属性 ────────────────────────────────────────────────

    private fun event(id: Int, photoIds: List<Long> = emptyList()) = EventSnapshot(
        id = id,
        eventType = "护栏损坏",
        location = "K12+300",
        description = "右侧护栏",
        startTimeMs = 1_000L,
        endTimeMs = 2_000L,
        photoIds = photoIds,
        isPending = false,
        hints = emptyList(),
    )

    private fun photo(id: Long) = PhotoInfo(
        id = id,
        displayName = "IMG_$id.jpg",
        sourceRef = "/dcim/IMG_$id.jpg",
        captureTimeMs = null,
        latitude = null,
        longitude = null,
        isDerived = false,
    )

    @Test
    fun `可导出需要同时满足就绪_有输出目录_有事件`() {
        val base = AppState(settings = Settings(outputDir = "/out"), status = CoreStatus.READY, events = listOf(event(1)))
        assertTrue(base.canExport, "三个条件都满足时必须可导出")

        assertFalse(base.copy(status = CoreStatus.ANALYZING).canExport, "分析中不可导出")
        assertFalse(base.copy(settings = base.settings.copy(outputDir = null)).canExport, "无输出目录不可导出")
        assertFalse(base.copy(events = emptyList()).canExport, "无事件不可导出")
    }

    @Test
    fun `未匹配照片按 id 关联且缺失的 id 被跳过`() {
        val state = AppState(
            settings = Settings(),
            photos = listOf(photo(3), photo(1)),
            unmatchedPhotoIds = listOf(1, 99, 3),
        )
        // 顺序跟 unmatchedPhotoIds 走（不是 photos 的顺序）；99 不存在 → 跳过而不是抛异常
        assertEquals(listOf(1L, 3L), state.unmatchedPhotos.map { it.id })
    }

    @Test
    fun `没有未匹配照片时不构建索引`() {
        val state = AppState(settings = Settings(), photos = listOf(photo(1)))
        assertTrue(state.unmatchedPhotos.isEmpty())
    }

    @Test
    fun `分析摘要的已匹配数等于总数减未匹配`() {
        val summary = AnalysisSummary(
            candidateCount = 10,
            photoCount = 8,
            clusterCount = 2,
            unmatchedCount = 3,
            mergedDerivedGroups = 1,
            heicCount = 0,
            noGpsCount = 2,
        )
        assertEquals(5, summary.matchedCount)
    }

    // ── 日志解码 ─────────────────────────────────────────────────────────

    @Test
    fun `UTF-8 文本原样解出`() {
        assertEquals("08:30 巡查 K12+300 护栏损坏", decodeText("08:30 巡查 K12+300 护栏损坏".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `带 BOM 的 UTF-8 必须剥掉 BOM`() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val bytes = bom + "08:30 巡查".toByteArray(Charsets.UTF_8)
        val text = decodeText(bytes)
        assertEquals("08:30 巡查", text)
        // 若 BOM 残留，首字符会变成不可见字符，首行识别失败 → 这条用例就是那个故障的护栏
        assertFalse(text.startsWith("\uFEFF"), "BOM 不得残留")
    }

    @Test
    fun `非 UTF-8 的 GBK 文本回落解码而不是变成问号`() {
        val bytes = "08:30 巡查 护栏损坏".toByteArray(charset("GBK"))
        assertEquals("08:30 巡查 护栏损坏", decodeText(bytes))
    }

    @Test
    fun `空字节解出空串而不是 null`() {
        assertEquals("", decodeText(ByteArray(0)))
    }

    @Test
    fun `任意字节都不抛异常_保证总能显示原文`() {
        // 0xFF 0xFE 既不是合法 UTF-8，也不是常见 GBK 文本：兜底必须接住，不许抛
        // （宁可显示一段乱码让用户知道"这文件编码不对"，也不要抛异常让界面一片空白）。
        // ★ 不断言具体字符：0xFF 0xFE 在 GBK 的扩展区里能"解"出某个字符，
        //   所以这里只钉住"不抛 + 非空"这条真正的不变量 —— 第一版断言了内容，被现实打回。
        val text = decodeText(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x41))
        assertTrue(text.isNotEmpty(), "兜底解码必须返回非空字符串")
    }

    // ── 端口默认实现 ─────────────────────────────────────────────────────

    @Test
    fun `空外壳实现一律返回不可用`() {
        assertEquals(ShellOutcome.UNAVAILABLE, ShellLauncher.NOOP.open("/tmp/x.xlsx"))
        assertEquals(ShellOutcome.UNAVAILABLE, ShellLauncher.NOOP.reveal("/tmp"))
    }

    @Test
    fun `空计划没有目标且不可导出`() {
        val plan = ExportPlan.empty()
        assertEquals(0, plan.totalTargets)
        assertFalse(plan.hasTargets)
        assertEquals("99_未分类", plan.unmatchedFolder)
    }
}
