// AdjustUseCaseTest —— 人工调整的编排语义（T09-C2）
//
// 这里测的不是"移动怎么写"（那在 domain 层，已由 T07 双跑验证），而是：
//   · 返回值口径（真正变了才计数，否则界面会说假话）；
//   · 提示语只在真的发生时出现；
//   · 准入判据（没分析 / 导出中一律不动状态）；
//   · 视图是否跟着更新（eventPhotoIds / unmatchedPhotoIds / ledger 三者同步）。
package app.usecase

import app.api.CoreStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdjustUseCaseTest {

    private fun harness(): Harness {
        val h = Harness(photos = Harness.twoPhotos(), exif = Harness.twoPhotoExif())
        h.analyze()
        return h
    }

    private fun Harness.photoOfEvent(eventId: Int): Long =
        store.current().eventPhotoIds.getValue(eventId).first()

    @Test
    fun `移动到另一事件后两边的照片表同步变化`() {
        val h = harness()
        val photo = h.photoOfEvent(2)

        assertEquals(1, h.adjust.movePhotos(listOf(photo), 1))

        val s = h.store.current()
        assertTrue(s.eventPhotoIds.getValue(1).contains(photo))
        assertTrue(s.eventPhotoIds[2].isNullOrEmpty(), "移动不是复制：原事件必须空")
        assertEquals("已移动 1 张照片", s.message)
    }

    @Test
    fun `移动到未匹配池后出现在未匹配列表里`() {
        val h = harness()
        val photo = h.photoOfEvent(2)

        assertEquals(1, h.adjust.movePhotos(listOf(photo), null))

        val s = h.store.current()
        assertTrue(s.unmatchedPhotoIds.contains(photo))
        assertTrue(s.eventPhotoIds[2].isNullOrEmpty())
    }

    @Test
    fun `移动到它原本就在的事件不计变化也不弹提示`() {
        val h = harness()
        val photo = h.photoOfEvent(2)
        val messageBefore = h.store.current().message

        assertEquals(0, h.adjust.movePhotos(listOf(photo), 2), "目标就是它的家 → 什么都没发生")
        assertEquals(messageBefore, h.store.current().message, "没变化就不要说话")
    }

    @Test
    fun `未知照片 id 被忽略`() {
        val h = harness()
        assertEquals(0, h.adjust.movePhotos(listOf(999L), 1))
        assertEquals(0, h.adjust.movePhotos(emptyList(), 1))
    }

    @Test
    fun `复制后两个事件各有一份且原事件不丢`() {
        val h = harness()
        val photo = h.photoOfEvent(2)

        assertEquals(1, h.adjust.copyPhotos(listOf(photo), 3))

        val s = h.store.current()
        assertTrue(s.eventPhotoIds.getValue(2).contains(photo), "复制不改变归属：原事件仍有")
        assertTrue(s.eventPhotoIds.getValue(3).contains(photo), "目标事件多一份")
        assertEquals("已复制 1 张照片到该事件", s.message)
    }

    @Test
    fun `重复复制不会长出第二份`() {
        val h = harness()
        val photo = h.photoOfEvent(2)

        assertEquals(1, h.adjust.copyPhotos(listOf(photo), 3))
        assertEquals(0, h.adjust.copyPhotos(listOf(photo), 3), "已在目标 → 不计新增")
    }

    @Test
    fun `未匹配池里的照片不能复制`() {
        val h = harness()
        val photo = h.photoOfEvent(2)
        h.adjust.movePhotos(listOf(photo), null)

        assertEquals(0, h.adjust.copyPhotos(listOf(photo), 1), "它没有家，复制等于复制一份空气")
    }

    @Test
    fun `移动会清掉该照片的复制关系`() {
        val h = harness()
        val photo = h.photoOfEvent(2)
        h.adjust.copyPhotos(listOf(photo), 3)
        h.adjust.movePhotos(listOf(photo), 1)

        val s = h.store.current()
        assertTrue(s.eventPhotoIds.getValue(1).contains(photo))
        assertTrue(s.eventPhotoIds[3]!!.none { it == photo }, "移走之后不该还挂在原来那条里")
    }

    @Test
    fun `台账照片槽位可以改到指定照片`() {
        val h = harness()
        val later = h.photoOfEvent(3)
        // 先把 12:00 那张移进事件 2，这样事件 2 有两张可选
        h.adjust.movePhotos(listOf(later), 2)

        h.adjust.markLedgerPhoto(2, 1, later)
        val row = h.store.current().ledger.first { it.location.contains("K138+700") }
        assertTrue(row.photo1Label!!.endsWith("IMG_20260814_120000.jpg"), "台账照片1 应为你指定的那张：${row.photo1Label}")

        h.adjust.markLedgerPhoto(2, 1, null)
        val restored = h.store.current().ledger.first { it.location.contains("K138+700") }
        assertTrue(
            restored.photo1Label!!.endsWith("IMG_20260814_101530.jpg"),
            "清空槽位应回落到默认（最早一张）：${restored.photo1Label}",
        )
    }

    @Test
    fun `非法槽位被静默忽略`() {
        val h = harness()
        val photo = h.photoOfEvent(2)
        h.adjust.markLedgerPhoto(2, 3, photo)
        h.adjust.markLedgerPhoto(2, 0, photo)
        // 不抛异常、不改台账（仍是默认的早一张）
        val row = h.store.current().ledger.first { it.location.contains("K138+700") }
        assertTrue(row.photo1Label!!.endsWith("IMG_20260814_101530.jpg"))
    }

    @Test
    fun `重置清除全部人工调整并说明清了几处`() {
        val h = harness()
        val photo = h.photoOfEvent(2)
        h.adjust.movePhotos(listOf(photo), 1)
        h.adjust.copyPhotos(listOf(h.photoOfEvent(3)), 1)

        h.adjust.resetManualAdjustments()
        assertEquals("已恢复最初分类（清除 2 处人工调整）", h.store.current().message)
        assertTrue(h.store.current().eventPhotoIds[1].isNullOrEmpty())

        h.adjust.resetManualAdjustments()
        assertEquals("已是最初分类状态", h.store.current().message)
    }

    @Test
    fun `导出进行中拒绝改动`() {
        val h = harness()
        val photo = h.photoOfEvent(2)
        h.store.withLock { h.store.publish { it.copy(status = CoreStatus.EXPORTING) } }
        val snapshot = h.store.current().eventPhotoIds

        assertEquals(0, h.adjust.movePhotos(listOf(photo), 1))
        assertEquals(0, h.adjust.copyPhotos(listOf(photo), 3))
        assertEquals(snapshot, h.store.current().eventPhotoIds, "导出期间状态必须冻结")
    }

    @Test
    fun `没有分析结果时所有调整都是空操作`() {
        val h = Harness(photos = Harness.twoPhotos(), exif = Harness.twoPhotoExif())
        assertEquals(0, h.adjust.movePhotos(listOf(1L), 1))
        assertEquals(0, h.adjust.copyPhotos(listOf(1L), 1))
        h.adjust.markLedgerPhoto(1, 1, 1L)
        h.adjust.resetManualAdjustments()
        assertNull(h.store.current().error)
        assertTrue(h.store.current().eventPhotoIds.isEmpty())
    }
}
