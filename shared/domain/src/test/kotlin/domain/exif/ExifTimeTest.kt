// shared/domain/exif/ExifTimeTest.kt —— EXIF 时间解析（纯 JVM 单测，T03-C1）
//
// 前 5 条从安卓 `data/ExifTimeParserTest` **原样迁来**（断言一行未改，只换类名与 import）：
// 它们是"换实现不能换行为"的锚点。后面 4 类是本轮新增，每条都对应文件头表格里的一个能力或一条约束。
package domain.exif

import algorithm.AppZone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class ExifTimeTest {
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    // ────────────────────── 迁自安卓 ExifTimeParserTest（5 条，断言不变）──────────────────────

    @Test
    fun `冒号格式解析`() {
        assertEquals(at(2026, 9, 3, 8, 30), ExifTime.parse("2026:09:03 08:30:00", zone))
    }

    @Test
    fun `横线格式解析`() {
        assertEquals(at(2026, 9, 3, 8, 30), ExifTime.parse("2026-09-03 08:30:00", zone))
    }

    @Test
    fun `跨日日期正确`() {
        assertEquals(at(2026, 9, 4, 0, 5), ExifTime.parse("2026:09:04 00:05:00", zone))
    }

    @Test
    fun `非法时间返回null`() {
        assertNull(ExifTime.parse("2026:13:99 99:99:99", zone))
    }

    @Test
    fun `空串返回null`() {
        assertNull(ExifTime.parse("", zone))
        assertNull(ExifTime.parse("   ", zone))
    }

    // ────────────────────────────── 新增 4 类 ──────────────────────────────

    /**
     * ① 超长（>19 字符）必须解析成功 —— 这是 **PC 端独有能力**（`substring(0,19)` 截断）。
     * 安卓旧实现拿整串去 parse，`.123` 会让它失败。只搬安卓的不截断实现，PC 原来能读的照片会集体变成"无时间"。
     */
    @Test
    fun `带毫秒尾巴也能解析（PC 独有能力）`() {
        assertEquals(at(2026, 9, 3, 8, 30), ExifTime.parse("2026:09:03 08:30:00.123", zone))
        assertEquals(at(2026, 9, 3, 8, 30), ExifTime.parse("2026-09-03 08:30:00.123", zone))
        // 毫秒不进结果：EXIF 秒级时间戳，毫秒部分一律丢弃（保留会让同一张照片的 epoch 随写入方式变化）
        assertEquals(
            ExifTime.parse("2026:09:03 08:30:00", zone),
            ExifTime.parse("2026:09:03 08:30:00.999", zone),
        )
    }

    /**
     * ② 带时区尾巴：**解析成功，但忽略尾巴**，仍按业务时区解释。
     *
     * ★ 断言用的是 `+00:00` 而不是 `+08:00`：如果实现"按偏移换算"，`+00:00` 会算出 16:30 北京时间，
     *   与期望值差 8 小时 —— 这样这条用例才真的能区分"忽略"与"支持"。用 `+08:00` 的话两种实现结果相同，
     *   属于假断言。
     */
    @Test
    fun `带时区尾巴时忽略偏移按业务时区解释`() {
        val parsed = ExifTime.parse("2026:09:03 08:30:00+00:00", zone)
        // 按业务时区解释 → 08:30 北京时间（= 00:30Z）
        assertEquals(Instant.parse("2026-09-03T00:30:00Z").toEpochMilli(), parsed)
        assertEquals(at(2026, 9, 3, 8, 30), parsed)
        // 若实现"按偏移换算"，会得到 08:30Z（= 16:30 北京）。这条显式排除那种实现。
        assertNotEquals(Instant.parse("2026-09-03T08:30:00Z").toEpochMilli(), parsed)
    }

    /** ③ 长度不足 19 → null（PC 端显式守卫；安卓旧实现靠异常兜底，行为相同但意图没写下来）。 */
    @Test
    fun `长度不足19返回null`() {
        assertNull(ExifTime.parse("2026:09:03", zone))
        assertNull(ExifTime.parse("2026:09:03 08:30", zone))
        assertNull(ExifTime.parse("2026-09-03 08:3", zone))
    }

    /**
     * ④ 混合批次：8 张短横 + 59 张冒号同批解析，全部成功且 epoch 严格递增。
     *
     * 为什么是 8/59：黄金样本实测分布 `colon 59 / dash 8 / none 2`（69 张）。
     * 单条用例覆盖不了"两种格式混在同一批里"——循环里的格式判定一旦写错（比如按位置 13 判），
     * 单条测试会全绿，混合批次会立刻暴露。
     */
    @Test
    fun `混合批次8张短横59张冒号全部解析且严格递增`() {
        val base = LocalDateTime.of(2026, 9, 3, 8, 0)
        // 短横只放在这 8 个下标上（1,9,…,57）：写 `i % 8 == 1` 会在 67 条里命中 9 个（到 65），
        // 这条用例自己就翻过车 —— 显式列出来，别用取模的边界去赌。
        val dashAt = (0 until 8).map { 1 + it * 8 }.toSet()
        val texts = (0 until 67).map { i ->
            val t = base.plusMinutes(i.toLong())
            if (i in dashAt) {
                String.format("%04d-%02d-%02d %02d:%02d:00", t.year, t.monthValue, t.dayOfMonth, t.hour, t.minute)
            } else {
                String.format("%04d:%02d:%02d %02d:%02d:00", t.year, t.monthValue, t.dayOfMonth, t.hour, t.minute)
            }
        }
        assertEquals(8, texts.count { it[4] == '-' }, "短横样本条数")
        assertEquals(59, texts.count { it[4] == ':' }, "冒号样本条数")

        val epochs = texts.map { ExifTime.parse(it, zone) }
        epochs.forEachIndexed { i, e -> assertNotNull(e, "第 $i 条解析失败：${texts[i]}") }
        val ms = epochs.map { it!! }
        for (i in 1 until ms.size) {
            assertTrue(ms[i] > ms[i - 1], "第 $i 条未严格递增：${texts[i - 1]} → ${texts[i]}")
        }
    }

    /** 附加：默认时区必须是业务时区（不是设备默认时区），且首尾空白被裁掉。 */
    @Test
    fun `默认时区为业务时区且容忍首尾空白`() {
        assertEquals(
            ExifTime.parse("2026:09:03 08:30:00", AppZone.ID),
            ExifTime.parse("2026:09:03 08:30:00"),
        )
        assertEquals(at(2026, 9, 3, 8, 30), ExifTime.parse("  2026:09:03 08:30:00  ", zone))
    }
}
