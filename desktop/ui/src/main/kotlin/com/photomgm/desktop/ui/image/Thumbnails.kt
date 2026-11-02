// ui/image/Thumbnails.kt —— 缩略图/大图的解码与缓存（桌面端，不依赖 core 的 platform 层）
//
// ★ 为什么 UI 自己解码而不找 core 要字节：
//   「我该把这张显示成多大」是界面自己的事（72 dp 的卡片格和 1200 px 的大图不是同一件事），
//   让 core 按某个尺寸预先产字节，等于把界面的尺寸写进业务层 —— 换个布局就要改 core。
//   契约里说得很清楚：PhotoInfo 不含缩略图字节，UI 按 sourceRef 自行加载（见 core/api/AppState）。
//
// ★ 必须按需降采样解码，禁止整图 ImageIO.read：
//   手机照常见 4000×3000，整图解码一张就是 48 MB。一个事件的缩略条同时要显示 8 张，
//   而且 Ctrl 多选会把整条来回点好几遍 —— 整图解码几百毫秒一张，界面会卡成幻灯片，
//   堆内存也会被 2G 上限顶到 OOM。所以先读图片头拿尺寸，再按 2 的幂次
//   setSourceSubsampling 解码，最后缩到目标像素（与 core 台账嵌图同一套思路）。
//
// ★ 缓存按**字节预算**淘汰，不按条数：
//   一张 1200 px 的大图和一张 64 px 的缩略图差 350 倍，按"最近 100 条"淘汰会让大图把预算吃光。
//   这里用 LinkedHashMap(accessOrder = true) 做 LRU，累计像素字节数，超预算就从最久未用的开始丢。
//
// ★ 解不出来的（HEIC 暂无 ImageIO 解码器、文件被移走/删除）返回 null，
//   由调用方退回占位块 —— 缩略图失败绝不能挡住主流程，也绝不弹错误框。
package com.photomgm.desktop.ui.image

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** 缩略图解码并发上限：3 个线程足够填满一屏，又不会让 20 个格子同时抢 IO 把磁盘打满。 */
@OptIn(ExperimentalCoroutinesApi::class)
private val imageDispatcher = Dispatchers.IO.limitedParallelism(3)

/** 内存预算：约 96 MB（2G 堆的上限下，留足导出时 POI 的峰值空间）。 */
private const val CACHE_BUDGET_BYTES = 96L * 1024 * 1024

private object ThumbCache {
    private val map = object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>): Boolean = false
    }
    private var used = 0L

    @Synchronized
    fun get(key: String): ImageBitmap? = map[key]

    @Synchronized
    fun put(key: String, image: ImageBitmap) {
        map.put(key, image)?.let { used -= it.bytes() }
        used += image.bytes()
        val it = map.entries.iterator()
        while (used > CACHE_BUDGET_BYTES && it.hasNext()) {
            used -= it.next().value.bytes()
            it.remove()
        }
    }

    private fun ImageBitmap.bytes(): Long = width.toLong() * height * 4
}

private fun cacheKey(path: String, maxEdge: Int) = "$maxEdge|$path"

/**
 * 取一张照片的位图（最长边 ≤ [maxEdge] 像素）。
 *
 * 缓存命中时**同步**返回（第一帧就有图，不闪占位块）；未命中时先给 null，
 * 解码完成后自动触发一次重组。
 */
@Composable
fun rememberPhotoImage(path: String?, maxEdge: Int): ImageBitmap? {
    val key = path?.let { cacheKey(it, maxEdge) }
    return produceState(
        initialValue = key?.let { ThumbCache.get(it) },
        key,
        maxEdge,
    ) {
        if (path == null || key == null) {
            value = null
            return@produceState
        }
        ThumbCache.get(key)?.let {
            value = it
            return@produceState
        }
        value = withContext(imageDispatcher) { decodeScaled(path, maxEdge)?.also { ThumbCache.put(key, it) } }
    }.value
}

/**
 * 解码并缩放到最长边 ≤ [maxEdge]。任何环节失败都返回 null（调用方显示占位块）。
 *
 * 独立成非 Composable 的普通函数，便于单测直接调用。
 */
fun decodeScaled(path: String, maxEdge: Int): ImageBitmap? = runCatching {
    val file = File(path)
    if (!file.isFile || maxEdge <= 0) return null
    val raw = readSubsampled(file, maxEdge) ?: return null
    val scaled = scaleToEdge(raw, maxEdge)
    scaled.toComposeImageBitmap()
}.getOrNull()

/** 按 2 的幂次降采样解码：解码结果仍不小于目标尺寸，缩到最后一步才不会糊。 */
private fun readSubsampled(file: File, maxEdge: Int): BufferedImage? = runCatching {
    ImageIO.createImageInputStream(file).use { input ->
        if (input == null) return null
        val readers = ImageIO.getImageReaders(input)
        if (!readers.hasNext()) return null
        val reader = readers.next()
        try {
            reader.setInput(input, true, true)
            val w = reader.getWidth(0)
            val h = reader.getHeight(0)
            if (w <= 0 || h <= 0) return null
            var sub = 1
            // 降采样到"仍不小于目标边长"为止：解码结果落在 [maxEdge, 2×maxEdge)，
            // 既保留足够像素给最后一步双线性缩放，也不会为一张 64 px 的格子解码 4000 px 的原图。
            while (w / (sub * 2) >= maxEdge && h / (sub * 2) >= maxEdge) sub *= 2
            val param = reader.defaultReadParam
            if (sub > 1) param.setSourceSubsampling(sub, sub, 0, 0)
            reader.read(0, param)
        } finally {
            reader.dispose()
        }
    }
}.getOrNull()

/** 等比缩到最长边 = [maxEdge]；已经更小就原样返回（不放大，放大只会更糊）。 */
private fun scaleToEdge(src: BufferedImage, maxEdge: Int): BufferedImage {
    val longest = maxOf(src.width, src.height)
    if (longest <= maxEdge) return src
    val ratio = maxEdge.toDouble() / longest
    val w = (src.width * ratio).toInt().coerceAtLeast(1)
    val h = (src.height * ratio).toInt().coerceAtLeast(1)
    val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    val g = out.createGraphics()
    try {
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = java.awt.Color.WHITE
        g.fillRect(0, 0, w, h)
        g.drawImage(src, 0, 0, w, h, null)
    } finally {
        g.dispose()
    }
    return out
}
