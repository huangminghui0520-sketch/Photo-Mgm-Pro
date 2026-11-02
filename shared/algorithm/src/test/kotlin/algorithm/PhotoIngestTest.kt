// PhotoIngest 单元测试（§4.2~§4.4 / §9 测试要点）
package algorithm

import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhotoIngestTest {
    private val day = LocalDate.of(2026, 8, 14)

    private fun c(id: Long, ref: String, name: String) = CandidatePhoto(id, ref, name)

    // ---- 目录白名单 ----

    @Test fun `前缀分隔符匹配`() {
        assertTrue(PhotoIngest.inSourceDir("DCIM/Camera/a.jpg", "DCIM/Camera"))
        assertTrue(PhotoIngest.inSourceDir("DCIM/Camera", "DCIM/Camera"))
        assertTrue(PhotoIngest.inSourceDir("DCIM/Camera/sub/b.jpg", "DCIM/Camera"))
    }

    @Test fun `不误配同名前缀目录`() {
        assertFalse(PhotoIngest.inSourceDir("DCIM/CameraOld/a.jpg", "DCIM/Camera"))
        assertFalse(PhotoIngest.inSourceDir("DCIM/Camera2/a.jpg", "DCIM/Camera"))
        assertFalse(PhotoIngest.inSourceDir("DCIM/CameraExtra/b.jpg", "DCIM/Camera"))
    }

    // ---- 粗筛：白名单 + 日期 + 去重 ----

    @Test fun `粗筛保留日期内且在白名单内的照片`() {
        val candidates = listOf(
            c(1, "DCIM/Camera/IMG_20260814_101530.jpg", "IMG_20260814_101530.jpg"),
            c(2, "DCIM/Camera/IMG_20260815_090000.jpg", "IMG_20260815_090000.jpg"),   // 超出日期
            c(3, "DCIM/CameraOld/IMG_20260814_120000.jpg", "IMG_20260814_120000.jpg"), // 目录越界
            c(4, "Pictures/IMG_20260814_130000.jpg", "IMG_20260814_130000.jpg"),       // 不在白名单
        )
        val out = PhotoIngest.coarseFilter(candidates, listOf("DCIM/Camera"), day..day)
        assertEquals(listOf("IMG_20260814_101530.jpg"), out.map { it.displayName })
    }

    @Test fun `粗筛去重保留首现`() {
        val candidates = listOf(
            c(1, "DCIM/Camera/IMG_20260814_101530.jpg", "IMG_20260814_101530.jpg"),
            c(9, "content://media/IMG_20260814_101530.jpg", "IMG_20260814_101530.jpg"), // 同文件不同引用
        )
        val out = PhotoIngest.coarseFilter(candidates, listOf("DCIM/Camera"), day..day)
        assertEquals(1, out.size)
        assertEquals(1L, out[0].id)
    }

    @Test fun `sourceDirs为空不过滤目录`() {
        val candidates = listOf(
            c(1, "D:/photos/IMG_20260814_101530.jpg", "IMG_20260814_101530.jpg"),
            c(2, "D:/photos/IMG_20260815_090000.jpg", "IMG_20260815_090000.jpg"),
        )
        val out = PhotoIngest.coarseFilter(candidates, emptyList(), day..day)
        assertEquals(1, out.size)
    }

    // ---- EXIF 精读编排 ----

    private class FakeExif : ExifReader {
        override fun readCaptureTimeMs(sourceRef: String): Long? =
            if (sourceRef.contains("good")) 1780000000000L else null
        override fun readGps(sourceRef: String): Pair<Double, Double>? =
            if (sourceRef.contains("good")) 23.1 to 113.2 else null
    }

    @Test fun `精读成功字段齐全`() {
        val p = PhotoIngest.readExif(listOf(c(1, "DCIM/Camera/good.jpg", "a.jpg")), FakeExif())
        assertEquals(1L, p[0].id)
        assertEquals(1780000000000L, p[0].captureTimeMs)
        assertEquals(23.1, p[0].latitude)
        assertEquals(113.2, p[0].longitude)
    }

    @Test fun `精读失败字段置null不中断`() {
        val ps = PhotoIngest.readExif(
            listOf(
                c(1, "DCIM/Camera/bad.jpg", "a.jpg"),
                c(2, "DCIM/Camera/good.jpg", "b.jpg"),
            ),
            FakeExif(),
        )
        assertNull(ps[0].captureTimeMs)
        assertNull(ps[0].latitude)
        assertNull(ps[0].longitude)
        assertEquals(1780000000000L, ps[1].captureTimeMs)  // 前一条失败不中断后续
    }

    @Test fun `按id去重`() {
        val photos = listOf(
            algorithm.model.Photo(5, "a", "1.jpg", null, null, null),
            algorithm.model.Photo(5, "b", "1.jpg", null, null, null),
            algorithm.model.Photo(7, "c", "2.jpg", null, null, null),
        )
        val out = PhotoIngest.dedupById(photos)
        assertEquals(2, out.size)
        assertEquals(listOf(5L, 7L), out.map { it.id })
    }

    // ---- 探针测试：缺陷回归（先 RED 后 GREEN）----

    // ★ 缺陷回归：Android 10+ MediaStore DATA 列返回物理路径（/storage/emulated/0/DCIM/Camera/IMG.jpg），
    //   源目录为相对路径（DCIM/Camera）。inSourceDir 必须同时匹配物理路径（后缀）与相对路径（前缀），
    //   否则粗筛把全部照片过滤掉 → 0 张（用户实测"粗选仍无照片"）。
    @Test fun `物理路径sourceRef匹配相对路径源目录`() {
        assertTrue(PhotoIngest.inSourceDir(
            "/storage/emulated/0/DCIM/Camera/IMG_20260814_101530.jpg", "DCIM/Camera"))
        assertTrue(PhotoIngest.inSourceDir(
            "/storage/emulated/0/DCIM/Camera/sub/IMG_20260814_101530.jpg", "DCIM/Camera"))
        assertTrue(PhotoIngest.inSourceDir(
            "D:/photos/DCIM/Camera/IMG_20260814_101530.jpg", "DCIM/Camera"))
    }

    @Test fun `物理路径sourceRef不误配同名前缀目录`() {
        assertFalse(PhotoIngest.inSourceDir(
            "/storage/emulated/0/DCIM/CameraOld/IMG_20260814_101530.jpg", "DCIM/Camera"))
        assertFalse(PhotoIngest.inSourceDir(
            "/storage/emulated/0/DCIM/CameraExtra/IMG_20260814_101530.jpg", "DCIM/Camera"))
        assertFalse(PhotoIngest.inSourceDir(
            "/storage/emulated/0/Other/DCIM/CameraBackup/IMG_20260814_101530.jpg", "DCIM/Camera"))
    }

    @Test fun `粗筛接受物理路径sourceRef`() {
        val candidates = listOf(
            c(1, "/storage/emulated/0/DCIM/Camera/IMG_20260814_101530.jpg", "IMG_20260814_101530.jpg"),
            c(2, "/storage/emulated/0/DCIM/CameraOld/IMG_20260814_120000.jpg", "IMG_20260814_120000.jpg"),
            c(3, "/storage/emulated/0/DCIM/Camera/IMG_20260815_090000.jpg", "IMG_20260815_090000.jpg"),
        )
        val out = PhotoIngest.coarseFilter(candidates, listOf("DCIM/Camera"), day..day)
        assertEquals(listOf("IMG_20260814_101530.jpg"), out.map { it.displayName })
    }

    @Test fun `PC端无意义id为0时不得按id去重`() {
        val photos = listOf(
            algorithm.model.Photo(0, "a.jpg", "a.jpg", null, null, null),
            algorithm.model.Photo(0, "b.jpg", "b.jpg", null, null, null),
            algorithm.model.Photo(0, "c.jpg", "c.jpg", null, null, null),
        )
        val out = PhotoIngest.dedupById(photos)
        assertEquals(3, out.size, "PC 端 id 均为 0（无意义），去重不得误删到 1 张")
    }

    @Test fun `源目录带尾部斜杠仍匹配`() {
        assertTrue(PhotoIngest.inSourceDir("DCIM/Camera/a.jpg", "DCIM/Camera/"))
        assertTrue(PhotoIngest.inSourceDir("DCIM/Camera/sub/b.jpg", "DCIM/Camera/"))
    }

    @Test fun `跨日加班次日照片在粗筛范围内保留`() {
        // 契约（§4.2）：末条跨日加班（23:30~次日01:00）的次日照片，调用方须传 end=次日；范围内保留、范围外剔除
        val candidates = listOf(
            c(1, "DCIM/Camera/IMG_20260904_003000.jpg", "IMG_20260904_003000.jpg"),
            c(2, "DCIM/Camera/IMG_20260905_080000.jpg", "IMG_20260905_080000.jpg"),
        )
        val out = PhotoIngest.coarseFilter(
            candidates,
            listOf("DCIM/Camera"),
            LocalDate.of(2026, 9, 3)..LocalDate.of(2026, 9, 4),
        )
        assertEquals(listOf("IMG_20260904_003000.jpg"), out.map { it.displayName })
    }

    // ---- 派生文件基名去重（2026-10-01 新增，修复压缩版无 EXIF 全部落未分类） ----

    @Test fun `派生文件按基名去重保留主图`() {
        // 9-25 实例实际形态：主图 + _original + _compressed + _original_compressed 四份同源文件
        val candidates = listOf(
            c(1, "a/IMG_20260925_080504148.jpg", "IMG_20260925_080504148.jpg"),
            c(2, "a/IMG_20260925_080504148_original.jpg", "IMG_20260925_080504148_original.jpg"),
            c(3, "a/IMG_20260925_080504148_compressed.jpeg", "IMG_20260925_080504148_compressed.jpeg"),
            c(4, "a/IMG_20260925_080504148_original_compressed.jpeg", "IMG_20260925_080504148_original_compressed.jpeg"),
        )
        val (out, merged) = PhotoIngest.dedupDerived(candidates)
        assertEquals(1, out.size)
        assertEquals("IMG_20260925_080504148.jpg", out[0].displayName, "保留主图（无派生后缀）")
        assertEquals(3, merged, "合并组数 = 被去重文件数")
    }

    @Test fun `无主图时_original优先于_compressed`() {
        val candidates = listOf(
            c(1, "a/IMG_20260925_080504148_compressed.jpeg", "IMG_20260925_080504148_compressed.jpeg"),
            c(2, "a/IMG_20260925_080504148_original.jpg", "IMG_20260925_080504148_original.jpg"),
        )
        val (out, _) = PhotoIngest.dedupDerived(candidates)
        assertEquals(1, out.size)
        assertEquals("IMG_20260925_080504148_original.jpg", out[0].displayName, "_original 优先于 _compressed")
    }

    @Test fun `不同基名不合并`() {
        val candidates = listOf(
            c(1, "a/IMG_20260925_080504148.jpg", "IMG_20260925_080504148.jpg"),
            c(2, "a/IMG_20260925_080504149.jpg", "IMG_20260925_080504149.jpg"),
            c(3, "a/IMG_20260925_080504149_compressed.jpeg", "IMG_20260925_080504149_compressed.jpeg"),
        )
        val (out, merged) = PhotoIngest.dedupDerived(candidates)
        assertEquals(2, out.size)
        assertEquals(1, merged)
        assertEquals("IMG_20260925_080504149.jpg", out[1].displayName, "压缩版被其主图替换")
    }

    /**
     * ★ 2026-10-31 审查回归：不同目录下的同名文件是**两张不同照片**，不是同一张的主图/派生副本。
     *   早期分组键只取基名 → 其中一张真实照片被判为副本静默丢弃（相机重置计数后重新拍摄的
     *   IMG_0001.jpg、或多个源目录下的备份目录同名文件，都是典型场景）。
     */
    @Test fun `不同目录同名文件不互相合并`() {
        val candidates = listOf(
            c(1, "a/IMG_20260925_080504148.jpg", "IMG_20260925_080504148.jpg"),
            c(2, "b/IMG_20260925_080504148.jpg", "IMG_20260925_080504148.jpg"),
        )
        val (out, merged) = PhotoIngest.dedupDerived(candidates)
        assertEquals(2, out.size, "跨目录同名属两张不同照片，都应保留")
        assertEquals(0, merged, "不得把其中一张当成副本合并掉")
    }

    /** 回归保护：分组键加入目录维度后，同目录内的派生去重行为必须保持不变。 */
    @Test fun `同目录同名派生副本仍然合并`() {
        val candidates = listOf(
            c(1, "a/IMG_20260925_080504148.jpg", "IMG_20260925_080504148.jpg"),
            c(2, "a/IMG_20260925_080504148_compressed.jpeg", "IMG_20260925_080504148_compressed.jpeg"),
        )
        val (out, merged) = PhotoIngest.dedupDerived(candidates)
        assertEquals(1, out.size)
        assertEquals(1, merged)
    }

    @Test fun `派生后缀大小写不敏感且仅当紧跟扩展名`() {
        val candidates = listOf(
            c(1, "a/IMG_20260925_080504148_Compressed.JPEG", "IMG_20260925_080504148_Compressed.JPEG"),
            c(2, "a/IMG_20260925_080504148.jpg", "IMG_20260925_080504148.jpg"),
            c(3, "a/IMG_20260925_compressed_080504148.jpg", "IMG_20260925_compressed_080504148.jpg"), // 非派生位置，不合并
        )
        val (out, merged) = PhotoIngest.dedupDerived(candidates)
        assertEquals(2, out.size)
        assertEquals(1, merged)
    }

    // ---- ★ 2026-10-24 方案B'：内容哈希双层判定（水印相机同序号 original 误杀修复） ----

    /** 哈希表版 hashReader：ref -> SHA（缺项 = null，模拟读失败）。 */
    private fun hashOf(vararg pairs: Pair<String, String?>): (String) -> String? {
        val m = pairs.toMap()
        return { ref -> m[ref] }
    }

    @Test fun `水印相机同序号original哈希不同保留为独立照片`() {
        // 真机证据：IMG_20261024_101014661.jpg + IMG_20261024_101014661_original.jpg
        // 同一次快门（EXIF 同刻同 GPS），内容 = 主图+水印合成 → 哈希不同 → original 必须保留
        val candidates = listOf(
            c(1, "a/IMG_20261024_101014661.jpg", "IMG_20261024_101014661.jpg"),
            c(2, "a/IMG_20261024_101014661_original.jpg", "IMG_20261024_101014661_original.jpg"),
        )
        val (out, merged) = PhotoIngest.dedupDerived(
            candidates,
            hashOf("a/IMG_20261024_101014661.jpg" to "AA", "a/IMG_20261024_101014661_original.jpg" to "BB"),
        )
        assertEquals(2, out.size, "哈希不同 + _original → 独立照片保留（bug 修复）")
        assertEquals(0, merged)
        assertEquals("IMG_20261024_101014661_original.jpg", out[1].displayName)
    }

    @Test fun `同内容复制副本哈希一致仍合并`() {
        // 未来某相机真输出"同内容 _original 副本" → 哈希一致 → 应合并（安全网，不重复）
        val candidates = listOf(
            c(1, "a/IMG_x.jpg", "IMG_x.jpg"),
            c(2, "a/IMG_x_original.jpg", "IMG_x_original.jpg"),
        )
        val (out, merged) = PhotoIngest.dedupDerived(
            candidates,
            hashOf("a/IMG_x.jpg" to "SAME", "a/IMG_x_original.jpg" to "SAME"),
        )
        assertEquals(1, out.size)
        assertEquals("IMG_x.jpg", out[0].displayName)
        assertEquals(1, merged)
    }

    @Test fun `压缩副本哈希不同仍按加工副本合并`() {
        // 9-25 场景：主图 + _compressed（压缩编码内容不同）→ 必须合并，避免压缩版落未分类
        val candidates = listOf(
            c(1, "a/IMG_20260925_080504148.jpg", "IMG_20260925_080504148.jpg"),
            c(2, "a/IMG_20260925_080504148_compressed.jpeg", "IMG_20260925_080504148_compressed.jpeg"),
        )
        val (out, merged) = PhotoIngest.dedupDerived(
            candidates,
            hashOf("a/IMG_20260925_080504148.jpg" to "A", "a/IMG_20260925_080504148_compressed.jpeg" to "B"),
        )
        assertEquals(1, out.size)
        assertEquals("IMG_20260925_080504148.jpg", out[0].displayName)
        assertEquals(1, merged)
    }

    @Test fun `original哈希读取失败保守保留`() {
        // 哈希不可读（content:// URI / 文件被删）→ 保守保留 original（宁可多留，不丢独立照片）
        val candidates = listOf(
            c(1, "content://media/IMG_y.jpg", "IMG_y.jpg"),
            c(2, "content://media/IMG_y_original.jpg", "IMG_y_original.jpg"),
        )
        val (out, merged) = PhotoIngest.dedupDerived(
            candidates,
            hashOf("content://media/IMG_y.jpg" to "A"),   // original 哈希缺项 → null
        )
        assertEquals(2, out.size)
        assertEquals(0, merged)
    }

    @Test fun `主图哈希失败original哈希成功仍保留original`() {
        val candidates = listOf(
            c(1, "content://media/IMG_z.jpg", "IMG_z.jpg"),
            c(2, "content://media/IMG_z_original.jpg", "IMG_z_original.jpg"),
        )
        val (out, merged) = PhotoIngest.dedupDerived(
            candidates,
            hashOf("content://media/IMG_z_original.jpg" to "B"),   // 主图哈希缺项 → 无法判定一致 → 保守保留
        )
        assertEquals(2, out.size)
        assertEquals(0, merged)
    }

    @Test fun `无主图时哈希双层判定仍保留original合并compressed`() {
        val candidates = listOf(
            c(1, "a/IMG_w_compressed.jpeg", "IMG_w_compressed.jpeg"),
            c(2, "a/IMG_w_original.jpg", "IMG_w_original.jpg"),
        )
        val (out, merged) = PhotoIngest.dedupDerived(
            candidates,
            hashOf("a/IMG_w_compressed.jpeg" to "X", "a/IMG_w_original.jpg" to "Y"),
        )
        assertEquals(1, out.size)
        assertEquals("IMG_w_original.jpg", out[0].displayName, "基准 = 分数最小（_original）")
        assertEquals(1, merged, "压缩版为加工副本 → 合并")
    }
}
