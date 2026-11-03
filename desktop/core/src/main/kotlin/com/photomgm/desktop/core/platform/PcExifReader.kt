// desktop/core/platform/PcExifReader.kt —— EXIF 读取（实现核心的 ExifReader 接口）
//
// Android 侧是 androidx ExifInterface；桌面侧用 metadata-extractor（Apache-2.0，~350KB）。
// 语义必须与 Android 实现一致（否则同一批照片两端分类结果不同，R8 双端漂移）：
//   · 拍摄时间优先 DateTimeOriginal（0x9003），缺失退回 DateTimeDigitized / DateTime；
//   · 时间字符串是"本地挂钟时间"，必须按**业务时区** [zone]（默认北京时间）解释成 epoch 毫秒，
//     不能用系统默认时区 —— 否则跨时区运行时台账日期整体偏移；
//   · GPS 读不到返回 null，不抛异常；HEIC/损坏文件同理（→ 该照片进"无 GPS/无时间"分支）。
package com.photomgm.desktop.core.platform

import algorithm.AppZone
import algorithm.ExifReader
import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.Metadata
import com.drew.metadata.exif.ExifIFD0Directory
import com.drew.metadata.exif.ExifSubIFDDirectory
import com.drew.metadata.exif.GpsDirectory
import domain.exif.ExifTime
import java.io.File
import java.time.ZoneId

internal class PcExifReader(
    private val zone: ZoneId = AppZone.ID,
) : ExifReader {

    override fun readCaptureTimeMs(sourceRef: String): Long? = readAll(sourceRef).first

    override fun readGps(sourceRef: String): Pair<Double, Double>? = readAll(sourceRef).second

    /**
     * 合并读取：**只打开一次文件**（Android 侧同样为此覆写了 readAll）。
     * 批量精读 1000 张时，两次独立读取会让 IO 翻倍。
     */
    override fun readAll(sourceRef: String): Pair<Long?, Pair<Double, Double>?> {
        val path = runCatching { PcPaths.toPath(sourceRef) }.getOrNull() ?: return null to null
        if (!path.toFile().isFile) return null to null
        val meta = runCatching { ImageMetadataReader.readMetadata(File(path.toString())) }.getOrNull()
            ?: return null to null
        return readTime(meta) to readGps(meta)
    }

    private fun readTime(meta: Metadata): Long? {
        val sub = runCatching { meta.getFirstDirectoryOfType(ExifSubIFDDirectory::class.java) }.getOrNull()
        val ifd0 = runCatching { meta.getFirstDirectoryOfType(ExifIFD0Directory::class.java) }.getOrNull()
        val raw = sequenceOf(
            runCatching { sub?.getString(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL) }.getOrNull(),
            runCatching { sub?.getString(ExifSubIFDDirectory.TAG_DATETIME_DIGITIZED) }.getOrNull(),
            runCatching { ifd0?.getString(ExifIFD0Directory.TAG_DATETIME) }.getOrNull(),
        ).firstOrNull { !it.isNullOrBlank() } ?: return null
        return parseExifDateTime(raw!!)
    }

    private fun readGps(meta: Metadata): Pair<Double, Double>? {
        val gps = runCatching { meta.getFirstDirectoryOfType(GpsDirectory::class.java) }.getOrNull()
            ?: return null
        val geo = runCatching { gps.geoLocation }.getOrNull() ?: return null
        // (0,0) 是"GPS 被清零/未写入"的典型值，按无 GPS 处理（与核心 isZeroGps 同口径）
        if (geo.isZero) return null
        return geo.latitude to geo.longitude
    }

    companion object {
        /**
         * 把 EXIF 的"本地挂钟时间"按 [zone] 解释成 epoch 毫秒。
         *
         * T03-C2：解析规则已收敛到 [domain.exif.ExifTime]（两端同一份实现）。原来这里只认
         * `yyyy:MM:dd`，安卓认出短横格式，于是同一张照片在 PC 上"无拍摄时间"、在手机上却有 ——
         * 8 张真实素材就掉进了 `99_未分类`。现在取两端能力并集：
         * 冒号/短横都认 + `substring(0,19)` 截断（容忍毫秒与时区尾巴）+ 失败返回 null。
         *
         * 保留本函数而不让调用方直接调 ExifTime：单测拿它验证时区语义（同一字符串在不同 zone
         * 下应得到不同 epoch），这层薄壳让断言不必关心包名迁移。
         */
        fun parseExifDateTime(raw: String, zone: ZoneId = AppZone.ID): Long? = ExifTime.parse(raw, zone)
    }
}
