// algorithm/LogParserPendingTest.kt —— 待定事件区兼容性测试（§3.4 关键点5 / §6.4 / 业务规则18）
// 无时间但含事件语义的行 → startTimeMs=null 的待定事件（进 pendingEventIds，照片不自动归入）；
// 续行（"已拍照取证"）→ 并入上一行；说明文字 → skippedLines。
package algorithm

import algorithm.model.Cluster
import algorithm.model.Photo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId

class LogParserPendingTest {
    private val date = LocalDate.of(2026, 8, 17)
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test fun `无时间但有事件语义 → 待定事件`() {
        val p = LogParserEngine.parse("接报K150+000M处发生两车追尾事故，立即前往处置", date, zone)
        assertEquals(1, p.events.size)
        val e = p.events[0]
        assertNull(e.startTimeMs, "无时间事件 startTimeMs 应为 null")
        assertNull(e.endTimeMs, "无时间事件 endTimeMs 应为 null")
        assertEquals("K150+000M", e.location)
        assertEquals("交通事故处置", e.eventType)
        assertTrue(p.notices.any { it.contains("未记录时间") }, "应有待定事件提示")
        assertTrue(p.skippedLines.isEmpty(), "无时间事件不应进 skipped")
    }

    @Test fun `无时间续行 → 并入上一行，不生成待定事件`() {
        val p = LogParserEngine.parse("08时00分 交接班，检查装备完成\n已拍照取证", date, zone)
        assertEquals(1, p.events.size, "续行应并入上一行，不独立成事件")
        val e = p.events[0]
        assertNotNull(e.startTimeMs, "有时间事件 startTimeMs 不应为 null")
        assertTrue(e.description.contains("已拍照取证"), "续行内容应并入上一行描述")
    }

    @Test fun `无时间说明文字 → 续行合并或skipped`() {
        // 跟在前一条日志后的说明文字 → 作为续行并入上一行（非独立事件）
        val p = LogParserEngine.parse("08时00分 出发\n备注：以上为测试数据", date, zone)
        assertEquals(1, p.events.size, "说明文字应并入上一行")
        assertTrue(p.events[0].description.contains("备注"), "说明文字内容应并入上一行描述")
        // 单独成段、无前行的说明文字 → 进 skipped
        val p2 = LogParserEngine.parse("备注：以上为测试数据", date, zone)
        assertEquals(0, p2.events.size)
        assertEquals(1, p2.skippedLines.size, "独立说明文字应进 skipped")
    }

    @Test fun `无时间行首动作词 → 待定事件`() {
        val p = LogParserEngine.parse("08时00分 出发\n发现K140+000M处路面有坑槽，已记录", date, zone)
        assertEquals(2, p.events.size, "行首动作词无时间行应独立成待定事件")
        val pending = p.events.first { it.startTimeMs == null }
        assertEquals("发现K140+000M处路面有坑槽，已记录", pending.description)
        assertEquals("K140+000M", pending.location)
    }

    @Test fun `无时间事件端到端进pendingEventIds`() {
        val logText = """
            08时00分 交接班，检查装备完成
            接报K150+000M处发生两车追尾事故，立即前往处置
            09时00分 巡查至K160+000M处，路况正常
        """.trimIndent()
        val parsed = LogParserEngine.parse(logText, date, zone)
        assertEquals(3, parsed.events.size)
        val pending = parsed.events.filter { it.startTimeMs == null }
        assertEquals(1, pending.size)

        // 构造空照片输入跑分类引擎：待定事件进 pendingEventIds
        val engine = EventClassifyEngine()
        val result = engine.classify(
            events = parsed.events,
            clusters = emptyList(),
            singles = listOf(Photo(id = 1, sourceRef = "p1", displayName = "p1.jpg", captureTimeMs = null, latitude = null, longitude = null)),
            patrolDate = date,
        )
        assertTrue(result.pendingEventIds.isNotEmpty(), "待定事件应进 pendingEventIds")
        assertEquals(pending[0].id, result.pendingEventIds[0])
        assertTrue(result.interventions.any { it.message.contains("未记录时间") }, "应有待定事件人工指定提示")
        assertTrue(result.unmatched.contains(1L), "无时间照片不应自动归入待定事件")
    }

    @Test fun `空文本不产生事件`() {
        val p = LogParserEngine.parse("", date, zone)
        assertTrue(p.events.isEmpty())
        assertTrue(p.skippedLines.isEmpty())
    }
}
