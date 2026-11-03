// domain/adjust/ManualAdjustmentTest.kt —— 人工调整的纯状态变换（T07）
//
// 两端原来都把这段写在 ViewModel 里，只被端到端测试间接覆盖。抽出来之后
// **重复点击 / 目标即原事件 / 未知 id** 这三类边界第一次可以被单独断言。
package domain.adjust

import algorithm.ClassifyResult
import domain.view.AdjustmentLayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ManualAdjustmentTest {

    private fun classify() = ClassifyResult(
        eventPhotoMap = linkedMapOf(1 to mutableListOf(1L, 2L), 2 to mutableListOf(3L)),
        unmatched = mutableListOf(4L),
        interventions = emptyList(),
        pendingEventIds = emptyList(),
    )

    private val known = setOf(1L, 2L, 3L, 4L)

    // ── move ──────────────────────────────────────────────────────────────

    @Test
    fun `移动写入目标事件并清掉旧的复制份`() {
        val s0 = ManualState(
            overrideMap = mapOf(1L to 1),
            copyMap = mapOf(2L to setOf(1)),
        )
        val out = ManualAdjustment.move(s0, listOf(2L), 2, known)
        assertEquals(mapOf(1L to 1, 2L to 2), out.state.overrideMap)
        assertTrue(2L !in out.state.copyMap, "移动即归属变更：旧的复制份必须一起清掉，否则原事件还留一份")
        assertEquals(1, out.affected)
    }

    @Test
    fun `移到空目标落库为负一`() {
        val out = ManualAdjustment.move(ManualState.EMPTY, listOf(3L), null, known)
        assertEquals(mapOf(3L to AdjustmentLayer.REMOVED), out.state.overrideMap)
    }

    @Test
    fun `未知id被忽略且不计入受影响张数`() {
        val out = ManualAdjustment.move(ManualState.EMPTY, listOf(999L, 1L), 2, known)
        assertEquals(mapOf(1L to 2), out.state.overrideMap)
        assertEquals(1, out.affected)
    }

    @Test
    fun `重复id只写一次`() {
        val out = ManualAdjustment.move(ManualState.EMPTY, listOf(1L, 1L, 1L), 2, known)
        assertEquals(mapOf(1L to 2), out.state.overrideMap)
        assertEquals(1, out.affected)
    }

    @Test
    fun `多次移动保持写入顺序_决定台账取首末张`() {
        val s1 = ManualAdjustment.move(ManualState.EMPTY, listOf(3L), 1, known).state
        val s2 = ManualAdjustment.move(s1, listOf(2L), 1, known).state
        assertEquals(listOf(3L, 2L), s2.overrideMap.keys.toList())
    }

    // ── changedCount ──────────────────────────────────────────────────────

    @Test
    fun `原地移动不算变更`() {
        val c = classify()
        val before = AdjustmentLayer.EMPTY
        val after = AdjustmentLayer(overrideMap = mapOf(1L to 1))
        assertEquals(0, ManualAdjustment.changedCount(c, before, after, listOf(1L)),
            "目标本来就是它的家时什么都没发生，报「已移动 1 张」是假话")
    }

    @Test
    fun `真正换事件才算变更`() {
        val c = classify()
        val before = AdjustmentLayer.EMPTY
        val after = AdjustmentLayer(overrideMap = mapOf(1L to 2))
        assertEquals(1, ManualAdjustment.changedCount(c, before, after, listOf(1L)))
    }

    @Test
    fun `移出到未匹配池也算变更`() {
        val c = classify()
        val after = AdjustmentLayer(overrideMap = mapOf(3L to AdjustmentLayer.REMOVED))
        assertEquals(1, ManualAdjustment.changedCount(c, AdjustmentLayer.EMPTY, after, listOf(3L)))
    }

    // ── copy ──────────────────────────────────────────────────────────────

    private fun effective() = mapOf(1 to listOf(1L, 2L), 2 to listOf(3L))

    @Test
    fun `复制新增一份且源事件不变`() {
        val out = ManualAdjustment.copy(ManualState.EMPTY, listOf(1L), 2, effective(), known)
        assertEquals(mapOf(1L to setOf(2)), out.state.copyMap)
        assertEquals(1, out.added)
    }

    @Test
    fun `重复复制到同一事件不计数`() {
        val s1 = ManualAdjustment.copy(ManualState.EMPTY, listOf(1L), 2, effective(), known).state
        val s2 = ManualAdjustment.copy(s1, listOf(1L), 2, effective(), known)
        assertEquals(0, s2.added)
        assertEquals(setOf(2), s2.state.copyMap[1L])
    }

    @Test
    fun `目标已持有该照片时跳过`() {
        val out = ManualAdjustment.copy(ManualState.EMPTY, listOf(1L), 1, effective(), known)
        assertEquals(0, out.added)
    }

    @Test
    fun `未匹配池里的照片复制无意义`() {
        val out = ManualAdjustment.copy(ManualState.EMPTY, listOf(4L), 1, effective(), known)
        assertEquals(0, out.added)
        assertTrue(out.state.copyMap.isEmpty(), "没有家的照片复制等于同时出现在两处")
    }

    @Test
    fun `未知照片不复制`() {
        val out = ManualAdjustment.copy(ManualState.EMPTY, listOf(999L), 1, effective(), known)
        assertEquals(0, out.added)
    }

    @Test
    fun `复制到负数事件被拒`() {
        val out = ManualAdjustment.copy(ManualState.EMPTY, listOf(1L), -1, effective(), known)
        assertEquals(0, out.added)
    }

    // ── mark / reset ──────────────────────────────────────────────────────

    @Test
    fun `设置槽位一不影响槽位二`() {
        val s0 = ManualState(marked = mapOf(1 to (null to 9L)))
        val s1 = ManualAdjustment.mark(s0, 1, 1, 7L)
        assertEquals(7L to 9L, s1.marked[1])
    }

    @Test
    fun `清空槽位写null`() {
        val s0 = ManualState(marked = mapOf(1 to (7L to 9L)))
        assertEquals(null to 9L, ManualAdjustment.mark(s0, 1, 1, null).marked[1])
    }

    @Test
    fun `非法槽位原样返回`() {
        val s0 = ManualState(marked = mapOf(1 to (7L to 9L)))
        assertEquals(s0, ManualAdjustment.mark(s0, 1, 3, 5L))
    }

    @Test
    fun `重置清空三类痕迹`() {
        val s0 = ManualState(
            overrideMap = mapOf(1L to 2),
            copyMap = mapOf(2L to setOf(1)),
            marked = mapOf(1 to (1L to null)),
        )
        assertEquals(ManualState.EMPTY, ManualAdjustment.reset())
        assertEquals(2, ManualAdjustment.adjustmentCount(s0), "提示语口径：移动 + 复制，不含台账标记")
    }

    @Test
    fun `toLayer 原样透出叠加层`() {
        val s = ManualState(overrideMap = mapOf(1L to 2), copyMap = mapOf(2L to setOf(1)))
        assertEquals(AdjustmentLayer(mapOf(1L to 2), mapOf(2L to setOf(1))), s.toLayer())
    }
}
