// algorithm/DedupTieAnchorTest.kt —— 2026-11-07 跨目录重复副本：groupDupContent tie 全纳入回归
// 背景：--source 指向 ceshi 根目录时，PcPhotoSourceProvider 递归把「源目录 + 手机导入区间
//       子目录」两份照片都扫进来；同一内容的带水印主图在两处各一份，与同一个 _original 的
//       isSame 都成立、文件名序号差相同（tie）。旧实现 `if (diff < bestDiff)` 只挂最先遇到
//       的一个锚点，另一份主图无映射 → 「仅原图」下经 `preferred[id] ?: id` 原样输出。
package algorithm

import algorithm.model.Photo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DedupTieAnchorTest {

    private val t0 = 1_700_000_000_000L

    /** 与 DedupKeepModeTest 同构；dir 参数用于区分「同一内容在两个目录的副本」。 */
    private fun photo(id: Long, name: String, tMs: Long = t0, dir: String = "/src") =
        Photo(id, "$dir/$name", name, tMs, 23.25, 113.02)

    /**
     * 一组跨目录重复副本：主图 …478.jpg 与原图 …479_original.jpg 在 /root 与
     * /root/24_0000__24_2359 各一份（对应真实数据 110 主图 + 110 _original 的一对）。
     * 4 张的 GPS/时间都相同 → 两个 derived 与两个 anchor 的 isSame 全成立，序号差均为 1（tie）。
     */
    private fun crossDirCopies() = listOf(
        photo(1, "IMG_20261024_084736478.jpg", dir = "/root"),
        photo(2, "IMG_20261024_084736478.jpg", dir = "/root/24_0000__24_2359"),
        photo(3, "IMG_20261024_084736479_original.jpg", dir = "/root"),
        photo(4, "IMG_20261024_084736479_original.jpg", dir = "/root/24_0000__24_2359"),
    )

    /** 组结构归一化，便于两次运行逐项比较（Map.equals 不看顺序，这里把顺序也纳入）。 */
    private fun shape(groups: Map<Long, List<Photo>>): List<Pair<Long, List<Long>>> =
        groups.entries.map { (k, v) -> k to v.map { it.id } }

    // ── U1：跨目录同名主图 + 原图（tie）→ 仅原图只输出一个原图、无主图 ──
    @Test
    fun `U1 跨目录同名副本tie全纳入 仅原图只输出一个原图且无主图`() {
        val photos = crossDirCopies()
        val groups = PhotoIngest.groupDupContent(photos)

        // 两个等价锚点都建组，且每组都挂上两个 _original（tie 全纳入的核心断言）
        assertEquals(setOf(1L, 2L), groups.keys, "两份主图都必须成组，preferred 才能覆盖所有副本")
        assertEquals(listOf(3L, 4L), groups[1]!!.map { it.id })
        assertEquals(listOf(3L, 4L), groups[2]!!.map { it.id })

        val out = PhotoIngest.expandForKeepMode(
            listOf(1L, 2L, 3L, 4L), groups, DedupKeepMode.KEEP_PLAIN,
        )
        assertEquals(listOf(3L), out, "两个主图与两个原图全部折叠到同一原图 id，LinkedHashSet 按 id 去重后只剩 1 张")
        assertTrue(out.none { it == 1L || it == 2L }, "带水印主图不得出现在「仅原图」输出里")
    }

    // ── U2：单目录主图 + 原图（非 tie）→ 行为不变 ──
    @Test
    fun `U2 单目录主图加原图非tie 仅原图输出原图行为不变`() {
        val photos = listOf(
            photo(1, "IMG_20261024_084736478.jpg"),
            photo(2, "IMG_20261024_084736479_original.jpg"),
        )
        val groups = PhotoIngest.groupDupContent(photos)
        assertEquals(setOf(1L), groups.keys)
        assertEquals(listOf(2L), groups[1]!!.map { it.id })
        assertEquals(
            listOf(2L),
            PhotoIngest.expandForKeepMode(listOf(1L, 2L), groups, DedupKeepMode.KEEP_PLAIN),
        )
    }

    // ── U3：单份照片（手机自带相机，无派生后缀）→ 原样保留 ──
    @Test
    fun `U3 单份照片无派生 三态下原样保留`() {
        // 真实样本 IMG_20261024_095529.jpg：vivo 自带相机，无 _original，不得被一刀切误删
        val vivo = photo(9, "IMG_20261024_095529.jpg")
        val groups = PhotoIngest.groupDupContent(listOf(vivo))
        assertTrue(groups.isEmpty(), "无派生即不建组")
        for (mode in DedupKeepMode.entries) {
            assertEquals(listOf(9L), PhotoIngest.expandForKeepMode(listOf(9L), groups, mode),
                "单份照片在 $mode 下必须原样保留")
        }
    }

    // ── U4：跨目录但内容不同（文件名/时刻不同）→ 不合并，各输出 ──
    @Test
    fun `U4 跨目录不同内容不合并 各输出各自的原图`() {
        val photos = listOf(
            photo(1, "IMG_20261024_084736478.jpg", t0, "/root"),
            photo(2, "IMG_20261024_084736479_original.jpg", t0, "/root"),
            photo(3, "IMG_20261024_095529123.jpg", t0 + 3_000_000, "/root/sub"),
            photo(4, "IMG_20261024_095529124_original.jpg", t0 + 3_000_000, "/root/sub"),
        )
        val groups = PhotoIngest.groupDupContent(photos)
        assertEquals(setOf(1L, 3L), groups.keys, "相隔 50 分钟的两个瞬间各自成组，禁止跨瞬间并组")
        assertEquals(listOf(2L), groups[1]!!.map { it.id })
        assertEquals(listOf(4L), groups[3]!!.map { it.id })
        assertEquals(
            listOf(2L, 4L),
            PhotoIngest.expandForKeepMode(listOf(1L, 2L, 3L, 4L), groups, DedupKeepMode.KEEP_PLAIN),
            "两组各自保留自己的原图，不合并成一张",
        )
    }

    // ── U5：仅水印跨目录 → 行为不变（两张主图都保留，无原图）──
    @Test
    fun `U5 仅水印跨目录 两张主图都保留行为不变`() {
        val groups = PhotoIngest.groupDupContent(crossDirCopies())
        val out = PhotoIngest.expandForKeepMode(
            listOf(1L, 2L, 3L, 4L), groups, DedupKeepMode.KEEP_WATERMARKED,
        )
        // 旧实现（只挂第一个锚点）下：组 1 折叠为 1、锚点 2 无映射原样 → 也是 [1, 2]
        assertEquals(listOf(1L, 2L), out, "仅水印输出与修复前一致：两张带水印主图")
        assertTrue(out.none { it == 3L || it == 4L }, "原图不得出现在「仅水印」输出里")
    }

    // ── U6：两者都保留跨目录 → 行为不变（原样返回全部 id）──
    @Test
    fun `U6 两者都保留跨目录 原样返回全部照片行为不变`() {
        val groups = PhotoIngest.groupDupContent(crossDirCopies())
        val ids = listOf(1L, 2L, 3L, 4L)
        assertEquals(ids, PhotoIngest.expandForKeepMode(ids, groups, DedupKeepMode.KEEP_BOTH),
            "KEEP_BOTH 直接返回 photoIds，不受 groupDupContent 影响")
    }

    // ── U7：tie 稳定性 —— 同一批数据跑两次，组结构完全一致 ──
    @Test
    fun `U7 tie稳定性 同批数据两次分组结构完全一致`() {
        val photos = crossDirCopies()
        val first = shape(PhotoIngest.groupDupContent(photos))
        val second = shape(PhotoIngest.groupDupContent(photos))
        assertEquals(first, second, "groupDupContent 是纯函数，tie 全纳入后仍须顺序稳定")
        // 三态展开同样锁一次：两次结果必须一致（minByOrNull 稳定取首个最小者的依赖）
        val g = PhotoIngest.groupDupContent(photos)
        val ids = listOf(1L, 2L, 3L, 4L)
        assertEquals(
            PhotoIngest.expandForKeepMode(ids, g, DedupKeepMode.KEEP_PLAIN),
            PhotoIngest.expandForKeepMode(ids, g, DedupKeepMode.KEEP_PLAIN),
        )
        assertEquals(listOf(3L), PhotoIngest.expandForKeepMode(ids, g, DedupKeepMode.KEEP_PLAIN),
            "两组同分派生选到同一 id，仅原图稳定收敛为 1 张")
    }
}
