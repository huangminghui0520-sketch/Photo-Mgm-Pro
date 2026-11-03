// domain/view/PhotoOrderTest.kt —— 照片先后口径（T07 引入，T12 启用）
//
// 这条口径目前**没有接到生产流程**（见 PhotoOrder.kt 文件头），所以这里锁的不是
// 「现状行为」而是「将来切换后必须如此」。三条语义各有一个用例：
// 时间优先 / 无时间排最后 / 同刻按文件名 —— 第三条是连拍照片的唯一去歧义手段。
package domain.view

import algorithm.model.Photo
import kotlin.test.Test
import kotlin.test.assertEquals

class PhotoOrderTest {

    private fun photo(id: Long, name: String, time: Long? = null) =
        Photo(id = id, sourceRef = "/src/$name", displayName = name, captureTimeMs = time, latitude = null, longitude = null)

    @Test
    fun `按拍摄时间升序`() {
        val a = photo(1, "IMG_3.jpg", 3_000L)
        val b = photo(2, "IMG_1.jpg", 1_000L)
        val c = photo(3, "IMG_2.jpg", 2_000L)
        assertEquals(listOf(2L, 3L, 1L), PhotoOrder.sortedPhotos(listOf(a, b, c)).map { it.id })
    }

    @Test
    fun `无拍摄时间的排最后而不是最前`() {
        val unknown = photo(1, "IMG_unknown.jpg", null)
        val early = photo(2, "IMG_a.jpg", 1_000L)
        assertEquals(listOf(2L, 1L), PhotoOrder.sortedPhotos(listOf(unknown, early)).map { it.id })
    }

    @Test
    fun `同一时刻按文件名升序_连拍去歧义`() {
        val burst3 = photo(1, "IMG_20260814_101530_3.jpg", 5_000L)
        val burst1 = photo(2, "IMG_20260814_101530_1.jpg", 5_000L)
        val burst2 = photo(3, "IMG_20260814_101530_2.jpg", 5_000L)
        assertEquals(
            listOf("IMG_20260814_101530_1.jpg", "IMG_20260814_101530_2.jpg", "IMG_20260814_101530_3.jpg"),
            PhotoOrder.sortedPhotos(listOf(burst3, burst1, burst2)).map { it.displayName },
        )
    }

    @Test
    fun `排序不改入参`() {
        val input = listOf(photo(1, "b.jpg", 2_000L), photo(2, "a.jpg", 1_000L))
        PhotoOrder.sortedPhotos(input)
        assertEquals(listOf(1L, 2L), input.map { it.id }, "返回新列表，调用方的快照不能被就地改动")
    }

    @Test
    fun `事件表按时间重排id`() {
        val byId = mapOf(
            1L to photo(1, "IMG_3.jpg", 3_000L),
            2L to photo(2, "IMG_1.jpg", 1_000L),
            3L to photo(3, "IMG_2.jpg", 2_000L),
        )
        val out = PhotoOrder.sortEventMap(mapOf(7 to listOf(1L, 2L, 3L)), byId::get)
        assertEquals(listOf(2L, 3L, 1L), out[7])
    }

    @Test
    fun `查不到的照片排最后并按id升序`() {
        val byId = mapOf(5L to photo(5, "IMG_5.jpg", 1_000L))
        val out = PhotoOrder.sortEventMap(mapOf(7 to listOf(9L, 5L, 8L)), byId::get)
        assertEquals(listOf(5L, 8L, 9L), out[7])
    }
}
