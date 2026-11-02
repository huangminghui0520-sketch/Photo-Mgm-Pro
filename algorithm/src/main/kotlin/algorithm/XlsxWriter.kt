// algorithm/XlsxWriter.kt —— §7.3 Excel 写入（纯 Kotlin，零第三方依赖，可移植 PC）
// ★ 导出Excel 2.0 方案：7 列（序号/时间/结束时间/地点/日志内容/照片1/照片2），
//   表头行高 25，数据行行高 = 照片高度（81pt = 2.86cm，恒定），
//   F/G 照片列宽 = 照片宽度（20 字符 = 108 磅 = 3.81cm），照片以缩略图嵌入。
// ★ 2026-11-02「台账照片格尺寸对齐」：行高/列宽全部由照片 EMU 尺寸换算（唯一真值），
//   并补上 <sheetViews> —— 缺它时 Excel 在非 100% 缩放下会把 ht 缩成 0.8 倍。
// ★ 2026-10-18 P1-1：新增"结束时间"列（C 列）；照片列由 E/F 移至 F/G（0-based 5/6）。
// ★ 2026-11-02 「台账排版一轮」：
//   ① 数据行行高恒等于照片高度（81pt）：照片正好填满一行，不再浮在长文本行中间、上下留白；
//   ② 照片下方不再写任何文字（照片嵌进去了就留空单元格，只在**没嵌进去**时留文件名兜底）；
//   ③ B/C 两列只显示「时:分」（底层仍是完整日期序列号，排序/跨日计算不受影响）；
//   ④ 全部单元格统一 水平居中 + 垂直居中 + 自动换行。
// ★ 照片嵌入：imageLoader(ref) 返回图片字节（JPEG/PNG），自动生成 drawing + media + rels；
//   图片锚定到照片1/照片2 所在单元格（F/G 列 × 1 行显示区）。无图片或加载失败 → 照片列仅写文件名。
package algorithm

import algorithm.model.LedgerRow
import java.io.OutputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.floor
import kotlin.math.round

object XlsxWriter {
    /**
     * 表头（导出Excel 2.0 方案 §2.1；2026-10-18 P1-1 新增"结束时间"）。
     *
     * ★ 2026-11-02：B 列由「日期」改名「时间」——它现在只显示 `时:分`（导出根目录已按
     *   巡查日归档，列里再重复一遍日期只是占宽度）。C 列「结束时间」本来就只是时刻。
     */
    val HEADERS: List<String> = listOf("序号", "时间", "结束时间", "地点", "日志内容", "照片1", "照片2")

    // ---------- 照片几何：台账行高 / 列宽的唯一真值 ----------

    /** 1cm = 360000 EMU（OOXML 图形单位；1in = 914400 EMU）。 */
    private const val EMU_PER_CM = 360_000L

    /** 1 磅 = 12700 EMU（1/72 英寸）——Excel 行高的单位是磅。 */
    private const val EMU_PER_PT = 12_700L

    /**
     * 嵌入照片的显示尺寸（EMU）：宽 1371600、高 1028700，严格 4:3。
     *
     * ★ 这是台账版式的**唯一真值**。照片宽 **3.81cm**、高 **2.8575cm（≈2.86cm）**，
     *   行高与 F/G 列宽都从它换算出来，不再各写各的魔数。
     *   历史上三处各写一套，照片列宽手写 22 字符 = 159px ≈ 4.21cm，比照片宽出 0.4cm，
     *   于是照片要么横向留缝、要么压到相邻列；行高与照片高度也只是"碰巧都写成了 81"。
     */
    private const val PIC_CX_EMU = 1_371_600L   // 3.81 cm
    private const val PIC_CY_EMU = 1_028_700L   // 2.8575 cm

    /** 照片宽（cm）= 3.81（供文档/外部核对，勿在别处再写死）。 */
    val PIC_W_CM: Double = PIC_CX_EMU.toDouble() / EMU_PER_CM

    /** 照片高（cm）= 2.8575（台账里按 2.86cm 表述，误差 < 0.005cm 可忽略）。 */
    val PIC_H_CM: Double = PIC_CY_EMU.toDouble() / EMU_PER_CM

