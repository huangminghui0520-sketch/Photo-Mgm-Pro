// algorithm/LogParserEngine.kt —— §3.4 日志解析主流程（按 v7.0 文档原样实现）
package algorithm

import algorithm.model.LogEvent
import java.time.LocalDate
import java.time.ZoneId

/** 日志解析结果（§3.4） */
data class ParsedLog(
    val events: List<LogEvent>,        // 已去重、按 endTime 升序
    val skippedLines: List<String>,    // 无法识别行
    val inspectors: String?, val recorder: String?, val vehicle: String?,
    val notices: List<String>,
)

object LogParserEngine {
    private val SEQ_RE = Regex("""^(?:\d{1,2}[\.、\)）]|[(（]\d{1,2}[)）]|第\d{1,2}条|No\.?\d{1,2}|①)""")
    private val TIME_START_RE = Regex("""^(?:\d{1,2}[:时点]|(?:上午|下午|中午|傍晚)\s*[\d一二三四五六七八九十]+点|[0-5]\d{3})""")

    /** @param zone 日志里的本地挂钟时间 → 绝对时刻所用时区（默认北京时间，见 [AppZone]）。 */
    fun parse(text: String, date: LocalDate, zone: ZoneId = AppZone.ID): ParsedLog {
        val lines = preprocess(text, date, zone)
        val events = mutableListOf<LogEvent>()
        val skipped = mutableListOf<String>()
        var inspectors: String? = null; var recorder: String? = null; var vehicle: String? = null

        for (line in lines) {
            // 全局信息（如实提取，不脱敏）
            // ★ inspectors 保持冒号可选（样本「巡查人员张某某、李某某」无冒号；捕获组要求多人连接，
            //   故「巡查人员名单」不会被误抓）；recorder 修复：冒号必填，排除「记录时间→时间」误抓
            inspectors = inspectors ?: Regex("""巡查人员[:：]?\s*([\u4e00-\u9fa5]{2,3}(?:[、,，\s]+[\u4e00-\u9fa5]{2,3})+)""")
                .find(line)?.groupValues?.get(1)
            recorder = recorder ?: Regex("""(?:记录人|记录)\s*[:：]\s*([\u4e00-\u9fa5]{2,3})""")
                .find(line)?.groupValues?.get(1)
            vehicle = vehicle ?: Regex("""[\u4e00-\u9fa5][A-Z][A-Z0-9]{5,6}""")
                .find(line)?.groupValues?.get(0)

            val points = TimeExtractor.extractAll(line, date, zone)
            if (points.isEmpty()) {
                // ★ 兼容性增强（§3.4 关键点5 / §6.4 / 业务规则18）：
                //   无时间但含事件语义的行 → 待定事件（startTimeMs=null，进待定事件区，照片不自动归入，预览页人工指定时间）；
                //   否则 → skippedLines（无法识别行）
                if (isLogLike(line)) {
                    val desc = line.trim()
                    events.add(LogEvent(
                        id = 0,
                        startTimeMs = null,
                        endTimeMs = null,
                        timePoints = emptyList(),
                        location = LocationExtractor.extract(desc) ?: "",
                        eventType = EventTypeClassifier.classify(desc),
                        description = desc,
                    ))
                } else {
                    skipped.add(line)
                }
                continue
            }
            val desc = line.trim()
            events.add(LogEvent(
                id = 0,
                startTimeMs = points.first(),
                endTimeMs = points.last(),
                timePoints = points,
                location = LocationExtractor.extract(desc) ?: "",
                eventType = EventTypeClassifier.classify(desc),
                description = desc,
            ))
        }
        // 去重：键 = start|地点|【剥行首时间前缀后的描述】
        //   → "上午8点 与早班…交接班…" 与 "08时00分 与早班…交接班…" 合并为 1 条
        //   → 时间相同但内容不同的两条（样本两条 8时30分：护栏损坏/标志牌倾斜）不被误删
        val dedup = LinkedHashMap<String, LogEvent>()
        val timePrefix = Regex("""^(?:上午|下午|中午|傍晚)?\s*[\d一二三四五六七八九十]+(?:[点时][\d一二三四五六七八九十]*分?|[:：]\d{1,2})?\s*""")
        for (e in events) {
            val coreDesc = timePrefix.replaceFirst(e.description, "")
            dedup.putIfAbsent("${e.startTimeMs}|${e.location}|$coreDesc", e)
        }
        // 按 endTime 升序编号
        val sorted = dedup.values.sortedBy { it.endTimeMs ?: Long.MAX_VALUE }
        sorted.forEachIndexed { i, e -> e.id = i + 1 }
        val notices = buildList {
            if (skipped.isNotEmpty()) add("${skipped.size} 行无法识别，已跳过")
            if (sorted.any { it.startTimeMs == null }) add("存在未记录时间的事件，需在预览页人工指定")
        }
        return ParsedLog(sorted, skipped, inspectors, recorder, vehicle, notices)
    }

    /** 预处理：统一换行 → 去空行 → 去行首序号 → 续行合并（行首非时间则并入上一行）。 */
    private fun preprocess(text: String, date: LocalDate, zone: ZoneId): List<String> {
        val lines = text.replace("\r\n", "\n").replace("\r", "\n")
            .split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        val out = mutableListOf<String>()
        for (raw in lines) {
            val line = removeSeq(raw)
            val isNewEntry = TIME_START_RE.containsMatchIn(line) ||
                TimeExtractor.extractAll(line, date, zone).isNotEmpty()
            if (!isNewEntry && out.isNotEmpty()) {
                // ★ 兼容性增强：无时间行若疑似独立日志（含事件语义），不并入上一行，独立成待定事件；
                //   否则视为续行（如"已拍照取证"）并入上一行
                if (isLogLike(line)) out.add(line)
                else out[out.size - 1] = out.last() + " " + line
            } else out.add(line)
        }
        return out
    }

    /** 剥离行首序号（如 1. / 1、 / (1) / 第1条 / ①）；"0800""08:00"等时间开头不剥离。 */
    private fun removeSeq(line: String): String {
        val m = SEQ_RE.find(line) ?: return line
        return line.substring(m.range.last + 1).trim()
    }

    // ★ 无时间行判定：是否像一条独立日志（而非续行/说明文字）。
    //   含地点 / 事件类型命中 / 行首出现日志动作起始词 → 视为独立日志（进待定事件区）。
    private val LOG_START_ACTION = Regex(
        """^(?:接报|巡查|发现|处理|到达|前往|进行|开展|检查|上报|记录|报备|处置|劝离|清理|协助|返回|出发|完成|在|收车|交接|召开|执行)""")
    private fun isLogLike(line: String): Boolean {
        if (line.length < 4) return false                       // 太短不像独立日志
        if (LocationExtractor.extract(line) != null) return true // 含桩号/收费站等地点的行 → 事件
        if (EventTypeClassifier.classify(line) != "主线巡查") return true  // 命中具体事件类型
        return LOG_START_ACTION.containsMatchIn(line)           // 行首动作起始词
    }
}
