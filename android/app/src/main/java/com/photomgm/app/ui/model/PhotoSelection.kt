// app/ui/model/PhotoSelection.kt —— 照片多选与时间格式化的纯函数（无 UI 依赖）
package com.photomgm.app.ui.model

import androidx.compose.runtime.saveable.Saver
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * 自定义 Saver：Set<Long> → 可保存的基础类型列表。
 *
 * ★ 两个照片弹窗（EventPhotosSheet / UnmatchedPhotosSheet）共用同一份「选中集合」的
 *   rememberSaveable 序列化器，抽到 model 避免各写一份导致行为漂移。
 */
internal val LongSetSaver: Saver<Set<Long>, List<Long>> = Saver(
    save = { it.toList() },
    restore = { it.toSet() },
)

private val fmt = DateTimeFormatter.ofPattern("HH:mm")

/** 把起止毫秒时间戳格式化成「HH:mm」或「HH:mm～HH:mm」；无时间返回「无时间」。 */
internal fun fmtRange(start: Long?, end: Long?): String {
    val zone = algorithm.AppZone.ID
    val s = start?.let { Instant.ofEpochMilli(it).atZone(zone).format(fmt) } ?: return "无时间"
    val e = end ?: return s
    if (e == start) return s
    return "$s～${Instant.ofEpochMilli(e).atZone(zone).format(fmt)}"
}

/** 照片瓦片右下角的时间角标格式（仅时分）。 */
internal val tileTimeFmt = DateTimeFormatter.ofPattern("HH:mm")
