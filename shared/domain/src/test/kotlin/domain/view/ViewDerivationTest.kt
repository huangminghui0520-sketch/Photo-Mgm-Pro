// domain/view/ViewDerivationTest.kt —— 「人工调整后的有效归属」语义锁定（T07）
//
// 这些用例不是从两端测试搬过来的（两端都没有针对这三条函数的独立单测，它们只被
// 端到端测试间接覆盖）。写法上刻意**只断言输出结构**，不碰 UI、不碰文件，
// 这样同一组用例将来可以给安卓侧复用。
//
// 重点在四条容易写错、且错了也不会立刻报错的语义：
//   · 「移动」优先于「复制」：移进未匹配池后，复制份必须消失；
//   · 原地移动（target == 原事件）**不算**人工意图，不能进 passthrough；
//   · 三态展开只作用于事件内照片，未匹配不参与；
//   · 未知 id（陈旧缓存残留）既不能被移走，也不能被插进任何事件。
package domain.view

import algorithm.ClassifyResult
import algorithm.DedupKeepMode
import algorithm.model.Photo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ViewDerivationTest {

    private fun photo(id: Long, name: String = "IMG_$id.jpg", time: Long? = null) =
        Photo(id = id, sourceRef = "/src/$name", displayName = name, captureTimeMs = time, latitude = null, longitude = null)

    /** 事件 1：[1,2]，事件 2：[3]，未匹配：[4]。 */
    private fun baseClassify() = ClassifyResult(
        eventPhotoMap = linkedMapOf(1 to mutableListOf(1L, 2L), 2 to mutableListOf(3L)),
        unmatched = mutableListOf(4L),
        interventions = emptyList(),
        pendingEventIds = emptyList(),
    )

    private val layerEmpty = AdjustmentLayer.EMPTY

    // ── effectiveMap ──────────────────────────────────────────────────────

    @Test
    fun `无人为调整时事件表与未匹配池原样返回`() {
        val c = baseClassify()
        assertEquals(mapOf(1 to listOf(1L, 2L), 2 to listOf(3L)), ViewDerivation.effectiveMap(c, layerEmpty))
        assertEquals(listOf(4L), ViewDerivation.effectiveUnmatched(c, layerEmpty))
    }

    @Test
    fun `移动到另一事件_从原事件摘除并追加到目标末尾`() {
        val c = baseClassify()
        val adj = AdjustmentLayer(overrideMap = linkedMapOf(1L to 2))
        assertEquals(
            mapOf(1 to listOf(2L), 2 to listOf(3L, 1L)),
            ViewDerivation.effectiveMap(c, adj),
            "移动后必须从原事件消失，且追加在目标事件末尾（影响台账选首/末张）",
        )
    }

    @Test
    fun `移到负一_从所有事件摘除且不归入任何事件`() {
        val c = baseClassify()
        val adj = AdjustmentLayer(overrideMap = mapOf(2L to AdjustmentLayer.REMOVED))
        val m = ViewDerivation.effectiveMap(c, adj)
        assertEquals(mapOf(1 to listOf(1L), 2 to listOf(3L)), m)
        assertTrue(m.values.none { 2L in it }, "被移除的照片不得残留在任何事件里")
    }

    @Test
    fun `复制_原事件保留且目标新增一份`() {
        val c = baseClassify()
        val adj = AdjustmentLayer(copyMap = linkedMapOf(1L to setOf(2)))
        assertEquals(
            mapOf(1 to listOf(1L, 2L), 2 to listOf(3L, 1L)),
            ViewDerivation.effectiveMap(c, adj),
            "复制是多归属：源事件不能少一张",
        )
    }

    @Test
    fun `移入未匹配池后复制份失效_移动优先于复制`() {
        // 先复制到事件 2，再整张移出所有事件：结果里不该还有事件 2 的那一份。
        val c = baseClassify()
        val adj = AdjustmentLayer(
            overrideMap = mapOf(1L to AdjustmentLayer.REMOVED),
            copyMap = mapOf(1L to setOf(2)),
        )
        val m = ViewDerivation.effectiveMap(c, adj)
        assertTrue(m.values.none { 1L in it }, "移出后仍挂在别的事件里 = 同一张照片出现两处")
    }

    @Test
    fun `复制到已持有该照片的事件不会长出第二份`() {
        val c = baseClassify()
        val adj = AdjustmentLayer(copyMap = mapOf(1L to setOf(1)))
        assertEquals(listOf(1L, 2L), ViewDerivation.effectiveMap(c, adj)[1])
    }

    @Test
    fun `未知照片id既不摘除也不插入`() {
        // 陈旧 overrideMap（上一次分析遗留）里带一个本批次不存在的 id。
        val c = baseClassify()
        val adj = AdjustmentLayer(overrideMap = mapOf(999L to 2))
        assertEquals(
            mapOf(1 to listOf(1L, 2L), 2 to listOf(3L)),
            ViewDerivation.effectiveMap(c, adj),
        )
    }

    // ── effectiveUnmatched ────────────────────────────────────────────────

    @Test
    fun `未匹配池_移入事件则扣除_从事件移除则补进`() {
        val c = baseClassify()
        // 照片 3 从事件 2 移出 → 应进未匹配池；照片 4 从池里移入事件 1 → 应离开池。
        val adj = AdjustmentLayer(overrideMap = mapOf(3L to AdjustmentLayer.REMOVED, 4L to 1))
        assertEquals(listOf(3L), ViewDerivation.effectiveUnmatched(c, adj))
    }

    @Test
    fun `未匹配池不重复_同一张既被移除又本来就在池里`() {
        // 照片 4 本来就在未匹配池，又被显式"移除"一次：不能出现两次。
        val c = baseClassify()
        val adj = AdjustmentLayer(overrideMap = mapOf(4L to AdjustmentLayer.REMOVED))
        val out = ViewDerivation.effectiveUnmatched(c, adj)
        assertEquals(listOf(4L), out, "distinct 语义：导出时同一张不能写两次")
    }

    // ── expandedEventMap（三态展开） ──────────────────────────────────────

    /** 事件 1 里有一组重复：10 是主图（代表），11 是 _original 派生。 */
    private fun dupClassify() = ClassifyResult(
        eventPhotoMap = linkedMapOf(1 to mutableListOf(10L, 11L, 12L)),
        unmatched = mutableListOf(),
        interventions = emptyList(),
        pendingEventIds = emptyList(),
    )

    private fun dupGroups() = mapOf(
        10L to listOf(photo(10, "IMG_1.jpg"), photo(11, "IMG_1_original.jpg")),
    )

    @Test
    fun `都保留模式等于不做三态展开`() {
        val c = dupClassify()
        assertEquals(
            ViewDerivation.effectiveMap(c, layerEmpty),
            ViewDerivation.expandedEventMap(c, layerEmpty, DedupKeepMode.KEEP_BOTH, dupGroups(), emptyMap()),
        )
    }

    @Test
    fun `仅保留带水印_重复组折叠为代表`() {
        val c = dupClassify()
        val out = ViewDerivation.expandedEventMap(c, layerEmpty, DedupKeepMode.KEEP_WATERMARKED, dupGroups(), emptyMap())
        assertEquals(listOf(10L, 12L), out[1], "11 是派生副本，应折叠掉；12 不属于任何重复组，保留")
    }

    @Test
    fun `仅保留不带水印_折叠为派生副本`() {
        val c = dupClassify()
        val out = ViewDerivation.expandedEventMap(c, layerEmpty, DedupKeepMode.KEEP_PLAIN, dupGroups(), emptyMap())
        assertEquals(listOf(11L, 12L), out[1])
    }

    @Test
    fun `原地移动不算人工意图_仍参与三态折叠`() {
        // target == 原事件：UI 上等于没动。若它进了 passthrough，主图与 _original 会同时展示。
        val c = dupClassify()
        val adj = AdjustmentLayer(overrideMap = mapOf(10L to 1))
        val out = ViewDerivation.expandedEventMap(c, adj, DedupKeepMode.KEEP_WATERMARKED, dupGroups(), emptyMap())
        assertEquals(listOf(10L, 12L), out[1], "原地移动的照片必须仍被三态过滤，否则一组重复会冒出两张")
    }

    @Test
    fun `真正移到别的事件后_源事件整组不再展示`() {
        // 代表（主图）被移走 → 残留的派生副本不能顶替展示（2026-10-31 C 的「冒出备份」修复）。
        val c = dupClassify()
        val adj = AdjustmentLayer(overrideMap = mapOf(10L to 2))
        val out = ViewDerivation.expandedEventMap(c, adj, DedupKeepMode.KEEP_WATERMARKED, dupGroups(), emptyMap())
        assertEquals(listOf(12L), out[1], "源事件里只剩不属于重复组的 12；11 跟随整组抑制")
        assertEquals(listOf(10L), out[2], "被移走的那张出现在目标事件")
    }

    @Test
    fun `台账标记的照片压过三态过滤`() {
        val c = dupClassify()
        val out = ViewDerivation.expandedEventMap(
            c, layerEmpty, DedupKeepMode.KEEP_WATERMARKED, dupGroups(),
            marked = mapOf(1 to (11L to null)),
        )
        assertEquals(listOf(10L, 11L, 12L), out[1], "人工指定为台账照片的派生副本必须留在列表里")
    }

    // ── ownersOf / derive ────────────────────────────────────────────────

    @Test
    fun `owner集合升序且包含复制份`() {
        val c = baseClassify()
        val adj = AdjustmentLayer(copyMap = mapOf(1L to setOf(3, 2)))
        assertEquals(setOf(1, 2, 3), ViewDerivation.ownersOf(c, adj, 1L))
        assertEquals(listOf(1, 2, 3), ViewDerivation.ownersOf(c, adj, 1L).toList(), "必须是升序，差集比较才对得上")
        assertEquals(emptySet(), ViewDerivation.ownersOf(c, adj, 4L), "未匹配池里的照片不属于任何事件")
    }

    @Test
    fun `derive 三个视图与逐个调用结果一致`() {
        val c = dupClassify()
        val adj = AdjustmentLayer(overrideMap = mapOf(12L to 2))
        val v = ViewDerivation.derive(c, adj, DedupKeepMode.KEEP_PLAIN, dupGroups(), mapOf(1 to (null to 11L)))
        assertEquals(ViewDerivation.effectiveMap(c, adj), v.effectiveMap)
        assertEquals(ViewDerivation.expandedEventMap(c, adj, DedupKeepMode.KEEP_PLAIN, dupGroups(), mapOf(1 to (null to 11L))), v.eventPhotoMap)
        assertEquals(ViewDerivation.effectiveUnmatched(c, adj), v.unmatched)
    }
}
