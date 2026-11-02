// algorithm/ImageCropTest.kt —— 4:3 中心裁剪几何计算测试（导出 Excel 宽 3.8cm）
package algorithm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImageCropTest {

    private fun assertRatio(rect: ImageCrop.CropRect) {
        // 输出矩形必须为 4:3（允许 1px 舍入误差）
        val ratio = rect.width.toDouble() / rect.height
        assertTrue(ratio >= 4.0 / 3.0 - 0.02 && ratio <= 4.0 / 3.0 + 0.02,
            "裁剪比例应为 4:3，实际 $ratio（${rect.width}x${rect.height}）")
    }

    @Test fun `恰好4比3全图裁剪`() {
        val rect = ImageCrop.centerCrop4to3(1600, 1200)!!
        assertEquals(0, rect.left); assertEquals(0, rect.top)
        assertEquals(1600, rect.width); assertEquals(1200, rect.height)
    }

    @Test fun `横图按高度裁宽并居中`() {
        // 4000x1800 → 宽高比 2.22 > 1.33 → 裁宽至 2400（1800*4/3），上下不裁
        val rect = ImageCrop.centerCrop4to3(4000, 1800)!!
        assertEquals(2400, rect.width); assertEquals(1800, rect.height)
        assertEquals(0, rect.top)
        assertEquals((4000 - 2400) / 2, rect.left)
        assertRatio(rect)
    }

    @Test fun `竖图按宽度裁高并居中`() {
        // 1080x2400 → 宽高比 0.45 < 1.33 → 裁高至 810（1080*3/4），左右不裁
        val rect = ImageCrop.centerCrop4to3(1080, 2400)!!
        assertEquals(1080, rect.width)
        assertEquals(810, rect.height)
        assertEquals(0, rect.left)
        assertRatio(rect)
    }

    @Test fun `方形图按宽度裁高`() {
        // 1000x1000 → 裁高至 750（1000*3/4），顶部偏移 (1000-750)/2=125
        val rect = ImageCrop.centerCrop4to3(1000, 1000)!!
        assertEquals(1000, rect.width)
        assertEquals(750, rect.height)
        assertEquals(0, rect.left)
        assertEquals(125, rect.top)
        assertRatio(rect)
    }

    @Test fun `非法尺寸返回null`() {
        assertNull(ImageCrop.centerCrop4to3(0, 100))
        assertNull(ImageCrop.centerCrop4to3(-10, 100))
        assertNull(ImageCrop.centerCrop4to3(100, 0))
    }

    @Test fun `极小尺寸不越界`() {
        // 10x10 → 裁高至 round(10*3/4)=round(7.5)=8，顶部偏移 (10-8)/2=1
        val rect = ImageCrop.centerCrop4to3(10, 10)!!
        assertTrue(rect.left >= 0 && rect.top >= 0)
        assertTrue(rect.width in 1..10 && rect.height in 1..10)
        assertEquals(0, rect.left)
        assertTrue(rect.height == 7 || rect.height == 8, "高度≈7或8，实际 ${rect.height}")
    }
}
