// algorithm/XlsxWriterTest.kt —— §7.3 Excel 写入测试（导出Excel 2.0 方案）
package algorithm

import algorithm.model.LedgerRow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class XlsxWriterTest {
    private fun row(date: String = "2026-09-03 08:30",
                    end: String = "2026-09-03 10:00",
                    loc: String = "K138+700M", desc: String = "护栏检查 & 修复",
                    p1: String? = "DCIM/Camera/IMG_20260903_083001.jpg",
                    p2: String? = null,
                    folder: String = "") =
        LedgerRow(date, end, loc, desc, p1, p2, folder)

    private fun entries(bytes: ByteArray): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                out += e.name to z.readBytes().toString(Charsets.UTF_8)
            }
        }
        return out
    }

    @Test fun `xlsx包含全部OOXML部件`() {
        val bytes = ByteArrayOutputStream().also { XlsxWriter.write(listOf(row()), it) }.toByteArray()
        val names = entries(bytes).map { it.first }
        assertTrue("[Content_Types].xml" in names)
        assertTrue("_rels/.rels" in names)
        assertTrue("xl/workbook.xml" in names)
        assertTrue("xl/_rels/workbook.xml.rels" in names)
        assertTrue("xl/worksheets/sheet1.xml" in names)
        assertTrue("xl/styles.xml" in names)
    }

    @Test fun `表头7列与数据行写入sheet`() {
        val bytes = ByteArrayOutputStream().also { XlsxWriter.write(listOf(row()), it) }.toByteArray()
        val sheet = entries(bytes).first { it.first == "xl/worksheets/sheet1.xml" }.second
        // 2026-11-02：B 列由「日期」改名「时间」（只显示 时:分）
        for (h in listOf("序号", "时间", "结束时间", "地点", "日志内容", "照片1", "照片2")) {
            assertTrue(sheet.contains("<t xml:space=\"preserve\">$h</t>"), "表头缺 $h")
        }
        assertTrue(sheet.contains("""<c r="B2" s="2"><v>46268.35"""), "B 列应为真实时间序列号")
        assertTrue(sheet.contains("""<c r="C2" s="2"><v>46268.4166"""), "C 列结束时间应为真实时间序列号")
        assertTrue(sheet.contains("K138+700M"))
        assertTrue(sheet.contains("护栏检查 &amp; 修复"))
    }

    @Test fun `时间与结束时间为真实时间类型且只显示时分`() {
        val bytes = ByteArrayOutputStream().also { XlsxWriter.write(listOf(row()), it) }.toByteArray()
        val sheet = entries(bytes).first { it.first == "xl/worksheets/sheet1.xml" }.second
        // P2-2：2026-09-03 08:30 → 序列号 46268.35417；10:00 → 46268.41667
        // ★ 2026-11-02：底层仍是完整日期序列号（排序/跨日正确），只把显示格式改成 hh:mm
        assertTrue(sheet.contains("""<c r="B2" s="2"><v>46268.35"""), "B2 序列号(2026-09-03 08:30)")
        assertTrue(sheet.contains("""<c r="C2" s="2"><v>46268.4166"""), "C2 序列号(2026-09-03 10:00)")
        assertTrue(!sheet.contains("<t xml:space=\"preserve\">2026-09-03 08:30</t>"), "不应再写文本日期")
        val styles = entries(bytes).first { it.first == "xl/styles.xml" }.second
        assertTrue(styles.contains("""numFmtId="164""""), "styles 应有自定义时间格式 164")
        assertTrue(styles.contains("""formatCode="hh:mm""""), "格式码应为 hh:mm（只显示 时:分）")
        assertTrue(!styles.contains("""formatCode="yyyy-mm-dd hh:mm""""), "不应再保留完整日期时间格式")
        assertTrue(styles.contains("""cellXfs count="3""""), "cellXfs 应为 3 条（0 默认/1 通用居中换行/2 时间）")
    }

    /**
     * ★ 2026-11-02：**所有单元格**统一 水平居中 + 垂直居中 + 自动换行。
     *
     * 背景：原先只有 E 列套了 wrapText 且是 `vertical="top"`，其余单元格连 s 属性都没有，
     * 于是同一行里序号靠右、地点靠左、日志顶端对齐，打印出来参差不齐。
     */
    @Test fun `所有单元格居中且自动换行`() {
        val bytes = ByteArrayOutputStream().also {
            XlsxWriter.write(listOf(row(), row(loc = "K1+000", desc = "第二行")), it)
        }.toByteArray()
        val es = entries(bytes)
        val sheet = es.first { it.first == "xl/worksheets/sheet1.xml" }.second
        val styles = es.first { it.first == "xl/styles.xml" }.second

        // 1) 两个对齐 xf（通用 / 时间）都必须写全三条属性
        assertEquals(2, Regex("""horizontal="center"""").findAll(styles).count(), "两个 xf 都要水平居中")
        assertEquals(2, Regex("""vertical="center"""").findAll(styles).count(), "两个 xf 都要垂直居中")
        assertEquals(2, Regex("""wrapText="1"""").findAll(styles).count(), "两个 xf 都要自动换行")

        // 2) 表头 + 两行数据的每个单元格都必须带样式（不带 s 就落回默认 xf，等于没居中）
        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = false }.newDocumentBuilder()
            .parse(org.xml.sax.InputSource(java.io.StringReader(sheet)))
        val cells = doc.getElementsByTagName("c")
        assertTrue(cells.length >= 21, "3 行 × 7 列，实际 ${cells.length}")
        var unstyled = 0
        for (i in 0 until cells.length) {
            val c = cells.item(i) as org.w3c.dom.Element
            val s = c.getAttribute("s")
            if (s != "1" && s != "2") unstyled++
        }
        assertEquals(0, unstyled, "存在没有套居中样式的单元格")
    }

    @Test fun `空日期回退文本且不报错`() {
        val bytes = ByteArrayOutputStream().also {
            XlsxWriter.write(listOf(row(date = "", end = "")), it)
        }.toByteArray()
        val sheet = entries(bytes).first { it.first == "xl/worksheets/sheet1.xml" }.second
        assertTrue(sheet.contains("""<c r="B2" s="1" t="inlineStr">"""), "空日期 B2 回退为文本单元格（仍套居中样式）")
        assertTrue(!sheet.contains("""<c r="B2" s="2">"""), "空日期不应套时间样式")
    }

    @Test fun `异常日期字符串回退文本`() {
        val bytes = ByteArrayOutputStream().also {
            XlsxWriter.write(listOf(row(date = "上午巡查", end = "09-03 10:00")), it)
        }.toByteArray()
        val sheet = entries(bytes).first { it.first == "xl/worksheets/sheet1.xml" }.second
        assertTrue(sheet.contains("""<t xml:space="preserve">上午巡查</t>"""), "异常日期回退文本")
        assertTrue(sheet.contains("""<t xml:space="preserve">09-03 10:00</t>"""), "异常结束时间回退文本")
    }

    @Test fun `跨日结束时间序列号正确`() {
        // 09-03 23:30 开始 → 46268.97917；09-04 01:00 结束 → 46269.04167（+1 天）
        val bytes = ByteArrayOutputStream().also {
            XlsxWriter.write(listOf(row(date = "2026-09-03 23:30", end = "2026-09-04 01:00")), it)
        }.toByteArray()
        val sheet = entries(bytes).first { it.first == "xl/worksheets/sheet1.xml" }.second
        assertTrue(sheet.contains("""<c r="B2" s="2"><v>46268.9791"""), "B2=09-03 23:30 序列号")
        assertTrue(sheet.contains("""<c r="C2" s="2"><v>46269.0416"""), "C2=09-04 01:00 序列号(+1 天)")
    }

    @Test fun `序号为程序生成行号`() {
        val bytes = ByteArrayOutputStream().also {
            XlsxWriter.write(listOf(row(), row(), row()), it)
        }.toByteArray()
        val sheet = entries(bytes).first { it.first == "xl/worksheets/sheet1.xml" }.second
        // 数据行首列 = 1/2/3（A2/A3/A4），且为数字单元格（无 t 属性），并套居中样式 s="1"
        assertTrue(sheet.contains("""<c r="A2" s="1"><v>1</v></c>"""), "A2 应为数字 1")
        assertTrue(sheet.contains("""<c r="A3" s="1"><v>2</v></c>"""), "A3 应为数字 2")
        assertTrue(sheet.contains("""<c r="A4" s="1"><v>3</v></c>"""), "A4 应为数字 3")
    }

    @Test fun `列宽与行高符合方案`() {
        val bytes = ByteArrayOutputStream().also { XlsxWriter.write(listOf(row()), it) }.toByteArray()
        val sheet = entries(bytes).first { it.first == "xl/worksheets/sheet1.xml" }.second
        // 列宽 A:8 B:18 C:18 D:24 E:60；F/G = 照片宽 3.81cm → 20 字符（Excel 读回 108 磅）
        for ((min, w) in listOf(1 to "8", 2 to "18", 3 to "18", 4 to "24", 5 to "60", 6 to "20", 7 to "20")) {
            assertTrue(sheet.contains("""<col min="$min" max="$min" width="$w" customWidth="1"/>"""),
                "列宽 $min 应为 $w")
        }
        // 行高：表头 25、数据 81（= 照片高 2.8575cm ≈ 2.86cm）
        assertTrue(sheet.contains("""<row r="1" ht="25" customHeight="1">"""), "表头行高 25")
        assertTrue(sheet.contains("""<row r="2" ht="81" customHeight="1">"""), "数据行高 81")
    }

    /**
     * ★ 2026-11-02「照片格尺寸对齐」的另一半：**必须写 `<sheetViews>`**。
     *
     * 没有这段视图声明时，Excel 会把 `ht` 当成"96 DPI 像素"重新解释：125% 缩放的 Windows
     * 上我们写的 81pt 被读成 64.8pt，而照片按 EMU 绝对尺寸渲染仍是 81pt —— 每张照片都
     * 比行高出一截、上下压行。用户反馈的"行高和照片高度不一致"正是这个。
     * 实测对照（Excel 365 / 120 DPI）：无 sheetViews → 读回 64.8pt、行距 64.8；
     * 有 sheetViews → 读回 81pt、行距 81，与照片等高。
     */
    @Test fun `sheetViews存在以保证Excel按磅渲染行高`() {
        val bytes = ByteArrayOutputStream().also { XlsxWriter.write(listOf(row()), it) }.toByteArray()
        val sheet = entries(bytes).first { it.first == "xl/worksheets/sheet1.xml" }.second
        assertTrue(
            sheet.contains("""<sheetViews><sheetView tabSelected="1" workbookViewId="0"/></sheetViews>"""),
            "缺 sheetViews 时 Excel 在非 100% 缩放下会把行高缩成 0.8 倍（照片压行）"
        )
        // 顺序：sheetViews 必须在 <cols> 之前（CT_Worksheet 的元素次序）
        assertTrue(sheet.indexOf("<sheetViews>") < sheet.indexOf("<cols>"), "sheetViews 应在 cols 之前")
    }

    /**
     * ★ 照片尺寸与行高/列宽的一致性：三者都由同一组 EMU 常量推出来，改任一处都会红。
     */
    @Test fun `照片尺寸与行高列宽三者一致`() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10,
            0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0xFF.toByte(), 0xD9.toByte())
        val bytes = ByteArrayOutputStream().also { XlsxWriter.write(listOf(row()), it) { jpeg } }.toByteArray()
        val sheet = entries(bytes).first { it.first == "xl/worksheets/sheet1.xml" }.second
        val drawing = entries(bytes).first { it.first == "xl/drawings/drawing1.xml" }.second

        // ① 照片：3.81cm × 2.8575cm（EMU）→ 108 磅 × 81 磅
        assertTrue(drawing.contains("""<xdr:ext cx="1371600" cy="1028700"/>"""), "照片应为 3.81×2.8575cm")
        assertEquals(108.0, 1371600.0 / 12700.0, 0.0, "照片宽 1371600 EMU = 108 磅 = 3.81cm")
        assertEquals(81.0, 1028700.0 / 12700.0, 0.0, "照片高 1028700 EMU = 81 磅 = 2.8575cm")

        // ② 行高 = 照片高（磅）
        assertTrue(sheet.contains("""<row r="2" ht="81" customHeight="1">"""), "数据行行高应 = 照片高 81pt")

        // ③ 列宽 = 照片宽：3.81cm ÷ 0.1905cm/字符 = 20 字符（Excel 实测读回 108 磅）
        assertTrue(sheet.contains("""<col min="6" max="6" width="20" customWidth="1"/>"""), "F 列宽应 = 照片宽")
        assertTrue(sheet.contains("""<col min="7" max="7" width="20" customWidth="1"/>"""), "G 列宽应 = 照片宽")

        // ④ 照片锚点 colOff/rowOff = 0 → 严丝合缝占满这一格，没有偏移留白
        assertTrue(drawing.contains("<xdr:colOff>0</xdr:colOff>"), "照片左偏移应为 0")
        assertTrue(drawing.contains("<xdr:rowOff>0</xdr:rowOff>"), "照片上偏移应为 0")
    }

    @Test fun `日志内容列应用wrapText样式`() {
        val bytes = ByteArrayOutputStream().also { XlsxWriter.write(listOf(row()), it) }.toByteArray()
        val sheet = entries(bytes).first { it.first == "xl/worksheets/sheet1.xml" }.second
        // E2 单元格应用样式 1（wrapText）
        assertTrue(sheet.contains("""<c r="E2" s="1" t="inlineStr">"""), "日志内容列 E2 应带 s=1")
        val styles = entries(bytes).first { it.first == "xl/styles.xml" }.second
        assertTrue(styles.contains("""wrapText="1""""), "styles.xml 应有 wrapText 对齐")
    }

    /**
     * ★ XML 非法控制字符必须被剔除。
     *
     * 背景：escape 原先只转义 5 个实体，不过滤控制字符。日志从 Word/PDF 粘贴常带 \v(0x0B)/\f(0x0C)，
     * 而 XML 1.0 完全禁止这些码点 —— 一个字符就让 sheet1.xml 无法解析、Excel 报"文件损坏"，
     * 整个台账打不开（与历史上的 `s="1` 缺陷同级）。
     */
    @Test fun `日志含XML非法控制字符时仍为良构XML`() {
        val dirty = "巡查\u000B发现\u000C护栏\u0000损坏\u001F，已\u0007处理"
        val bytes = ByteArrayOutputStream().also {
            XlsxWriter.write(listOf(row(desc = dirty)), it)
        }.toByteArray()
        val sheet = entries(bytes).first { it.first == "xl/worksheets/sheet1.xml" }.second
        // 必须能被解析（这一步就验证了非法字符已被剔除）
        javax.xml.parsers.DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = false }.newDocumentBuilder()
            .parse(org.xml.sax.InputSource(java.io.StringReader(sheet)))
        // 合法内容要保留，非法控制字符要消失
        assertTrue(sheet.contains("巡查") && sheet.contains("护栏") && sheet.contains("处理"), "应保留可见文字")
        assertTrue(!sheet.contains('\u000B'), "不应残留 \\u000B")
        assertTrue(!sheet.contains('\u000C'), "不应残留 \\u000C")
        assertTrue(!sheet.contains('\u0000'), "不应残留 \\u0000")
        assertTrue(!sheet.contains('\u001F'), "不应残留 \\u001F")
    }

    /** 序号必须是数字单元格（无 t 属性），否则 Excel 报"数字以文本存储"且无法排序求和。 */
    @Test fun `序号列为数字单元格而非文本`() {
        val bytes = ByteArrayOutputStream().also {
            XlsxWriter.write(listOf(row(), row()), it)
        }.toByteArray()
        val sheet = entries(bytes).first { it.first == "xl/worksheets/sheet1.xml" }.second
        assertTrue(sheet.contains("""<c r="A2" s="1"><v>1</v></c>"""), "A2 应为数字单元格")
        assertTrue(sheet.contains("""<c r="A3" s="1"><v>2</v></c>"""), "A3 应为数字单元格")
        assertTrue(!sheet.contains("""<c r="A2" s="1" t="inlineStr">"""), "序号不应再是 inlineStr 文本")
    }

    /**
     * ★ 2026-11-02：数据行行高**恒等于照片高度**（81pt），不再随日志内容增长。
     *
     * 背景：行高按文本行数增长时，照片被垂直居中在长行里 —— 一行 300 字就能把照片顶到
     * 半空中，上下各留一大片空白。现在行行等高、照片正好填满一行，台账可以直接按行装订。
     * 代价（有意为之）：超长日志在单元格内被裁切，完整原文在 `export.log` 与事件文件夹里。
     */
    @Test fun `数据行行高恒等于照片高度`() {
        fun rowHeightOf(desc: String): Int {
            val bytes = ByteArrayOutputStream().also {
                XlsxWriter.write(listOf(row(desc = desc)), it)
            }.toByteArray()
            val sheet = entries(bytes).first { it.first == "xl/worksheets/sheet1.xml" }.second
            return Regex("""<row r="2" ht="(\d+)"""").find(sheet)!!.groupValues[1].toInt()
        }
        assertEquals(81, rowHeightOf("短"), "短日志行高应为照片高 81pt")
        assertEquals(81, rowHeightOf((1..10).joinToString("\n") { "短" }), "10 个换行也不应撑高")
        assertEquals(81, rowHeightOf("巡".repeat(300)), "300 字也不应撑高（避免照片在行内悬空）")
        // 照片高 1028700 EMU ÷ 12700 EMU/pt = 81pt：行高与照片高度是同一个数
        assertEquals(81, 1028700 / 12700)
    }

    /**
     * ★ 图片必须固定尺寸（oneCellAnchor + a:ext），不能随行高拉伸。
     *
     * 背景：原先用 twoCellAnchor 锚满"1 列 × 1 行"，而数据行高随日志长度增长。
     * 日志超过约 150 字时行高 > 81pt，图片被纵向拉伸（300 字 → 168pt → 2.07 倍），
     * 4:3 取证照片失真。改为 oneCellAnchor 固定尺寸后，行高怎么变图片都是 4:3。
     * ★ 2026-11-02：行高已恒等于照片高度，这里同时钉住"长短日志行高一致、图片尺寸一致、
     *   rowOff 恒为 0（照片正好填满一行，不垂直居中、不留白）"。
     */
    @Test fun `图片锚定为固定尺寸不随行高拉伸`() {
        val jpg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10,
            0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0xFF.toByte(), 0xD9.toByte())
        val loader: (String?) -> ByteArray? = { ref -> if (ref != null) jpg else null }

        fun drawingOf(desc: String): Pair<Int, String> {
            val bytes = ByteArrayOutputStream().also {
                XlsxWriter.write(listOf(row(p1 = "a.jpg", p2 = "b.jpg", desc = desc)), it, loader)
            }.toByteArray()
            val sheet = entries(bytes).first { it.first == "xl/worksheets/sheet1.xml" }.second
            val h = Regex("""<row r="2" ht="(\d+)"""").find(sheet)!!.groupValues[1].toInt()
            return h to entries(bytes).first { it.first == "xl/drawings/drawing1.xml" }.second
        }

        val (hShort, dShort) = drawingOf("护栏检查")
        val (hLong, dLong) = drawingOf("巡".repeat(300))

        // 前提：短/长描述行高一致（都等于照片高度），这本身就是本轮的验收点
        assertEquals(81, hShort, "短描述行高应为照片高 81pt")
        assertEquals(81, hLong, "长描述行高也应为 81pt（不再随内容增长）")

        // 断言 1：用 oneCellAnchor（固定尺寸），不是 twoCellAnchor（随行高拉伸）
        assertTrue(dShort.contains("<xdr:oneCellAnchor>"), "应使用 oneCellAnchor")
        assertTrue(!dShort.contains("<xdr:twoCellAnchor"), "不应再用 twoCellAnchor")

        // 断言 2：尺寸由 a:ext 显式给出，且 4:3（1371600 × 1028700 EMU）
        assertTrue(
            dShort.contains("""<xdr:ext cx="1371600" cy="1028700"/>"""),
            "应有固定尺寸 a:ext（4:3）",
        )
        // 断言 3：rowOff 恒为 0 —— 照片高 = 行高，不需要任何垂直偏移
        assertTrue(dShort.contains("""<xdr:rowOff>0</xdr:rowOff>"""), "照片应贴行顶（rowOff=0）")

        // 断言 4：核心——行高不变，锚定尺寸也必须完全一致
        val extShort = Regex("""<xdr:ext cx="\d+" cy="\d+"/>""").find(dShort)?.value
        val extLong = Regex("""<xdr:ext cx="\d+" cy="\d+"/>""").find(dLong)?.value
        assertEquals(extShort, extLong, "行高变化时图片尺寸必须保持不变")
        // 断言 5：drawing XML 良构
        javax.xml.parsers.DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = false }.newDocumentBuilder()
            .parse(org.xml.sax.InputSource(java.io.StringReader(dLong)))
    }

    /**
     * ★ 2026-11-02：照片嵌进去了，照片下方就不再写任何文字。
     *
     * 原先照片列还会写 `文件夹名/文件名`：照片占满整格后，那行字被照片压住/溢出到下一行，
     * 已勾选"照片底部不显示文字"的场景里属于多余信息。现在嵌成功就留空单元格。
     */
    @Test fun `照片嵌入后单元格不再写文字`() {
        val jpg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10,
            0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0xFF.toByte(), 0xD9.toByte())
        val loader: (String?) -> ByteArray? = { ref -> if (ref != null) jpg else null }
        val bytes = ByteArrayOutputStream().also {
            XlsxWriter.write(listOf(row(p1 = "01_事件/IMG_01.jpg", p2 = "01_事件/IMG_02.jpg")), it, loader)
        }.toByteArray()
        val sheet = entries(bytes).first { it.first == "xl/worksheets/sheet1.xml" }.second
        assertTrue(!sheet.contains("IMG_01.jpg"), "照片1 已嵌入，不应再写文件名")
        assertTrue(!sheet.contains("IMG_02.jpg"), "照片2 已嵌入，不应再写文件名")
        // 空单元格也要存在且带样式：否则整行只有 5 个格子，列样式与行高对不齐
        assertTrue(sheet.contains("""<c r="F2" s="1"/>"""), "照片1 应为带样式的空单元格")
        assertTrue(sheet.contains("""<c r="G2" s="1"/>"""), "照片2 应为带样式的空单元格")
    }

    /**
     * ★ 后置条件：整个数据行必须是良构 XML。
     *
     * 背景：样式属性曾写成 `""" s="1"""`（缺前导空格），`$r$style` 直接拼接后闭合引号
     * 被并入属性值，产出 `<c r="D2" s="1 t="inlineStr">` —— 畸形 XML，整个 xlsx 部件
     * 损坏。当时只断言"是否含 s=1"，而畸形输出恰好也含该子串，于是漏判。
     * 此用例直接解析 XML，可捕获任意属性拼接错误。
     */
    @Test fun `数据行输出为良构XML`() {
        val bytes = ByteArrayOutputStream().also {
            XlsxWriter.write(listOf(row(desc = "巡查发现护栏损坏，已通知养护单位处理"), row()), it)
        }.toByteArray()
        val sheet = entries(bytes).first { it.first == "xl/worksheets/sheet1.xml" }.second
        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = false }.newDocumentBuilder()
            .parse(org.xml.sax.InputSource(java.io.StringReader(sheet)))
        val cells = doc.getElementsByTagName("c")
        var eStyled = 0
        for (i in 0 until cells.length) {
            val c = cells.item(i) as org.w3c.dom.Element
            if (c.getAttribute("r").startsWith("E") && c.getAttribute("r") != "E1") {
                if (c.getAttribute("s") == "1") eStyled++
            }
        }
        assertEquals(2, eStyled, "两条数据行的 E 列都应带样式 s=1")
    }

    @Test fun `照片列写文件名最后一段`() {
        val bytes = ByteArrayOutputStream().also {
            XlsxWriter.write(listOf(row(p1 = "DCIM/Camera/IMG_20260903_083001.jpg",
                p2 = "D:/Pics/2026/IMG_0002.png")), it)
        }.toByteArray()
        val sheet = entries(bytes).first { it.first == "xl/worksheets/sheet1.xml" }.second
        assertTrue(sheet.contains("IMG_20260903_083001.jpg"))
        assertTrue(sheet.contains("IMG_0002.png"))
        assertTrue(!sheet.contains("DCIM/Camera/"))
    }

    @Test fun `照片列带文件夹名前缀`() {
        // 回归 P1-3/P2-4：folderName 非空时照片列写 "文件夹名/文件名"（含序号前缀），消除跨文件夹同名歧义
        val bytes = ByteArrayOutputStream().also {
            XlsxWriter.write(listOf(row(folder = "01_0908_巡查至北行K123+000M", p1 = "IMG_01.jpg")), it)
        }.toByteArray()
        val sheet = entries(bytes).first { it.first == "xl/worksheets/sheet1.xml" }.second
        assertTrue(sheet.contains("01_0908_巡查至北行K123+000M/IMG_01.jpg"), "照片列应含 文件夹名/文件名")
        assertTrue(!sheet.contains("<t xml:space=\"preserve\">IMG_01.jpg</t>"), "不应再只写裸文件名")
    }

    @Test fun `特殊字符XML转义`() {
        val bytes = ByteArrayOutputStream().also {
            XlsxWriter.write(listOf(row(desc = "护栏 & 修复 <损坏> \"引号\" '撇号'")), it)
        }.toByteArray()
        val sheet = entries(bytes).first { it.first == "xl/worksheets/sheet1.xml" }.second
        assertTrue(sheet.contains("护栏 &amp; 修复 &lt;损坏&gt; &quot;引号&quot; &apos;撇号&apos;"))
        assertTrue(!sheet.contains("& 修复 <"))
    }

    @Test fun `空照片列输出空单元格`() {
        val bytes = ByteArrayOutputStream().also { XlsxWriter.write(listOf(row(p1 = null, p2 = null)), it) }.toByteArray()
        val sheet = entries(bytes).first { it.first == "xl/worksheets/sheet1.xml" }.second
        // 表头 7 列 + 数据 7 列 = 14 个单元格（含 2 空照片列）
        assertEquals(14, Regex("""<c r="[A-G]""").findAll(sheet).count())
    }

    // ---- ★ §7.3 照片嵌入（锚定 F/G 列，0-based 5/6） ----

    @Test fun `嵌入照片时生成media与drawing部件`() {
        val jpg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10,
            0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0xFF.toByte(), 0xD9.toByte())
        val loader: (String?) -> ByteArray? = { ref -> if (ref != null) jpg else null }
        val bytes = ByteArrayOutputStream().also {
            XlsxWriter.write(listOf(row(p1 = "a.jpg", p2 = "b.jpg")), it, loader)
        }.toByteArray()
        val names = entries(bytes).map { it.first }
        assertTrue("xl/media/image1.jpg" in names, "照片1 media 部件")
        assertTrue("xl/media/image2.jpg" in names, "照片2 media 部件")
        assertTrue("xl/drawings/drawing1.xml" in names, "drawing 部件")
        assertTrue("xl/drawings/_rels/drawing1.xml.rels" in names, "drawing rels")
        assertTrue("xl/worksheets/_rels/sheet1.xml.rels" in names, "sheet rels")
        val ct = entries(bytes).first { it.first == "[Content_Types].xml" }.second
        assertTrue(ct.contains("""<Default Extension="jpg" ContentType="image/jpeg"/>"""))
        assertTrue(ct.contains("""<Override PartName="/xl/drawings/drawing1.xml" ContentType="application/vnd.openxmlformats-officedocument.drawing+xml"/>"""))
        val sheet = entries(bytes).first { it.first == "xl/worksheets/sheet1.xml" }.second
        assertTrue(sheet.contains("""<drawing r:id="rIdDraw1"/>"""), "sheet 引用 drawing")
        val drawing = entries(bytes).first { it.first == "xl/drawings/drawing1.xml" }.second
        assertTrue(drawing.contains("""<xdr:from><xdr:col>5</xdr:col>"""), "照片1 锚定第 6 列(F)")
        assertTrue(drawing.contains("""<xdr:from><xdr:col>6</xdr:col>"""), "照片2 锚定第 7 列(G)")
        assertTrue(drawing.contains("""<xdr:row>1</xdr:row>"""), "数据行 1 锚定")
    }

    @Test fun `PNG照片按png扩展名与ContentType写入`() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x01)
        val loader: (String?) -> ByteArray? = { ref -> if (ref != null) png else null }
        val bytes = ByteArrayOutputStream().also {
            XlsxWriter.write(listOf(row(p1 = "a.png")), it, loader)
        }.toByteArray()
        val names = entries(bytes).map { it.first }
        assertTrue("xl/media/image1.png" in names, "PNG media 扩展名")
        val ct = entries(bytes).first { it.first == "[Content_Types].xml" }.second
        assertTrue(ct.contains("""<Default Extension="png" ContentType="image/png"/>"""))
        val rels = entries(bytes).first { it.first == "xl/drawings/_rels/drawing1.xml.rels" }.second
        assertTrue(rels.contains("""Target="../media/image1.png""""))
    }

    @Test fun `无照片字节时不生成drawing与media部件`() {
        val loader: (String?) -> ByteArray? = { null }
        val bytes = ByteArrayOutputStream().also {
            XlsxWriter.write(listOf(row(p1 = "a.jpg", p2 = "b.jpg")), it, loader)
        }.toByteArray()
        val names = entries(bytes).map { it.first }
        assertTrue("xl/drawings/drawing1.xml" !in names)
        assertTrue("xl/media/image1.jpg" !in names)
        val sheet = entries(bytes).first { it.first == "xl/worksheets/sheet1.xml" }.second
        assertTrue(!sheet.contains("<drawing"))
    }

    /**
     * ★ 2026-10-31 审查回归：Excel/WPS 行高规范上限为 **409.5pt**。
     *   数据行写的是 customHeight="1"（禁止 Excel 自动收敛），ht 一旦超限，打开时会触发
     *   「发现部分内容有问题」的修复提示，修复失败则整份台账不可用。
     *   日志含 23 个换行段时估算值即达 413pt（空段也各计 1 行，很容易达到）。
     */
    @Test fun `超长日志的行高被钳制在Excel上限内`() {
        // 60 个换行段：未钳制时 (60*16+8)*1.1 ≈ 1064pt
        val longDesc = (1..60).joinToString("\n") { "第${it}段检查内容" }
        val bytes = ByteArrayOutputStream().also { XlsxWriter.write(listOf(row(desc = longDesc)), it) }.toByteArray()
        val sheet = entries(bytes).first { it.first == "xl/worksheets/sheet1.xml" }.second
        val ht = Regex("""<row r="2" ht="(\d+)"""").find(sheet)?.groupValues?.get(1)?.toInt()
            ?: error("数据行未写 ht，无法校验行高")
        assertTrue(ht <= 409, "行高 $ht 超过 Excel 上限 409.5pt（会触发修复提示、可能导致整表不可用）")
    }

    // ---- ★ 2026-10-31 W1：不可识别格式不得谎报为 png（与 F1 同属"台账打不开"故障面） ----

    /** WebP 文件头：RIFF + size + "WEBP"。 */
    private val webp = byteArrayOf(0x52, 0x49, 0x46, 0x46, 0x1A, 0x00, 0x00, 0x00, 0x57, 0x45, 0x42, 0x50)

    /** HEIC 文件头：size + "ftyp" + 主 brand "heic"。 */
    private val heic = byteArrayOf(0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70,
        0x68, 0x65, 0x69, 0x63, 0x00, 0x00, 0x00, 0x00)

    @Test fun `WebP字节不被嵌入_且不被声明为png`() {
        val bytes = ByteArrayOutputStream().also {
            XlsxWriter.write(listOf(row(p1 = "a.webp")), it) { _ -> webp }
        }.toByteArray()
        val names = entries(bytes).map { it.first }
        assertTrue(names.none { it.startsWith("xl/media/") }, "WebP 不得生成 media 部件")
        assertTrue("xl/drawings/drawing1.xml" !in names, "无图则不应有 drawing")
        val ct = entries(bytes).first { it.first == "[Content_Types].xml" }.second
        assertTrue(!ct.contains("""ContentType="image/png""""), "不得声明 image/png")
        // 退化为文件名：照片列仍要写文件名，信息不丢
        val sheet = entries(bytes).first { it.first == "xl/worksheets/sheet1.xml" }.second
        assertTrue(sheet.contains(">a.webp<"), "照片列应退化为文件名")
    }

    @Test fun `HEIC字节不被嵌入_且不被声明为png`() {
        val bytes = ByteArrayOutputStream().also {
            XlsxWriter.write(listOf(row(p1 = "a.heic")), it) { _ -> heic }
        }.toByteArray()
        val names = entries(bytes).map { it.first }
        assertTrue(names.none { it.startsWith("xl/media/") }, "HEIC 不得生成 media 部件")
        val ct = entries(bytes).first { it.first == "[Content_Types].xml" }.second
        assertTrue(!ct.contains("""ContentType="image/png""""), "不得声明 image/png")
    }

    /** 跳过不可嵌入的照片后，剩余图片的 media 编号必须连续（image1 起），否则 rels 指向空部件。 */
    @Test fun `跳过不可嵌入格式后_剩余图片编号连续`() {
        val jpg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10,
            0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0xFF.toByte(), 0xD9.toByte())
        val loader: (String?) -> ByteArray? = { ref ->
            if (ref?.endsWith(".webp") == true) webp else jpg
        }
        val bytes = ByteArrayOutputStream().also {
            XlsxWriter.write(listOf(row(p1 = "a.webp", p2 = "b.jpg")), it, loader)
        }.toByteArray()
        val names = entries(bytes).map { it.first }
        assertTrue("xl/media/image1.jpg" in names, "JPEG 应作为 image1 写入（编号连续）")
        assertTrue("xl/media/image2.jpg" !in names, "不得残留 image2")
        val rels = entries(bytes).first { it.first == "xl/drawings/_rels/drawing1.xml.rels" }.second
        assertTrue(rels.contains("""Target="../media/image1.jpg""""), "rels 必须指向 image1")
        val drawing = entries(bytes).first { it.first == "xl/drawings/drawing1.xml" }.second
        assertTrue(drawing.contains("""<xdr:from><xdr:col>6</xdr:col>"""), "被嵌入的是第 2 张（G 列）")
    }

    // ---- ★ 2026-10-31 W2（issue #5）：嵌入图片总字节预算，超限降级而不崩 ----
    //
    // 背景：本实现先把全部图片字节收进内存再一次性写 ZIP，峰值 ≈ 照片数 × 单张字节数；
    // OOM 是 Error 不是 Exception，外层 catch(Exception) 抓不到，进程直接死。
    // 兜底 = 总字节上限；超限后跳过嵌入，但必须在台账里注明，不许静默丢数据。

    /** 一段"像 JPEG"的字节：只要求文件头合法（imageFormat 只嗅前 3 字节）。 */
    private fun jpegOf(size: Int): ByteArray =
        ByteArray(size).also {
            it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte(); it[2] = 0xFF.toByte()
        }

    /**
     * 超限时的**降级语义**：前面的照片照常嵌入，超预算那张及其后一律不嵌入，
     * 且受影响的照片列要写明原因（信息不丢、文件仍能打开）。
     */
    @Test fun `图片总字节超限时跳过后续嵌入并在台账里注明`() {
        val jpeg = jpegOf(800)                       // 每张 800B
        val rows = listOf(
            row(p1 = "a.jpg", p2 = "b.jpg"),         // 第 2 行：a 进（800），b 超（1600>1000）
            row(p1 = "c.jpg", p2 = "d.jpg"),         // 第 3 行：都超
        )
        val bytes = ByteArrayOutputStream().also {
            XlsxWriter.writeWithBudget(rows, it, maxImageBytes = 1000) { jpeg }
        }.toByteArray()
        val es = entries(bytes)
        val names = es.map { it.first }

        // 1) 只嵌入了预算内的那一张，且 media 编号连续
        assertEquals(1, names.count { it.startsWith("xl/media/") }, "只应嵌入预算内的 1 张")
        assertTrue("xl/media/image1.jpg" in names)
        assertTrue("xl/media/image2.jpg" !in names, "超预算的图不得写入 media")
        // 2) drawing 与 rels 一致（1 张 → 1 个锚点、1 条 rel）
        val drawing = es.first { it.first == "xl/drawings/drawing1.xml" }.second
        assertEquals(1, Regex("<xdr:oneCellAnchor>").findAll(drawing).count())
        val rels = es.first { it.first.endsWith("drawing1.xml.rels") }.second
        assertEquals(1, Regex("<Relationship ").findAll(rels).count())

        // 3) 台账里必须注明：3 个被跳过的照片单元格写文件名 + 未嵌入说明
        val sheet = es.first { it.first == "xl/worksheets/sheet1.xml" }.second
        assertEquals(
            3,
            Regex(Regex.escape(XlsxWriter.IMAGE_SKIPPED_NOTE)).findAll(sheet).count(),
            "b/c/d 三个单元格都要注明未嵌入",
        )
        assertTrue(sheet.contains(">b.jpg${XlsxWriter.IMAGE_SKIPPED_NOTE}<"), "被跳过的单元格仍要写文件名")
        // 4) 预算内那张已嵌入 → 照片下方不再写字（2026-11-02）
        assertTrue(sheet.contains("""<c r="F2" s="1"/>"""), "已嵌入的照片列应为空单元格")
        // 5) 仍然是一份结构完整的 xlsx
        assertTrue("xl/worksheets/sheet1.xml" in names)
    }

    /**
     * 预算用尽后**不得再调用 imageLoader**。
     *
     * 这是兜底能否成立的关键：App 侧的 imageLoader 会解码 + 压 JPEG，继续调用等于继续分配内存，
     * 只判"不写进 media"根本挡不住 OOM。用例直接数调用次数。
     */
    @Test fun `预算用尽后不再向 imageLoader 取图`() {
        var calls = 0
        val jpeg = jpegOf(800)
        val rows = listOf(
            row(p1 = "a.jpg", p2 = "b.jpg"),
            row(p1 = "c.jpg", p2 = "d.jpg"),
        )
        ByteArrayOutputStream().also {
            XlsxWriter.writeWithBudget(rows, it, maxImageBytes = 1000) { calls++; jpeg }
        }
        // a 取一次（进），b 取一次（判定超预算 → 用尽）；c/d 一次都不能取
        assertEquals(2, calls, "预算用尽后仍在向 imageLoader 取图，兜底失效")
    }

    /** 回归：预算充足时不得出现"未嵌入"说明（正常路径不能被兜底逻辑污染）。 */
    @Test fun `预算充足时不出现未嵌入说明`() {
        val jpeg = jpegOf(800)
        val bytes = ByteArrayOutputStream().also {
            XlsxWriter.writeWithBudget(listOf(row(p1 = "a.jpg", p2 = "b.jpg")), it, maxImageBytes = 1_000_000) { jpeg }
        }.toByteArray()
        val es = entries(bytes)
        assertEquals(2, es.count { it.first.startsWith("xl/media/") }, "预算充足时两张都应嵌入")
        val sheet = es.first { it.first == "xl/worksheets/sheet1.xml" }.second
        assertTrue(!sheet.contains(XlsxWriter.IMAGE_SKIPPED_NOTE), "预算充足时不得出现未嵌入说明")
    }
}
