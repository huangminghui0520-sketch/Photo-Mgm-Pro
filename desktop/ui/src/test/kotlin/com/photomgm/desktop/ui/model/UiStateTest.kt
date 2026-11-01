// ui/test/UiStateTest —— 单页界面的状态归约
//
// 覆盖的是"界面对 core 状态的响应规则"。这些规则如果写错，症状是：
//   · 分析完了日志框还占着半屏（→ 事件看不到）
//   · 重新分析后弹窗还开着一个不存在的事件（→ 白屏/崩溃）
//   · 同一次导出弹两次结果条（→ 用户以为导了两遍）
package com.photomgm.desktop.ui.model

import com.photomgm.desktop.core.api.CoreStatus
import com.photomgm.desktop.core.api.ResumeVerifyMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UiStateTest {

    private val photos = (1L..3L).map { photo(it) }

    private fun stateWithEvents() = appState(
        events = listOf(
            event(1, photoIds = listOf(1, 2)),
            event(2, location = "K140+000", photoIds = listOf(3)),
        ),
        photos = photos,
        eventPhotoIds = mapOf(1 to listOf(1L, 2L), 2 to listOf(3L)),
    )

    // ── 分析 ─────────────────────────────────────────────────────────────

    @Test
    fun `分析进行中不能被清掉`() {
        val next = reduce(UiState(busy = Busy.ANALYZE), appState(status = CoreStatus.ANALYZING))
        assertEquals(Busy.ANALYZE, next.busy)
    }

    @Test
    fun `分析完成后自动收起日志框并关上确认框`() {
        val next = reduce(
            UiState(busy = Busy.ANALYZE, logEntryVisible = true, sheet = Sheet.ConfirmReanalyze),
            stateWithEvents(),
        )
        assertNull(next.busy, "分析结束不该还显示忙碌")
        assertFalse(next.logEntryVisible, "日志框必须收起来，把高度让给事件卡片")
        assertNull(next.sheet, "确认框要跟着关掉")
    }

    @Test
    fun `解析出 0 个事件时日志框必须留着`() {
        val next = reduce(
            UiState(busy = Busy.ANALYZE, logEntryVisible = true),
            appState(events = emptyList(), logText = "无关内容"),
        )
        assertTrue(next.logEntryVisible, "没有事件还收起日志框，用户连改的地方都没有")
    }

    @Test
    fun `首帧：已有结果时日志框收起并聚焦第一张照片`() {
        val ui = initialUi(stateWithEvents())
        assertFalse(ui.logEntryVisible)
        assertEquals(1L, ui.activePhotoId)
    }

    @Test
    fun `首帧：空状态时日志框展开`() {
        val ui = initialUi(appState(status = CoreStatus.IDLE))
        assertTrue(ui.logEntryVisible)
        assertNull(ui.activePhotoId)
    }

    // ── 导出 ─────────────────────────────────────────────────────────────

    @Test
    fun `导出完成后记住这次结果并清掉已收起`() {
        val result = exportResult()
        val next = reduce(UiState(busy = Busy.EXPORT, dismissedResultKey = "old"), stateWithEvents().copy(lastExport = result))
        assertNull(next.busy)
        assertEquals(result.exportDir.toString(), next.seenExportKey)
        assertNull(next.dismissedResultKey, "新结果要重新出现，不能沿用上次的收起状态")
    }

    @Test
    fun `结果条只在识别过一次之后显示`() {
        val result = exportResult()
        val fresh = stateWithEvents().copy(lastExport = result)
        assertNull(resultToShow(UiState(), fresh), "重启后看到的历史结果不该主动弹出来")

        val seen = UiState(seenExportKey = result.exportDir.toString())
        assertNotNull(resultToShow(seen, fresh))

        val dismissed = seen.copy(dismissedResultKey = result.exportDir.toString())
        assertNull(resultToShow(dismissed, fresh), "用户收起后不再显示")
    }

    @Test
    fun `失败的导出不产生结果条`() {
        val next = reduce(UiState(busy = Busy.EXPORT), appState(status = CoreStatus.FAILED))
        assertNull(next.busy)
        assertNull(next.seenExportKey)
    }

    @Test
    fun `核心在导出中而界面没标记时补上覆盖层`() {
        val next = reduce(UiState(), appState(status = CoreStatus.EXPORTING))
        assertEquals(Busy.EXPORT, next.busy)
    }

    @Test
    fun `失败会撤掉忙碌态`() {
        val next = reduce(UiState(busy = Busy.ANALYZE), appState(status = CoreStatus.FAILED))
        assertNull(next.busy)
    }

    // ── 弹窗与选择 ────────────────────────────────────────────────────────

    @Test
    fun `事件消失后对应的照片弹窗自动关闭`() {
        val next = reduce(UiState(sheet = Sheet.Photos(Selection.Event(99))), stateWithEvents())
        assertNull(next.sheet, "不能留一个指向不存在事件的弹窗")
    }

    @Test
    fun `未匹配池清空后它的弹窗自动关闭`() {
        val withUnmatched = appState(photos = photos, unmatched = listOf(3))
        assertEquals(
            Sheet.Photos(Selection.Unmatched),
            reduce(UiState(sheet = Sheet.Photos(Selection.Unmatched)), withUnmatched).sheet,
        )
        assertNull(reduce(UiState(sheet = Sheet.Photos(Selection.Unmatched)), stateWithEvents()).sheet)
    }

    @Test
    fun `移动弹窗里失效的照片被剔除，全失效则关闭`() {
        val partially = reduce(UiState(sheet = Sheet.Move(listOf(1, 404), Selection.Event(1))), stateWithEvents())
        assertEquals(Sheet.Move(listOf(1), Selection.Event(1)), partially.sheet)
        assertNull(reduce(UiState(sheet = Sheet.Move(listOf(404), Selection.Event(1))), stateWithEvents()).sheet)
    }

    @Test
    fun `多选里失效的照片被剔除`() {
        val next = reduce(UiState(selectedPhotoIds = setOf(1L, 404L)), stateWithEvents())
        assertEquals(setOf(1L), next.selectedPhotoIds)
    }

    @Test
    fun `预览的那张照片失效后置空`() {
        assertNull(reduce(UiState(activePhotoId = 404), stateWithEvents()).activePhotoId)
        assertEquals(2L, reduce(UiState(activePhotoId = 2), stateWithEvents()).activePhotoId)
    }

    @Test
    fun `设置弹窗不受实体变化影响`() {
        assertEquals(Sheet.Settings, reduce(UiState(sheet = Sheet.Settings), appState()).sheet)
    }

    @Test
    fun `归约是幂等的`() {
        val state = stateWithEvents().copy(lastExport = exportResult())
        val once = reduce(UiState(busy = Busy.EXPORT, logEntryVisible = true), state)
        val twice = reduce(once, state)
        assertEquals(once, twice)
    }

    @Test
    fun `sheetSelection 反映弹窗指向`() {
        assertEquals(Selection.Event(1), sheetSelection(UiState(sheet = Sheet.Photos(Selection.Event(1)))))
        assertEquals(Selection.None, sheetSelection(UiState(sheet = Sheet.Settings)))
        assertEquals(Selection.None, sheetSelection(UiState()))
    }

    @Test
    fun `导出选项默认生成台账且用快速校验`() {
        val opts = UiState().exportOptions
        assertTrue(opts.includeLedger)
        assertEquals(ResumeVerifyMode.SIZE_AND_MTIME, opts.verifyMode)
    }
}
