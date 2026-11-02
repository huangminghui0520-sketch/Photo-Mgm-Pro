// ui/test/.../ThumbnailsTest.kt —— 缩略图解码（真实 JPEG 往返，不打桩）
//
// 这条链路只在"真读文件、真解码"时才会暴露问题（HEIC 无解码器、文件被删、超大图 OOM），
// 所以用例直接造一张真 JPEG 写盘再读回来，而不是 mock ImageIO。
package com.photomgm.desktop.ui.image

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThumbnailsTest {

    private fun writeJpeg(dir: File, name: String, w: Int, h: Int): File {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color(40, 120, 200)
        g.fillRect(0, 0, w, h)
        g.dispose()
        val file = File(dir, name)
        ImageIO.write(img, "jpg", file)
        return file
    }

    @Test
    fun `大图按目标边长缩下来且保持比例`() {
        val dir = createTempDir()
        try {
            val file = writeJpeg(dir, "big.jpg", 1200, 900)
            val image = assertNotNull(decodeScaled(file.absolutePath, 160))
            // 长边等于目标值，短边按比例（±2 px 允许四舍五入）
            assertEquals(160, image.width)
            assertTrue(image.height in 118..122, "height=${image.height}")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `比目标还小的图不放大`() {
        val dir = createTempDir()
        try {
            val file = writeJpeg(dir, "small.jpg", 120, 90)
            val image = assertNotNull(decodeScaled(file.absolutePath, 400))
            assertEquals(120, image.width)
            assertEquals(90, image.height)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `文件不存在或不是图片时返回 null 而不是抛异常`() {
        val dir = createTempDir()
        try {
            assertNull(decodeScaled(File(dir, "missing.jpg").absolutePath, 128))
            val txt = File(dir, "note.txt").apply { writeText("不是图片") }
            assertNull(decodeScaled(txt.absolutePath, 128))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `尺寸非法时不崩`() {
        val dir = createTempDir()
        try {
            val file = writeJpeg(dir, "a.jpg", 64, 64)
            assertNull(decodeScaled(file.absolutePath, 0))
        } finally {
            dir.deleteRecursively()
        }
    }
}

private fun createTempDir(): File = java.nio.file.Files.createTempDirectory("photomgm-thumb").toFile()
