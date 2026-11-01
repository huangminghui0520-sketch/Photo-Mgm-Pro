// 测试夹具：生成真实可解码的 JPEG，并可选写入 EXIF（拍摄时间 + GPS）
//
// 为什么自己造 EXIF 而不是放一张真实照片当 fixture：
//   ① 仓库不引入二进制大文件；② 用例要精确控制"有/无 GPS""时间偏移""HEIC 无 EXIF"等边界；
//   ③ 造出来的 APP1 段是标准 TIFF/EXIF 结构，能被 metadata-extractor 正常解析 → 测的是真链路。
package com.photomgm.desktop.core

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

object TestImages {

    /** 写一张普通 JPEG（无 EXIF）。 */
    fun writeJpeg(path: Path, width: Int = 800, height: Int = 600, color: Color = Color(200, 30, 30)) {
        Files.createDirectories(path.parent)
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = color
        g.fillRect(0, 0, width, height)
        g.color = Color.WHITE
        g.fillOval(width / 4, height / 4, width / 2, height / 2)
        g.dispose()
        Files.newOutputStream(path).use { ImageIO.write(img, "jpg", it) }
    }

    /** 写一张带 EXIF 的 JPEG：拍摄时间 + GPS（十进制度）。 */
    fun writeJpegWithExif(
        path: Path,
        dateTime: String = "2026:08:14 10:15:30",
        lat: Double = 30.25,
        lng: Double = 120.5,
        width: Int = 800,
        height: Int = 600,
        color: Color = Color(30, 120, 200),
    ) {
        Files.createDirectories(path.parent)
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = color
        g.fillRect(0, 0, width, height)
        g.color = Color.WHITE
        g.fillRect(width / 3, height / 3, width / 4, height / 4)
        g.dispose()
        val baos = ByteArrayOutputStream()
        ImageIO.write(img, "jpg", baos)
        val jpeg = baos.toByteArray()

        val app1 = buildApp1(dateTime, lat, lng)
        Files.newOutputStream(path).use { out ->
            out.write(jpeg, 0, 2)              // SOI (FFD8)
            out.write(app1)                    // APP1 段（插在 SOI 之后，符合 JPEG 规范）
            out.write(jpeg, 2, jpeg.size - 2)  // 其余原始段
        }
    }

    /**
     * 构造 APP1（Exif）段：`FFE1 + 长度(2) + "Exif\0\0" + TIFF`。
     *
     * TIFF 内布局（小端，偏移量在注释里写死，便于核对）：
     *   0   TIFF header（8B）
     *   8   IFD0（30B：2 + 2×12 + 4）  → ExifIFD 指针=38，GPS IFD 指针=56
     *   38  ExifIFD（18B：2 + 12 + 4） → DateTimeOriginal 字符串偏移=110
     *   56  GPS IFD（54B：2 + 4×12 + 4）→ 纬度有理数=130，经度有理数=154
     *   110 时间字符串（20B）
     *   130 纬度（3×RATIONAL=24B）
     *   154 经度（3×RATIONAL=24B）
     */
    private fun buildApp1(dateTime: String, lat: Double, lng: Double): ByteArray {
        val tiff = ByteBuffer.allocate(178).order(ByteOrder.LITTLE_ENDIAN)

        // TIFF header
        tiff.put('I'.code.toByte()).put('I'.code.toByte())
        tiff.putShort(42)
        tiff.putInt(8)

        // IFD0：两个指针项
        tiff.putShort(2)
        tiff.putShort(0x8769.toShort()); tiff.putShort(4); tiff.putInt(1); tiff.putInt(38) // ExifIFDPointer
        tiff.putShort(0x8825.toShort()); tiff.putShort(4); tiff.putInt(1); tiff.putInt(56) // GPSInfoIFDPointer
        tiff.putInt(0)

        // ExifIFD：DateTimeOriginal（ASCII，20 字节，含结尾 NUL）
        tiff.putShort(1)
        tiff.putShort(0x9003.toShort()); tiff.putShort(2); tiff.putInt(20); tiff.putInt(110)
        tiff.putInt(0)

        // GPS IFD：4 项
        tiff.putShort(4)
        // 纬度参考（ASCII 2 字节，内联："N\0"）
        tiff.putShort(1); tiff.putShort(2); tiff.putInt(2)
        tiff.put('N'.code.toByte()).put(0).put(0).put(0)
        // 纬度 RATIONAL×3 → 偏移 130
        tiff.putShort(2); tiff.putShort(5); tiff.putInt(3); tiff.putInt(130)
        // 经度参考（"E\0"）
        tiff.putShort(3); tiff.putShort(2); tiff.putInt(2)
        tiff.put('E'.code.toByte()).put(0).put(0).put(0)
        // 经度 RATIONAL×3 → 偏移 154
        tiff.putShort(4); tiff.putShort(5); tiff.putInt(3); tiff.putInt(154)
        tiff.putInt(0)

        // 数据区：时间字符串 20 字节（19 + NUL）
        val dt = dateTime.toByteArray(Charsets.US_ASCII)
        tiff.put(dt)
        for (i in dt.size until 20) tiff.put(0)

        putDms(tiff, lat)
        putDms(tiff, lng)

        val tiffBytes = tiff.array()
        val payload = ByteArray(6 + tiffBytes.size)
        payload[0] = 'E'.code.toByte()
        payload[1] = 'x'.code.toByte()
        payload[2] = 'i'.code.toByte()
        payload[3] = 'f'.code.toByte()
        payload[4] = 0
        payload[5] = 0
        System.arraycopy(tiffBytes, 0, payload, 6, tiffBytes.size)

        val segment = ByteBuffer.allocate(4 + payload.size).order(ByteOrder.BIG_ENDIAN)
        segment.put(0xFF.toByte()).put(0xE1.toByte())
        segment.putShort((payload.size + 2).toShort())
        segment.put(payload)
        return segment.array()
    }

    /** 十进制度 → (度, 分, 秒) 三组 RATIONAL，各 8 字节。 */
    private fun putDms(buf: ByteBuffer, decimal: Double) {
        val abs = abs(decimal)
        val deg = floor(abs).toInt()
        val minutesFull = (abs - deg) * 60.0
        val min = floor(minutesFull).toInt()
        val secTimes10000 = ((minutesFull - min) * 60.0 * 10000.0).roundToInt()

        buf.putInt(deg); buf.putInt(1)
        buf.putInt(min); buf.putInt(1)
        buf.putInt(secTimes10000); buf.putInt(10000)
    }
}
