// EXIF 读取单测：时间/GPS 正确性、无 EXIF 降级、时区语义
package com.photomgm.desktop.core

import algorithm.AppZone
import com.photomgm.desktop.core.platform.PcExifReader
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PcExifReaderTest {

    @TempDir
    lateinit var tmp: Path

    private val reader = PcExifReader()

    @Test
    fun `读取拍摄时间与 GPS`() {
        val file = tmp.resolve("IMG_20260814_101530.jpg")
        TestImages.writeJpegWithExif(file, "2026:08:14 10:15:30", 30.25, 120.5)

        val (timeMs, gps) = reader.readAll(file.toString())

        val expected = LocalDateTime.of(2026, 8, 14, 10, 15, 30)
            .atZone(AppZone.ID).toInstant().toEpochMilli()
        assertEquals(expected, timeMs, "拍摄时间必须按业务时区解释")

        val p = assertNotNull(gps, "GPS 应可读出")
        assertTrue(abs(p.first - 30.25) < 0.0001, "纬度偏差过大：${p.first}")
        assertTrue(abs(p.second - 120.5) < 0.0001, "经度偏差过大：${p.second}")
    }

    @Test
    fun `时间按北京时间解释而非 UTC`() {
        val ms = assertNotNull(PcExifReader.parseExifDateTime("2026:08:14 10:15:30", AppZone.ID))
        val utcMs = assertNotNull(PcExifReader.parseExifDateTime("2026:08:14 10:15:30", ZoneId.of("UTC")))
        assertEquals(8 * 3600_000L, utcMs - ms, "北京时间应比 UTC 早 8 小时对应的 epoch 更小")
        assertEquals(
            "2026-08-14T10:15:30",
            Instant.ofEpochMilli(ms).atZone(AppZone.ID).toLocalDateTime().toString(),
        )
    }

    @Test
    fun `无 EXIF 的 JPEG 返回 null 且不抛异常`() {
        val file = tmp.resolve("plain.jpg")
        TestImages.writeJpeg(file)
        val (timeMs, gps) = reader.readAll(file.toString())
        assertNull(timeMs)
        assertNull(gps)
    }

    @Test
    fun `文件不存在返回 null`() {
        val (timeMs, gps) = reader.readAll(tmp.resolve("nope.jpg").toString())
        assertNull(timeMs)
        assertNull(gps)
    }

    @Test
    fun `非图片文件返回 null`() {
        val f = tmp.resolve("log.txt")
        java.nio.file.Files.writeString(f, "08时00分 出发")
        assertNull(reader.readCaptureTimeMs(f.toString()))
    }

    @Test
    fun `畸形时间字符串返回 null`() {
        assertNull(PcExifReader.parseExifDateTime("0000:00:00 00:00:00"))
        assertNull(PcExifReader.parseExifDateTime("abc"))
        assertNull(PcExifReader.parseExifDateTime(""))
    }
}
