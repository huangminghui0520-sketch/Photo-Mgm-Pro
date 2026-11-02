// data/ExifPhotoReader.kt —— §4.3 EXIF 精读（Android 实现）
// 读取失败字段置 null、不中断（§4.3）；时间解析走纯函数 ExifTimeParser（可单测）
// ★ C2 优化（性能基线 E2a 证据 203→67ms）：readAll 一次 ExifInterface 构造同时读时间+GPS，
//   消除 readCaptureTimeMs/readGps 的重复构造；语义与损坏三分类（T1 0字节/T2 无GPS/T3 无EXIF）经 oracle 验证一致。
package com.photomgm.data

import algorithm.AppZone
import algorithm.ExifReader
import androidx.exifinterface.media.ExifInterface

/**
 * ExifInterface 数据接入：按文件路径/uri 精读拍摄时间 + GPS。
 * - captureTimeMs：TAG_DATETIME_ORIGINAL 优先，回退 TAG_DATETIME；解析失败 null
 * - GPS：latLong（ExifInterface 已封装北/南纬、东/西经换算）；缺失 null
 * - readAll：一次构造读两者；时间与 GPS 故障相互隔离（各自 runCatching，置 null 不中断）
 */
class ExifPhotoReader : ExifReader {

    override fun readCaptureTimeMs(sourceRef: String): Long? = readAll(sourceRef).first

    override fun readGps(sourceRef: String): Pair<Double, Double>? = readAll(sourceRef).second

    override fun readAll(sourceRef: String): Pair<Long?, Pair<Double, Double>?> = runCatching {
        val ei = ExifInterface(sourceRef)
        val t = runCatching {
            val raw = ei.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: ei.getAttribute(ExifInterface.TAG_DATETIME)
                ?: return@runCatching null
            // ★ 显式传业务时区（issue #2）：EXIF 时间无时区信息，必须固定按北京时间解释，
            //   不能跟随设备默认时区，否则同一张照片在不同机器上算式不同。
            ExifTimeParser.parse(raw, AppZone.ID)
        }.getOrNull()
        val g = runCatching {
            ei.latLong?.let { Pair(it[0], it[1]) }
        }.getOrNull()
        t to g
    }.getOrNull() ?: (null to null)
}