    /**
     * Excel 列宽"1 个字符"折算成厘米 —— 经 Excel 实测校准。
     *
     * Excel 的列宽单位不是长度，而是"默认字体（Calibri 11）中 '0' 的个数的倍数"，
     * 且各版本/DPI 下的换算带内边距与取整误差。实测（Excel 365，96/120 DPI 两种缩放）：
     *   列宽 20 字符 → 单元格宽 = 108 磅 = 3.81 厘米，与照片宽**完全相等**；
     *   列宽 19.86 字符 → 107.4 磅（比照片窄 0.6 磅，照片右缘会压进 G 列）。
     * 由此得 1 字符 ≈ 5.4 磅 ≈ 0.1905 厘米，故 3.81cm ÷ 0.1905 ≈ **20 字符**。
     * （旧的 22 字符 ≈ 4.21cm，比照片宽出 0.4cm，照片右侧留一条白缝。）
     */
    private const val CM_PER_COL_CHAR = 0.1905

    /**
     * 厘米 → Excel 列宽（字符），按 [CM_PER_COL_CHAR] 折算并取整到 0.01。
     *
     * ★ 为什么不再用 "(px − 5) / 7"：那是 96 DPI、无缩放下的理论换算，实测在 125% 缩放的
     *   Excel 里会算出 19.86，读回宽度 107.4 磅 ≠ 照片 108 磅。改用实测系数后 3.81cm → 20，
     *   读回 108.000 磅，与照片严丝合缝。
     */
    private fun colWidthCharsForCm(cm: Double): Double =
        round(cm / CM_PER_COL_CHAR * 100.0) / 100.0

    /**
     * 列宽（字符）：A:8, B:18, C:18, D:24, E:60, F/G:20（= 照片宽 3.81cm）。
     *
     * ★ 2026-11-02「照片列宽对齐照片宽度」：F/G 由手写的 22（≈4.21cm）改为由 [PIC_W_CM]
     *   折算的 20（= 3.81cm）。照片是 oneCellAnchor 固定尺寸，列宽大于照片只会在右侧留缝，
     *   小于照片则照片压进相邻列；只有两者相等，照片才严丝合缝地占满这一格。
     */
    private val COL_WIDTHS: List<String> = listOf(
        "8", "18", "18", "24", "60",
        fmtWidth(colWidthCharsForCm(PIC_W_CM)),
        fmtWidth(colWidthCharsForCm(PIC_W_CM)),
    )

    /** 列宽文本：整数去掉小数尾巴（8.0 → "8"），小数保留两位（19.857… → "19.86"）。 */
    private fun fmtWidth(w: Double): String =
        if (w == floor(w)) w.toInt().toString() else w.toString()

    /** 表头行高（磅）。 */
    private const val HEADER_ROW_H = 25

    /**
     * 数据行行高（磅）= **照片高度** 1028700 EMU ÷ 12700 = **81pt = 2.8575cm（≈2.86cm）**。
     *
     * ★ 每条数据行都带照片列（F/G），所以"含照片的行"就是全部数据行 —— 行高对整表恒定：
     *   · 照片刚好填满整行，上下不留白，也没有"照片浮在长文本行中间"的观感；
     *   · 行高是常量，`ht` 天然撞不到 Excel 的 409.5pt 上限（原先的动态估算 + 上限钳制一并删除）。
     * ★ 代价（已知且有意为之）：日志超过约 4 行（E 列 60 字符宽 ≈ 每行 30 个汉字）时，
     *   单元格内会被裁切。换来的是"行行等高、照片与行对齐"，便于打印装订；
     *   完整原文仍在 `export.log` 与事件文件夹里，不依赖台账单格来存档。
     */
    private val DATA_ROW_H: Int = (PIC_CY_EMU / EMU_PER_PT).toInt()   // 81

    /**
     * 数据行的单元格。
     *
     * ★ 2026-11-02 去掉了「这条要不要加 wrapText」的开关：现在**全部单元格**统一
     *   水平居中 + 垂直居中 + 自动换行，样式按单元格类型固定给出，不再由调用方逐格决定。
     */
    private sealed interface Cell {
        /** 数字单元格（无 t 属性，Excel 按数值处理）。 */
        data class Number(val value: String) : Cell

