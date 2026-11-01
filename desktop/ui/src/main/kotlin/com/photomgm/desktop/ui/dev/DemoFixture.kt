// desktop/ui/dev/DemoFixture.kt —— U1 的"真实 AppState 渲染数据源"（仅 --demo 时启用）
//
// 为什么需要它：U1 的验收是「骨架能吃真实 AppState」，但开发机上没有内业照片库。
// 如果只跑空状态，日志只能证明 `status=IDLE photos=0`，"能渲染"这件事等于没被验证。
//
// 做法：造一个**临时目录 + 真实 JPEG + 真实巡查日志文本**，走核心的 analyze() 流水线，
// 让 AppState 由真实的分类逻辑产出。不是 mock，不是硬编码的快照。
//
// ★ 两个刻意的设计：
//   1. 数据目录独立（%APPDATA%\PhotoMgm-demo）：绝不污染用户的 settings.json。
//   2. 生成的 JPEG **不带 EXIF GPS**：这恰好是内业现场最常见的情况（水印相机抹掉 GPS）。
//      所以事件来自日志、照片全进未匹配池 —— 正好把 U3 最难的两块（事件卡空照片、
//      未匹配池满）压出来。带 GPS 的照片要等 U3 用真实数据走查。
package com.photomgm.desktop.ui.dev

import com.photomgm.desktop.core.api.CoreStatus
import com.photomgm.desktop.core.api.PhotoMgmCore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.Color
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO

object DemoFixture {

    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

    /** 8 张照片的拍摄时刻（时, 分），与日志时间一一对应。 */
    private val SHOTS = listOf(8 to 32, 8 to 41, 9 to 12, 9 to 15, 9 to 40, 10 to 2, 10 to 20, 10 to 55)

    private val LOG_TEXT = """
        上午8时30分 与早班巡查人员张某某、李某某在A收费管理中心进行交接班，检查巡查装备齐全完好，车况正常，开启粤E00001车载视频监控，视频清晰，已报监控中心。记录人：王某某。
        8时30分 到达上行K138+700M处，检查交安设施，发现护栏损坏，已拍照取证。
        8时45分 巡查至B收费站，检查收费站设施，收费车道设备运行正常，未发现异常。
        9时10分 在B收费站往北行方向，发现路面有抛洒物，已清理，拍照取证。
        9时30分 巡查至E互通，检查匝道情况正常。
        9时40分 巡查至下行K140+200M处，发现桥梁伸缩缝有轻微裂缝，未影响通行，记录在案。
        10时00分 接报K145+000M处发生两车追尾事故，立即前往，10时15分到达现场，事故涉及粤A11111和粤B22222，造成路产损坏，已拍照取证，交警到场处理。
    """.trimIndent()

    /** 造数据并跑完整分析流水线；返回临时源目录（调用方可删除）。 */
    fun prepare(core: PhotoMgmCore): Path {
        val root = Files.createTempDirectory("photomgm-demo-")
        val day = core.state.value.settings.patrolDate
        val stamp = day.format(STAMP)

        SHOTS.forEachIndexed { i, (h, m) ->
            val name = "IMG_${stamp}_%02d%02d%02d.jpg".format(h, m, i * 7 % 60)
            writeJpeg(root.resolve(name), i)
        }

        core.updateSettings {
            it.copy(sourceDirs = listOf(root.toString().replace('\\', '/')))
        }
        core.setLogText(LOG_TEXT)
        core.analyze()

        // 等流水线收敛，让窗口一打开就是 READY（而不是闪一下 ANALYZING）。
        val done = runBlocking {
            withTimeoutOrNull(30_000) {
                core.state.first { it.status == CoreStatus.READY || it.status == CoreStatus.FAILED }
            }
        }
        requireNotNull(done) { "演示数据分析超时" }
        // 分析前那几次 updateSettings 会留一条「设置已更新，请重新分析」的提示；
        // 分析已经跑完，这条提示就是过期信息，不清掉会让状态栏一直挂着黄字。
        core.clearMessage()
        return root
    }

    /** 造一张 640×480 的渐变 JPEG —— 内容无所谓，要的是"能被 ImageIO 解码的真实文件"。 */
    private fun writeJpeg(file: Path, index: Int) {
        val img = BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
        val hue = (index * 37 % 360) / 360f
        val top = Color.getHSBColor(hue, 0.18f, 0.86f)
        val bottom = Color.getHSBColor(hue, 0.34f, 0.52f)
        g.paint = GradientPaint(0f, 0f, top, 0f, 480f, bottom)
        g.fillRect(0, 0, 640, 480)
        g.color = Color(255, 255, 255, 200)
        g.font = g.font.deriveFont(72f)
        // 只画数字：不依赖中文字体，避免在缺字体的机器上画出方块。
        g.drawString("%02d".format(index + 1), 40, 110)
        g.dispose()
        Files.newOutputStream(file).use { ImageIO.write(img, "jpg", it) }
    }
}
