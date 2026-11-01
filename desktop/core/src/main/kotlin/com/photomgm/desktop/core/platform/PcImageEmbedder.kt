// desktop/core/platform/PcImageEmbedder.kt —— 台账嵌图（Android Bitmap 链路的桌面替代）
//
// 与 Android 端 app/ImageUtils 的规格必须**逐参数一致**（否则同一批数据两端"嵌了几张图"不同 → R8）：
//   4:3 中心裁剪 → 缩放 400×300 → JPEG 质量 50%
// 裁剪几何复用核心 algorithm.ImageCrop（纯数学、可单测），不在桌面端重写一份。
//
// ★ 内存策略：先读图片头拿尺寸，再按 2 的幂次降采样解码（setSourceSubsampling），
//   禁止整图解码——手机照常见 4000×3000，整图解码每张约 48MB，几十张就 OOM。
package com.photomgm.desktop.core.platform

import algorithm.ImageCrop
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Paths
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

internal class PcImageEmbedder(
    private val targetWidth: Int = DEFAULT_WIDTH,
    private val targetHeight: Int = DEFAULT_HEIGHT,
    private val jpegQuality: Float = DEFAULT_QUALITY,
) {

    /**
     * 读取 sourceRef 并产出用于嵌入台账的 JPEG 字节；任何环节失败返回 null
     * （调用方按"该单元格只写文件名"处理，绝不产出损坏字节 —— 与 Android 端一致）。
     */
    fun embed(sourceRef: String?): ByteArray? {
        if (sourceRef.isNullOrBlank()) return null
        val img = decodeSubsampled(sourceRef, targetWidth * 2, targetHeight * 2) ?: return null
        return runCatching {
            val crop = ImageCrop.centerCrop4to3(img.width, img.height) ?: return null
            val scaled = scaleToTarget(img, crop.left, crop.top, crop.width, crop.height)
            writeJpeg(scaled, jpegQuality)
        }.getOrNull()
    }

    /**
     * 供未来 UI 使用的缩略图（不参与业务逻辑；UI 接入时可直接用，也可自行实现）。
     * 返回解码后的 BufferedImage，最长边 ≤ [maxEdge]。
     */
    fun readThumbnail(sourceRef: String, maxEdge: Int = 256): BufferedImage? =
        decodeSubsampled(sourceRef, maxEdge, maxEdge)

    // ── 内部实现 ─────────────────────────────────────────────────────────

    private fun decodeSubsampled(sourceRef: String, minW: Int, minH: Int): BufferedImage? {
        val path = runCatching { Paths.get(PcPaths.normalize(sourceRef)) }.getOrNull() ?: return null
        if (!Files.isRegularFile(path)) return null
        return runCatching {
            ImageIO.createImageInputStream(path.toFile()).use { iis ->
                if (iis == null) return null
                val readers = ImageIO.getImageReaders(iis)
                if (!readers.hasNext()) return null
                val reader = readers.next()
                try {
                    reader.setInput(iis, true, true)
                    val w = reader.getWidth(0)
                    val h = reader.getHeight(0)
                    if (w <= 0 || h <= 0) return null
                    var sub = 1
                    // 保证解码后仍不小于目标尺寸的 2 倍，避免放大失真又尽量少占内存
                    while (w / (sub * 2) >= minW && h / (sub * 2) >= minH) sub *= 2
                    val param = reader.defaultReadParam
                    if (sub > 1) param.setSourceSubsampling(sub, sub, 0, 0)
                    reader.read(0, param)
                } finally {
                    reader.dispose()
                }
            }
        }.getOrNull()
    }

    private fun scaleToTarget(src: BufferedImage, x: Int, y: Int, w: Int, h: Int): BufferedImage {
        val out = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            // 用整型宽高填充底色，避免 JPEG 无 alpha 时出现黑边
            g.color = java.awt.Color.WHITE
            g.fillRect(0, 0, targetWidth, targetHeight)
            g.drawImage(src, 0, 0, targetWidth, targetHeight, x, y, x + w, y + h, null)
        } finally {
            g.dispose()
        }
        return out
    }

    private fun writeJpeg(image: BufferedImage, quality: Float): ByteArray {
        val writers = ImageIO.getImageWritersByFormatName("jpeg")
        require(writers.hasNext()) { "当前 JRE 无 JPEG 编码器" }
        val writer = writers.next()
        return try {
            val param = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = quality
            }
            val bytes = ByteArrayOutputStream(64 * 1024)
            ImageIO.createImageOutputStream(bytes).use { ios ->
                writer.output = ios
                writer.write(null, IIOImage(image, null, null), param)
            }
            bytes.toByteArray()
        } finally {
            writer.dispose()
        }
    }

    companion object {
        /** 目标宽（px）：导出 Excel 2.0 方案固定值，与 Android 端 ImageUtils.TARGET_WIDTH 一致。 */
        const val DEFAULT_WIDTH = 400

        /** 目标高 = 400 × 3/4。 */
        const val DEFAULT_HEIGHT = 300

        /** JPEG 质量 50%。 */
        const val DEFAULT_QUALITY = 0.5f
    }
}
