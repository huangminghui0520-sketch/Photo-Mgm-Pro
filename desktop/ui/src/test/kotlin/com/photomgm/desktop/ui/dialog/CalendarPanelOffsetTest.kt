// ui/test/dialog/CalendarPanelOffsetTest.kt —— 浮层定位纯函数
//
// ★ 为什么给它单独一个测试文件：定位那三条规则（水平居中 / 贴下方 6 dp / 放不下翻上方 /
//   始终夹进容器）是"面板跑到窗口外、跑到按钮左边、盖住自己那颗按钮"的唯一原因，
//   而这些分支原来是内联在 Composable 里的局部 val，只能靠肉眼量。
//   抽成纯函数后每个分支都能用整数算清楚；主页（传窗口尺寸）与手机导入弹窗（传卡片尺寸）
//   共用同一份实现，所以这里测的就是两处的行为。
package com.photomgm.desktop.ui.dialog

import androidx.compose.ui.unit.IntOffset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CalendarPanelOffsetTest {

    // 与真实控件对齐：日期按钮 196×30、日历面板 262×264（CALENDAR_PANEL_W / ESTIMATED_H）。
    private val anchorW = 196
    private val anchorH = 30
    private val panelW = 262
    private val panelH = 264

    private fun offset(
        anchorX: Int = 300,
        anchorY: Int = 200,
        windowW: Int = 1200,
        windowH: Int = 800,
        panel: Int = panelH,
    ): IntOffset = calendarPanelOffset(
        anchorX = anchorX,
        anchorY = anchorY,
        anchorW = anchorW,
        anchorH = anchorH,
        windowW = windowW,
        windowH = windowH,
        panelW = panelW,
        panelH = panel,
    )

    @Test
    fun `U11 浮层贴按钮正下方且水平居中 并在边缘与翻转时仍留在容器内`() {
        // ① 常规：水平居中于按钮（300 + (196−262)/2 = 267），纵向贴在按钮下方 6 dp（200+30+6）
        val normal = offset()
        assertEquals(267, normal.x)
        assertEquals(236, normal.y)

        // ② 左边夹紧：居中会算出 −33，必须停在容器左内边（8），不能出界
        assertEquals(8, offset(anchorX = 0).x)
        // ③ 右边夹紧：居中会算出 1067，容器右内边是 1200−262−8 = 930
        assertEquals(930, offset(anchorX = 1100).x)

        // ④ 下方放不下就翻到按钮上方：窗口 560 高时 336+264 超出下沿，
        //    上方 300−6−264 = 30 够用 → 取 30，且面板整体仍在容器内
        val flipped = offset(anchorY = 300, windowH = 560)
        assertEquals(30, flipped.y)
        assertTrue(flipped.y + panelH <= 560 - 8, "翻转后不得压到容器下沿：$flipped")

        // ⑤ 上下都放不下：兜底贴容器下沿（300−264−8 = 28），而不是保持下方（236）溢出
        val cramped = offset(anchorY = 200, windowH = 300)
        assertEquals(28, cramped.y)
        assertTrue(cramped.y + panelH <= 300 - 8, "兜底后必须留在容器内：$cramped")

        // ⑥ 容器比面板还窄（不该发生，但函数不能返回负数或甩出左边）：夹到左内边
        assertEquals(8, offset(anchorX = 0, windowW = 200).x)
    }
}
