// TimeExtractor 单元测试（§3.1 / §9 测试要点）
package algorithm

import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TimeExtractorTest {
    private val date = LocalDate.of(2026, 9, 3)
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun t(ms: Long): LocalTime = Instant.ofEpochMilli(ms).atZone(zone).toLocalTime()
    private fun d(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    private fun times(ms: List<Long>) = ms.map(::t)
    private fun fmt(h: Int, m: Int) = LocalTime.of(h, m)

    @Test fun `冒号格式 9 05`() {
        val r = TimeExtractor.extractAll("9:05 到达C收费站，途经，主线巡查，路况正常", date, zone)
        assertEquals(listOf(fmt(9, 5)), times(r))
    }

    @Test fun `中文时分 8时5分`() {
        val r = TimeExtractor.extractAll("8时5分 出发", date, zone)
        assertEquals(listOf(fmt(8, 5)), times(r))
    }

    @Test fun `08时00分`() {
        val r = TimeExtractor.extractAll("08时00分 与早班巡查人员交接班", date, zone)
        assertEquals(listOf(fmt(8, 0)), times(r))
    }

    @Test fun `下午3点只解析出一个 15 点`() {
        val r = TimeExtractor.extractAll("下午3点 巡查人员在K172+500M处发现路侧有堆积物", date, zone)
        assertEquals(1, r.size)
        assertEquals(fmt(15, 0), t(r[0]))
    }

    @Test fun `时间段 13时48分至14时48分 两条时间点`() {
        val r = TimeExtractor.extractAll("13时48分至14时48分 在K164+000M至K166+000M路段进行涉路施工监管", date, zone)
        assertEquals(listOf(fmt(13, 48), fmt(14, 48)), times(r))
    }

    @Test fun `跨日回绕 23时30分到次日01时00分`() {
        val r = TimeExtractor.extractAll("23时30分 接报K210+000M处有障碍物，前往处理，01时00分处理完毕", date, zone)
        assertEquals(2, r.size)
        assertEquals(fmt(23, 30), t(r[0]))
        assertEquals(fmt(1, 0), t(r[1]))
        assertEquals(date, d(r[0]))
        assertEquals(date.plusDays(1), d(r[1]))   // ★ 次日 01:00，回绕 +1 天
    }

    @Test fun `纯数字 0800`() {
        val r = TimeExtractor.extractAll("0800 到达", date, zone)
        assertEquals(listOf(fmt(8, 0)), times(r))
    }

    @Test fun `中文数字 八点`() {
        // 文档 §3.1 toInt 仅支持一~十二（单字）；"三十分"等组合不在支持范围，此处只测文档支持的形态
        val r = TimeExtractor.extractAll("八点 到达", date, zone)
        assertEquals(listOf(fmt(8, 0)), times(r))
    }

    @Test fun `中文数字 下午三点 period偏移`() {
        val r = TimeExtractor.extractAll("下午三点 到达", date, zone)
        assertEquals(1, r.size)   // pattern3/4 均命中 15:00，按时间值去重后仅 1 个
        assertEquals(fmt(15, 0), t(r[0]))
    }

    @Test fun `无时间返回空`() {
        assertTrue(TimeExtractor.extractAll("此行为说明文字，无时间", date, zone).isEmpty())
    }

    @Test fun `多时间点按出现顺序而非按时分排序`() {
        val r = TimeExtractor.extractAll("10时00分 接报，10时15分到达现场，处理完毕", date, zone)
        assertEquals(listOf(fmt(10, 0), fmt(10, 15)), times(r))
    }

    // ---- 探针测试：时间提取缺陷回归（先 RED 后 GREEN）----

    @Test fun `八点半只解析出一个时间点0830`() {
        val r = TimeExtractor.extractAll("八点半 到达", date, zone)
        assertEquals(1, r.size, "X点半不得被拆成 八点+半 两个伪时间点")
        assertEquals(fmt(8, 30), t(r[0]))
    }

    @Test fun `中文组合分钟三十分解析为30`() {
        val r = TimeExtractor.extractAll("八点三十分 到达", date, zone)
        assertEquals(listOf(fmt(8, 30)), times(r), "中文组合分钟(三十=30)不得归零")
    }

    // ---- 扩展：修复后新覆盖的组合时间格式 ----

    @Test fun `阿拉伯数字点半`() {
        val r = TimeExtractor.extractAll("8点半 到达", date, zone)
        assertEquals(listOf(fmt(8, 30)), times(r))
    }

    @Test fun `下午三点半单点1530`() {
        val r = TimeExtractor.extractAll("下午三点半 到达", date, zone)
        assertEquals(1, r.size)
        assertEquals(fmt(15, 30), t(r[0]))
    }

    @Test fun `中文组合分钟十分`() {
        val r = TimeExtractor.extractAll("八点十分 到达", date, zone)
        assertEquals(listOf(fmt(8, 10)), times(r))
    }

    @Test fun `中文组合分钟二十分`() {
        val r = TimeExtractor.extractAll("十一点二十分 到达", date, zone)
        assertEquals(listOf(fmt(11, 20)), times(r))
    }
}
