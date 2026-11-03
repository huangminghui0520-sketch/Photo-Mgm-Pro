// ViewDerivationParityTest.kt —— 双跑对比：PC 现行实现 vs shared/domain 新实现（T07 唯一验收证据）
//
// 为什么是「随机组合扫描」而不是十几个手写用例：
//   这段逻辑的输入空间是 4 个集合的笛卡尔积（overrideMap × copyMap × marked × keepMode），
//   手写用例只能覆盖我想到的组合，而 T07 的风险恰恰是「我没想到的那种组合下两端不同」。
//   固定种子（20261103）保证失败可复现 —— 不是"随机测试跑一次就过"。
//
// 比较口径（注意 Map 的 equals 不比较迭代顺序，但值是有序 List，所以事件内照片顺序仍被比较）：
//   · effectiveMap        逐事件逐个 id
//   · expandedEventMap    三种 keepMode 各比一次
//   · effectiveUnmatched  逐个 id（顺序也算）
//   · labelOf             四类边界
//
// ★ 这个测试在 T12 切换调用后会失效（PC 实现被删）。届时它的价值已经兑现：
//   等价性被证明过一次，切换是"换一个提供者"而不是"改一段逻辑"。
package com.photomgm.desktop.core

import algorithm.ClassifyResult
import algorithm.DedupKeepMode
import algorithm.PhotoIngest
import algorithm.expandForKeepMode
import algorithm.model.Photo
import com.photomgm.desktop.core.logic.PcViewDerivation
import domain.view.AdjustmentLayer
import domain.view.ViewDerivation
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ViewDerivationParityTest {

    private fun p(id: Long, name: String = "IMG_$id.jpg") =
        Photo(id = id, sourceRef = "/src/$name", displayName = name, captureTimeMs = null, latitude = null, longitude = null)

    /** 事件 1 里有一组重复：1 是主图、10 是 `_original` 派生；事件 3 是空事件（人工移动的落点）。 */
    private fun classify() = ClassifyResult(
        eventPhotoMap = linkedMapOf(
            1 to mutableListOf(1L, 2L, 10L),
            2 to mutableListOf(3L, 4L),
            3 to mutableListOf(),
        ),
        unmatched = mutableListOf(5L, 6L),
        interventions = emptyList(),
        pendingEventIds = emptyList(),
    )

    private fun dupGroups() = mapOf(1L to listOf(p(1), p(10, "IMG_1_original.jpg")))

    private val allIds = listOf(1L, 2L, 3L, 4L, 5L, 6L, 10L)

    // ── 主对比：随机组合 ──────────────────────────────────────────────────

    @Test
    fun `随机组合下 PC 与 domain 的三视图逐字段相等`() {
        val rnd = Random(20261103)
        val c = classify()
        val dup = dupGroups()
        var nonTrivial = 0

        repeat(400) {
            val overrideMap = LinkedHashMap<Long, Int>()
            for (id in allIds) {
                if (rnd.nextInt(100) < 45) overrideMap[id] = listOf(1, 2, 3, -1)[rnd.nextInt(4)]
            }
            val copyMap = LinkedHashMap<Long, MutableSet<Int>>()
            for (id in allIds) {
                if (rnd.nextInt(100) < 30) {
                    val targets = (1..3).filter { rnd.nextBoolean() }.toMutableSet()
                    if (targets.isNotEmpty()) copyMap[id] = targets
                }
            }
            val marked = LinkedHashMap<Int, Pair<Long?, Long?>>()
            for (ev in 1..3) {
                if (rnd.nextInt(100) < 50) {
                    marked[ev] = (allIds.random(rnd)) to if (rnd.nextBoolean()) allIds.random(rnd) else null
                }
            }
            val layer = AdjustmentLayer(overrideMap, copyMap)

            assertEquals(
                PcViewDerivation.effectiveMap(c, overrideMap, copyMap),
                ViewDerivation.effectiveMap(c, layer),
                "effectiveMap 不等：override=$overrideMap copy=$copyMap",
            )
            assertEquals(
                PcViewDerivation.effectiveUnmatched(c, overrideMap),
                ViewDerivation.effectiveUnmatched(c, layer),
                "effectiveUnmatched 不等：override=$overrideMap",
            )
            for (mode in DedupKeepMode.values()) {
                assertEquals(
                    PcViewDerivation.expandedEventMap(c, overrideMap, copyMap, mode, dup, marked),
                    ViewDerivation.expandedEventMap(c, layer, mode, dup, marked),
                    "expandedEventMap 不等：mode=$mode override=$overrideMap copy=$copyMap marked=$marked",
                )
            }
            if (overrideMap.isNotEmpty() || copyMap.isNotEmpty() || marked.isNotEmpty()) nonTrivial++
        }

        assertTrue(nonTrivial > 300, "绝大多数轮次必须是真正加了叠加层的，否则等于在比两个空输入：$nonTrivial/400")
    }

    // ── 分辨力反证 ────────────────────────────────────────────────────────

    @Test
    fun `双跑测试有分辨力_原地移动若被算作直通则输出不同`() {
        // 这条不是测实现，是测**测试本身**：如果我把 domain 的 passthrough 判据改成
        // "只要在 overrideMap 里就算直通"（一个很容易犯的错），上面的随机对比会立刻红。
        // 这里手工验证"那个错误实现"确实产出不同结果，证明上面那组断言不是空转。
        //
        // 必须用 KEEP_PLAIN 才看得出差别：KEEP_WATERMARKED 下"应保留版本"就是主图本身，
        // 直通与折叠结果恰好相同 —— 拿它做反证会得出"这条用例没有分辨力"的错误结论。
        val c = classify()
        val sameEventMove = mapOf(1L to 1)          // 在原地：target == 原事件
        val layer = AdjustmentLayer(overrideMap = sameEventMove)

        val correct = ViewDerivation.expandedEventMap(c, layer, DedupKeepMode.KEEP_PLAIN, dupGroups(), emptyMap())
        val ifPassthroughIncluded = PhotoIngest.expandForKeepMode(
            c.eventPhotoMap[1]!!, dupGroups(), DedupKeepMode.KEEP_PLAIN,
            keepAlways = emptySet(), passthrough = setOf(1L),
        )
        // ★ 期望值是 [2, 10] 而不是 [10, 2]：原地移动虽然不算"人工意图"（不进 passthrough），
        //   但仍会经过 effectiveMap 的"先摘除、再追加到末尾"，于是照片 1 被挪到了列表尾部。
        //   这是既有行为，不是本步引入的 —— T12 接上 PhotoOrder 之后，顺序才会由时间/文件名决定。
        assertEquals(listOf(2L, 10L), correct[1], "原地移动不该绕过三态折叠：1 与 10 应折叠成 10（不带水印版）")
        assertNotEquals(correct[1], ifPassthroughIncluded, "若两者相同，说明这条用例测不出 passthrough 判据")
    }

    @Test
    fun `双跑测试有分辨力_复制份被计入直通则输出不同`() {
        // 另一个容易犯的错：把 copyMap 的键也当直通。复制不改变归属，不该影响三态折叠。
        val c = classify()
        val layer = AdjustmentLayer(copyMap = mapOf(1L to setOf(2)))
        val correct = ViewDerivation.expandedEventMap(c, layer, DedupKeepMode.KEEP_PLAIN, dupGroups(), emptyMap())
        assertEquals(listOf(10L, 2L), correct[1], "复制到事件 2 后，事件 1 仍按 KEEP_PLAIN 折叠为 _original")

        val ifCopyCounted = PhotoIngest.expandForKeepMode(
            c.eventPhotoMap[1]!!, dupGroups(), DedupKeepMode.KEEP_PLAIN,
            keepAlways = emptySet(), passthrough = setOf(1L),
        )
        assertNotEquals(correct[1], ifCopyCounted)
    }

    // ── labelOf 边界 ──────────────────────────────────────────────────────

    @Test
    fun `labelOf 四类边界与 PC 一致`() {
        val photos = listOf(p(1, "IMG_A.jpg"), p(2, "IMG_B.jpg"))
        val byId = photos.associateBy { it.id }
        val ref = photos[0].sourceRef

        assertEquals(
            PcViewDerivation.labelOf("01_巡查", ref, byId),
            domain.ledger.LedgerProjection.labelOf("01_巡查", ref, photos.associateBy { it.sourceRef }),
        )
        // 反查不到 → 退化为最后一段
        assertEquals("01_巡查/IMG_Z.jpg", PcViewDerivation.labelOf("01_巡查", "/src/IMG_Z.jpg", byId))
        assertEquals(
            PcViewDerivation.labelOf("01_巡查", "/src/IMG_Z.jpg", byId),
            domain.ledger.LedgerProjection.labelOf("01_巡查", "/src/IMG_Z.jpg", emptyMap()),
        )
        // 空 ref
        assertEquals(null, PcViewDerivation.labelOf("01_巡查", null, byId))
        assertEquals(null, domain.ledger.LedgerProjection.labelOf("01_巡查", null, emptyMap()))
        // 空 folderName
        assertEquals("IMG_A.jpg", PcViewDerivation.labelOf("", ref, byId))
        assertEquals("IMG_A.jpg", domain.ledger.LedgerProjection.labelOf("", ref, photos.associateBy { it.sourceRef }))
    }
}
