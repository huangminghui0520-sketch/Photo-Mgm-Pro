// algorithm/TimeExtractor.kt —— §3.1 时间提取
// 修复（经探针测试验证）：
//   1. 中文"X点半/下午三点半"曾被 pattern3(八点)+pattern4(八点半) 拆成两个伪时间点 → 合并为一个 pattern，分钟支持 中文分|半
//   2. 中文组合分钟("三十分"=30)因 toInt 仅支持一~十二被归零 → 新增 toMinute 支持 1~59
//   3. 阿拉伯数字"8点半"此前不识别"半" → pattern 增加 分|半
package algorithm

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

object TimeExtractor {
    private val patterns = listOf(
        // 08:00 / 8时5分 / 9:05
        Regex("""(\d{1,2})[:时](\d{1,2})(?:[:分](\d{1,2}))?"""),
        // ★ 阿拉伯数字点 + 可选分/半："8点" / "8点30分" / "下午3点" / "8点半"
        //   （"分/半"必须收进【单一】捕获组，否则"半"会落到另一个组号，分钟读取为 null）
        Regex("""(上午|下午|中午|傍晚)?\s*(\d{1,2})点((?:[ ]?\d{1,2})分|半)?"""),
        // ★ 中文数字点 + 可选中文分/半："八点" / "八点三十分" / "八点半" / "下午三点半"
        //   （合并原 pattern3/pattern4，消除"八点半"被拆成 08:00+08:30 两个伪时间点的重叠缺陷；
        //     且不能同时放独立的 (\d{1,2})点 模式，否则"下午3点"会被匹配两次 → 15:00 与 03:00 伪点）
        Regex("""(上午|下午|中午|傍晚)?\s*([一二三四五六七八九十]+)点((?:[一二三四五六七八九十]+)分|半)?"""),
        // 0800 / 1420
        Regex("""\b([01]\d|2[0-3])([0-5]\d)\b"""),
    )

    /**
     * 提取一行日志中的全部时间点，返回完整时间戳（含日期）。
     * ★ 按【文本出现顺序】排序（不是按时分排序）——保证跨日正确：
     *   "23时30分 接报……01时00分处理完毕" → [23:30, 次日01:00]
     * 跨日判定：当前时间（时分）早于上一条 → 该条起日期 +1 天。
     */
    fun extractAll(text: String, date: LocalDate, zone: ZoneId): List<Long> {
        // ★ 保护年份：先等长屏蔽 19xx/20xx，避免 pattern4(0800/1420) 把 "2026" 解析成 20:26
        //   （"2026" 的 "20"+"26" 恰好构成合法 HHmm；仅靠 \b 边界无法排除，必须屏蔽年份）
        val masked = text.replace(Regex("""(?:19|20)\d{2}"""), "    ")
        val found = mutableListOf<Pair<Int, LocalTime>>()      // (出现位置, 时间)
        for (p in patterns) {
            for (m in p.findAll(masked)) {
                parseLocalTime(p, m)?.let { found.add(m.range.first to it) }
            }
        }
        // 按出现位置排序、按时间值去重（保留首次出现位置）
        val unique = LinkedHashMap<LocalTime, Int>()
        found.sortedBy { it.first }.forEach { (pos, t) -> unique.putIfAbsent(t, pos) }
        val ordered = unique.keys.toList()
        if (ordered.isEmpty()) return emptyList()
        // 跨日：时分回绕（当前 < 上一条）→ 日期 +1 天
        var dayOffset = 0L
        return ordered.mapIndexed { i, t ->
            if (i > 0 && t.isBefore(ordered[i - 1])) dayOffset++
            LocalDateTime.of(date.plusDays(dayOffset), t).atZone(zone).toInstant().toEpochMilli()
        }
    }

    /** 组号约定：含"上午"的模式 = 有 period，hour 在 group2、minute 在 group3；无 period 模式 hour 在 group1、minute 在 group2。 */
    private fun parseLocalTime(p: Regex, m: MatchResult): LocalTime? {
        val hasPeriod = p.pattern.contains("上午")
        val isChinese = p.pattern.contains("一")
        val hourIdx = if (hasPeriod) 2 else 1
        val hourRaw = m.groupValues.getOrNull(hourIdx) ?: return null
        val hour = (if (isChinese) toInt(hourRaw) else hourRaw.toIntOrNull()) ?: return null
        val minRaw = m.groupValues.getOrNull(hourIdx + 1) ?: ""
        val minute = when {
            minRaw == "半" -> 30
            minRaw.endsWith("分") -> {
                val num = minRaw.removeSuffix("分").trim()
                if (isChinese) toMinute(num) ?: 0 else num.toIntOrNull() ?: 0
            }
            minRaw.isNotEmpty() -> (if (isChinese) toMinute(minRaw) else minRaw.toIntOrNull()) ?: 0
            else -> 0
        }
        var h = hour
        if (hasPeriod) when (m.groupValues[1]) {
            "下午" -> if (h < 12) h += 12
            "中午" -> h = 12
            "傍晚" -> h = 18
        }
        return try { LocalTime.of(h, minute) } catch (e: Exception) { null }
    }

    private val DIGIT = mapOf(
        "一" to 1, "二" to 2, "三" to 3, "四" to 4, "五" to 5,
        "六" to 6, "七" to 7, "八" to 8, "九" to 9,
    )

    /** 中文组合数字 1~59：一~九 / 十 / 十一~十九 / 二十~五十（整十）/ 二十一~五十九。 */
    private fun toMinute(s: String): Int? {
        if (s == "十") return 10                                    // 必须先于 length==1（"十"不在 DIGIT）
        if (s.length == 1) return DIGIT[s]
        if (s.length == 2 && s[0] == '十') return 10 + (DIGIT[s[1].toString()] ?: return null)   // 十一~十九
        if (s.length == 2 && s[1] == '十') return (DIGIT[s[0].toString()] ?: return null) * 10    // 二十/三十/四十/五十
        if (s.length == 3 && s[1] == '十') {
            val tens = DIGIT[s[0].toString()] ?: return null
            val ones = DIGIT[s[2].toString()] ?: return null
            return tens * 10 + ones                                                                 // 二十一~五十九
        }
        return null
    }

    /** 小时中文数字（一~十二）；分钟组合走 toMinute。 */
    private fun toInt(s: String?): Int? = when (s) {
        null, "" -> null
        "一" -> 1; "二" -> 2; "三" -> 3; "四" -> 4; "五" -> 5
        "六" -> 6; "七" -> 7; "八" -> 8; "九" -> 9; "十" -> 10
        "十一" -> 11; "十二" -> 12
        else -> s.toIntOrNull()
    }
}
