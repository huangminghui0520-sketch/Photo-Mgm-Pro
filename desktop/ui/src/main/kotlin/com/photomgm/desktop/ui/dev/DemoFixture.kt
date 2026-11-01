// ui/dev/DemoFixture.kt —— --demo：造一套真实素材，走真实流水线
//
// ★ 目的只有一个：让"界面在真实 AppState 下长什么样"这件事可复现、可截图、可对比。
//   所以它不打桩 AppState，而是造出真实的照片文件（带 EXIF 时间与 GPS）与真实日志文本，
//   调 core.updateSettings / setLogText / analyze()，等状态真的变成 READY。
//   如果哪天界面只在假数据上正常、真实数据上塌了，用 --demo 就能立刻看见。
package com.photomgm.desktop.ui.dev

import algorithm.AppZone
import com.photomgm.desktop.core.api.CoreStatus
import com.photomgm.desktop.core.api.PhotoMgmCore
import java.awt.Color
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object DemoFixture {

    /** 返回 true 表示演示数据已就绪（分析完成）。 */
    fun install(core: PhotoMgmCore, dataDir: Path): Boolean {
        val day = LocalDate.now(AppZone.ID)
        val root = dataDir.resolve("demo")
        val srcA = root.resolve("src-a")
        val srcB = root.resolve("src-b")
        val out = root.resolve("导出")
        Files.createDirectories(srcA)
        Files.createDirectories(srcB)
        Files.createDirectories(out)

        val stamp = day.format(DateTimeFormatter.BASIC_ISO_DATE)          // 20261101
        val exifDay = day.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"))

        // 每条事件配 1~2 张照片；GPS 沿桩号递增（同一路段，聚类才成立）。
        // K138+700 / K139+100 / K140+200 / K142+300 / K146+500 / 事故点 K145+000
        val shots = listOf(
            Shot("08:31:12", 30.2500, 120.5000, Color(90, 110, 130), srcA),
            Shot("08:32:05", 30.2502, 120.5006, Color(80, 120, 140), srcA),
            Shot("08:43:20", 30.2530, 120.5060, Color(120, 110, 95), srcA),
            Shot("08:56:40", 30.2620, 120.5180, Color(110, 105, 120), srcA),
            Shot("08:57:10", 30.2622, 120.5185, Color(100, 95, 110), srcA),
            Shot("09:11:30", 30.2790, 120.5410, Color(95, 115, 105), srcA),
            Shot("09:41:15", 30.3150, 120.5900, Color(115, 100, 90), srcA),
            Shot("09:42:00", 30.3153, 120.5906, Color(105, 95, 85), srcA),
            Shot("10:16:20", 30.3030, 120.5720, Color(130, 90, 85), srcB),
            Shot("10:17:05", 30.3032, 120.5725, Color(125, 85, 80), srcB),
            // 两张**没有 EXIF 时间**的照片：演示未匹配池。
            // ★ 光让 GPS 偏远不会进未匹配池——分类器有"终点兜底"，时间晚于末条记录就归末条。
            //   真正会留在池里的是"读不到拍摄时间"的照片（换机、被压缩过、导出时丢了 EXIF），
            //   这也是现场最常见的一类。
            Shot("08:35:00", 30.2500, 120.5000, Color(70, 70, 70), srcB, noExif = true),
            Shot("08:36:00", 30.2501, 120.5002, Color(65, 65, 65), srcB, noExif = true),
        )

        shots.forEachIndexed { index, shot ->
            val name = "IMG_${stamp}_${shot.time.replace(":", "")}.jpg"
            val target = shot.dir.resolve(name)
            if (!Files.exists(target)) {
                if (shot.noExif) {
                    ExifJpeg.writePlain(target, shot.color)
                } else {
                    ExifJpeg.write(
                        path = target,
                        exifDateTime = "$exifDay ${shot.time}",
                        lat = shot.lat,
                        lng = shot.lng,
                        color = shot.color,
                    )
                }
            }
            // 一张派生副本（水印相机的 _original）：用来演示三态去重的"合并组"
            if (index == 0) {
                val derived = shot.dir.resolve("IMG_${stamp}_${shot.time.replace(":", "")}_original.jpg")
                if (!Files.exists(derived)) Files.copy(target, derived)
            }
        }

        core.updateSettings {
            it.copy(
                patrolDate = day,
                sourceDirs = listOf(normalize(srcA), normalize(srcB)),
                outputDir = normalize(out),
                namingTemplate = 1,
                window = null,
            )
        }
        core.setLogText(LOG)

        core.analyze()
        val deadline = System.currentTimeMillis() + 40_000
        while (System.currentTimeMillis() < deadline) {
            val s = core.state.value
            if (s.status == CoreStatus.READY || s.status == CoreStatus.FAILED) return true
            Thread.sleep(50)
        }
        return false
    }

    private fun normalize(p: Path): String = p.toAbsolutePath().toString().replace('\\', '/')

    private data class Shot(
        val time: String,
        val lat: Double,
        val lng: Double,
        val color: Color,
        val dir: Path,
        val noExif: Boolean = false,
    )

    /** 与现场格式一致的巡查日志：只有时分，日期由"巡查日期"补全。 */
    private val LOG = """
08时15分 出发，巡查人员张某某、李某某从A收费管理中心出发，往北行方向巡查。
08时30分 到达上行K138+700M处，检查交安设施，发现护栏损坏，已拍照取证。
08时42分 巡查至上行K139+100M处，发现路面有抛洒物，已清理，拍照取证。
08时55分 巡查至下行K140+200M处桥梁，检查支座，发现伸缩缝轻微裂缝，已记录并拍照取证。
09时10分 巡查至上行K142+300M处，进行高边坡巡查，发现边坡有少量落石，已拍照取证。
09时25分 巡查至D服务区，检查服务区消防设施齐全，卫生状况良好。
09时40分 巡查至上行K146+500M处，发现路面有坑槽，已记录并拍照取证。
10时00分 接报K145+000M处发生两车追尾事故，立即前往，10时15分到达现场，事故涉及粤A11111和粤B22222，造成路产损坏，已拍照取证，交警到场处理。
    """.trimIndent()
}
