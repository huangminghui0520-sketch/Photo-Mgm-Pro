// algorithm/XlsxPoiReadbackTest.kt —— 用 Apache POI **读回**校验（2026-10-31 审查补强）
//
// 为什么要有这个类：
// 本轮 F1（行高超 409.5pt）与 W1（WebP/HEIC 被误声明为 image/png）都属于
// "写出来的 xlsx 打不开 / 不可用"。原先的断言方式是匹配 XML 字符串片段，只能证明
// "我们写进去了什么"，证明不了"Excel 读出来是什么"。改用 POI 按**语义**读回：
// 单元格文本与类型、行高磅值、嵌入图片的实际字节——这三者正是这两类故障的真实判据。
//
// 覆盖范围：F1（行高）、W1（图片格式）、以及 XML 转义/非法字符（POI 解析即失败）。
// 不覆盖： drawing 锚点像素级定位（那是呈现细节，仍由 XlsxWriterTest 的字符串断言负责）。
package algorithm

import algorithm.model.LedgerRow
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class XlsxPoiReadbackTest {

    /** 最小合法 JPEG（SOI + APP0 + EOI）。 */
    private val jpeg = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
        0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01,
        0xFF.toByte(), 0xD9.toByte(),
    )

    /** 最小合法 PNG 头（8 字节签名 + 若干）。 */
    private val png = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x01,
    )

    /** WebP：RIFF + size + "WEBP"（Excel 不支持）。 */
    private val webp = byteArrayOf(
        0x52, 0x49, 0x46, 0x46, 0x1A, 0x00, 0x00, 0x00, 0x57, 0x45, 0x42, 0x50,
    )

    /** HEIC：size + "ftyp" + brand "heic"（Excel 不支持）。 */
    private val heic = byteArrayOf(
        0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70,
        0x68, 0x65, 0x69, 0x63, 0x00, 0x00, 0x00, 0x00,
    )

    private fun row(
        desc: String = "护栏检查 & 修复",
        p1: String? = "DCIM/Camera/IMG_001.jpg",
        p2: String? = null,
        folder: String = "",
    ) = LedgerRow("2026-09-03 08:30", "2026-09-03 10:00", "K138+700M", desc, p1, p2, folder)

    private fun write(rows: List<LedgerRow>, loader: (String?) -> ByteArray? = { null }): ByteArray =
        ByteArrayOutputStream().also { XlsxWriter.write(rows, it, loader) }.toByteArray()

    private fun open(bytes: ByteArray): XSSFWorkbook = XSSFWorkbook(ByteArrayInputStream(bytes))

    private fun isJpeg(b: ByteArray) =
        b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte()

    private fun isPng(b: ByteArray) =
        b.size >= 8 && b[0] == 0x89.toByte() && b[1] == 0x50.toByte() &&
            b[2] == 0x4E.toByte() && b[3] == 0x47.toByte()

    @Test fun `POI能打开台账并按语义读回单元格`() {
        open(write(listOf(row()))).use { wb ->
            assertEquals(1, wb.numberOfSheets, "只应有一张工作表")
            val sheet = wb.getSheetAt(0)
            assertEquals("巡查台账", sheet.sheetName)
            assertEquals("序号", sheet.getRow(0).getCell(0).stringCellValue, "表头首列")

            val r = sheet.getRow(1)
            assertEquals(CellType.NUMERIC, r.getCell(0).cellType, "序号必须是数字（可排序求和）")
            assertEquals(1.0, r.getCell(0).numericCellValue, 0.0)

            val dateCell = r.getCell(1)
            assertEquals(CellType.NUMERIC, dateCell.cellType, "B 列是真实日期序列号")
            assertTrue(DateUtil.isCellDateFormatted(dateCell), "B 列须套日期格式，否则显示成数字")

            assertEquals("K138+700M", r.getCell(3).stringCellValue)
            assertEquals("护栏检查 & 修复", r.getCell(4).stringCellValue, "& 必须被正确转义后读回")
            assertEquals("IMG_001.jpg", r.getCell(5).stringCellValue, "照片列写文件名")
        }
    }

    /**
     * ★ F1 回归（用 POI 读真实行高，而不是正则匹配 XML）。
     * Excel/WPS 行高规范上限 409.5pt；数据行写的是 customHeight="1"（禁止自动收敛），
     * 超限会触发「发现部分内容有问题」，修复失败则**整份台账不可用**。
     */
    @Test fun `POI读回_超长日志的行高不超过Excel上限`() {
        val longDesc = (1..60).joinToString("\n") { "第${it}段检查内容" }   // 未钳制约 1064pt
        open(write(listOf(row(desc = longDesc)))).use { wb ->
            val h = wb.getSheetAt(0).getRow(1).heightInPoints
            assertTrue(h <= 409.5, "行高 $h 超过 Excel 上限 409.5pt（会触发修复提示、可能导致整表不可用）")
        }
    }

    /**
     * ★ 2026-11-02 台账排版一轮，用 POI 按**语义**读回（而不是匹配 XML 字符串）：
     *
     *   ① B/C 两列显示格式必须是 `hh:mm`（不是 `yyyy-mm-dd hh:mm`）；
     *      值仍是完整日期序列号 —— 所以跨零点（23:30 → 01:00）排序/相减依然正确。
     *   ② 全表每个单元格 水平居中 + 垂直居中 + 自动换行。
     *   ③ 数据行行高 = 照片高度 81pt。
     */
    @Test fun `POI读回_时间为时分且全表居中换行`() {
        open(write(listOf(row()))).use { wb ->
            val sheet = wb.getSheetAt(0)
            val fmt = wb.createDataFormat()
            val r = sheet.getRow(1)

            assertEquals("时间", sheet.getRow(0).getCell(1).stringCellValue, "B 列表头应为「时间」")
            for (col in listOf(1, 2)) {
                val c = r.getCell(col)
                assertEquals(CellType.NUMERIC, c.cellType, "第 $col 列应是数值（可排序）")
                assertEquals("hh:mm", fmt.getFormat(c.cellStyle.dataFormat), "第 $col 列应只显示 时:分")
                // 底层保留日期：08:30 的序列号必然 > 1 天，只存"当日秒数"会是 0.35
                assertTrue(c.numericCellValue > 1.0, "第 $col 列底层必须保留完整日期（跨零点才对）")
            }

            var checked = 0
            for (rowIdx in 0..sheet.lastRowNum) {
                val row = sheet.getRow(rowIdx) ?: continue
                for (i in 0 until row.lastCellNum) {
                    val cell = row.getCell(i) ?: continue
                    val st = cell.cellStyle
                    assertEquals(org.apache.poi.ss.usermodel.HorizontalAlignment.CENTER, st.alignment,
                        "R${rowIdx + 1}C${i + 1} 应水平居中")
                    assertEquals(org.apache.poi.ss.usermodel.VerticalAlignment.CENTER, st.verticalAlignment,
                        "R${rowIdx + 1}C${i + 1} 应垂直居中")
                    assertTrue(st.wrapText, "R${rowIdx + 1}C${i + 1} 应启用自动换行")
                    checked++
                }
            }
            assertTrue(checked >= 14, "应检查到整表单元格（表头 7 + 数据 7），实际 $checked")

            assertEquals(81f, r.heightInPoints, 0.01f, "数据行行高应等于照片高度 81pt")
        }
    }

    /**
     * ★ 2026-11-02「台账照片格对齐」：用 POI 按**语义**读回行高与列宽。
     *
     * 目标（用户口径）：含照片的行行高 = 照片高 2.86cm（=81pt），F/G 列宽 = 照片宽 3.81cm。
     * 列宽用 20 字符（Excel 实测读回 108 磅 = 3.81cm，见 XlsxWriter.CM_PER_COL_CHAR 的注释）。
     * 同时校验 A~E 列宽**没被顺手改掉**（"其他内容与格式保持不变"）。
     */
    @Test fun `POI读回_照片行行高与FG列宽等于照片尺寸`() {
        open(write(listOf(row(), row()))).use { wb ->
            val sheet = wb.getSheetAt(0)

            for (r in 1..2) {
                assertEquals(81f, sheet.getRow(r).heightInPoints, 0.01f,
                    "第 ${r + 1} 行行高应 = 照片高 2.8575cm（81pt）")
            }

            for (col in listOf(5, 6)) {
                val chars = sheet.getColumnWidth(col) / 256.0
                assertEquals(20.0, chars, 0.01, "第 ${col + 1} 列应 20 字符（= 照片宽 3.81cm / 108 磅）")
            }

            // 其余列宽保持原样（A:8 B:18 C:18 D:24 E:60）
            for ((col, w) in listOf(0 to 8, 1 to 18, 2 to 18, 3 to 24, 4 to 60)) {
                assertEquals(w.toDouble(), sheet.getColumnWidth(col) / 256.0, 0.01, "第 ${col + 1} 列宽应保持 $w")
            }
        }
    }

    /**
     * ★ 回归：`<sheetViews>` 必须在部件里。
     *
     * 缺它时 Excel 在非 100% 缩放下会把 `ht` 按 96DPI 像素重新解释（81pt → 64.8pt），
     * 照片却按 EMU 绝对尺寸渲染 81pt，于是照片比行高、上下压行。
     * POI 读回的行高是文件里的原始值，看不出这个问题，所以这条只能断言"部件在不在"。
     */
    @Test fun `sheetViews部件存在`() {
        open(write(listOf(row()))).use { wb ->
            val xml = wb.getSheetAt(0).ctWorksheet.xmlText()
            assertTrue(xml.contains("sheetView"), "worksheet 必须带 sheetViews（否则 Excel 会缩行高）")
        }
    }

    /** ★ W1 回归：嵌入的每张图片，**实际字节**必须与其声明的 ContentType 相符。 */
    @Test fun `POI读回_嵌入图片的字节与其声明类型一致`() {
        var n = 0
        val loader: (String?) -> ByteArray? = { if (n++ == 0) jpeg else png }
        open(write(listOf(row(p1 = "a.jpg", p2 = "b.png")), loader)).use { wb ->
            val pics = wb.allPictures
            assertEquals(2, pics.size, "两张都应是 Excel 支持的格式，都被嵌入")
            for (pd in pics) {
                val b = pd.data
                val magic = when {
                    isJpeg(b) -> "image/jpeg"
                    isPng(b) -> "image/png"
                    else -> "UNKNOWN"
                }
                assertTrue(magic != "UNKNOWN", "嵌入了 Excel 不支持的图片格式（mime=${pd.mimeType}）")
                assertEquals(magic, pd.mimeType, "声明的 ContentType 与实际字节不符 → Excel 会报文件损坏")
            }
        }
    }

    /** ★ W1 回归：WebP / HEIC 一律跳过嵌入，但照片列仍保留文件名（信息不丢）。 */
    @Test fun `POI读回_WebP与HEIC不出现在图片中且退化为文件名`() {
        val rows = listOf(row(p1 = "a.webp", p2 = "b.heic"))
        val loader: (String?) -> ByteArray? = { ref -> if (ref?.endsWith(".webp") == true) webp else heic }
        val bytes = write(rows, loader)
        open(bytes).use { wb ->
            assertTrue(wb.allPictures.isEmpty(), "不可识别格式不得被嵌入（否则 Excel 报文件损坏）")
            val r = wb.getSheetAt(0).getRow(1)
            assertEquals("a.webp", r.getCell(5).stringCellValue, "照片1 退化为文件名")
            assertEquals("b.heic", r.getCell(6).stringCellValue, "照片2 退化为文件名")
        }
    }

    /** 混合场景：跳过 WebP 后，JPEG 仍以 image1 连续编号写入（rels 不得指向空部件）。 */
    @Test fun `POI读回_跳过不可嵌入格式后剩余图片编号连续`() {
        val loader: (String?) -> ByteArray? = { ref -> if (ref?.endsWith(".webp") == true) webp else jpeg }
        open(write(listOf(row(p1 = "a.webp", p2 = "b.jpg")), loader)).use { wb ->
            assertEquals(1, wb.allPictures.size, "只嵌入 JPEG 那一张")
            assertTrue(isJpeg(wb.allPictures[0].data), "嵌入的应是 JPEG")
            assertEquals("image/jpeg", wb.allPictures[0].mimeType)
        }
    }
}
