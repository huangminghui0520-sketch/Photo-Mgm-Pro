// LogParserReport.kt —— 日志解析诊断/验证入口（非核心算法逻辑，仅供人工审核解析输出）
// 用法：
//   默认跑黄金样本： gradle :algorithm:runLogParser
//   验证自己的日志： gradle :algorithm:runLogParser --args="--file=你的日志文件.txt"
package algorithm

import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object LogParserReport {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val TIME = DateTimeFormatter.ofPattern("HH:mm")
    private val DATE = DateTimeFormatter.ofPattern("MM-dd")

    @JvmStatic
    fun main(args: Array<String>) {
        val date = LocalDate.of(2026, 9, 3)   // 巡查日期（真实使用由设置页选择，此处黄金样本固定）
        val fileArg = args.firstOrNull { it.startsWith("--file=") }?.removePrefix("--file=")
        val text = if (fileArg != null) {
            File(fileArg).readText(Charsets.UTF_8)
        } else {
            LogParserReport::class.java.getResource("/golden_sample.txt")
                ?.readText(Charsets.UTF_8) ?: error("未找到 golden_sample.txt")
        }

        val r = LogParserEngine.parse(text, date, zone)
        val sb = StringBuilder()

        sb.appendLine("════════ 日志解析报告（巡查日期 $date，文件=${fileArg ?: "黄金样本"}）════════")
        sb.appendLine("巡查人员: ${r.inspectors ?: "—"}    记录人: ${r.recorder ?: "—"}    车牌: ${r.vehicle ?: "—"}")
        sb.appendLine("事件总数: ${r.events.size}    跳过行: ${r.skippedLines.size}")
        if (r.notices.isNotEmpty()) r.notices.forEach { sb.appendLine("提示: $it") }

        if (r.skippedLines.isNotEmpty()) {
            sb.appendLine("—— 跳过行 ——")
            r.skippedLines.forEach { sb.appendLine("  · $it") }
            sb.appendLine()
        }

        sb.appendLine()
        sb.appendLine("编号  时间区间                         事件类型              地点")
        sb.appendLine("──── ──────────────────────────────── ──────────────────── ─────────────")
        for (e in r.events) {
            sb.appendLine("[%02d] %-32s %-20s %s".format(e.id, fmtRange(e.startTimeMs, e.endTimeMs), e.eventType, e.location))
            sb.appendLine("       描述: ${e.description}")
        }

        val out = sb.toString()
        println(out)

        // 落盘到项目根目录，方便逐条审核
        val outFile = File(System.getProperty("user.dir"), "parse_report.txt")
        outFile.writeText(out, Charsets.UTF_8)
        println("报告已保存: ${outFile.absolutePath}")
    }

    /** 时间区间：单时间点显示 HH:mm；跨日前缀日期（MM-dd HH:mm）。 */
    private fun fmtRange(startMs: Long?, endMs: Long?): String {
        if (startMs == null) return "（待定）"
        val s = Instant.ofEpochMilli(startMs).atZone(zone)
        val e = if (endMs != null) Instant.ofEpochMilli(endMs).atZone(zone) else s
        return if (s.toLocalDate() != e.toLocalDate()) {
            "${s.format(DATE)} ${s.format(TIME)}～${e.format(DATE)} ${e.format(TIME)}"
        } else if (endMs == null || endMs == startMs) {
            s.format(TIME)
        } else {
            "${s.format(TIME)}～${e.format(TIME)}"
        }
    }
}
