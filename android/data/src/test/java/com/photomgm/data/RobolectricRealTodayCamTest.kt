// data/RobolectricRealTodayCamTest.kt —— ★ 决定性验证（2026-10-24）
// JVM(Robolectric) 上跑真实 androidx ExifInterface 1.3.7 读真实 todayCam 照片（桌面副本）：
//   若时间读到、GPS(latLong) null → 根因确认：ExifInterface 与 todayCam GPS 布局不兼容 → 真机判重 0 组。
package com.photomgm.data

import androidx.exifinterface.media.ExifInterface
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RobolectricRealTodayCamTest {

    private val dir = File("D:/Photo-Mgm/Photo-Mgm/shots/tmp_exif")

    /**
     * ★ [SKIP-SCAN] 原来 `dir.listFiles() ?: emptyList()` 在目录缺失时循环 0 次，
     *   用例「通过」却什么都没验证 —— 典型的静默空绿。改为带原因跳过，让 skipped 可见。
     */
    private fun requireSamples() {
        assumeTrue(
            "[SKIP-SCAN] 真实 todayCam 样本目录不存在（$dir）：本用例只做真实照片 EXIF 明细打印对照，仓库与 CI 均不携带；缺样本时跳过并计入运行期 skipped",
            dir.isDirectory
        )
    }

    @Test
    fun readRealTodayCamPhotos() {
        requireSamples()
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
