// algorithm/TimeZoneExplicitTest.kt —— issue #2：业务时区必须显式、且与设备时区无关
//
// 覆盖的契约（对应 issue #2 验收标准）：
//   1. 唯一业务时区 = Asia/Shanghai（AppZone.ID）；
//   2. 日志解析把「本地挂钟时间」→ 绝对时刻，默认就按北京时间，**不跟随设备默认时区**；
//   3. 北京 00:00 跨日边界：当日 00:05 属于当日，不是前一日 16:05；
//   4. 台账日期列（= 日期分组的落地形式）按北京时间渲染，跨日事件落到次日；
//   5. 解析与归类必须用同一个 ZoneId —— 用错会让凌晨事件的照片掉进未匹配池。
//
// ★ 第 2 条用 TimeZone.setDefault 在进程内切换默认时区来验证，不依赖 runner 的 TZ 环境变量：
//   CI 的 TZ=Asia/Shanghai 只是兜底，不能作为"正确性"的依据（issue #2 决策）。
package algorithm

import algorithm.model.Photo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

class TimeZoneExplicitTest {

    private val sh: ZoneId = ZoneId.of("Asia/Shanghai")
    private val date: LocalDate = LocalDate.of(2026, 9, 3)

    /** 北京时间的某一时刻 → epoch 毫秒（测试侧独立算式，不复用生产代码）。 */
    private fun shMs(d: Int, h: Int, m: Int): Long =
        LocalDate.of(2026, 9, d).atTime(h, m).atZone(sh).toInstant().toEpochMilli()

