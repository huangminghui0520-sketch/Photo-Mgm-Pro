// algorithm/ZipExporter.kt —— §7.4 ZIP 导出（纯 Kotlin，零 Android 依赖，可移植 PC）
// 按事件分类文件夹打包（复制不移动源文件）；流式复制 32KB；命名模板二选一；未匹配→「未分类」；
// 断点续传 .export_state.json（整包级别：上次已全部完成 → 跳过；extra=true 表示上次带台账）；
// 进度回调（总体百分比 + 当前文件名）；
// extraFiles：额外写入 ZIP 的文件（如台账 Excel），非空时跳过断点续传保证内容最新
package algorithm

import algorithm.model.LogEvent
import algorithm.model.Photo
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** @param zone 文件夹名所用时区（默认北京时间，见 [AppZone]）。 */
class ZipExporter(private val zone: ZoneId = AppZone.ID) {

    data class Result(
        val copied: Int,
        val failed: Int,
        val skippedFromState: Int,
        /** ★ 2026-10-18 P0-3：打包失败明细（ZIP 内路径：原因），供界面展示缺了哪些照片。 */
        val failedDetails: List<String> = emptyList(),
    )

    /** 断点续传状态：已完成路径集合 + 上次导出是否带台账（extraFiles 非空）。 */
    private data class Completed(val paths: Set<String>, val withLedger: Boolean)

