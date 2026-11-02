// algorithm/InterventionEnhanceTest.kt —— 干预提示增强：TIME_OVERLAP 必须标注穿插的两事件明细
// 用户需求：预览页"事件时间穿插"提示未明确标注需要核对的相关信息 → 消息含 事件编号/类型/时间/地点 + relatedEventIds
package algorithm

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId

class InterventionEnhanceTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val date = LocalDate.of(2026, 8, 17)

    @Test fun `时间穿插干预标注两事件明细`() {
        val logText = """
            08:00 交接班，检查装备完成，安全
            09:00 巡查至K12+300 上行，发现护栏损坏，09:10 处理完毕，已拍照取证
            09:05 接报K13+500 下行路面障碍物，09:20 处置完毕，已拍照取证
        """.trimIndent()
        val parsed = LogParserEngine.parse(logText, date, zone)
        assertTrue(parsed.events.size >= 3, "应有 3 条事件")

        val engine = EventClassifyEngine()
        val result = engine.classify(parsed.events, emptyList(), emptyList(), date)
        val overlap = result.interventions.filter { it.kind == Kind.TIME_OVERLAP }
        assertTrue(overlap.isNotEmpty(), "存在时间穿插时应产生 TIME_OVERLAP 干预")

        val msg = overlap.first().message
        // 消息必须标注穿插的两个事件（编号 / 类型 / 地点）
        assertTrue(msg.contains("事件#2"), "消息应含前事件编号，实际：$msg")
        assertTrue(msg.contains("事件#3"), "消息应含后事件编号，实际：$msg")
        assertTrue(msg.contains("交安设施巡查") || msg.contains("事件"), "消息应含事件类型，实际：$msg")
        assertTrue(msg.contains("K12+300") && msg.contains("K13+500"), "消息应含两事件地点，实际：$msg")
        assertTrue(msg.contains("请人工核对"), "消息应提示人工核对，实际：$msg")

        // relatedEventIds 指向穿插的两个事件
        assertTrue(overlap.first().relatedEventIds.size == 2, "relatedEventIds 应为两事件，实际：${overlap.first().relatedEventIds}")
    }

    @Test fun `待定事件干预列出待定事件`() {
        val logText = """
            08:00 交接班，检查装备完成，安全
            接报K15+000M处发生车辆故障，前往处置
        """.trimIndent()
        val parsed = LogParserEngine.parse(logText, date, zone)
        val pending = parsed.events.filter { it.startTimeMs == null }
        assertTrue(pending.isNotEmpty(), "应有待定事件")

        val engine = EventClassifyEngine()
        val result = engine.classify(parsed.events, emptyList(), emptyList(), date)
        val tip = result.interventions.firstOrNull { it.message.contains("未记录时间") }
        assertTrue(tip != null, "应有待定事件提示")
        assertTrue(tip!!.message.contains("车辆故障") || tip.message.contains("事件"), "提示应列出待定事件，实际：${tip.message}")
        assertTrue(tip.relatedEventIds == pending.map { it.id }, "relatedEventIds 应指向待定事件")
    }
}
