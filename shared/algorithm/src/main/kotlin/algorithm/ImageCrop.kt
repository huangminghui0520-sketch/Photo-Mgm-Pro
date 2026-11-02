// algorithm/ImageCrop.kt —— 4:3 中心裁剪几何计算（纯数学，零 Android 依赖，可移植 PC）
// ★ 导出Excel：照片以 4:3 缩略图嵌入（宽 3.8cm，高 2.85cm）。裁剪参数计算放核心（可移植、可单测），
//   实际 Bitmap 裁剪由 Android 侧（app/ImageUtils）按本结果执行。
package algorithm

object ImageCrop {
    /** 目标宽高比（4:3） */
    const val RATIO_W = 4
    const val RATIO_H = 3

    /** 中心裁剪矩形。 */
    data class CropRect(val left: Int, val top: Int, val width: Int, val height: Int)

    /**
     * 计算 4:3 中心裁剪矩形：
     * - 图片过宽（宽高比 > 4:3）→ 以高度为基准裁宽度；
     * - 图片过高（宽高比 < 4:3）→ 以宽度为基准裁高度；
     * - 恰好 4:3 → 全图。
     * 非法输入（宽或高 ≤ 0）→ null。
     */
    fun centerCrop4to3(w: Int, h: Int): CropRect? {
        if (w <= 0 || h <= 0) return null
        val target = RATIO_W.toDouble() / RATIO_H
        val cur = w.toDouble() / h
        return if (cur > target) {
            // 太宽：按高裁宽（居中）
            val cw = Math.round(h * target).toInt().coerceIn(1, w)
            CropRect(left = (w - cw) / 2, top = 0, width = cw, height = h)
        } else {
            // 太高或恰好：按宽裁高（居中）
            val ch = Math.round(w / target).toInt().coerceIn(1, h)
            CropRect(left = 0, top = (h - ch) / 2, width = w, height = ch)
        }
    }
}
