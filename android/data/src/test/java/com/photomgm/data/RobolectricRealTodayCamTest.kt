// data/RobolectricRealTodayCamTest.kt —— ★ 决定性验证（2026-10-24）
// JVM(Robolectric) 上跑真实 androidx ExifInterface 1.3.7 读真实 todayCam 照片（桌面副本）：
//   若时间读到、GPS(latLong) null → 根因确认：ExifInterface 与 todayCam GPS 布局不兼容 → 真机判重 0 组。
package com.photomgm.data

import androidx.exifinterface.media.ExifInterface
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RobolectricRealTodayCamTest {

    private val dir = File("D:/Photo-Mgm/Photo-Mgm/shots/tmp_exif")

    @Test
    fun readRealTodayCamPhotos() {
        val reader = ExifPhotoReader()
        for (f in dir.listFiles { it.isFile && it.extension.equals("jpg", true) }?.sortedBy { it.name } ?: emptyList()) {
            // 方式1：ExifPhotoReader（App 实际使用）
            val (t, g) = reader.readAll(f.absolutePath)
            // 方式2：ExifInterface 直读明细
            val ei = ExifInterface(f.absolutePath)
            val dto = ei.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            val dt = ei.getAttribute(ExifInterface.TAG_DATETIME)
            val ll = ei.latLong?.joinToString { "%.6f".format(it) }
            println("== ${f.name}: 读[time=$t gps=${g?.let { "%.6f,%.6f".format(it.first, it.second) }}] " +
                    "明细[DTO=$dto DT=$dt latLong=$ll]")
        }
    }
}
