// data/ExifTimeParser.kt —— EXIF 时间字符串解析（纯函数，JVM 可单测）
// ExifInterface 的时间格式为 "yyyy:MM:dd HH:mm:ss"（冒号分隔）；部分设备/工具写入 "yyyy-MM-dd HH:mm:ss"
package com.photomgm.data

import algorithm.AppZone
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object ExifTimeParser {
    private val COLON = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")
    private val DASH = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /**
     * 解析 EXIF 时间文本 → epoch 毫秒（绝对时刻）。
     *
     * @param zone 把「相机写下的本地挂钟时间」解释成绝对时刻所用时区。
     *             ★ EXIF 时间戳**不带时区信息**，只能按业务时区解释（默认 [AppZone.ID] 北京时间，
     *             issue #2）。用设备默认时区会导致同一张照片在不同机器上算出不同的 epoch，
     *             进而让事件归属随设备时区漂移。
     */
    fun parse(text: String, zone: ZoneId = AppZone.ID): Long? {
        val s = text.trim()
        if (s.isEmpty()) return null
        return runCatching {
            // 按日期分隔符判断：位置 4 是 ':'（yyyy:MM:dd）或 '-'（yyyy-MM-dd）；时间部分的冒号不可作判据
            val fmt = if (s.length > 4 && s[4] == ':') COLON else DASH
            LocalDateTime.parse(s, fmt).atZone(zone).toInstant().toEpochMilli()
        }.getOrNull()
    }
}