    /** 在指定默认时区下执行，结束后恢复（避免污染同 JVM 的其他用例）。 */
    private fun <T> withDefaultTimeZone(tz: String, block: () -> T): T {
        val old = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(tz))
        try {
            return block()
        } finally {
            TimeZone.setDefault(old)
        }
    }

    @Test
    fun `业务时区必须是北京时间`() {
        assertEquals(sh, AppZone.ID, "issue #2 决策：展示与日期分组固定 ZoneId.of(\"Asia/Shanghai\")")
        assertEquals("Asia/Shanghai", AppZone.ID.id)
    }

    @Test
    fun `日志解析默认时区就是北京时间_不随设备默认时区漂移`() {
        val text = "08时00分 出发巡查"
        val expected = shMs(3, 8, 0)

        // 设备在 UTC
        val utc = withDefaultTimeZone("UTC") {
            AlgorithmApi.parseLog(text, date).events.first().startTimeMs
        }
        // 设备在纽约（UTC-4/-5）
        val ny = withDefaultTimeZone("America/New_York") {
            AlgorithmApi.parseLog(text, date).events.first().startTimeMs
        }
        // 设备在北京
        val bj = withDefaultTimeZone("Asia/Shanghai") {
            AlgorithmApi.parseLog(text, date).events.first().startTimeMs
        }

        assertEquals(expected, utc, "设备在 UTC 时也必须按北京时间解释日志（旧实现会差 8 小时）")
        assertEquals(expected, ny, "设备在纽约时也必须按北京时间解释日志")
        assertEquals(expected, bj)
        // ★ 区分度哨兵：旧实现（默认 systemDefault）在 UTC 设备上会把 08:00 解释成
        //   2026-09-03T08:00Z。断言实际结果**不等于**这个值，保证本用例对"默认值改回
        //   systemDefault()"这类回归真的会变红，而不是碰巧通过。
        val legacyUtcInterpretation = date.atTime(8, 0).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()
        assertNotEquals(legacyUtcInterpretation, utc, "若默认参数退回 systemDefault()，UTC 设备上会命中此值")
    }

    @Test
    fun `北京零点跨日边界_当日00时05分属于当日而不是前一日`() {
        val parsed = AlgorithmApi.parseLog("00时05分 出发巡查", date, sh)
        val e = parsed.events.single()
        val start = requireNotNull(e.startTimeMs)

        // 北京 2026-09-03 00:05 == UTC 2026-09-02 16:05
        assertEquals(Instant.parse("2026-09-02T16:05:00Z").toEpochMilli(), start)
        assertEquals(shMs(3, 0, 5), start)
        // 落回北京时间必须还是 3 日（不是 2 日）
        assertEquals(3, Instant.ofEpochMilli(start).atZone(sh).dayOfMonth)
        // 旧实现（UTC 解释）会把它算成 2026-09-03T00:05Z，比正确值晚 8 小时 —— 证明用例有区分度
        assertNotEquals(Instant.parse("2026-09-03T00:05:00Z").toEpochMilli(), start)
    }

    @Test
    fun `台账日期列按北京时间渲染_含凌晨与跨日事件`() {
        // ★ 整段跑在「设备默认时区 = UTC」下：这样一旦有人让 LedgerEngine 忽略传入的 zone
        //   而退回 systemDefault()，下面每条断言都会红。用 TimeZone.setDefault 而不是 TZ 环境变量，
        //   因为 Windows 上 JVM 不读 TZ，靠环境变量做的验证在本机是假绿（实测确认过）。
        withDefaultTimeZone("UTC") {
            // 跨日：同一行里「23:50 → 01:10」时分回绕，解析层会把后者 +1 天（次日 01:10）
            val wrap = AlgorithmApi.parseLog("23时50分 接报K150处事故，01时10分处理完毕", date, sh)
            val we = wrap.events.single()
            assertEquals(shMs(3, 23, 50), we.startTimeMs)
            assertEquals(shMs(4, 1, 10), we.endTimeMs, "跨日时间点必须落到次日（北京时区）")

            val wrapRow = AlgorithmApi.buildLedger(wrap.events, emptyMap(), { null }, namingTemplate = 1, zone = sh).single()
            assertEquals("2026-09-03 23:50", wrapRow.dateTime, "日期列 = 事件开始时刻的北京日期")
            assertEquals("2026-09-04 01:10", wrapRow.endTime,
                "跨日事件的结束时间必须渲染成次日 01:10 —— 这是「日期分组」的落地形式")

            // 凌晨单点事件：日期列按北京时间落在当日，结束时间留空（起止相同）
            val early = AlgorithmApi.parseLog("00时05分 出发巡查", date, sh)
            val earlyRow = AlgorithmApi.buildLedger(early.events, emptyMap(), { null }, namingTemplate = 1, zone = sh).single()
            assertEquals("2026-09-03 00:05", earlyRow.dateTime,
                "北京 00:05 必须归到 3 日；若时区退化成 UTC 会被渲染成 08:05")
            assertEquals("", earlyRow.endTime)
        }
    }

    @Test
    fun `解析与归类必须用同一时区_否则凌晨事件的照片会掉进未匹配池`() {
        val parsed = AlgorithmApi.parseLog("00时05分 出发巡查", date, sh)
        val e = parsed.events.single()
        val photo = Photo(
            id = 1L,
            sourceRef = "/sdcard/DCIM/a.jpg",
            displayName = "a.jpg",
            captureTimeMs = e.startTimeMs,   // 与日志同一时刻
            latitude = 23.0,
            longitude = 113.0,
        )

        val same = AlgorithmApi.classify(parsed.events, emptyList(), listOf(photo), date, zone = sh)
        assertEquals(listOf(1L), same.eventPhotoMap[e.id], "同一时区：照片归该事件")

        // ★ 错配：解析按北京、归类按 UTC → UTC 的"当日零点"= 北京 08:00，
        //   凌晨 00:05 的照片早于它 → findWindow 直接 return null → 未匹配。
        //   这条断言就是"两处必须传同一个 zone"的可执行证明。
        val mismatched = AlgorithmApi.classify(
            parsed.events, emptyList(), listOf(photo), date, zone = ZoneId.of("UTC"),
        )
        assertEquals(listOf(1L), mismatched.unmatched,
            "解析/归类时区不一致时，凌晨照片会被判为未匹配 —— 这正是 issue #2 要防的错")
        assertEquals(emptyList<Long>(), mismatched.eventPhotoMap[e.id] ?: emptyList<Long>())
    }
}
