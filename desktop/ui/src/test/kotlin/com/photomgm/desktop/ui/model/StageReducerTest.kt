// ui/test/StageReducerTest.kt —— 状态机投影
//
// 这组用例是"界面结构 = 运行逻辑"这句话的可执行版本：
// 如果哪天有人把"分析完成自动进入复核"改坏，或者让导出完成后不跳结果页，
// 这里会红。界面可以随便重画，但**阶段流转必须与 core 的状态一致**。
package com.photomgm.desktop.ui.model

import com.photomgm.desktop.core.api.CoreStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StageReducerTest {

    @Test
    fun `分析进行中保持忙碌覆盖层`() {
        val ui = UiState(busy = Busy.ANALYZE, stage = Stage.INPUT)
        val next = reduce(ui, appState(status = CoreStatus.ANALYZING))
        assertEquals(Busy.ANALYZE, next.busy)
        assertEquals(Stage.INPUT, next.stage)
    }

    @Test
    fun `分析完成自动进入复核并落在待确认事件`() {
        val e1 = event(1, photoIds = listOf(10))
        val e2 = event(2, photoIds = listOf(11), hints = listOf("GPS 离事件过远"))
        val state = appState(
            events = listOf(e1, e2),
            photos = listOf(photo(10), photo(11)),
            eventPhotoIds = mapOf(1 to listOf(10), 2 to listOf(11)),
        )
        val next = reduce(UiState(busy = Busy.ANALYZE), state)
        assertNull(next.busy)
        assertEquals(Stage.REVIEW, next.stage)
        assertEquals(Selection.Event(2), next.selection, "应优先把光标放在有提示的事件上")
    }

    @Test
    fun `没有解析出事件时留在输入页`() {
        val next = reduce(UiState(busy = Busy.ANALYZE), appState(events = emptyList()))
        assertNull(next.busy)
        assertEquals(Stage.INPUT, next.stage)
    }

    @Test
    fun `导出完成进入结果页`() {
        val state = appState(
            events = listOf(event(1, photoIds = listOf(10))),
            photos = listOf(photo(10)),
            eventPhotoIds = mapOf(1 to listOf(10)),
            lastExport = exportResult(),
        )
        val next = reduce(UiState(busy = Busy.EXPORT, stage = Stage.EXPORT), state)
        assertNull(next.busy)
        assertEquals(Stage.RESULT, next.stage)
    }

    @Test
    fun `同一次导出结果不会重复触发跳转`() {
        val result = exportResult()
        val state = appState(lastExport = result)
        // 键必须用与实现相同的方式取（Windows 上 Path.toString 是反斜杠）
        val seen = UiState(busy = Busy.EXPORT, stage = Stage.EXPORT, seenExportKey = result.exportDir.toString())
        val next = reduce(seen, state)
        assertEquals(Stage.EXPORT, next.stage, "已经呈现过的结果不应把用户从当前页拽走")
    }

    @Test
    fun `失败时撤掉忙碌覆盖层`() {
        val next = reduce(UiState(busy = Busy.ANALYZE), appState(status = CoreStatus.FAILED))
        assertNull(next.busy)
    }

    @Test
    fun `核心在导出中而界面没标记时补齐覆盖层`() {
        val next = reduce(UiState(stage = Stage.REVIEW), appState(status = CoreStatus.EXPORTING))
        assertEquals(Busy.EXPORT, next.busy)
        assertEquals(Stage.EXPORT, next.stage)
    }

    @Test
    fun `重新分析后失效的选择会被清掉`() {
        val ui = UiState(stage = Stage.REVIEW, selection = Selection.Event(99))
        val state = appState(
            events = listOf(event(1, photoIds = listOf(10))),
            photos = listOf(photo(10)),
            eventPhotoIds = mapOf(1 to listOf(10)),
        )
        val next = reduce(ui, state)
        assertEquals(Selection.Event(1), next.selection)
    }

    @Test
    fun `失效的照片选中会被清掉`() {
        val ui = UiState(
            stage = Stage.REVIEW,
            selection = Selection.Event(1),
            selectedPhotoIds = setOf(10, 999),
        )
        val state = appState(
            events = listOf(event(1, photoIds = listOf(10))),
            photos = listOf(photo(10)),
            eventPhotoIds = mapOf(1 to listOf(10)),
        )
        assertEquals(setOf(10L), reduce(ui, state).selectedPhotoIds)
    }

    @Test
    fun `归约幂等`() {
        val state = appState(
            events = listOf(event(1, photoIds = listOf(10))),
            photos = listOf(photo(10)),
            eventPhotoIds = mapOf(1 to listOf(10)),
        )
        val once = reduce(UiState(busy = Busy.ANALYZE), state)
        val twice = reduce(once, state)
        assertEquals(once, twice, "同一快照重复归约不能产生二次跳转")
    }

    @Test
    fun `未匹配池独立可选中`() {
        val state = appState(
            events = emptyList(),
            photos = listOf(photo(10)),
            unmatched = listOf(10),
        )
        assertEquals(Selection.Unmatched, initialStateFor(state))
    }

    @Test
    fun `阶段与步骤的对应关系`() {
        assertEquals(Step.EXPORT, Stage.RESULT.step)
        assertEquals(Step.EXPORT, Stage.EXPORT.step)
        assertEquals(Step.REVIEW, Stage.REVIEW.step)
        assertEquals(Step.INPUT, Stage.INPUT.step)
        assertTrue(Busy.ANALYZE.step == Step.INPUT)
        assertTrue(Busy.EXPORT.step == Step.EXPORT)
    }
}
