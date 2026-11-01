// 测试公共夹具：构造核心模型对象（事件 / 照片），避免每个用例重复样板。
package com.photomgm.desktop.core

import algorithm.AppZone
import algorithm.model.LogEvent
import algorithm.model.Photo
import java.nio.file.Path
import java.time.LocalDate
import java.time.LocalDateTime

object Fixtures {

    val PATROL_DATE: LocalDate = LocalDate.of(2026, 8, 14)

    /** 某日某时某分（北京时间）的 epoch 毫秒。 */
    fun at(hour: Int, minute: Int, date: LocalDate = PATROL_DATE): Long =
        LocalDateTime.of(date.year, date.monthValue, date.dayOfMonth, hour, minute)
            .atZone(AppZone.ID).toInstant().toEpochMilli()

    fun event(
        id: Int,
        startHour: Int,
        startMinute: Int,
        eventType: String,
        location: String,
        description: String,
        endHour: Int = startHour,
        endMinute: Int = startMinute,
    ): LogEvent {
        val start = at(startHour, startMinute)
        val end = at(endHour, endMinute)
        return LogEvent(
            id = id,
            startTimeMs = start,
            endTimeMs = end,
            timePoints = listOf(start, end).distinct(),
            location = location,
            eventType = eventType,
            description = description,
        )
    }

    fun photo(
        id: Long,
        path: Path,
        displayName: String = path.fileName.toString(),
        captureTimeMs: Long? = at(10, 15),
        lat: Double? = 30.25,
        lng: Double? = 120.5,
    ): Photo = Photo(
        id = id,
        sourceRef = path.toAbsolutePath().toString().replace('\\', '/'),
        displayName = displayName,
        captureTimeMs = captureTimeMs,
        latitude = lat,
        longitude = lng,
    )
}
