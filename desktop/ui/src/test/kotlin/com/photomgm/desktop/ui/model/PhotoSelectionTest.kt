// ui/test/.../PhotoSelectionTest.kt —— 缩略条的选择规则
//
// 这是一条回归用例：便携版实测反馈「Ctrl+点只能选中两张」「连点几下勾选自己乱跳」。
// 根因有两层，两个都必须被钉死：
//   ① 点击回调闭包捕获了旧的 UiState（见 component/Press.kt 的说明）；
//   ② 同一个 press 既可能落到格子、又可能落到右上角红勾，"单选"和"切换"两个分支
//      在同一次点击里都跑了一遍，后跑的把先跑的覆盖掉。
// 现在职责分开：格子 = 单选/连选（clickPhotoSelection），红勾 = 切换（nextPhotoSelection）。
// 下面分别锁住这两条路径。
package com.photomgm.desktop.ui.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhotoSelectionTest {

    // ---- 红勾：切换这一张、保留其它（唯一的加选入口） ----

    @Test
    fun `连点五张红勾应当累积成五张`() {
        var selected: Set<Long> = emptySet()
        listOf(11L, 12L, 13L, 14L, 15L).forEach { id ->
            selected = nextPhotoSelection(selected, id, additive = true)
        }
        assertEquals(setOf(11L, 12L, 13L, 14L, 15L), selected)
    }

    @Test
    fun `再点已勾选的红勾应当取消它且保留其它`() {
        val selected = setOf(1L, 2L, 3L)
        assertEquals(setOf(1L, 3L), nextPhotoSelection(selected, 2L, additive = true))
    }

    @Test
    fun `红勾不会因为点第二次就清空其它勾选`() {
        val selected = setOf(1L, 2L, 3L)
        val after = nextPhotoSelection(selected, 4L, additive = true)
        assertEquals(setOf(1L, 2L, 3L, 4L), after, "加选必须保留原有勾选")
    }

    @Test
    fun `没勾选时操作对象是当前大图`() {
        assertEquals(listOf(7L), operationTargets(emptySet(), 7L))
        assertEquals(emptyList(), operationTargets(emptySet(), null))
    }

    @Test
    fun `勾选后操作对象就是勾的这些`() {
        assertEquals(setOf(1L, 2L), operationTargets(setOf(1L, 2L), 3L).toSet())
    }

    // ---- 点图本体：单选 / Shift 连选（不再有 Ctrl 分支） ----

    private val strip = listOf(11L, 12L, 13L, 14L, 15L)

    private fun click(
        selected: Set<Long>,
        anchor: Long?,
        id: Long,
        shift: Boolean = false,
    ) = clickPhotoSelection(selected, strip, anchor, id, shift)

    @Test
    fun `普通点只留这一张，绝不保留其它勾选`() {
        val r = click(selected = setOf(1L, 2L, 3L), anchor = 1L, id = 9L)
        assertEquals(setOf(9L), r.selected, "点图本体永远只做单选，这是『误触发 Ctrl 加选』的正面用例")
    }

    @Test
    fun `连续两次普通点同一张，结果仍是选中它（不会像 Ctrl 那样取消）`() {
        var r = click(selected = emptySet(), anchor = null, id = 13L)
        assertEquals(setOf(13L), r.selected)
        r = click(selected = r.selected, anchor = r.anchor, id = 13L)
        assertEquals(setOf(13L), r.selected, "第二次点同一张不能把它 toggle 掉")
        assertEquals(13L, r.anchor)
    }

    @Test
    fun `连续普通点两张，只留最后一张`() {
        var r = click(selected = emptySet(), anchor = null, id = 12L)
        r = click(selected = r.selected, anchor = r.anchor, id = 14L)
        assertEquals(setOf(14L), r.selected)
    }

    @Test
    fun `Shift 连选选中锚点到终点的连续一段`() {
        val r = click(selected = setOf(12L), anchor = 12L, id = 14L, shift = true)
        assertEquals(setOf(12L, 13L, 14L), r.selected)
        assertEquals(12L, r.anchor, "锚点不动：还要能继续调整终点")
    }

    @Test
    fun `Shift 反向连选也按可见顺序取区间`() {
        val r = click(selected = setOf(14L), anchor = 14L, id = 12L, shift = true)
        assertEquals(setOf(12L, 13L, 14L), r.selected)
    }

    @Test
    fun `Shift 连选替换原有勾选`() {
        val r = click(selected = setOf(11L, 15L), anchor = 12L, id = 14L, shift = true)
        assertEquals(setOf(12L, 13L, 14L), r.selected, "连选是重新框一段，不是在旧勾选上叠")
    }

    @Test
    fun `Shift 连选不会把锚点之前的勾选当成一段`() {
        val r = click(selected = setOf(11L), anchor = 13L, id = 15L, shift = true)
        assertEquals(setOf(13L, 14L, 15L), r.selected, "Ctrl+Shift 并入语义已删除，不得再并进 11")
        assertFalse(11L in r.selected)
    }

    @Test
    fun `连续两次 Shift 都从最初锚点起算`() {
        val first = click(selected = emptySet(), anchor = 11L, id = 14L, shift = true)
        assertEquals(setOf(11L, 12L, 13L, 14L), first.selected)
        // 第二次点 13：锚点仍是 11 → 区间收缩成 11..13（而不是从 14 往回选）
        val second = click(selected = first.selected, anchor = first.anchor, id = 13L, shift = true)
        assertEquals(setOf(11L, 12L, 13L), second.selected)
    }

    @Test
    fun `没有锚点时 Shift 退化为单选`() {
        val r = click(selected = setOf(1L, 2L), anchor = null, id = 13L, shift = true)
        assertEquals(setOf(13L), r.selected)
        assertEquals(13L, r.anchor, "退化之后要建立锚点，下一次 Shift 才有起点")
    }

    @Test
    fun `锚点被搜索过滤掉时 Shift 退化为单选`() {
        val r = click(selected = setOf(99L), anchor = 99L, id = 13L, shift = true)
        assertEquals(setOf(13L), r.selected, "不能从一张看不见的照片开始连选")
        assertEquals(13L, r.anchor)
    }

    @Test
    fun `普通点清空其它勾选并把锚点移到这张`() {
        val r = click(selected = setOf(11L, 12L), anchor = 11L, id = 14L)
        assertEquals(setOf(14L), r.selected)
        assertEquals(14L, r.anchor)
    }

    @Test
    fun `Shift 点自己只留这一张`() {
        val r = click(selected = setOf(11L, 13L, 15L), anchor = 13L, id = 13L, shift = true)
        assertEquals(setOf(13L), r.selected)
    }

    @Test
    fun `单选后 Shift 连选只框住屏幕上的这一段`() {
        // 普通点 12（锚点=12），再 Shift+点 15 → 12..15
        var r = click(selected = emptySet(), anchor = null, id = 12L)
        r = click(selected = r.selected, anchor = r.anchor, id = 15L, shift = true)
        assertEquals(setOf(12L, 13L, 14L, 15L), r.selected)
        assertEquals(4, operationTargets(r.selected, null).size, "连选的 4 张就是这次的操作对象")
    }

    @Test
    fun `红勾之后的 Shift 从红勾那张起算`() {
        // 红勾 13 只改勾选、不改预览；UiState 里锚点被设为 13 → Shift+点 15 得 13..15
        var selected = nextPhotoSelection(emptySet(), 13L, additive = true)
        var anchor: Long? = 13L
        val r = click(selected = selected, anchor = anchor, id = 15L, shift = true)
        selected = r.selected
        anchor = r.anchor
        assertTrue(13L in selected && 14L in selected && 15L in selected)
        assertEquals(13L, anchor)
    }
}
