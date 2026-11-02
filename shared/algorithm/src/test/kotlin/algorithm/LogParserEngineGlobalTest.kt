// algorithm/LogParserEngineGlobalTest.kt —— 全局信息（巡查人员/记录人/车牌）提取边界测试
// ★ 探针：冒号可选正则会误抓「记录时间→时间」「巡查人员名单→名单」，须变红后修复
package algorithm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId

class LogParserEngineGlobalTest {
    private val date = LocalDate.of(2026, 9, 3)
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test fun `记录时间不误抓为记录人`() {
        val p = LogParserEngine.parse("记录时间：2026-09-03 08时00分 出发", date, zone)
        assertNull(p.recorder, "「记录时间」中的时间二字不得被当作记录人")
    }

    @Test fun `巡查人员名单不误抓为巡查人员`() {
        val p = LogParserEngine.parse("巡查人员名单 08时00分 出发", date, zone)
        assertNull(p.inspectors, "「名单」不得被当作巡查人员")
    }

    @Test fun `正常提取记录人`() {
        val p = LogParserEngine.parse("记录人：王某某 08时00分 出发", date, zone)
        assertEquals("王某某", p.recorder)
    }

    @Test fun `记录冒号形式提取`() {
        val p = LogParserEngine.parse("记录：王某某 08时00分 出发", date, zone)
        assertEquals("王某某", p.recorder)
    }

    @Test fun `正常提取巡查人员`() {
        val p = LogParserEngine.parse("巡查人员：张某某、李某某 08时00分 出发", date, zone)
        assertEquals("张某某、李某某", p.inspectors)
    }

    @Test fun `正常提取车牌`() {
        val p = LogParserEngine.parse("车牌粤E00001 08时00分 出发", date, zone)
        assertEquals("粤E00001", p.vehicle)
    }
}
