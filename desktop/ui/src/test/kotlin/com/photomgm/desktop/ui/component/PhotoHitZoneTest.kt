// ui/test/.../component/PhotoHitZoneTest.kt —— 缩略图"红勾区"与"格子区"的边界
//
// 现场症状：点红勾或连点几次，勾选自己乱跳；点图也会莫名其妙变成"加选"。
// 根因是区域判定靠"子节点有没有消费事件"来猜（按下阶段并不保证消费），
// 现在改成几何判定：右上角 CHECK_HIT_DP 见方的那块归红勾，其余归格子。
// 这个函数就是那条边界的唯一真相，必须被用例钉死 —— 否则改动 1 dp 就会悄悄重叠。
package com.photomgm.desktop.ui.component

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhotoHitZoneTest {

    private val tile = IntSize(64, 64)   // 64 dp 缩略图 @100% 缩放
    private val check = 24               // CHECK_HIT_DP

    @Test
    fun `右上角方块内部属于红勾区`() {
        assertTrue(inTopEndZone(Offset(60f, 4f), tile, check))
        assertTrue(inTopEndZone(Offset(44f, 20f), tile, check))
    }

    @Test
    fun `红勾区的边界点按半开区间算`() {
        // 左边界（x = width-check）与上边界（y = 0）含在区内；
        // 下边界（y = check）不含 —— 否则区域变成 24×25，和格子重叠一行像素。
        assertTrue(inTopEndZone(Offset(40f, 0f), tile, check))
        assertTrue(inTopEndZone(Offset(40f, 23f), tile, check))
        assertFalse(inTopEndZone(Offset(40f, 24f), tile, check))
        assertTrue(inTopEndZone(Offset(64f - 1f, 0f), tile, check))
    }

    @Test
    fun `左边与下边都不属于红勾区`() {
        assertFalse(inTopEndZone(Offset(10f, 10f), tile, check), "左上角是格子的点击区")
        assertFalse(inTopEndZone(Offset(50f, 40f), tile, check), "红勾区只有上面一小块，不能吃掉整个右半格")
        assertFalse(inTopEndZone(Offset(39f, 4f), tile, check), "差 1 px 就越界")
    }

    @Test
    fun `格子中心属于格子区`() {
        assertFalse(inTopEndZone(Offset(32f, 32f), tile, check))
    }

    @Test
    fun `尺寸未知时不吞任何点击`() {
        assertFalse(inTopEndZone(Offset(1f, 1f), IntSize.Zero, check))
    }

    @Test
    fun `两块区域严格互补且不重叠`() {
        // 扫描整格：每个像素只归一边（这就是"一次点击只做一件事"的数学形式）
        var checkHits = 0
        var tileHits = 0
        for (x in 0 until tile.width) {
            for (y in 0 until tile.height) {
                if (inTopEndZone(Offset(x.toFloat(), y.toFloat()), tile, check)) checkHits++ else tileHits++
            }
        }
        assertTrue(checkHits > 0 && tileHits > 0)
        assertTrue(checkHits == check * check, "红勾区正好是 $check×$check 个像素，一块不多一块不少")
        assertTrue(tileHits > checkHits, "格子本体仍应是主要点击面积")
    }
}