    /**
     * 导出 ZIP。
     * @param eventPhotoMap 已合并人工移动后的 eventId -> photoIds（app 侧 effectiveMap）
     * @param unmatched     未匹配照片 id（§7.4 → 「未分类」）
     * @param photoById     照片解析（Android=物理路径 data 层；PC=文件列表）
     * @param namingTemplate 0=事件类型｜1=巡查日志内容（§7.4）
     * @param outFile       ZIP 输出文件
     * @param stateFile     断点续传状态（上次全部完成后跳过）
     * @param extraFiles    ZIP 内路径 -> 字节（如台账 Excel）；非空时整包跳过失效（保证内容最新）
     */
    fun export(
        events: List<LogEvent>,
        eventPhotoMap: Map<Int, List<Long>>,
        unmatched: List<Long>,
        photoById: (Long) -> Photo?,
        namingTemplate: Int,
        outFile: File,
        stateFile: File?,
        extraFiles: Map<String, ByteArray> = emptyMap(),
        onProgress: (Float, String) -> Unit,
    ): Result {
        // 1) 建 文件夹 -> 照片 映射（★ P2-4：结束时间排序 + 序号前缀，未匹配 → 99_未分类）
        //    ★ 必须按排序后的事件序列遍历，否则 ZIP 条目顺序退回 events 原序（映射序号虽对、排列错乱）
        val folders = linkedMapOf<String, MutableList<Photo>>()
        val numbered = numberedFolderNames(events, namingTemplate)
        val orderedEvents = events.sortedBy { it.endTimeMs ?: Long.MAX_VALUE }
        for (e in orderedEvents) {
            val folder = numbered[e.id] ?: folderName(e, namingTemplate)
            val list = folders.getOrPut(folder) { mutableListOf() }
            (eventPhotoMap[e.id] ?: emptyList()).forEach { pid ->
                photoById(pid)?.let { list.add(it) }
            }
        }
        (unmatched).forEach { pid ->
            photoById(pid)?.let { folders.getOrPut(UNMATCHED_FOLDER) { mutableListOf() }.add(it) }
        }

        // 2) 去重（同一照片可能被重复列出）+ 全目标路径集合
        val targets = linkedMapOf<String, File>()   // zip 内路径 -> 源文件
        val usedNames = mutableSetOf<String>()
        for ((folder, photos) in folders) {
            for (p in photos) {
                if (p.sourceRef.isEmpty()) continue
                val base = p.displayName.ifBlank { p.sourceRef.substringAfterLast('/').substringAfterLast('\\') }
                var name = base
                var k = 1
                while ("$folder/$name" in usedNames) {
                    // ★ 2026-10-31 审查修复：base 无扩展名时，旧写法会拼出 "IMG001_1." 这类尾随点名，
                    //   Windows 解压报「文件名无效」导致该条目丢失；按有无扩展名分别拼接。
                    //   dot > 0 才视为扩展名（排除 ".nomedia" 这类点开头文件名）。
                    val dot = base.lastIndexOf('.')
                    name = if (dot > 0) "${base.substring(0, dot)}_$k${base.substring(dot)}"
                    else "${base}_$k"
                    k++
                }
                usedNames += "$folder/$name"
                targets["$folder/$name"] = File(p.sourceRef)
            }
        }

        // 3) 断点续传：仅当本次期望的目标集合与已完成集合【完全一致】才整包跳过。
        //    ★ 修复：旧逻辑 targets ⊆ completed 即跳过，用户移动/删除照片后仍返回含旧照片的 ZIP；
        //      改为集合完全相等才跳过，任何增删改都重新打包。
        //    ★ extraFiles 非空时不跳过：台账内容随标记/移动变化，必须每次都重新打包保证最新。
        //    ★ 2026-10-18 P0-1：跳过还要求"上次导出未带台账(extra=false)"。
        //      否则台账生成失败（extraFiles 为空）时，若上次带台账导出过且照片集合未变，
        //      会误判"已完成"整包跳过 → 输出目录不刷新，用户拿到旧 ZIP（含旧台账）甚至无文件产出。
        val completed = readCompleted(stateFile)
        if (extraFiles.isEmpty() && completed != null &&
            !completed.withLedger && completed.paths == targets.keys &&
            // ★ 2026-10-31 审查修复：仅比对路径集合不足以判定"已完成"。状态文件按巡查日期存放
            //   （与输出位置解耦），用户换输出目录、或删除上次产物后状态仍在 → 走跳过分支会让
            //   调用方拿到 copied=0 / failed=0，进而不复制任何文件到新目录（零产出却提示
            //   "跳过已完成 N"）。补上产物存在性校验：产物不在就重新打包。
            outFile.isFile && outFile.length() > 0
        ) {
            return Result(0, 0, targets.size)
        }

        // 4) 流式打包（32KB 缓冲）
        var copied = 0
        var failed = 0
        val failedDetails = mutableListOf<String>()
        val total = targets.size + extraFiles.size
        FileOutputStream(outFile).use { fos ->
            ZipOutputStream(fos.buffered()).use { zip ->
                for ((path, src) in targets) {
                    try {
                        if (!src.isFile) throw java.io.IOException("文件不存在: ${src.path}")
                        zip.putNextEntry(ZipEntry(path))
                        FileInputStream(src).use { ins ->
                            val buf = ByteArray(32 * 1024)
                            while (true) {
                                val n = ins.read(buf)
                                if (n < 0) break
                                zip.write(buf, 0, n)
                            }
                        }
                        zip.closeEntry()
                        copied++
                    } catch (e: Exception) {
                        failed++
                        // ★ 2026-10-18 P0-3：记录失败明细（路径 + 原因，原因限长防消息爆炸）
                        failedDetails += "$path：${(e.message ?: e.javaClass.simpleName).take(80)}"
                    }
                    onProgress(if (total == 0) 1f else copied.toFloat() / total, path)
                }
                // 4.5) 额外文件（如台账 Excel）
                for ((path, bytes) in extraFiles) {
                    try {
                        zip.putNextEntry(ZipEntry(path))
                        zip.write(bytes)
                        zip.closeEntry()
                        copied++
                    } catch (e: Exception) {
                        failed++
                        failedDetails += "$path：${(e.message ?: e.javaClass.simpleName).take(80)}"
                    }
                    onProgress(if (total == 0) 1f else copied.toFloat() / total, path)
                }
            }
        }
        // 5) 写完成状态（记录本次是否带台账，供下次跳过判断）
        if (failed == 0) writeCompleted(stateFile, targets.keys, extraFiles.isNotEmpty())
        return Result(copied, failed, 0, failedDetails)
    }

    /**
     * 事件分类文件夹名（§7.4）。
     * 模板 1 = 巡查日志内容（描述去开头时间、截断 50 字符、换行合并为空格）。
     * 例：巡查至北行K123+000M处发现护栏损坏
     * ★ 本函数只产出"无序号前缀"的文件夹名；带前缀的最终文件夹名见 [numberedFolderNames]（P2-4）。
     * ★ 2026-11-01 实测决策：去掉原「月日_」前缀，目录名改为「序号_巡查日志内容」。
     *   原因：长日志（>50 字符）被 [truncate] 硬截断到 50 字符，实测「…做好记录，继」末尾缺「续巡查。」；
     *   月日信息已在台账的「日期/结束时间」列完整保留，目录名里再带月日无必要且挤占描述空间。
     *   截断规则统一收敛到 [truncate]（描述 50 字符、换行合并空格、末尾 trimEnd），见该函数注释。
     */
    fun folderName(e: LogEvent, namingTemplate: Int): String {
        return if (namingTemplate == 1) {
            if (e.description.isNotBlank()) {
                sanitize(truncate(stripLeadingTime(e.description), 50))
            } else {
                sanitize(e.eventType)
            }
        } else {
            sanitize(e.eventType)
        }
    }

