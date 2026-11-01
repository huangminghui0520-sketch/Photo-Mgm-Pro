// ui/dev/ExifJpeg.kt —— 演示用 JPEG 生成（带 EXIF 时间 + GPS）
//
// ★ 只在 --demo 下使用。为什么要造 EXIF：演示界面必须吃**真实 AppState**，
//   而真实 AppState 里的照片时间/GPS 只能来自 EXIF。若演示照片没有 EXIF，
//   core 会判定"全部无 GPS"，演示出来的就是一片"未匹配"，看不出复核页真正长什么样。
//   构造方式与 core 测试夹具同构（小端 TIFF + APP1 段），不引入任何依赖。
package com.photomgm.desktop.ui.dev

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

object ExifJpeg {

    fun write(
        path: Path,
        exifDateTime: String,
        lat: Double,
        lng: Double,
        color: Color,
        width: Int = 640,
        height: Int = 480,
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
        val app1 = app1(exifDateTime, lat, lng)

        Files.newOutputStream(path).use { out ->
            out.write(jpeg, 0, 2)
            out.write(app1)
            out.write(jpeg, 2, jpeg.size - 2)
        }
    }

    /** 写一张**没有 EXIF**的 JPEG：模拟"没写时间的补拍照片"。 */
    fun writePlain(path: Path, color: Color, width: Int = 640, height: Int = 480) {
        Files.createDirectories(path.parent)
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = color
        g.fillRect(0, 0, width, height)
        g.dispose()
        Files.newOutputStream(path).use { ImageIO.write(img, "jpg", it) }
    }

    private fun app1(dateTime: String, lat: Double, lng: Double): ByteArray {
        val tiff = ByteBuffer.allocate(178).order(ByteOrder.LITTLE_ENDIAN)
        tiff.put('I'.code.toByte()).put('I'.code.toByte())
        tiff.putShort(42)
        tiff.putInt(8)

        tiff.putShort(2)
        tiff.putShort(0x8769.toShort()); tiff.putShort(4); tiff.putInt(1); tiff.putInt(38)
        tiff.putShort(0x8825.toShort()); tiff.putShort(4); tiff.putInt(1); tiff.putInt(56)
        tiff.putInt(0)

        tiff.putShort(1)
        tiff.putShort(0x9003.toShort()); tiff.putShort(2); tiff.putInt(20); tiff.putInt(110)
        tiff.putInt(0)

        tiff.putShort(4)
        tiff.putShort(1); tiff.putShort(2); tiff.putInt(2)
        tiff.put('N'.code.toByte()).put(0).put(0).put(0)
        tiff.putShort(2); tiff.putShort(5); tiff.putInt(3); tiff.putInt(130)
        tiff.putShort(3); tiff.putShort(2); tiff.putInt(2)
        tiff.put('E'.code.toByte()).put(0).put(0).put(0)
        tiff.putShort(4); tiff.putShort(5); tiff.putInt(3); tiff.putInt(154)
        tiff.putInt(0)

        val dt = dateTime.toByteArray(Charsets.US_ASCII)
        tiff.put(dt)
        for (i in dt.size until 20) tiff.put(0)

        dms(tiff, lat)
        dms(tiff, lng)

        val tiffBytes = tiff.array()
        val payload = ByteArray(6 + tiffBytes.size)
        payload[0] = 'E'.code.toByte()
        payload[1] = 'x'.code.toByte()
        payload[2] = 'i'.code.toByte()
        payload[3] = 'f'.code.toByte()
        System.arraycopy(tiffBytes, 0, payload, 6, tiffBytes.size)

        val segment = ByteBuffer.allocate(4 + payload.size).order(ByteOrder.BIG_ENDIAN)
        segment.put(0xFF.toByte()).put(0xE1.toByte())
        segment.putShort((payload.size + 2).toShort())
        segment.put(payload)
        return segment.array()
    }

    private fun dms(buf: ByteBuffer, decimal: Double) {
        val abs = abs(decimal)
        val deg = floor(abs).toInt()
        val minutesFull = (abs - deg) * 60.0
        val min = floor(minutesFull).toInt()
        val sec = ((minutesFull - min) * 60.0 * 10000.0).roundToInt()
        buf.putInt(deg); buf.putInt(1)
        buf.putInt(min); buf.putInt(1)
        buf.putInt(sec); buf.putInt(10000)
    }
}
