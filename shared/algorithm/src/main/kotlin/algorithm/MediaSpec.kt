// algorithm/MediaSpec.kt —— 台账嵌图的媒体规格（唯一真值）
//
// 两端各自压缩照片再嵌入台账：PC 走 ImageIO、安卓走 Bitmap.compress。
// 编码器不同（字节永远不相等，跨端别比字节），但**规格必须一致**：
// 宽高或质量一旦分叉，同一批数据两端"嵌进去的照片"观感不同、台账体积也对不上（R8）。
//
// 2026-11-03（T04）之前这两个值是两份拷贝：PcImageEmbedder.DEFAULT_* 与
// ImageUtils.TARGET_*/JPEG_QUALITY，注释都写着"与另一端一致"，但没有任何机制保证 ——
// 改一边不会报错，只会悄悄分叉。现在由本文件单点定义，两端引用。
//
// ★ 本文件只放"照片规格"，**不放排版尺寸**。XlsxWriter 的 PIC_CX_EMU（3.81cm / 1371600 EMU）
//   是排版尺寸，与 400px 之间没有换算关系（一个是源分辨率，一个是显示大小）。
//   把两者合并会把台表格子改坏 —— 基线会立刻报差异，别以为是"顺手统一"。
package algorithm

object MediaSpec {
    /** 台账嵌图目标宽（px）。 */
    const val EMBED_WIDTH: Int = 400

    /** 目标高 = 宽 × 3/4 = 300（4:3；比例常量复用 [ImageCrop]，不在本文件再写一个 3/4）。 */
    val EMBED_HEIGHT: Int = EMBED_WIDTH * ImageCrop.RATIO_H / ImageCrop.RATIO_W

    /** JPEG 质量（百分数）。Android 的 `Bitmap.compress(..., quality, ...)` 直接用这个。 */
    const val JPEG_QUALITY_PERCENT: Int = 50

    /**
     * JPEG 质量（0..1 小数）。PC 的 `ImageWriteParam.quality` 要小数。
     *
     * 由百分数换算而非另写一个 0.5f —— 50/100f 在二进制里精确等于 0.5f，不会有浮点漂移；
     * 但"两端各写一个字面量"正是当初分叉的来源，所以这里只留一个数。
     */
    val JPEG_QUALITY_FRACTION: Float get() = JPEG_QUALITY_PERCENT / 100f
}