        /** 文本单元格（inlineStr，xml:space="preserve" 保留空白与换行）。 */
        data class Text(val value: String) : Cell

        /** 时间单元格：值=完整日期序列号（排序与跨日都正确），显示格式 `hh:mm`（[STYLE_TIME]）。 */
        data class Time(val serial: Double) : Cell

        /** 空单元格：照片已嵌进这个格子 → 照片下方不再写字（仍套 [STYLE_BODY]，保持列样式一致）。 */
        object Blank : Cell
    }

    /** 图片锚定：照片1 列=5、照片2 列=6（0-based F/G）；显示 1 列 × 1 行。 */
    private const val COL_P1 = 5
    private const val COL_P2 = 6

    /** styles.xml cellXfs 索引：1=通用（居中+换行）、2=时间 hh:mm（居中+换行）。 */
    private const val STYLE_BODY = 1
    private const val STYLE_TIME = 2

    /**
     * 嵌入图片的**总字节预算**（W2 / issue #5）。
     *
     * 为什么需要：本实现是"先把全部图片字节收集进内存，再一次性写 ZIP"，峰值内存 ≈
     * 照片数 × 单张字节数。真机上一份几百张的台账能轻松吃掉数百 MB —— 而 OOM 是 `Error`
     * 不是 `Exception`，外层的 `catch (Exception)` 抓不到，直接崩，且崩在导出过程中
     * （前面写好的 ZIP 也废了）。
     *
     * 取值 64MB：远低于低端机给单进程的堆上限，又足以容纳常见规模（一张 4:3 缩略图约
     * 100~300KB，64MB ≈ 200~600 张）。超限不是错误，而是**降级**：后续照片不再嵌入，
     * 台账照片列仍写文件名并注明未嵌入，信息不丢、文件仍能打开。
     *
     * ⚠ 真正的解法是流式写入（边解码边落盘，峰值只跟单张图有关），属重构，另排期。
     */
    const val MAX_IMAGE_BYTES: Long = 64L * 1024 * 1024

    /** 照片因预算不足未嵌入时，追加在台账照片列文本后的说明（保证"跳过"可见，不是静默丢数据）。 */
    internal const val IMAGE_SKIPPED_NOTE = "（未嵌入：照片总量超出内存上限）"

    /**
     * 把台账行写入 xlsx 字节流（ZIP 容器 + OOXML 部件）。imageLoader 返回照片字节（可为 null）。
     *
     * 生产入口：嵌入图片用默认预算 [MAX_IMAGE_BYTES]。
     * ★ 为什么单独留 [writeWithBudget] 而不是给这里加一个 `maxImageBytes` 参数：
     *   Kotlin 的尾随 lambda 绑定**最后一个**形参，参数加在 lambda 之后会让
     *   `XlsxWriter.write(rows, out) { ref -> bytes }` 这种写法把 lambda 绑到 Long 上，
     *   现有全部调用点（含 App 侧）都得改。故预算只走 internal 重载，公开签名保持不动。
     */
    fun write(
        rows: List<LedgerRow>,
        out: OutputStream,
        imageLoader: (String?) -> ByteArray? = { null },
    ): Unit = writeWithBudget(rows, out, MAX_IMAGE_BYTES, imageLoader)

