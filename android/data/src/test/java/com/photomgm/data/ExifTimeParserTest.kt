// data/ExifTimeParserTest.kt —— EXIF 时间解析（纯 JVM 单测）
package com.photomgm.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ExifTimeParserTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    @Test fun `冒号格式解析`() {
        assertEquals(at(2026, 9, 3, 8, 30), ExifTimeParser.parse("2026:09:03 08:30:00", zone))
    }

    @Test fun `横线格式解析`() {
        assertEquals(at(2026, 9, 3, 8, 30), ExifTimeParser.parse("2026-09-03 08:30:00", zone))
    }

    @Test fun `跨日日期正确`() {
        assertEquals(at(2026, 9, 4, 0, 5), ExifTimeParser.parse("2026:09:04 00:05:00", zone))
    }

    @Test fun `非法时间返回null`() {
        assertNull(ExifTimeParser.parse("2026:13:99 99:99:99", zone))
    }

    @Test fun `空串返回null`() {
        assertNull(ExifTimeParser.parse("", zone))
        assertNull(ExifTimeParser.parse("   ", zone))
    }
}
