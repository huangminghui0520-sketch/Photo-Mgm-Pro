// ui/test/.../PhotoOrderTest.kt —— 事件内照片的显示顺序
//
// 便携版实测反馈「事件里的缩略图顺序是乱的」：core 给的 photoIds 是按扫描/分配顺序来的，
// 与拍摄时间无关，于是复核时要在一排照片里来回找"下一张到底是哪张"。
// 这里把"显示顺序 = 拍摄时间升序"这条口径钉死（只影响界面，不改导出序号）。
package com.photomgm.desktop.ui.model

import com.photomgm.desktop.core.api.EventSnapshot
import com.photomgm.desktop.core.api.PhotoInfo
import kotlin.test.Test
import kotlin.test.assertEquals

class PhotoOrderTest {

    private fun p(id: Long, captureTimeMs: Long?) =
        PhotoInfo(
            id = id,
            displayName = "IMG_$id.jpg",
            sourceRef = "C:/photos/IMG_$id.jpg",
            captureTimeMs = captureTimeMs,
            latitude = 23.0,
            longitude = 113.0,
            isDerived = false,
        )

    @Test
    fun `按拍摄时间升序排列`() {
        val t0 = 1_800_000_000_000L
        val photos = listOf(p(3, t0 + 120_000), p(1, t0), p(2, t0 + 60_000))
        assertEquals(listOf(1L, 2L, 3L), WorkQueue.sortedByCapture(photos).map { it.id })
    }

    @Test
    fun `无拍摄时间的排最后`() {
        val t0 = 1_800_000_000_000L
        val photos = listOf(p(1, null), p(2, t0 + 5_000), p(3, t0))
        assertEquals(listOf(3L, 2L, 1L), WorkQueue.sortedByCapture(photos).map { it.id })
    }

    @Test
    fun `同一时刻按 id 兜底_两次打开顺序一致`() {
        val t = 1_800_000_000_000L
        val photos = listOf(p(9, t), p(4, t), p(7, t))
        assertEquals(listOf(4L, 7L, 9L), WorkQueue.sortedByCapture(photos).map { it.id })
    }

    @Test
    fun `事件卡片与弹窗用同一个顺序`() {
        val t0 = 1_800_000_000_000L
        // core 给的顺序是 2,1,3（扫描顺序），显示必须重排成 1,2,3
        val event = EventSnapshot(
            id = 1,
            eventType = "巡查",
            location = "K1+000 桥底",
            description = "发现裂缝",
            startTimeMs = t0,
            endTimeMs = t0 + 600_000,
            photoIds = listOf(2L, 1L, 3L),
            isPending = false,
            hints = emptyList(),
        )
        val byId = listOf(p(1, t0), p(2, t0 + 10_000), p(3, t0 + 20_000)).associateBy { it.id }
        assertEquals(listOf(1L, 2L, 3L), WorkQueue.photosOfEvent(event, byId).map { it.id })
    }
}
