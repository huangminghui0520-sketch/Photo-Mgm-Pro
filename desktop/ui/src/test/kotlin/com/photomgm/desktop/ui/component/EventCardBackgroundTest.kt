// ui/test/.../component/EventCardBackgroundTest.kt —— 卡片悬停底色的四条分支
//
// 悬停表达原本只有"描边加实一档"，这里再叠一层底色。规则必须是纯函数：
// 鼠标移出后底色必须精确回到静止值，不能靠动画残留一个"看起来差不多"的灰。
// warm 卡（未匹配池）不能借悬停变成普通灰卡，所以它的两支只改 warnSoft 的 alpha。
package com.photomgm.desktop.ui.component

import com.photomgm.desktop.ui.theme.PmColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

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

        // 四个分支的精确色值：非悬停的两支必须与改动前逐字节一致（0.55f / surface）
        assertEquals(PmColor.surface, eventCardBackground(isHovered = false, warm = false))
        assertEquals(PmColor.surface2, eventCardBackground(isHovered = true, warm = false))
        assertEquals(PmColor.warnSoft.copy(alpha = 0.55f), eventCardBackground(isHovered = false, warm = true))
        assertEquals(PmColor.warnSoft.copy(alpha = 0.85f), eventCardBackground(isHovered = true, warm = true))

        // 移出恢复：先算过悬停态，静止值仍逐字节不变（纯函数，无跨卡片共享状态）
        val idleBefore = eventCardBackground(isHovered = false, warm = false)
        eventCardBackground(isHovered = true, warm = false)
        eventCardBackground(isHovered = true, warm = true)
        assertEquals(idleBefore, eventCardBackground(isHovered = false, warm = false))
        assertEquals(PmColor.warnSoft.copy(alpha = 0.55f), eventCardBackground(isHovered = false, warm = true))
    }
}
