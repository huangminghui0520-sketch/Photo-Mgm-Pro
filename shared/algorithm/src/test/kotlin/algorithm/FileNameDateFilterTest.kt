// FileNameDateFilter 单元测试（§4.2 / §9 测试要点）
package algorithm

import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileNameDateFilterTest {
    private val d = LocalDate.of(2026, 8, 14)

    @Test fun `IMG下划线格式`() {
        assertEquals(d, FileNameDateFilter.extractDate("IMG_20260814_101530.jpg"))
    }

    @Test fun `IMG无分隔格式`() {
        assertEquals(d, FileNameDateFilter.extractDate("IMG20260814101530.jpg"))
    }

    @Test fun `PANO全景格式`() {
        assertEquals(d, FileNameDateFilter.extractDate("PANO_20260814_101530.jpg"))
    }

    @Test fun `VID视频格式`() {
        assertEquals(d, FileNameDateFilter.extractDate("VID_20260814_101530.mp4"))
    }

    @Test fun `BURST连拍格式`() {
        assertEquals(d, FileNameDateFilter.extractDate("BURST20260814101530.jpg"))
    }

    @Test fun `截图排除`() {
        assertNull(FileNameDateFilter.extractDate("Screenshot_20260814-101530.png"))
    }

    @Test fun `微信导出排除`() {
        assertNull(FileNameDateFilter.extractDate("mmexport1690000000000.jpg"))
    }

    @Test fun `无日期返回null`() {
        assertNull(FileNameDateFilter.extractDate("abc.jpg"))
    }

    @Test fun `非法日期返回null`() {
        assertNull(FileNameDateFilter.extractDate("IMG_20261345_101530.jpg"))  // 13 月
        assertNull(FileNameDateFilter.extractDate("IMG_20260230_101530.jpg"))  // 2 月 30 日
    }

    @Test fun `日期区间闭区间`() {
        assertTrue(FileNameDateFilter.isInRange("IMG_20260814_101530.jpg", LocalDate.of(2026, 8, 14), LocalDate.of(2026, 8, 14)))
        assertTrue(FileNameDateFilter.isInRange("IMG_20260813_101530.jpg", LocalDate.of(2026, 8, 13), LocalDate.of(2026, 8, 14)))
        assertFalse(FileNameDateFilter.isInRange("IMG_20260812_101530.jpg", LocalDate.of(2026, 8, 13), LocalDate.of(2026, 8, 14)))
        assertFalse(FileNameDateFilter.isInRange("IMG_20260815_101530.jpg", LocalDate.of(2026, 8, 13), LocalDate.of(2026, 8, 14)))
    }
}
