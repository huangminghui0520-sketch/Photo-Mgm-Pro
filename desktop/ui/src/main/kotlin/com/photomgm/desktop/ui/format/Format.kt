// ui/format/Format.kt —— 展示格式化（只做"人看得懂"，不做业务判断）
//
// ★ 时间一律按 algorithm.AppZone.ID（北京时间）格式化，**不读系统时区**。
//   行程记录一旦按 UTC 显示，撞上跨零点的日志就会把"23:40 的照片"排到第二天，
//   这是现场会真的吵起来的那类错误。核心给的是 epoch 毫秒，格式化归 UI，这里就是落点。
package com.photomgm.desktop.ui.format

import algorithm.AppZone
import com.photomgm.desktop.core.api.PhotoInfo
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

private val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val DATE_CN: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

fun hhmm(ms: Long?): String =
    ms?.let { Instant.ofEpochMilli(it).atZone(AppZone.ID).format(HHMM) } ?: "--:--"

/** 起止时间：两端相同时只显示一次（`08:30 – 08:30` 会让人以为区间为零）。 */
fun timeRange(startMs: Long?, endMs: Long?): String {
    val s = hhmm(startMs)
    if (endMs == null || startMs == endMs) return s
    return "$s–${hhmm(endMs)}"
}

fun dateText(d: LocalDate): String = d.format(DATE_CN)

fun parseDate(raw: String): LocalDate? = try {
    LocalDate.parse(raw.trim(), DATE_CN)
} catch (_: DateTimeParseException) {
    null
}

fun nowDate(): LocalDate = LocalDate.now(AppZone.ID)

/** 照片的辅助信息行：时间 + GPS 状态。没有 GPS 时明确写"无 GPS"，不要留白让人以为加载中。 */
fun photoMeta(p: PhotoInfo): String {
    val time = hhmm(p.captureTimeMs)
    val gps = if (p.hasGps) "GPS" else "无 GPS"
    return "$time · $gps"
}

/** 派生文件（_original / _compressed）在文件名上通常已带后缀，UI 再加角标避免认错"重复照片"。 */
fun derivedSuffix(p: PhotoInfo): String? = if (p.isDerived) "派生" else null

fun clockText(): String = LocalDateTime.now(AppZone.ID).format(DateTimeFormatter.ofPattern("HH:mm:ss"))
