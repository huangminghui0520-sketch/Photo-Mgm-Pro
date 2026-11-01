// ui/test/FeedbackTest —— "能不能对这几张照片做这个动作"以及"做完说什么"
//
// 覆盖的规则看起来是文案，其实是**契约**：core 会静默跳过不合法的照片，
// 界面若不把这些情况说出来，用户看到的就是"点了没反应"。反过来，若界面在
// core 什么都没做时报了成功，那是系统拒绝执行时装作成功 —— 比沉默更糟。
package com.photomgm.desktop.ui.model

import com.photomgm.desktop.core.api.CoreStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeedbackTest {

    private val photos = (1L..4L).map { photo(it) }
    private val base = appState(
        events = listOf(
            event(1, location = "K138+700", photoIds = listOf(1, 2)),
            event(2, location = "K140+000", photoIds = listOf(3)),
        ),
        photos = photos,
        eventPhotoIds = mapOf(1 to listOf(1L, 2L), 2 to listOf(3L)),
    )

    // ── 动手之前的校验 ──────────────────────────────────────────────────

    @Test
    fun `没有选中照片时被拒绝`() {
        val reason = rejectReason(base, emptyList(), 2, AssignMode.MOVE)
        assertNotNull(reason, "空清单不该被当成'执行成功'")
    }

    @Test
    fun `照片已经不在结果里时被拒绝`() {
        val reason = rejectReason(base, listOf(999L), 2, AssignMode.MOVE)
        assertNotNull(reason)
        assertTrue(reason.contains("重新分析"), "要给出下一步，而不是只说不行：$reason")
    }

    @Test
    fun `导出还没结束时不能调整归属`() {
        val exporting = appState(
            events = base.events,
            photos = photos,
            status = CoreStatus.EXPORTING,
        )
        val reason = rejectReason(exporting, listOf(1), 2, AssignMode.MOVE)
        assertNotNull(reason)
        assertTrue(reason.contains("导出"), "理由要说清是被导出挡住了：$reason")
    }

    @Test
    fun `目标事件不存在时被拒绝`() {
        val reason = rejectReason(base, listOf(1), 77, AssignMode.MOVE)
        assertNotNull(reason)
        assertTrue(reason.contains("重新分析"), "$reason")
    }

    @Test
    fun `移入未匹配池永远合法`() {
        // 它只是一个容器，不存在"重复"或"它自己就是目标"的问题。
        assertNull(rejectReason(base, listOf(1), null, AssignMode.MOVE))
    }

    @Test
    fun `复制到已经全部持有的目标时被拒绝`() {
        val reason = rejectReason(base, listOf(1, 2), 1, AssignMode.COPY)
        assertNotNull(reason, "目标事件已经有这两张了：不该再长出第二份")
        assertTrue(reason.contains("已经有"), "$reason")
    }

    @Test
    fun `复制到部分持有的目标不算拒绝`() {
        // 事件 1 已经有第 1 张；复制 1、2 两张时第 2 张是新增 —— 允许，
        // 但结果必须告诉用户"只有一张过去了"（由 copyPartialText 承担）。
        assertNull(rejectReason(base, listOf(2, 3), 1, AssignMode.COPY))
    }

    @Test
    fun `未匹配照片不能复制`() {
        val withUnmatched = base.copy(unmatchedPhotoIds = listOf(4L))
        val reason = rejectReason(withUnmatched, listOf(4), 1, AssignMode.COPY)
        assertNotNull(reason, "它没有家，复制会让它同时出现在两个地方")
        assertTrue(reason.contains("未匹配"), "$reason")
    }

    @Test
    fun `未匹配照片可以移动`() {
        val withUnmatched = base.copy(unmatchedPhotoIds = listOf(4L))
        // 移动正是"给它一个家"的手段，必须放行。
        assertNull(rejectReason(withUnmatched, listOf(4), 1, AssignMode.MOVE))
    }

    @Test
    fun `ownedPhotoIds 只认已经挂在事件上的照片`() {
        assertEquals(setOf(1L, 2L, 3L), ownedPhotoIds(base))
    }

    // ── 执行后的文案 ────────────────────────────────────────────────────

    @Test
    fun `成功文案带上目标`() {
        assertEquals(
            "已把 1 张照片移到 事件 02 · 08:00 中山路",
            assignSuccessText(AssignMode.MOVE, 1, "事件 02 · 08:00 中山路"),
        )
        assertTrue(
            assignSuccessText(AssignMode.COPY, 2, "事件 02").contains("原事件仍保留"),
            "复制必须说清原图还在，否则用户会以为搬走了",
        )
    }

    @Test
    fun `成功提示是确定语气，失败提示不是`() {
        assertEquals(NoticeTone.OK, assignSuccessNotice(AssignMode.MOVE, 1, "事件 02").tone)
        assertEquals(NoticeTone.WARN, assignFailureNotice("目标事件已经有这 1 张了").tone)
    }

    @Test
    fun `部分没生效要把原因说清楚`() {
        val text = copyPartialText(1, 2, "事件 02")
        assertTrue(text.contains("另 2 张该事件已有"), "沉默褪成'少复制了一张'：$text")
        assertTrue(text.contains("已复制 1 张"), "$text")
    }
}
