// data/ExifMergedReaderTest.kt —— C2-合并（readAll 一次构造）的正式回归测试
// 来源：PerfE2 实验 oracle 转正（保留逐字段相等 + 损坏三分类断言，作为合并实现的护城河）
// ① 真实样本对照：readAll 与「双构造基线」逐字段相等（时间 ms/纬度/经度）；源目录照片存在才跑，缺失则跳过
// ② 损坏三分类（合成样本，恒跑）：T1 0字节→(null,null)；T3 无EXIF→(null,null)
package com.photomgm.data

import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExifMergedReaderTest {

    private val reader = ExifPhotoReader()

    // 双构造基线：复刻合并前的独立 readGps/readCaptureTimeMs 语义（各自 runCatching 置 null）
    private fun baseline2x(sourceRef: String): Pair<Long?, Pair<Double, Double>?> =
        runCatching {
            val ei = ExifInterface(sourceRef)
            val t = runCatching {
                val raw = ei.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: ei.getAttribute(ExifInterface.TAG_DATETIME)
                    ?: return@runCatching null
                ExifTimeParser.parse(raw)
            }.getOrNull()
            val g = runCatching { ei.latLong?.let { Pair(it[0], it[1]) } }.getOrNull()
            t to g
        }.getOrNull() ?: (null to null)

    private fun collectRealSamples(): List<File> {
        val out = mutableListOf<File>()
        fun collectDir(root: File) {
            if (!root.exists()) return
            root.listFiles()?.forEach { f ->
                if (f.isDirectory) collectDir(f)
                else if (f.name.lowercase().endsWith(".jpg") || f.name.lowercase().endsWith(".jpeg")) out.add(f)
            }
        }
        collectDir(File("C:/Users/Administrator/Desktop/源目录/9-25"))
        collectDir(File("C:/Users/Administrator/Desktop/源目录/9-27"))
        return out
    }

    /** ① 真实样本 oracle：合并实现与双构造基线逐字段相等。 */
    @Test
    fun `合并与双构造基线逐字段一致_真实样本`() {
        val samples = collectRealSamples()
        assumeTrue("源目录照片不存在，跳过真实样本 oracle", samples.isNotEmpty())
        var mismatch = 0
        val bad = mutableListOf<String>()
        for (f in samples) {
            val base = baseline2x(f.absolutePath)
            val merged = reader.readAll(f.absolutePath)
            if (base.first != merged.first || base.second != merged.second) {
                mismatch++
                if (bad.size < 5) bad.add("${f.name}: base=(${base.first},${base.second}) merged=(${merged.first},${merged.second})")
            }
        }
        bad.forEach { println("  MISMATCH $it") }
        assertEquals("readAll 与双构造基线逐字段相等", 0, mismatch)
        println("MERGED_ORACLE_OK=$mismatch/${samples.size} (真实样本)")
    }

    /** ② 损坏 T1：0 字节/截断 → 构造异常 → (null, null)。 */
    @Test
    fun `损坏_零字节_时间与GPS均为null`() {
        val tmp = File(System.getProperty("java.io.tmpdir"), "merged_t1_${System.nanoTime()}.jpg").also {
            it.writeBytes(ByteArray(0)); it.deleteOnExit()
        }
        val r = reader.readAll(tmp.absolutePath)
        assertNull(r.first)
        assertNull(r.second)
    }

    /** ② 损坏 T3：无 EXIF 数据（纯文本改名）→ 构造成功字段全 null。 */
    @Test
    fun `损坏_无EXIF数据_时间与GPS均为null`() {
        val tmp = File(System.getProperty("java.io.tmpdir"), "merged_t3_${System.nanoTime()}.jpg").also {
            it.writeText("not a jpeg at all"); it.deleteOnExit()
        }
        val r = reader.readAll(tmp.absolutePath)
        assertNull(r.first)
        assertNull(r.second)
    }

    /** ② 损坏 T2：真实样本中「有时间无 GPS」照片（9-27 全量无 GPS）语义不被破坏。 */
    @Test
    fun `损坏_有时间无GPS_时间保留GPS为null`() {
        val t2 = collectRealSamples().firstOrNull {
            it.absolutePath.contains("9-27") && !it.name.contains("_original")
        }
        assumeTrue("9-27 照片缺失，跳过 T2 断言", t2 != null)
        val r = reader.readAll(t2!!.absolutePath)
        assertTrue("T2 应有拍摄时间", r.first != null)
        assertNull("T2 GPS 应为 null", r.second)
    }

    /** 兼容性：readCaptureTimeMs / readGps 委托 readAll 后行为不变（真实样本抽样）。 */
    @Test
    fun `独立读取方法委托readAll后行为一致`() {
        val samples = collectRealSamples()
        assumeTrue("源目录照片不存在，跳过委托一致性", samples.isNotEmpty())
        val f = samples.first()
        assertEquals(reader.readAll(f.absolutePath).first, reader.readCaptureTimeMs(f.absolutePath))
        assertEquals(reader.readAll(f.absolutePath).second, reader.readGps(f.absolutePath))
    }
}
