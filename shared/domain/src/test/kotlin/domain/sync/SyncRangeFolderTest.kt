// shared/domain/sync/SyncRangeFolderTest.kt —— 区间命名与校验（纯 JVM 单测）
//
// V1/V2/V4/V5/V6 来自验收表；补零、秒截断、31 天边界、确定性是命名规则的四个硬约束。
// 断言全部锁「逐字字符串」，不用 contains：命名要落盘成文件夹名，一个字符都不能漂。
package domain.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime

class SyncRangeFolderTest {

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0): LocalDateTime =
        LocalDateTime.of(y, mo, d, h, mi, s)

    // ── V1 默认区间 ──────────────────────────────────────────────────────

    @Test
    fun `V1 巡查日1105默认是0000到2359且命名为05_0000__05_2359`() {
        val (start, end) = SyncRangeFolder.defaultRange(LocalDate.of(2026, 11, 5))
        assertEquals(at(2026, 11, 5, 0, 0), start)
        assertEquals(at(2026, 11, 5, 23, 59), end)
        assertEquals("05_0000__05_2359", SyncRangeFolder.folderName(start, end))
        assertNull(SyncRangeFolder.validateRange(start, end))
    }

    // ── V2 / V4 跨日 ─────────────────────────────────────────────────────

    @Test
    fun `V2 开始提前到1104的2300命名为04_2300__05_2359`() {
        val name = SyncRangeFolder.folderName(at(2026, 11, 4, 23, 0), at(2026, 11, 5, 23, 59))
        assertEquals("04_2300__05_2359", name)
    }

    @Test
    fun `V4 结束延后到1106的0200命名为04_2300__06_0200`() {
        val name = SyncRangeFolder.folderName(at(2026, 11, 4, 23, 0), at(2026, 11, 6, 2, 0))
        assertEquals("04_2300__06_0200", name)
        assertNull(SyncRangeFolder.validateRange(at(2026, 11, 4, 23, 0), at(2026, 11, 6, 2, 0)))
    }

    // ── V6 跨年 ──────────────────────────────────────────────────────────

    @Test
    fun `V6 跨年1231的2200到0101的0200命名为31_2200__01_0200且不报错`() {
        val start = at(2026, 12, 31, 22, 0)
        val end = at(2027, 1, 1, 2, 0)
        assertEquals("31_2200__01_0200", SyncRangeFolder.folderName(start, end))
        assertNull(SyncRangeFolder.validateRange(start, end))
    }

    @Test
    fun `跨年同名不写年份且由完整LocalDateTime判断先后`() {
        val name = SyncRangeFolder.folderName(at(2026, 12, 31, 22, 0), at(2027, 1, 1, 2, 0))
        assertFalse(name.contains("2026"))
        assertFalse(name.contains("2027"))
        // 文件夹名看着像倒序（31 → 01），但完整时间是合法递增的
        assertNull(SyncRangeFolder.validateRange(at(2026, 12, 31, 22, 0), at(2027, 1, 1, 2, 0)))
    }

    // ── 补零 ─────────────────────────────────────────────────────────────

    @Test
    fun `月日时分全部两位补零`() {
        assertEquals(
            "01_0000__02_0102",
            SyncRangeFolder.folderName(at(2026, 1, 1, 0, 0), at(2026, 1, 2, 1, 2)),
        )
        assertEquals(
            "09_0708__09_0709",
            SyncRangeFolder.folderName(at(2026, 9, 9, 7, 8), at(2026, 9, 9, 7, 9)),
        )
    }

    @Test
    fun `月不参与命名同月不同日与不同月同日都按日区分`() {
        val nov = SyncRangeFolder.folderName(at(2026, 11, 4, 23, 0), at(2026, 11, 5, 23, 59))
        val dec = SyncRangeFolder.folderName(at(2026, 12, 4, 23, 0), at(2026, 12, 5, 23, 59))
        assertEquals("04_2300__05_2359", nov)
        assertEquals(nov, dec)
    }

    // ── 秒截断 ───────────────────────────────────────────────────────────

    @Test
    fun `秒与纳秒不参与命名同分钟视为同一文件夹`() {
        val base = SyncRangeFolder.folderName(at(2026, 11, 4, 23, 0, 0), at(2026, 11, 5, 23, 59, 0))
        val withSeconds = SyncRangeFolder.folderName(at(2026, 11, 4, 23, 0, 45), at(2026, 11, 5, 23, 59, 59))
        val withNanos = SyncRangeFolder.folderName(
            at(2026, 11, 4, 23, 0, 0).withNano(123_456_789),
            at(2026, 11, 5, 23, 59, 59).withNano(999_999_999),
        )
        assertEquals("04_2300__05_2359", base)
        assertEquals(base, withSeconds)
        assertEquals(base, withNanos)
    }

    // ── V5 与边界校验 ────────────────────────────────────────────────────

    @Test
    fun `V5 开始晚于结束或与结束相等都被拒绝`() {
        assertEquals(
            "开始时间必须早于结束时间",
            SyncRangeFolder.validateRange(at(2026, 11, 5, 12, 0), at(2026, 11, 5, 10, 0)),
        )
        assertEquals(
            "开始时间必须早于结束时间",
            SyncRangeFolder.validateRange(at(2026, 11, 5, 12, 0), at(2026, 11, 5, 12, 0)),
        )
    }

    @Test
    fun `起止为空被拒绝`() {
        val end = at(2026, 11, 5, 23, 59)
        val start = at(2026, 11, 5, 0, 0)
        assertNotNull(SyncRangeFolder.validateRange(null, end))
        assertNotNull(SyncRangeFolder.validateRange(start, null))
        assertNotNull(SyncRangeFolder.validateRange(null, null))
        assertEquals("请填写完整的开始与结束时间", SyncRangeFolder.validateRange(null, end))
    }

    @Test
    fun `31天边界恰好31天可提交超过31天被拒绝`() {
        val start = at(2026, 11, 1, 0, 0)
        assertNull(SyncRangeFolder.validateRange(start, start.plusDays(31)))
        assertEquals("时间区间过长", SyncRangeFolder.validateRange(start, start.plusDays(31).plusMinutes(1)))
        assertEquals("时间区间过长", SyncRangeFolder.validateRange(start, start.plusDays(32)))
    }

    @Test
    fun `跨度上限可配置`() {
        val start = at(2026, 11, 1, 0, 0)
        assertNull(SyncRangeFolder.validateRange(start, start.plusDays(3), maxSpanDays = 3))
        assertEquals("时间区间过长", SyncRangeFolder.validateRange(start, start.plusDays(4), maxSpanDays = 3))
    }

    @Test
    fun `确定性同区间重复调用与换年份结果一致`() {
        val a = SyncRangeFolder.folderName(at(2026, 11, 4, 23, 0), at(2026, 11, 5, 23, 59))
        val b = SyncRangeFolder.folderName(at(2026, 11, 4, 23, 0), at(2026, 11, 5, 23, 59))
        val c = SyncRangeFolder.folderName(at(2025, 11, 4, 23, 0), at(2025, 11, 5, 23, 59))
        assertEquals(a, b)
        assertEquals(a, c)
        assertEquals("04_2300__05_2359", a)
    }
}
