// ui/test/.../component/EventCardBackgroundTest.kt —— 卡片悬停底色的四条分支
//
// 悬停表达原本只有"描边加实一档"，这里再叠一层底色。规则必须是纯函数：
// 鼠标移出后底色必须精确回到静止值，不能靠动画残留一个"看起来差不多"的灰。
// warm 卡（未匹配池）不能借悬停变成普通灰卡，所以它的两支只改 warnSoft 的 alpha。
package com.photomgm.desktop.ui.component

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.photomgm.desktop.ui.theme.PmColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class EventCardBackgroundTest {

    @Test
    fun `U14 普通卡与 warm 卡悬停都换底色且移出恢复`() {
        // 判据：两种卡各自 静止态 ≠ 悬停态
        assertNotEquals(
            eventCardBackground(isHovered = false, warm = false),
            eventCardBackground(isHovered = true, warm = false),
            "普通卡：悬停底色必须与静止态不同",
        )
        assertNotEquals(
            eventCardBackground(isHovered = false, warm = true),
            eventCardBackground(isHovered = true, warm = true),
            "warm 卡：悬停底色必须与静止态不同",
        )

        // 四个分支的精确色值：非悬停的两支必须与改动前逐字节一致（0.45f / surface）
        assertEquals(PmColor.surface, eventCardBackground(isHovered = false, warm = false))
        assertEquals(PmColor.stage, eventCardBackground(isHovered = true, warm = false))
        assertEquals(PmColor.warnSoft.copy(alpha = 0.45f), eventCardBackground(isHovered = false, warm = true))
        assertEquals(PmColor.warnSoft.copy(alpha = 1.00f), eventCardBackground(isHovered = true, warm = true))

        // 移出恢复：先算过悬停态，静止值仍逐字节不变（纯函数，无跨卡片共享状态）
        val idleBefore = eventCardBackground(isHovered = false, warm = false)
        eventCardBackground(isHovered = true, warm = false)
        eventCardBackground(isHovered = true, warm = true)
        assertEquals(idleBefore, eventCardBackground(isHovered = false, warm = false))
        assertEquals(PmColor.warnSoft.copy(alpha = 0.45f), eventCardBackground(isHovered = false, warm = true))
    }

    /** warm 两支只差 alpha，必须先合成到白底才拿到眼睛看到的显示色。 */
    private fun compositeOnWhite(color: Color): Color {
        val a = color.alpha
        return Color(
            color.red * a + 1f * (1 - a),
            color.green * a + 1f * (1 - a),
            color.blue * a + 1f * (1 - a),
            1f,
        )
    }

    /** 两个显示色在 0..255 通道上的最大差。 */
    private fun maxRgbDiff(a: Color, b: Color): Int {
        val x = a.toArgb()
        val y = b.toArgb()
        return maxOf(
            kotlin.math.abs(((x shr 16) and 0xFF) - ((y shr 16) and 0xFF)),
            kotlin.math.abs(((x shr 8) and 0xFF) - ((y shr 8) and 0xFF)),
            kotlin.math.abs((x and 0xFF) - (y and 0xFF)),
        )
    }

    @Test
    fun `U3 普通卡悬停底色与静止底色的 RGB 差至少 10`() {
        val idle = eventCardBackground(isHovered = false, warm = false)
        val hover = eventCardBackground(isHovered = true, warm = false)
        assertEquals(PmColor.surface, idle)
        assertEquals(PmColor.stage, hover)
        val diff = maxRgbDiff(idle, hover)
        assertTrue(diff >= 10, "普通卡 stage vs surface 的 Δmax 应 >= 10，实测 $diff")
    }

    @Test
    fun `U4 warm 卡悬停显示色与静止显示色的 RGB 差至少 10`() {
        val idle = compositeOnWhite(eventCardBackground(isHovered = false, warm = true))
        val hover = compositeOnWhite(eventCardBackground(isHovered = true, warm = true))
        val diff = maxRgbDiff(idle, hover)
        assertTrue(diff >= 10, "warm 卡 alpha 0.45 vs 1.00 合成白底后 Δmax 应 >= 10，实测 $diff")
    }
}
