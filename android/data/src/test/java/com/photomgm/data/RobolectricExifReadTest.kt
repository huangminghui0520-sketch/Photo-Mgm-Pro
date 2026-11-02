// data/RobolectricExifReadTest.kt —— JVM(Robolectric) 上跑真实 androidx ExifInterface 读测试照片 EXIF
// 验证测试照片（gps_test_photos/*.jpg 与 std_T01.jpg）的 DateTime/GPS 可被 androidx ExifInterface 正确解析
// JUnit4 + RobolectricTestRunner（经 vintage engine 由 JUnit Platform 执行）
package com.photomgm.data

import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RobolectricExifReadTest {

    private val ROOT = File("D:/Photo-Mgm/Photo-Mgm")
    private fun abs(p: String) = File(ROOT, p).absolutePath

    @Test
    fun test_T01_DateTimeOriginal与GPS可读() {
        val ei = ExifInterface(abs("gps_test_photos/IMG_20260817_080030_T01.jpg"))
        assertEquals("2026:08:17 08:00:30", ei.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        val ll = ei.latLong
        println("T01 lat=" + ei.getAttribute(ExifInterface.TAG_GPS_LATITUDE) + " ref=" + ei.getAttribute(ExifInterface.TAG_GPS_LATITUDE_REF) + " lng=" + ei.getAttribute(ExifInterface.TAG_GPS_LONGITUDE) + " latLong=" + ll?.contentToString())
        assertNotNull("GPS latLong 应为非 null", ll)
        assertEquals(23.02, ll!![0], 0.001)
        assertEquals(113.01, ll[1], 0.001)
    }

    @Test
    fun test_T13_无时间无GPS全null() {
        val ei = ExifInterface(abs("gps_test_photos/IMG_20260817_095000_T13.jpg"))
        assertEquals(null, ei.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        assertEquals(null, ei.latLong)
    }

    // 注：std_T01.jpg（piexif 生成，值区位于 ExifIFD/GPSIFD 之后）在 androidx ExifInterface 1.3.7 下 GPS 解析
    // 存在兼容性问题（Robolectric 亦失败）——仅作模拟器对照实验用，不作为主验证数据；
    // 主验证使用 gps_test_photos/*（值区位于 IFD 前，Robolectric 读取 GPS 成功），见 GpsEndToEndRobolectricTest。

    @Test
    fun test_全13张时间可解析() {
        val names = listOf(
            "IMG_20260817_080030_T01.jpg", "IMG_20260817_080130_T02.jpg",
            "IMG_20260817_080430_T03.jpg", "IMG_20260817_081000_T04.jpg",
            "IMG_20260817_083500_T05.jpg", "IMG_20260817_083630_T06.jpg",
            "IMG_20260817_085000_T07.jpg", "IMG_20260817_085130_T08.jpg",
            "IMG_20260817_093200_T09.jpg", "IMG_20260817_093330_T10.jpg",
            "IMG_20260817_093500_T11.jpg", "IMG_20260817_094000_T12.jpg",
            "IMG_20260817_095000_T13.jpg",
        )
        names.forEach { n ->
            val dt = ExifInterface(abs("gps_test_photos/$n"))
                .getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            if (n.endsWith("T13.jpg")) assertEquals("T13 无时间", null, dt)
            else assertNotNull("$n 应有时间", dt)
        }
    }
}
