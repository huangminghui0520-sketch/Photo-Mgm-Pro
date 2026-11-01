// ui/test/WorkQueueTest.kt —— 复核队列口径
//
// 这里是"哪些事件算有问题"的唯一出处。界面只消费结论，不自己判断——
// 否则同一件事（比如"无照片"）会在列表、筛选、导出提示里各算一遍，然后不一致。
package com.photomgm.desktop.ui.model

import com.photomgm.desktop.core.api.CoreStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkQueueTest {

    private val e1 = event(1, location = "K138+700", photoIds = listOf(10))
    private val e2 = event(2, location = "K139+100", photoIds = emptyList())
    private val e3 = event(3, location = "K140+200", photoIds = listOf(11), hints = listOf("时间与相邻事件重叠"))
    private val e4 = event(4, location = "K145+000", pending = true, photoIds = listOf(12), description = "追尾事故")
    private val photos = listOf(photo(10), photo(11), photo(12), photo(13))
    private val state = appState(
        events = listOf(e1, e2, e3, e4),
        photos = photos,
        eventPhotoIds = mapOf(1 to listOf(10), 3 to listOf(11), 4 to listOf(12)),
        unmatched = listOf(13),
    )

    @Test
    fun `需要确认的三种来源`() {
        assertFalse(WorkQueue.needsReview(e1))
        assertTrue(WorkQueue.needsReview(e2), "无照片")
        assertTrue(WorkQueue.needsReview(e3), "有提示")
        assertTrue(WorkQueue.needsReview(e4), "无时间")
    }

    @Test
    fun `统计口径`() {
        val s = WorkQueue.stats(state)
        assertEquals(4, s.events)
        assertEquals(4, s.photos)
        assertEquals(1, s.unmatched)
        assertEquals(3, s.needReview)
        assertEquals(1, s.noPhoto)
        assertEquals(1, s.pending)
        assertEquals(1, s.withHints)
        assertEquals(3, s.matched, "事件里挂着的照片数")
    }

    @Test
    fun `筛选未匹配时事件列表清空`() {
        assertTrue(WorkQueue.filter(state, ReviewFilter.UNMATCHED, "").isEmpty())
    }

    @Test
    fun `筛选待确认`() {
        val list = WorkQueue.filter(state, ReviewFilter.NEEDS_REVIEW, "")
        assertEquals(listOf(2, 3, 4), list.map { it.id })
    }

    @Test
    fun `搜索命中桩号与描述`() {
        assertEquals(listOf(1), WorkQueue.filter(state, ReviewFilter.ALL, "138").map { it.id })
        assertEquals(listOf(4), WorkQueue.filter(state, ReviewFilter.ALL, "追尾").map { it.id })
    }

    @Test
    fun `筛选与搜索叠加`() {
        assertEquals(listOf(2), WorkQueue.filter(state, ReviewFilter.NO_PHOTO, "139").map { it.id })
        assertTrue(WorkQueue.filter(state, ReviewFilter.NO_PHOTO, "138").isEmpty())
    }

    @Test
    fun `照片区按事件取照片`() {
        val byId = WorkQueue.byId(state)
        assertEquals(listOf(11L), WorkQueue.photosOf(state, Selection.Event(3), byId).map { it.id })
        assertEquals(listOf(13L), WorkQueue.photosOf(state, Selection.Unmatched, byId).map { it.id })
        assertTrue(WorkQueue.photosOf(state, Selection.None, byId).isEmpty())
    }

    @Test
    fun `导出阻断项`() {
        assertTrue(WorkQueue.exportBlockers(state).isEmpty(), "READY + 有事件 + 有输出目录 = 可导出")

        val noDir = appState(events = listOf(e1), photos = photos, eventPhotoIds = mapOf(1 to listOf(10)), outputDir = null)
        assertTrue(WorkQueue.exportBlockers(noDir).single().contains("导出目录"))

        val noEvents = appState(events = emptyList(), status = CoreStatus.READY)
        assertTrue(WorkQueue.exportBlockers(noEvents).any { it.contains("事件") })
    }

    @Test
    fun `问题标签优先级`() {
        assertEquals(listOf("无时间", "1 条提示"), WorkQueue.issueLabels(event(9, pending = true, photoIds = listOf(1), hints = listOf("x"))))
    }

    @Test
    fun `复制后同一张照片被算作两处，但总数不重复`() {
        // 照片 10 被复制到事件 3：事件里"张数"的合计变 4，而"已归属"仍是 3 张不同的照片
        val copied = appState(
            events = listOf(e1, e2, e3, e4),
            photos = photos,
            eventPhotoIds = mapOf(1 to listOf(10), 3 to listOf(11, 10), 4 to listOf(12)),
            unmatched = listOf(13),
        )
        val count = WorkQueue.photoEventCount(copied)
        assertEquals(2, count[10L], "照片 10 出现在两个事件里")
        assertEquals(1, count[11L])

        val s = WorkQueue.stats(copied)
        assertEquals(3, s.matched, "已归属是去重后的照片张数，不能是事件照片格之和")
        assertEquals(listOf(1, 3), WorkQueue.eventsOfPhoto(copied, 10L))
        assertEquals(emptyList(), WorkQueue.eventsOfPhoto(copied, 13L), "未匹配照片不属于任何事件")
    }
}
