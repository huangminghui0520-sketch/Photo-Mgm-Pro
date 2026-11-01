// 目录扫描单测：递归、扩展名过滤、隐藏目录跳过、路径规范化
package com.photomgm.desktop.core

import com.photomgm.desktop.core.platform.PcPhotoSourceProvider
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PcPhotoSourceProviderTest {

    @TempDir
    lateinit var tmp: Path

    @Test
    fun `递归扫描并过滤非图片`() {
        TestImages.writeJpeg(tmp.resolve("a.jpg"))
        TestImages.writeJpeg(tmp.resolve("sub/b.png"))
        TestImages.writeJpeg(tmp.resolve("sub/deep/c.jpeg"))
        Files.writeString(tmp.resolve("note.txt"), "x")
        Files.writeString(tmp.resolve("sub/log.log"), "x")
        TestImages.writeJpeg(tmp.resolve("d.heic"))

        val provider = PcPhotoSourceProvider()
        val list = provider.listCandidates(tmp.toString())

        assertEquals(4, list.size, "应只返回 4 张图片（含 .heic，不含 txt/log）")
        assertTrue(list.all { it.sourceRef.contains('/') && !it.sourceRef.contains('\\') })
        assertTrue(list.all { it.id == 0L }, "id 由核心统一分配，扫描层不猜")
        // 稳定排序：两次扫描顺序一致
        assertEquals(list.map { it.sourceRef }, provider.listCandidates(tmp.toString()).map { it.sourceRef })
    }

    @Test
    fun `跳过隐藏目录`() {
        TestImages.writeJpeg(tmp.resolve("ok.jpg"))
        TestImages.writeJpeg(tmp.resolve(".trash/x.jpg"))
        val list = PcPhotoSourceProvider().listCandidates(tmp.toString())
        assertEquals(1, list.size)
    }

    @Test
    fun `源目录不存在时返回空并记录警告`() {
        val provider = PcPhotoSourceProvider()
        val list = provider.listCandidates(tmp.resolve("not-exists").toString())
        assertTrue(list.isEmpty())
        assertTrue(provider.warnings.isNotEmpty())
    }

    @Test
    fun `HEIC 识别`() {
        assertTrue(PcPhotoSourceProvider.isHeic("IMG_001.HEIC"))
        assertTrue(PcPhotoSourceProvider.isHeic("a.heif"))
        assertTrue(!PcPhotoSourceProvider.isHeic("a.jpg"))
        assertTrue(PcPhotoSourceProvider.isSupportedImage("a.JPEG"))
        assertTrue(!PcPhotoSourceProvider.isSupportedImage("a.txt"))
        assertTrue(!PcPhotoSourceProvider.isSupportedImage("noext"))
    }
}
