package algorithm

import algorithm.model.LedgerRow
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.File

/** 生成真实 xlsx 文件（供 openpyxl / 结构校验），**非交付物**。 */
class GenRealXlsxTest {

    /**
     * ★ 2026-10-31：CI（Linux runner）上没有 D 盘开发目录，原实现会直接 FileNotFoundException。
     *   这个用例本质是"开发者本机工具"（产出文件给人看），不是断言；
     *   缺数据时应当**跳过**而不是失败——与 app 侧（ExifMergedReaderTest / AppViewModelTest）
     *   保持同一约定：依赖本机真实数据的用例，数据不在就跳过，不伪造"绿"。
     */
    @Test
    fun gen() {
        val outDir = File("D:/Photo-Mgm/Photo-Mgm/shots")
        assumeTrue(outDir.isDirectory, "无产物目录 ${outDir.path}（真实照片环境缺失），跳过")
        val jpg = File(outDir, "sample.jpg").let { f ->
            if (f.exists()) f.readBytes() else byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        }
        val loader: (String?) -> ByteArray? = { ref -> if (ref != null) jpg else null }
        val rows = listOf(
            LedgerRow("2026-09-03 08:30", "2026-09-03 10:00", "K138+700M（上行）", "交接班、检查装备", "IMG_01.jpg", "IMG_02.jpg"),
            LedgerRow("2026-09-03 09:05", "2026-09-03 09:35", "K145+000M（下行）", "路面障碍物清理 & 交安设施检查", "IMG_03.jpg", null),
            LedgerRow("", "", "K164+000M", "无时间事件示例 <测试>", null, null),
        )
        val bytes = ByteArrayOutputStream().also { XlsxWriter.write(rows, it, loader) }.toByteArray()
        File(outDir, "export_test.xlsx").writeBytes(bytes)
        println("WROTE ${bytes.size} bytes")
    }
}