    /**
     * 同 [write]，但显式给出嵌入图片的总字节预算。
     *
     * @param maxImageBytes 总字节预算。超限后**不再向 imageLoader 取图**（继续取等于继续把
     *   字节读进内存，兜底就失效了），受影响的行在照片列追加 [IMAGE_SKIPPED_NOTE] 说明。
     *   internal：只为让测试用小预算复现降级路径，生产侧没有调它的理由。
     */
    internal fun writeWithBudget(
        rows: List<LedgerRow>,
        out: OutputStream,
        maxImageBytes: Long,
        imageLoader: (String?) -> ByteArray?,
    ) {
        val collected = collectImages(rows, imageLoader, maxImageBytes)
        val imgs = collected.images
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("[Content_Types].xml")); z.write(contentTypes(imgs).toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("_rels/.rels")); z.write(RELS.toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("xl/workbook.xml")); z.write(WORKBOOK.toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("xl/_rels/workbook.xml.rels")); z.write(WORKBOOK_RELS.toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml")); z.write(
                sheetXml(rows, imgs.isNotEmpty(), collected.embedded, collected.skipped).toByteArray()
            ); z.closeEntry()
            z.putNextEntry(ZipEntry("xl/styles.xml")); z.write(STYLES.toByteArray()); z.closeEntry()
            if (imgs.isNotEmpty()) {
                z.putNextEntry(ZipEntry("xl/worksheets/_rels/sheet1.xml.rels")); z.write(SHEET_RELS.toByteArray()); z.closeEntry()
                z.putNextEntry(ZipEntry("xl/drawings/drawing1.xml")); z.write(drawingXml(imgs).toByteArray()); z.closeEntry()
                z.putNextEntry(ZipEntry("xl/drawings/_rels/drawing1.xml.rels")); z.write(drawingRels(imgs).toByteArray()); z.closeEntry()
                imgs.forEachIndexed { idx, img ->
                    z.putNextEntry(ZipEntry("xl/media/image${idx + 1}.${img.ext}")); z.write(img.bytes); z.closeEntry()
                }
            }
        }
    }

    // ---------- 图片收集 ----------

    /** ★ 2026-11-02：行高恒等于照片高度，不再按图存 rowHeightPt（锚点 rowOff 恒为 0）。
     *  ★ 2026-10-31 W1：ext/contentType 随图一起定下来，避免后续每处都重新嗅探、也杜绝空断言。 */
    private data class Img(
        val bytes: ByteArray,
        val col: Int,
        val row: Int,
        val ext: String,
        val contentType: String,
    )

    /**
     * 收集结果：可嵌入的图片 + 被跳过的单元格。
     *
     * ★ 必须把 skipped 带回给 sheetXml：跳过是**降级**，不是错误，用户得能在台账里看到
     *   "这张为什么没图"，否则就是静默丢数据。
     */
    private class Collected(
        val images: List<Img>,
        /**
         * (数据行在 [rows] 中的 0-based 下标, 列号)。
         *
         * ★ 别用工作表行号：`Img.row` 是 drawing 锚点用的 0-based 行（表头=0），而
         *   单元格坐标 `r` 是 1-based（表头=1），两套坐标系差 1。曾经在这里混用，
         *   结果"未嵌入"说明打在了**上一行**的单元格上（测试实测 3 处只命中 2 处）。
         *   用 rows 下标做键最不容易错，取表时按同一个 i 查。
         */
        val skipped: Set<Pair<Int, Int>>,
    ) {
        /**
         * 真正嵌进了图片的单元格 (数据行下标, 列号)。
         *
         * ★ 2026-11-02：照片下方不再写字，于是"这一格要不要写文件名"必须看图片**到底进没进去**，
         *   不能只看 ref 在不在。用 images 反推，天然与 media 部件一一对应。
         */
        val embedded: Set<Pair<Int, Int>> = images.map { (it.row - 1) to it.col }.toSet()
    }

    /**
     * 每行照片1/照片2 → 有字节且**格式可嵌入**则收集；行号 = 表头(0) + 数据行索引(1-based)。
     * （行高恒定 [DATA_ROW_H]，不再逐行计算。）
     *
     * ★ W2（issue #5）：累计字节超过 [maxImageBytes] 后**立即停止**，既不再把已超预算的
     *   这一张塞进去，也不再为后续单元格调用 imageLoader —— 后者才是兜底的关键：
     *   imageLoader 在 App 侧会解码 + 压 JPEG，继续调用等于继续分配内存，预算就成了摆设。
     */
    private fun collectImages(
        rows: List<LedgerRow>,
        imageLoader: (String?) -> ByteArray?,
        maxImageBytes: Long,
    ): Collected {
        val out = mutableListOf<Img>()
        val skipped = mutableSetOf<Pair<Int, Int>>()
        var totalBytes = 0L
        var budgetExhausted = false
        rows.forEachIndexed { i, r ->
            listOf(r.photo1Ref to COL_P1, r.photo2Ref to COL_P2).forEach { (ref, col) ->
                if (ref == null) return@forEach
                if (budgetExhausted) {
                    skipped.add(i to col)
                    return@forEach
                }
                val b = runCatching { imageLoader(ref) }.getOrNull()
                if (b != null && b.isNotEmpty()) {
                    // ★ W1：识别不出格式（WebP/HEIC/GIF/损坏字节）→ 跳过嵌入。
                    //   照片列已写文件名（photoCell），信息不丢；绝不能写一个 Excel 不认的
                    //   ContentType 去换"看起来有图"，那会让整份台账打不开。
                    val fmt = imageFormat(b) ?: return@forEach
                    if (totalBytes + b.size > maxImageBytes) {
                        // 预算不够：这张不嵌入，并从此不再取图
                        budgetExhausted = true
                        skipped.add(i to col)
                        return@forEach
                    }
                    totalBytes += b.size
                    out.add(Img(b, col, i + 1, fmt.first, fmt.second))
                }
            }
        }
        return Collected(out, skipped)
    }

    /**
     * 按文件头判断格式；**返回 null = 不可嵌入**（Excel/WPS 不支持该格式）。
     *
     * ★ 2026-10-31 审查修复（W1）：原实现把无法识别的字节一律按 PNG 声明——
     *   WebP（RIFF….WEBP）/ HEIC（ftyp heic）/ GIF / 损坏字节都被写成 `.png` + `image/png`，
     *   Excel 打开发现内容与声明不符 → 报「发现部分内容有问题」，修复失败则**整表不可用**。
     *   与 F1（行高超 409.5pt）属同一类故障面，故本轮一并处理。
     *
     * 这里只放行 Excel 明确支持的 JPEG / PNG。转码（WebP/HEIC → JPEG）不属于本层职责：
     * 核心层是纯 Kotlin（无平台解码器、需保持 PC 可移植），转码由调用方在 imageLoader 内完成
     * （App 侧见 ImageUtils.decodeForThumbnail：ImageDecoder 解码后压 JPEG）。转码也失败的极少数
     * 情况（如 API<28 的 HEIC）退化为"单元格仅文件名"——这是最后一层兜底，不是新故障。
     */
    private fun imageFormat(bytes: ByteArray): Pair<String, String>? = when {
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() ->
            "jpg" to "image/jpeg"
        bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() -> "png" to "image/png"
        else -> null
    }

    // ---------- 部件生成 ----------

    private fun contentTypes(imgs: List<Img>): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/>""")
        if (imgs.isNotEmpty()) {
            imgs.map { it.contentType }.distinct().forEach { ct ->
                sb.append("""<Default Extension="${extOf(ct)}" ContentType="$ct"/>""")
            }
            sb.append("""<Override PartName="/xl/drawings/drawing1.xml" ContentType="application/vnd.openxmlformats-officedocument.drawing+xml"/>""")
        }
        sb.append("""<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>""")
        return sb.toString()
    }

    private fun extOf(contentType: String): String = if (contentType.endsWith("png")) "png" else "jpg"

    private fun sheetXml(
        rows: List<LedgerRow>,
        hasDrawing: Boolean,
        embedded: Set<Pair<Int, Int>> = emptySet(),
        skipped: Set<Pair<Int, Int>> = emptySet(),
    ): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""")
        // ★★ 必须写 <sheetViews>，否则 Excel 会把 ht 当成"96 DPI 像素"重新解释：
        //   在 125% 缩放（120 DPI）的 Windows 上，我们写的 81pt 会被读成 64.8pt，
        //   而照片是按 EMU 绝对尺寸渲染的 81pt —— 于是每张照片都比行高出一截、上下压行，
        //   用户看到的正是"行高和照片高度不一致"。补上这段视图声明后 Excel 才按磅值原样渲染
        //   （实测：无此行 Excel 读回 64.8pt / 行距 64.8；有此行读回 81pt / 行距 81，与照片等高）。
        //   tabSelected="1"：首张表默认选中，Excel 才会正常进入普通视图。
        sb.append("""<sheetViews><sheetView tabSelected="1" workbookViewId="0"/></sheetViews>""")
        // 列宽（A:8 B:18 C:18 D:24 E:60，F/G = 照片宽 3.81cm → 20 字符 = 108 磅）
        sb.append("<cols>")
        COL_WIDTHS.forEachIndexed { i, w ->
            val min = i + 1
            sb.append("""<col min="$min" max="$min" width="$w" customWidth="1"/>""")
        }
        sb.append("</cols>")
        sb.append("<sheetData>")
        sb.append(headerRowXml())
        rows.forEachIndexed { i, r ->
            val sheetRow = i + 2   // 表头占第 1 行
            sb.append(dataRowXml(sheetRow, listOf(
                // 序号用数字单元格（不是文本）：否则 Excel 报"数字以文本存储"且无法求和排序
                Cell.Number((i + 1).toString()),
                timeCell(r.dateTime),                       // B 列：显示 时:分（底层完整日期，可排序）
                timeCell(r.endTime),                        // C 列：同上
                Cell.Text(r.location),
                Cell.Text(r.description),                   // E 列：自动换行（全部单元格都开了换行）
                photoCell(r, r.photo1Ref, collected = embedded, skipped = skipped, rowIdx = i, col = COL_P1),
                photoCell(r, r.photo2Ref, collected = embedded, skipped = skipped, rowIdx = i, col = COL_P2),
            )))
        }
        sb.append("</sheetData>")
        if (hasDrawing) sb.append("""<drawing r:id="rIdDraw1"/>""")
        sb.append("</worksheet>")
        return sb.toString()
    }

    /** 表头行：行高 25 磅；单元格与数据行同一套样式（居中 + 换行）。 */
    private fun headerRowXml(): String {
        val sb = StringBuilder("""<row r="1" ht="$HEADER_ROW_H" customHeight="1">""")
        HEADERS.forEachIndexed { i, h ->
            val col = ('A' + i)
            sb.append("""<c r="$col${1}" s="$STYLE_BODY" t="inlineStr"><is><t xml:space="preserve">$h</t></is></c>""")
        }
        sb.append("</row>")
        return sb.toString()
    }

    /**
     * 数据行：行高恒定 [DATA_ROW_H]（= 照片高度），所有单元格套 [STYLE_BODY] / [STYLE_TIME]。
     *
     * ★ 2026-11-02：样式不再是"按格子 optional 加 wrapText"，而是**每个格子都有样式**——
     *   `s` 属性缺失时会落回默认 xf（无居中），那正好是"只有部分单元格居中"的成因。
     */
    private fun dataRowXml(r: Int, cells: List<Cell>): String {
        val sb = StringBuilder("""<row r="$r" ht="$DATA_ROW_H" customHeight="1">""")
        cells.forEachIndexed { i, cell ->
            val ref = "${('A' + i)}$r"
            when (cell) {
                is Cell.Number -> sb.append("""<c r="$ref" s="$STYLE_BODY"><v>${cell.value}</v></c>""")
                is Cell.Time ->
                    // 值仍是完整日期序列号（排序/跨日正确），格式只显示 时:分
                    sb.append("""<c r="$ref" s="$STYLE_TIME"><v>${cell.serial}</v></c>""")
                is Cell.Text ->
                    sb.append(
                        """<c r="$ref" s="$STYLE_BODY" t="inlineStr"><is><t xml:space="preserve">""" +
                                escape(cell.value) + "</t></is></c>"
                    )
                // 空单元格也要写 s：照片格没字，但列样式（居中/换行）要与整行一致
                Cell.Blank -> sb.append("""<c r="$ref" s="$STYLE_BODY"/>""")
            }
        }
        sb.append("</row>")
        return sb.toString()
    }

    /** 照片列写文件名（sourceRef 最后一段，§7.3）；null → 空单元格。 */
    fun fileName(ref: String?): String? =
        ref?.substringAfterLast('/')?.substringAfterLast('\\')

    // ---------- 时间列（B/C：显示 时:分，值仍是完整日期） ----------

    /** 台账日期文本格式（与 LedgerEngine.formatDateTime/formatEndTime 输出一致）。 */
    private val DT_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /** 严格解析台账日期文本；空串或异常格式返回 null（调用方回退为文本单元格）。 */
    private fun parseDateTime(s: String): LocalDateTime? =
        if (s.isBlank()) null else try {
            LocalDateTime.parse(s, DT_FMT)
        } catch (_: Exception) {
            null
        }

    /** Excel 1900 日期系统序列号 = 自 1899-12-30 起天数 + 当日秒数/86400（标准公式，兼容 Excel 闰年偏移）。 */
    private fun toExcelSerial(dt: LocalDateTime): Double =
        ChronoUnit.DAYS.between(LocalDate.of(1899, 12, 30), dt.toLocalDate()).toDouble() +
                (dt.hour * 3600 + dt.minute * 60) / 86400.0

    /**
     * 时间列单元格：可解析为 `yyyy-MM-dd HH:mm` → 真实时间类型（显示 `hh:mm`）；
     * 否则（空/异常文本）回退文本，保底不报错。
     *
     * ★ 为什么保留完整日期再做 `hh:mm` 显示，而不是只存"当日秒数"：
     *   只存 0~1 的小数会让"跨零点的结束时间"（23:30 → 01:00）在排序/相减时出错；
     *   显示格式只影响"看起来是什么"，不影响底层值。
     */
    private fun timeCell(s: String): Cell =
        parseDateTime(s)?.let { Cell.Time(toExcelSerial(it)) } ?: Cell.Text(s)

    /**
     * 照片列单元格。
     *
     * ★ 2026-11-02：**照片下方不再有任何文字**。图片真的嵌进去了 → 留空单元格
     *   （照片本身占了整格，下面再堆一行 `文件夹/文件名` 只会把照片顶歪、还会溢出到下一行）。
     * ★ 只有"没嵌进去"时才写文件名：无字节 / 格式不支持（WebP/HEIC）/ 超预算。
     *   这时格子里没有照片，文件名是**唯一**能定位原图的线索，写上不属于"照片底部有字"；
     *   超预算的还要追加 [IMAGE_SKIPPED_NOTE]，否则就是静默丢数据。
     */
    private fun photoCell(
        r: LedgerRow,
        ref: String?,
        collected: Set<Pair<Int, Int>>,
        skipped: Set<Pair<Int, Int>>,
        rowIdx: Int,
        col: Int,
    ): Cell {
        if (ref == null) return Cell.Blank
        if ((rowIdx to col) in collected) return Cell.Blank
        val f = fileName(ref) ?: return Cell.Blank
        val label = if (r.folderName.isNotBlank()) "${r.folderName}/$f" else f
        return Cell.Text(if ((rowIdx to col) in skipped) label + IMAGE_SKIPPED_NOTE else label)
    }

    /**
     * 图片锚定：oneCellAnchor + 固定尺寸（EMU，见文件头的 [PIC_CX_EMU] / [PIC_CY_EMU]）。
     *
     * ★ 为什么不用 twoCellAnchor：
     *   原先锚满"1 列 × 1 行"，而数据行高随日志长度增长。日志超过约 150 字时
     *   行高 > 81pt，图片就被纵向拉伸（300 字 → 168pt → 拉伸 2.07 倍），4:3 取证照片失真。
     *   改用 oneCellAnchor 固定尺寸后，图片始终保持 4:3。
     *
     * 尺寸与行高 [DATA_ROW_H]、列宽 [COL_WIDTHS] 用的是**同一组 EMU 常量**：
     * 高 1028700 ÷ 12700 = 81pt（= 2.86cm）恰好等于行高，宽 1371600 = 3.81cm 恰好等于列宽，
     * 于是"行高 = 照片高、列宽 = 照片宽"是算出来的，不是三处手写碰巧对上。
     * ★ 2026-11-02：行高改为恒等于照片高度后，`rowOff` / `colOff` 恒为 0 —— 照片正好是一整行一整列，
     *   不再需要按行高算垂直居中偏移（原先"长文本行里照片浮在中间"的偏移逻辑随之删除）。
     */
    private fun drawingXml(imgs: List<Img>): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<xdr:wsDr xmlns:xdr="http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""")
        imgs.forEachIndexed { i, img ->
            // ★ 2026-11-02：行高恒等于照片高度 → rowOff 恒为 0，照片正好填满这一行（上下不留白）
            sb.append(
                """<xdr:oneCellAnchor><xdr:from><xdr:col>${img.col}</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>${img.row}</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:from><xdr:ext cx="$PIC_CX_EMU" cy="$PIC_CY_EMU"/><xdr:pic><xdr:nvPicPr><xdr:cNvPr id="${i + 2}" name="image${i + 1}"/><xdr:cNvPicPr><a:picLocks noChangeAspect="1"/></xdr:cNvPicPr></xdr:nvPicPr><xdr:blipFill><a:blip r:embed="rId${i + 1}"/><a:stretch><a:fillRect/></a:stretch></xdr:blipFill><xdr:spPr><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></xdr:spPr></xdr:pic><xdr:clientData/></xdr:oneCellAnchor>"""
            )
        }
        sb.append("</xdr:wsDr>")
        return sb.toString()
    }

    private fun drawingRels(imgs: List<Img>): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
        imgs.forEachIndexed { i, img ->
            sb.append("""<Relationship Id="rId${i + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="../media/image${i + 1}.${img.ext}"/>""")
        }
        sb.append("</Relationships>")
        return sb.toString()
    }

    /**
     * XML 文本转义。
     *
     * ★ 先剔除 XML 1.0 不允许的字符，再做实体转义。
     *   XML 1.0 只允许 #x9 | #xA | #xD | [#x20-#xD7FF] | [#xE000-#xFFFD] | [#x10000-#x10FFFF]。
     *   非法字符（如 \v 0x0B、\f 0x0C，以及 0x00-0x08/0x0E-0x1F）会让 sheet1.xml 无法解析，
     *   Excel 报"文件损坏"——单个字符就能毁掉整个台账。
     *   日志来自用户粘贴（Word/PDF 复制常带这类字符），必须在此兜住。
     */
    private fun escape(s: String): String = stripIllegalXmlChars(s)
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    /** 匹配 XML 1.0 非法字符：除 \t(09) \n(0A) \r(0D) 之外的 C0 控制符、DEL/C1 区间、0xFFFE/0xFFFF。 */
    private val ILLEGAL_XML: Regex =
        Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F-\\u009F\\uFFFE\\uFFFF]")

    private fun stripIllegalXmlChars(s: String): String = ILLEGAL_XML.replace(s, "")

    // ---------- 常量部件 ----------

    private val RELS = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>
    """.trimIndent()

    private val WORKBOOK = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="巡查台账" sheetId="1" r:id="rId1"/></sheets></workbook>
    """.trimIndent()

    private val WORKBOOK_RELS = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>
    """.trimIndent()

    private val SHEET_RELS = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rIdDraw1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/drawing" Target="../drawings/drawing1.xml"/></Relationships>
    """.trimIndent()

    /**
     * 样式表（cellXfs 索引与 [STYLE_BODY] / [STYLE_TIME] 严格对应）：
     *
     * | 索引 | 用途 | 数字格式 | 对齐 |
     * | --- | --- | --- | --- |
     * | 0 | 兜底默认（不应被引用） | General | 默认 |
     * | 1 | 全部普通单元格（含表头、序号、空单元格） | General | 水平居中 + 垂直居中 + 自动换行 |
     * | 2 | B/C 时间列 | numFmt 164 `hh:mm` | 同上 |
     *
     * ★ 2026-11-02：numFmt 164 由 `yyyy-mm-dd hh:mm` 改为 `hh:mm`（B/C 只显示 时:分）。
     *   值仍是完整日期序列号，改的只是"怎么显示"。
     * ★ 自动换行 + 垂直居中 要对**每个** xf 都写：Excel 不会把默认 xf 的对齐继承给带格式的格。
     */
    private val STYLES = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><numFmts count="1"><numFmt numFmtId="164" formatCode="hh:mm"/></numFmts><fonts count="1"><font><sz val="11"/><name val="Calibri"/></font></fonts><fills count="1"><fill><patternFill patternType="none"/></fill></fills><borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="3"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0" applyAlignment="1"><alignment horizontal="center" vertical="center" wrapText="1"/></xf><xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1" applyAlignment="1"><alignment horizontal="center" vertical="center" wrapText="1"/></xf></cellXfs></styleSheet>
    """.trimIndent()
}
