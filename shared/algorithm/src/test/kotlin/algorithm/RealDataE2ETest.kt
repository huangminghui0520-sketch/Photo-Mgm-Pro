// algorithm/src/test/kotlin/algorithm/RealDataE2ETest.kt
// 真实数据端到端测试（2026-10-24 桌面真实照片 + 真实巡查日志）：
//   读日志 → 解析 → 照片扫描/粗筛/派生去重 → EXIF 精读（预抽取 TSV，模拟安卓 ExifInterface 结果）
//   → GPS 分组 → 事件分类 → 台账 → XLSX/ZIP 导出
// 目的：真实测试运行，排查算法管线 bug；非交付物。
package algorithm

import algorithm.model.Photo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class RealDataE2ETest {

    private val dataDir = File("C:/Users/28988/Desktop/新建文件夹 (2)")
    private val logFile = File(dataDir, "10月24日巡查日志.txt")
    private val exifFile = File("D:/Photo-Mgm/Photo-Mgm/shots/real_exif.tsv")
    private val outZip = File("D:/Photo-Mgm/Photo-Mgm/shots/real_e2e_out.zip")
    private val outXlsx = File("D:/Photo-Mgm/Photo-Mgm/shots/real_e2e_ledger.xlsx")
    private val patrolDate = LocalDate.of(2026, 10, 24)
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    /** TSV 版 ExifReader：文件名<TAB>yyyy:MM:dd HH:mm:ss<TAB>lat<TAB>lng（lat/lng 空 = 无 GPS）。 */
    private class TsvExifReader(file: File) : ExifReader {
        private val fmt = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")
        private val map: Map<String, Pair<Long?, Pair<Double, Double>?>> = run {
            val m = HashMap<String, Pair<Long?, Pair<Double, Double>?>>()
            file.readLines().forEach { line ->
                val c = line.split('\t')
                if (c.size < 4) return@forEach
                val name = c[0]
                val ms = runCatching {
                    LocalDateTime.parse(c[1], fmt).atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
                }.getOrNull()
                val lat = c[2].toDoubleOrNull()
                val lng = c[3].toDoubleOrNull()
                m[name] = ms to (if (lat != null && lng != null) lat to lng else null)
            }
            m
        }

        private fun keyOf(ref: String) = File(ref).name

        override fun readCaptureTimeMs(sourceRef: String): Long? = map[keyOf(sourceRef)]?.first
        override fun readGps(sourceRef: String): Pair<Double, Double>? = map[keyOf(sourceRef)]?.second
    }

    @Test
    fun realDataEndToEnd() {
        // ★ 2026-10-31：依赖开发机上的真实日志与真实 EXIF 抽取表，CI（Linux runner）上不存在。
        //   与 app 侧约定一致：数据不在就跳过（assumeTrue），不伪造"绿"。
        assumeTrue(logFile.isFile, "真实日志缺失（${logFile.path}），跳过端到端用例")
        assumeTrue(exifFile.isFile, "真实 EXIF 抽取表缺失（${exifFile.path}），跳过端到端用例")
        // ── 1. 读日志（真实文件，UTF-8 带 BOM；不剥 BOM，验证解析器对 BOM 的耐受）──
        val logText = logFile.readText(Charsets.UTF_8)
        println("== 日志字节数: ${logText.length} 首字符码点: ${logText.firstOrNull()?.code} (65279=BOM)")

        // ── 2. 解析 ──
        val parsed = AlgorithmApi.parseLog(logText, patrolDate, zone)
        println("== 解析事件: ${parsed.events.size} 条, skipped: ${parsed.skippedLines.size} 条")
        parsed.skippedLines.forEach { println("   SKIP: $it") }
        parsed.notices.forEach { println("   NOTICE: $it") }
        parsed.events.forEach { e ->
            val st = e.startTimeMs?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDateTime().format(DateTimeFormatter.ofPattern("MM-dd HH:mm")) } ?: "无时间"
            val et = e.endTimeMs?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDateTime().format(DateTimeFormatter.ofPattern("HH:mm")) } ?: ""
            println("   #${e.id} [$st→$et] ${e.eventType} | ${e.location} | ${e.description.take(24)}")
        }

        // ── 3. 照片候选扫描 → 粗筛 → 派生去重 → EXIF 精读 ──
        // ★ 模拟安卓 MediaStore：为每张照片分配唯一 id（PC 真实场景无 _ID，见 B2 诊断）
        val candidates = dataDir.listFiles { f -> f.isFile && f.extension.equals("jpg", true) }
            ?.sortedBy { it.name }?.mapIndexed { i, f -> CandidatePhoto(id = (i + 1).toLong(), sourceRef = f.absolutePath, displayName = f.name) }
            ?: emptyList()
        println("== 原始 jpg 文件: ${candidates.size}")
        val coarse = PhotoIngest.coarseFilter(candidates, emptyList(), patrolDate..patrolDate)
        println("== 日期粗筛(10-24): ${coarse.size}")
        // ★ 2026-10-24 方案B'：注入真实内容哈希——同序号 original（主图+水印合成，内容不同）必须保留；
        //   旧逻辑（无哈希）会把 4 组同序号 original 误当副本剔除（10:10/10:22/15:59 各丢 1 张无水印）。
        val (deduped, merged) = PhotoIngest.dedupDerived(coarse) { sha256Hex(it) }
        println("== 派生去重: ${deduped.size} 张 (合并 $merged 组 _original/_compressed)")
        val exif = TsvExifReader(exifFile)
        val photos = PhotoIngest.readExif(deduped, exif)
        val uniq = PhotoIngest.dedupById(photos)
        println("== EXIF 精读: ${uniq.size} 张 | 有拍摄时间 ${uniq.count { it.captureTimeMs != null }} | 有GPS ${uniq.count { it.latitude != null }}")

        // ── 4. GPS 分组 ──
        val group = AlgorithmApi.gpsGroup(uniq)
        println("== GPS 分组: ${group.clusters.size} 簇 / 单张 ${group.singles.size} / 拆簇 ${group.splitCount}")
        group.clusters.forEachIndexed { i, c ->
            val t = c.representativeTimeMs?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDateTime().format(DateTimeFormatter.ofPattern("HH:mm:ss")) } ?: "?"
            val latS = c.centerLat?.let { "%.6f".format(it) } ?: "-"
            val lngS = c.centerLng?.let { "%.6f".format(it) } ?: "-"
            println("   簇${i + 1}: ${c.photos.size}张 时间$t 中心($latS,$lngS)")
        }
        group.singles.forEach { println("   单张: ${it.displayName} t=${it.captureTimeMs != null} gps=${it.latitude != null}") }

        // ── 5. 事件分类 ──
        val cls = AlgorithmApi.classify(parsed.events, group.clusters, group.singles, patrolDate)
        val assigned = cls.eventPhotoMap.values.sumOf { it.size }
        println("== 分类: 已归属 $assigned 张 / 未匹配 ${cls.unmatched.size} 张 / 待定事件 ${cls.pendingEventIds.size} 个")
        cls.unmatched.forEach { id -> println("   UNMATCHED: ${uniq.firstOrNull { it.id == id }?.displayName}") }
        cls.interventions.forEach { println("   干预[${it.kind}]: ${it.message}") }
        cls.eventPhotoMap.forEach { (eid, pids) ->
            if (pids.isNotEmpty()) {
                val ev = parsed.events.firstOrNull { it.id == eid }
                println("   事件#$eid ${ev?.location ?: "-"} ${ev?.eventType ?: "-"}: ${pids.size}张")
            }
        }

        // 照片守恒校验：已归属 + 未匹配 == 精读总数（单张应全部进入归属或未匹配）
        val photoById: (Long) -> Photo? = { id -> uniq.firstOrNull { it.id == id } }

        // ── 6. 台账 ──
        val rows = AlgorithmApi.buildLedger(parsed.events, cls.eventPhotoMap, photoById, namingTemplate = 1)
        println("== 台账行: ${rows.size}")
        rows.forEach { r ->
            println("   ${r.dateTime} ~ ${r.endTime} | ${r.location.take(16)} | ${r.description.take(14)} | ${r.photo1Ref ?: "-"} | ${r.photo2Ref ?: "-"} | [${r.folderName}]")
        }

        // ── 7. 导出 XLSX（纯文件名，不嵌图）──
        val xlsxBytes = java.io.ByteArrayOutputStream().also { AlgorithmApi.exportLedgerXlsx(rows, it) }.toByteArray()
        outXlsx.writeBytes(xlsxBytes)
        println("== XLSX: ${xlsxBytes.size} 字节 → $outXlsx")

        // ── 8. 导出 ZIP（台账随包）──
        val zipResult = AlgorithmApi.exportZip(
            parsed.events, cls.eventPhotoMap, cls.unmatched, photoById,
            namingTemplate = 1, outFile = outZip, stateFile = null,
            extraFiles = mapOf("台账.xlsx" to xlsxBytes),
            onProgress = { p, msg -> if (p <= 0f || p >= 1f) println("   zip进度: ${(p * 100).toInt()}% $msg") },
        )
        println("== ZIP: ${outZip.length()} 字节, copied=${zipResult.copied}, failed=${zipResult.failed}, 跳过=${zipResult.skippedFromState}, 失败明细=${zipResult.failedDetails}")
        zipResult.failedDetails.forEach { println("   失败: $it") }

        // ── 9. ★ 2026-10-24 B1/B5：三态去重验证（真实数据：110 jpg = 55 主图 + 55 _original；
        //       ★ B5 方案B' 修复后同序号 original 全保留（派生去重 0 合并），54 组内容重复，
        //       含 08:47 装备检查同刻多拍组（1s 容差内折叠））──
        val dupGroups = PhotoIngest.groupDupContent(uniq)
        val allEventIds = cls.eventPhotoMap.values.flatten()
        val wmIds = PhotoIngest.expandForKeepMode(allEventIds, dupGroups, DedupKeepMode.KEEP_WATERMARKED).toSet()
        val plainIds = PhotoIngest.expandForKeepMode(allEventIds, dupGroups, DedupKeepMode.KEEP_PLAIN).toSet()
        val bothIds = PhotoIngest.expandForKeepMode(allEventIds, dupGroups, DedupKeepMode.KEEP_BOTH).toSet()
        val uniqIds = uniq.map { it.id }.toSet()
        val groupMemberIds = dupGroups.keys + dupGroups.values.flatten().map { it.id }
        val singles = uniqIds.filter { it !in groupMemberIds }
        val inGroups = dupGroups.values.sumOf { it.size + 1 }
        println("== 三态验证: 精读${uniq.size}张, 内容重复组${dupGroups.size}组(参与${inGroups}张), 单份${singles.size}张")
        println("   KEEP_WATERMARKED(带水印) → ${wmIds.size} 张 | KEEP_PLAIN(不带水印) → ${plainIds.size} 张 | KEEP_BOTH → ${bothIds.size} 张")
        assertTrue(dupGroups.isNotEmpty(), "应存在内容重复组")
        assertTrue(dupGroups.size >= 46, "至少包含 46 组同刻同GPS 双份（pair_check.txt 证据）")
        assertEquals(dupGroups.size + singles.size, wmIds.size, "仅带水印 = 组数 + 单份（每组保留代表）")
        assertEquals(dupGroups.size + singles.size, plainIds.size, "仅不带水印 = 组数 + 单份（组内均含 original 或回退代表）")
        assertEquals(uniqIds.size, bothIds.size, "两者都保留 = 全部照片")
        assertTrue(uniqIds.containsAll(wmIds) && uniqIds.containsAll(plainIds), "展开结果必须是原照片子集")
        assertTrue(singles.all { it in wmIds && it in plainIds }, "单份照片在三态下必须全部保留（永不误删）")
        assertTrue(groupMemberIds.all { it in uniqIds }, "重复组照片必须都在原照片中")
        // ★ B5 修复后：110 张全部收录（同序号 original 不再被派生去重误杀），都保留必须 = 110
        assertEquals(110, uniq.size, "方案B' 修复后源目录 110 张照片必须全部收录（4 组同序号 original 全保留）")
        assertTrue(wmIds.size <= 55, "仅保留带水印 ≤ 带水印主图数 55（每组保留代表，同刻多拍会折叠）")

        // ── 10. ★ 2026-10-24 B4：GPS 清零降级判重（真机 ROM 相册脱敏场景复现）──
        //      真机证据：文件 GPS 标签被清零（0/1,0/1,0/1 + 空 ref）→ lat=0.0,lng=0.0，
        //      判重原逻辑 0 组；降级（时间+派生后缀+毫秒序号）必须仍识别全部双份。
        val zeroGps = uniq.map { it.copy(latitude = 0.0, longitude = 0.0) }
        val dupZero = PhotoIngest.groupDupContent(zeroGps)
        println("== GPS清零降级: 成组 ${dupZero.size} 组（原GPS逻辑 ${dupGroups.size} 组）")
        assertTrue(dupZero.size >= 46, "GPS 清零后降级判重应仍识别出全部双份（46 组同刻 + 8 组秒差1s）")
        assertTrue(dupZero.size <= dupGroups.size, "降级判重不应比 GPS 判重合并更多（防误并）")

        // ── 11. ★ 2026-10-24 B1：三态导出验证（真实写 ZIP，逐事件展开后走完整导出管线）──
        //      整体流程闭环：日志解析 → 照片扫描/EXIF → GPS 分组 → 分类 → 三态展开 → 台账 → ZIP
        val dup2 = PhotoIngest.groupDupContent(uniq)
        fun expandAll(epm: Map<Int, List<Long>>, mode: DedupKeepMode): Map<Int, List<Long>> =
            epm.mapValues { (_, ids) -> PhotoIngest.expandForKeepMode(ids, dup2, mode) }

        val wmZip = File("D:/Photo-Mgm/Photo-Mgm/shots/real_e2e_wm.zip")
        val plainZip = File("D:/Photo-Mgm/Photo-Mgm/shots/real_e2e_plain.zip")
        val bothZip = File("D:/Photo-Mgm/Photo-Mgm/shots/real_e2e_both.zip")
        val zipOuts = listOf(
            "仅带水印" to (wmZip to expandAll(cls.eventPhotoMap, DedupKeepMode.KEEP_WATERMARKED)),
            "仅不带水印" to (plainZip to expandAll(cls.eventPhotoMap, DedupKeepMode.KEEP_PLAIN)),
            "都保留" to (bothZip to expandAll(cls.eventPhotoMap, DedupKeepMode.KEEP_BOTH)),
        )
        for ((label, pair) in zipOuts) {
            val (zf, epm) = pair
            if (zf.exists()) zf.delete()
            val r = AlgorithmApi.exportZip(
                parsed.events, epm, cls.unmatched, photoById,
                namingTemplate = 1, outFile = zf, stateFile = null,
                onProgress = { _, _ -> },
            )
            val jpg = countJpgEntries(zf)
            val orig = countOriginalEntries(zf)
            println("== 三态导出[$label]: ${zf.name} ${zf.length()} 字节, jpg=$jpg, _original=$orig, copied=${r.copied}, failed=${r.failed}")
            // ★ 文件删除前断言（删除后读取会失败）
            when (label) {
                "仅带水印" -> {
                    assertEquals(54, jpg, "仅带水印 ZIP 应含 54 张 jpg")
                    assertEquals(0, orig, "仅带水印 ZIP 不应含 _original 备份")
                }
                "仅不带水印" -> assertEquals(54, jpg, "仅不带水印 ZIP 应含 54 张 jpg")
                "都保留" -> assertEquals(110, jpg, "都保留 ZIP 应含 110 张 jpg（方案B' 修复后同序号 original 全保留）")
            }
            zf.delete()   // 验证后清理（110张原图体积大，不留副本）
        }

        // ── 12. ★ 2026-10-31 C：人工移动 + 三态展开闭环（本 bug 的真实危害复现）──
        //      复现：把某组的「带水印代表」移出源事件、放进另一事件；展开后源事件不得再冒出
        //      同组的 _original，ZIP 里也不得出现它（修复前它会落进源事件文件夹）。
        val (movedRepId, movedMates) = dup2.entries.first { it.value.isNotEmpty() }
        val srcEventId = cls.eventPhotoMap.entries.first { (_, ids) -> movedRepId in ids }.key
        val dstEventId = cls.eventPhotoMap.keys.first { it != srcEventId }
        val movedMap = cls.eventPhotoMap.mapValues { (_, ids) -> ids.toMutableList() }.toMutableMap()
        movedMap[srcEventId] = movedMap[srcEventId]!!.filter { it != movedRepId }.toMutableList()
        movedMap.getOrPut(dstEventId) { mutableListOf() }.add(movedRepId)
        val movedPassthrough = setOf(movedRepId)
        val movedExpanded = movedMap.mapValues { (_, ids) ->
            PhotoIngest.expandForKeepMode(ids, dup2, DedupKeepMode.KEEP_WATERMARKED, emptySet(), movedPassthrough)
        }
        val mateIds = movedMates.map { it.id }
        println("== 人工移动闭环: 代表#$movedRepId 事件#$srcEventId → 事件#$dstEventId（同组残片 $mateIds）")
        assertTrue(movedRepId in movedExpanded[dstEventId].orEmpty(), "目标事件必须显示被移动的代表（人工意图直通）")
        assertTrue(movedExpanded[srcEventId].orEmpty().none { it in mateIds },
            "源事件不得再显示同组残留的 _original（本 bug 修复点）")
        // ZIP 实证：导出物里不得因「代表被移走」而漏出无水印原图
        val movedZip = File("D:/Photo-Mgm/Photo-Mgm/shots/real_e2e_moved_wm.zip")
        if (movedZip.exists()) movedZip.delete()
        val movedR = AlgorithmApi.exportZip(parsed.events, movedExpanded, cls.unmatched, photoById,
            namingTemplate = 1, outFile = movedZip, stateFile = null, onProgress = { _, _ -> })
        val movedJpg = countJpgEntries(movedZip)
        val movedOrig = countOriginalEntries(movedZip)
        // 未匹配池不过三态（既有约定），基线 = 未匹配里的 _original 数（本数据集未匹配 0 张）
        val unmatchedOriginal = cls.unmatched.count { photoById(it)?.displayName.orEmpty().contains("_original", true) }
        println("== 人工移动导出[仅带水印]: ${movedZip.name} ${movedZip.length()} 字节, jpg=$movedJpg, _original=$movedOrig, copied=${movedR.copied}, failed=${movedR.failed}")
        movedZip.delete()   // 验证后清理
        assertEquals(unmatchedOriginal, movedOrig, "代表被移走后，_original 不得因组映射失效漏进 ZIP")
        assertEquals(54, movedJpg, "移动只改变归属、不改变总数：仍 54 张（无幽灵重复、无丢失）")

        // ── 断言 ──
        assertTrue(parsed.events.isNotEmpty(), "日志应解析出事件")
        assertTrue(uniq.isNotEmpty(), "照片应非空")
        assertTrue(uniq.count { it.captureTimeMs != null } >= uniq.size * 0.9, "多数照片应有拍摄时间")
        assertEquals(uniq.size, assigned + cls.unmatched.size, "照片数量守恒")
        assertTrue(outZip.exists() && outZip.length() > 0, "ZIP 应生成")
        assertTrue(outXlsx.exists() && outXlsx.length() > 0, "XLSX 应生成")
        println("== 端到端真实测试完成 ==")
    }

    /** ZIP 内 jpg 条目数（只读中央目录，不解压数据）。 */
    private fun countJpgEntries(zip: File): Int = runCatching {
        java.util.zip.ZipFile(zip).use { z ->
            z.entries().asSequence().count { !it.isDirectory && it.name.endsWith(".jpg", ignoreCase = true) }
        }
    }.getOrElse { -1 }

    /** ZIP 内文件名含 _original 的条目数。 */
    private fun countOriginalEntries(zip: File): Int = runCatching {
        java.util.zip.ZipFile(zip).use { z ->
            z.entries().asSequence().count {
                !it.isDirectory && it.name.substringAfterLast('/').contains("_original", ignoreCase = true)
            }
        }
    }.getOrElse { -1 }

    /** 文件 SHA-256 十六进制（方案B' 内容哈希，JVM 纯实现）；读失败 null。 */
    private fun sha256Hex(path: String): String? = runCatching {
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(java.io.File(path).readBytes())
            .joinToString("") { "%02x".format(it) }
    }.getOrNull()
}
