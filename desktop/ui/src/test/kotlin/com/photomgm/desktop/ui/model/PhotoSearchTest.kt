// ui/test/PhotoSearchTest —— 照片弹窗里的搜索口径
//
// 覆盖的是"搜什么能搜到"：文件名、拍摄时刻、无时间。
// 这些规则写错的症状是"我记得有这张，但搜不出来"，而用户会因此以为照片丢了。
package com.photomgm.desktop.ui.model

import com.photomgm.desktop.ui.format.hhmm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhotoSearchTest {

    // 1_763_000_000_000 = 北京时间 2025-11-13 10:13:20；1_763_006_400_000 = 12:00
    private val withTime = photo(1, name = "IMG_20261113_101320.jpg", timeMs = 1_763_000_000_000)
    private val noTime = photo(2, name = "IMG_20261113_101320_original.jpg", timeMs = null)
    private val other = photo(3, name = "PXL_20261113_120000.jpg", timeMs = 1_763_006_400_000)
    private val all = listOf(withTime, noTime, other)

    @Test
    fun `空搜索词不过滤`() {
        assertEquals(3, filterPhotos(all, "").size)
        assertEquals(3, filterPhotos(all, "   ").size)
    }

    @Test
    fun `按文件名片段搜，忽略大小写`() {
        assertEquals(listOf(noTime), filterPhotos(all, "original"))
        assertEquals(listOf(noTime), filterPhotos(all, "ORIGINAL"))
        assertEquals(listOf(other), filterPhotos(all, "pxl"))
    }

    @Test
    fun `按拍摄时刻搜`() {
        val t = hhmm(withTime.captureTimeMs!!)
        assertEquals(listOf(withTime), filterPhotos(all, t))
        // 只记到小时也该命中（"十点多那张"）
        assertTrue(photoMatches(withTime, t.substringBefore(':')))
    }

    @Test
    fun `无时间的照片用状态词能搜到`() {
        assertEquals(listOf(noTime), filterPhotos(all, "无时间"))
        assertTrue(photoMatches(noTime, "无时间"))
        assertFalse(photoMatches(withTime, "无时间"))
    }

    @Test
    fun `搜不到时返回空列表而不是全部`() {
        assertEquals(emptyList(), filterPhotos(all, "不存在的名字"))
    }

    @Test
    fun `过滤保持原顺序`() {
        // IMG_2026 命中前两张（原名与 _original），顺序必须与入参一致
        assertEquals(listOf(1L, 2L), filterPhotos(all, "IMG_2026").map { it.id })
        assertEquals(listOf(1L, 2L, 3L), filterPhotos(all, "").map { it.id })
    }
}