    /**
     * ★ 2026-10-18 P2-4：带序号前缀的文件夹名映射（eventId -> "01_文件夹名"）。
     *
     * 设计：
     * 1) 排序键 = 事件结束时间升序（endTimeMs，无时间事件最后），与台账行 buildLedger 完全一致
     *    → ZIP 内文件夹顺序 ↔ 台账行顺序一一对应，解压后按名称排序也得到时间序（01/02/03…）。
     * 2) 序号 = 文件夹在排序序列中的首次出现位次 + 1（从 01 起，两位）；
     *    模板0 下同一事件类型多事件合并为同一文件夹 → 只编一次号（取组内最早事件位次）。
     * 3) 未分类文件夹固定为 99_ 前缀（ASCII 数字 < 汉字，按名称排序时始终排在所有事件文件夹之后）。
     */
    fun numberedFolderNames(events: List<LogEvent>, namingTemplate: Int): Map<Int, String> {
        val sorted = events.sortedBy { it.endTimeMs ?: Long.MAX_VALUE }
        val seqByBase = linkedMapOf<String, Int>()   // 无前缀文件夹名 -> 序号
        val result = linkedMapOf<Int, String>()      // eventId -> 带前缀文件夹名
        var next = 1
        for (e in sorted) {
            val base = folderName(e, namingTemplate)
            val seq = seqByBase.getOrPut(base) { next++ }
            result[e.id] = "%02d_%s".format(seq, base)
        }
        return result
    }

    /** 去除描述开头的时间前缀（"8时30分 "、"08:00 "、"上午8点 "、"13时48分至14时48分 "等）。 */
    private fun stripLeadingTime(s: String): String {
        val m = LEADING_TIME_RE.matchAt(s, 0)
        return if (m != null) s.substring(m.range.last + 1).trimStart() else s
    }

    /**
     * 描述单行化 + 截断（保留语义，避免文件夹名过长）。
     *
     * ★ 截断规则（模板 1 目录名「序号_巡查日志内容」的唯一实现处，实测决策 2026-11-01）：
     *   1. 换行/连续空白 → 合并为单个空格；
     *   2. 超 [max]（50）字符 → 硬截断到前 50 个字符（**按字符数，非字节**；中文、英文、数字各占 1）；
     *   3. 截断后末尾空白 trimEnd（避免留下尾随空格或半截标点前导空格）；
     *   —— 截断发生在**目录名拼接之前**，故最终目录名长度 = 序号前缀(2) + "_"(1) + 描述(≤50) ≤ 53 字符，
     *      远低于 Windows 单段名 255 上限，不会触发系统级截断；末尾缺字是 50 字符规则所致，属预期行为。
     */
    private fun truncate(s: String, max: Int): String {
        val oneLine = s.replace(Regex("\\s+"), " ").trim()
        return if (oneLine.length <= max) oneLine else oneLine.take(max).trimEnd()
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("""[\\/:*?"<>|]"""), "_")

    private fun readCompleted(f: File?): Completed? {
        if (f == null || !f.isFile) return null
        val raw = f.readText()
        val decoded = StateJson.decode(raw) ?: return Completed(emptySet(), false)
        return Completed(decoded.first, decoded.second)
    }

    private fun writeCompleted(f: File?, paths: Set<String>, withLedger: Boolean) {
        if (f == null) return
        f.parentFile?.mkdirs()
        f.writeText(StateJson.encode(paths, withLedger))
    }

    /**
     * ★ 2026-10-24 P2-3：断点续传状态文件的 JSON 编解码（替代原手写正则解析）。
     *
     * 原实现 `"completed":\s*\[(.*?)\]` + 按 `,` split：路径含 `]`（正则提前截断）、
     * 含 `,`（split 拆碎）、含 `"`（转义不完整）时解析失真 → 断点续传误判集合不等而失效。
     * 本实现做标准 JSON 字符串/布尔解析（完整转义），自包含零依赖（algorithm 保持 JVM 可移植）。
     *
     * 兼容性：旧格式 `{"completed":[...]}` 无 extra 字段 → 保守按"带台账"（withLedger=true，不跳过），
     * 与既有 P0-1 语义一致。
     */
    private object StateJson {
        fun encode(completed: Set<String>, extra: Boolean): String {
            val sb = StringBuilder("{\"completed\":[")
            completed.forEachIndexed { i, p ->
                if (i > 0) sb.append(',')
                sb.append(quote(p))
            }
            sb.append("],\"extra\":").append(extra).append('}')
            return sb.toString()
        }

