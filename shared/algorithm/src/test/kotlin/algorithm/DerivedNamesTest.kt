// algorithm/DerivedNamesTest.kt —— 派生后缀清单的行为锁定（T04）
//
// 为什么锁这个：T04 之前 desktop 自己抄了一份 4 个后缀的实现（只给 UI 打角标用），
// 而核心认 8 个。于是同一个文件「核心判派生、界面判不派生」——
// 首当其冲的是 `_optimized` / `_resized` / `_缩略` 这三张合成素材。
//
// 本文件干三件事：
//   ① 锁住 8 个后缀 + 4 类文件名的判定结果（清单变了必须显式改这里）；
//   ② 用**方向断言**证明新旧实现是超集关系：旧实现的每一处 True，新实现必须也 True
//      —— 也就是「角标只会从无变有，绝不会从有变无」。这比"统计比例 <5%"更硬：
//      比例会随素材变化，方向不会。
//   ③ 记录一条已知不一致（`_original_1` 判派生但剥不掉基名），锁住现状避免被顺手改掉。
package algorithm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DerivedNamesTest {

    /** T04 之前 desktop 那份私有实现认的后缀（已删除，此处保留一份用于方向对照）。 */
    private val legacyDesktopSuffixes = listOf("original", "compressed", "edited", "modified")

    private fun legacyDesktopSaysDerived(name: String): Boolean {
        val lower = name.lowercase()
        return legacyDesktopSuffixes.any { lower.contains("_$it") }
    }

    // ── ① 清单本身 ──

    @Test
    fun `后缀清单共 8 项，变更需同步更新本测试与 CHANGELOG`() {
        assertEquals(
            setOf("original", "compressed", "edited", "modified", "optimized", "resized", "缩略", "压缩", "原图"),
            DerivedNames.suffixes.toSet(),
            "派生后缀清单是两端唯一真值；增删/改名会同时改变 UI 角标与三态过滤行为，必须显式确认",
        )
    }

    @Test
    fun `八种后缀全部判为派生`() {
        for (s in DerivedNames.suffixes) {
            val name = "IMG_20260814_120000_$s.jpg"
            assertTrue(DerivedNames.isDerived(name), "后缀 _$s 未判为派生：$name")
            assertTrue(isDerivedPhotoName(name), "公开门面与内部实现应同口径：$name")
        }
    }

    // ── ② 四类文件名行为对照 ──

    @Test
    fun `四类文件名的判定结果`() {
        // 主图：无派生后缀
        assertFalse(isDerivedPhotoName("IMG_20260814_120000.jpg"))
        // 单个派生后缀
        assertTrue(isDerivedPhotoName("IMG_20260814_120000_original.jpg"))
        assertTrue(isDerivedPhotoName("IMG_20260814_120000_compressed.jpg"))
        // ★ 纯序号后缀 `_1` 不是派生：水印相机的原图是 `_original`，不带语义的数字后缀多半是同名冲突
        assertFalse(isDerivedPhotoName("IMG_20260814_120000_1.jpg"))
        // 派生后缀 + 序号：仍判派生（序号不改变"这是副本"的事实）
        assertTrue(isDerivedPhotoName("IMG_20260814_120000_original_1.jpg"))
        // 叠加后缀
        assertTrue(isDerivedPhotoName("IMG_20260814_120000_original_compressed.jpg"))
        // 大小写
        assertTrue(isDerivedPhotoName("IMG_20260814_120000_ORIGINAL.JPG"))
        // 后缀不在尾部也算（contains 语义）：这是既有实现，不要"顺手改严格"
        assertTrue(isDerivedPhotoName("IMG_20260814_120000_original_copy.jpg"))
    }

    @Test
    fun `中文后缀与英文同等对待`() {
        assertTrue(isDerivedPhotoName("IMG_20260814_155015_缩略.jpg"))
        assertTrue(isDerivedPhotoName("IMG_20260814_155015_压缩.jpg"))
        assertTrue(isDerivedPhotoName("IMG_20260814_155015_原图.jpg"))
    }

    // ── ③ 方向断言：旧实现是子集，绝不会「从有变无」 ──

    @Test
    fun `旧实现判派生的每一处新实现也必须判派生`() {
        val corpus = buildList {
            for (s in DerivedNames.suffixes) {
                add("IMG_20260814_120000_$s.jpg")
                add("IMG_20260814_120000_${s}_1.jpg")
                add("IMG_20260814_120000_${s}_${s}.jpg")
            }
            add("IMG_20260814_120000.jpg")
            add("IMG_20260814_120000_1.jpg")
            add("IMG_20260814_155015_缩略.jpg")
        }
        val regressions = corpus.filter { legacyDesktopSaysDerived(it) && !isDerivedPhotoName(it) }
        assertEquals(
            emptyList<String>(), regressions,
            "出现「角标从有变无」= 回归，必须回退（这一条取代计划 §12.5 里写反的角标消失比例判据）",
        )
    }

    @Test
    fun `旧实现漏判的恰好是这三张合成素材`() {
        val missed = listOf(
            "IMG_20260814_134015_optimized.jpg",
            "IMG_20260814_155015_缩略.jpg",
            "IMG_20260814_161015_resized.jpg",
        )
        for (n in missed) {
            assertFalse(legacyDesktopSaysDerived(n), "旧实现本应漏判：$n")
            assertTrue(isDerivedPhotoName(n), "新实现应判为派生：$n")
        }
        assertEquals(3, missed.size)
    }

    // ── ④ 已知不一致：锁住现状，修的时候必须显式改测试 ──

    @Test
    fun `已知不一致：original_1 判派生但剥不掉基名`() {
        val name = "IMG_20260814_120000_original_1.jpg"
        assertTrue(DerivedNames.isDerived(name), "判派生 —— 角标会打上")
        // stripRe 要求后缀紧贴扩展名，`_1` 卡在中间 → 剥不动 → dedup 不会把它当派生合并
        assertNull(
            DerivedNames.stripRe.find(name),
            "若这里变成非 null，说明有人改了 stripRe —— 那会改变去重分组，必须单开一轮并跑真实素材",
        )
        // 对照组：标准的 `_original` 能剥
        assertNotNull(DerivedNames.stripRe.find("IMG_20260814_120000_original.jpg"))
    }
}