        /** 解析 {"completed":[...],"extra":bool}；无 completed 键 → null；无 extra 键 → 保守按 true（不跳过）。 */
        fun decode(raw: String): Pair<Set<String>, Boolean>? {
            var completed: Set<String>? = null
            var extra: Boolean? = null
            var i = 0
            while (i < raw.length) {
                if (raw[i] != '"') { i++; continue }
                val key = parseString(raw, i) ?: return null
                i = key.second
                var j = skipWs(raw, i)
                if (j >= raw.length || raw[j] != ':') continue
                j = skipWs(raw, j + 1)
                when (key.first) {
                    "completed" -> {
                        if (j >= raw.length || raw[j] != '[') return null
                        val list = mutableListOf<String>()
                        var m = j + 1
                        while (true) {
                            m = skipWs(raw, m)
                            if (m >= raw.length) return null
                            if (raw[m] == ']') { m++; break }
                            if (raw[m] != '"') return null
                            val v = parseString(raw, m) ?: return null
                            list.add(v.first)
                            m = skipWs(raw, v.second)
                            if (m >= raw.length) return null
                            if (raw[m] == ',') { m++; continue }
                            if (raw[m] == ']') { m++; break }
                            return null
                        }
                        completed = list.toSet()
                        i = m
                    }
                    "extra" -> {
                        if (raw.startsWith("true", j)) { extra = true; i = j + 4 }
                        else if (raw.startsWith("false", j)) { extra = false; i = j + 5 }
                        else return null
                    }
                    else -> i = key.second
                }
            }
            val c = completed ?: return null
            return c to (extra ?: true)   // 旧格式无 extra → 保守按"带台账"（不跳过）
        }

        private fun skipWs(raw: String, from: Int): Int {
            var i = from
            while (i < raw.length && raw[i].isWhitespace()) i++
            return i
        }

        /** JSON 字符串字面量编码（完整转义：引号/反斜杠/控制字符）。 */
        private fun quote(s: String): String = buildString {
            append('"')
            for (c in s) when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
            append('"')
        }

        /** 解析 JSON 字符串字面量（含完整转义）；返回 (值, 结束引号后位置)。 */
        private fun parseString(raw: String, from: Int): Pair<String, Int>? {
            if (from >= raw.length || raw[from] != '"') return null
            val sb = StringBuilder()
            var i = from + 1
            while (i < raw.length) {
                when (val c = raw[i]) {
                    '"' -> return sb.toString() to (i + 1)
                    '\\' -> {
                        if (i + 1 >= raw.length) return null
                        when (val e = raw[i + 1]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 5 >= raw.length) return null
                                val code = raw.substring(i + 2, i + 6).toIntOrNull(16) ?: return null
                                sb.append(code.toChar())
                                i += 4
                            }
                            else -> return null
                        }
                        i += 2
                    }
                    else -> { sb.append(c); i++ }
                }
            }
            return null
        }
    }

    companion object {
        val BASIC_DATE: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE
        /**
         * 未匹配照片的固定文件夹（P2-4：99_ 前缀保证按名称排序时始终最后）。
         *
         * ★ T14：从 `private companion` 放开为公开常量 —— 它同时是 ZIP 目录名和台账照片列
         *   的标签，属「产物契约」，两端与共享 `app/export/ExportPlanner` 都必须引用**同一处**，
         *   否则改一边忘另一边，症状是"ZIP 里叫 A、台账里叫 B"（`check-shared-purity.py` 的 P4 扫它）。
         */
        const val UNMATCHED_FOLDER = "99_未分类"
        /** 开头时间前缀：上午/下午可选 + 时点 + 可选"至终点" + 尾随空格 */
        val LEADING_TIME_RE = Regex("""^(?:上午|下午)?\d{1,2}[点时:：]\d{0,2}(?:分)?(?:至\d{1,2}[点时:：]\d{0,2}(?:分)?)?\s*""")
    }
}
