// ui/test/CardEdgeTest —— 卡片描边的优先级
//
// 这一条规则的价值不在色值，在"谁抢到那条 1 dp 的边"。用户明确要求
// 「事件卡片在包含照片时显示红色边框」，于是必须回答：有照片又待确认时给什么？
// 答案是红边（有图是更基础的事实），待确认用序号色 + 标签表达。这条断言把答案钉住。
package com.photomgm.desktop.ui.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class CardEdgeTest {

    @Test
    fun `优先级：未匹配 高于 有照片 高于 待确认 高于 静止`() {
        assertEquals(CardEdge.UNMATCHED, cardEdge(hasPhotos = true, review = true, unmatched = true))
        assertEquals(CardEdge.UNMATCHED, cardEdge(hasPhotos = false, review = false, unmatched = true))
        assertEquals(CardEdge.PHOTOS, cardEdge(hasPhotos = true, review = true, unmatched = false))
        assertEquals(CardEdge.PHOTOS, cardEdge(hasPhotos = true, review = false, unmatched = false))
        assertEquals(CardEdge.REVIEW, cardEdge(hasPhotos = false, review = true, unmatched = false))
        assertEquals(CardEdge.REST, cardEdge(hasPhotos = false, review = false, unmatched = false))
    }

    @Test
    fun `无照片的待确认卡片仍然拿强调边`() {
        // "无照片"本身就要人处理，所以它必须还能被强调边挑出来 —— 这条边不能因为红边而消失。
        assertEquals(CardEdge.REVIEW, cardEdge(hasPhotos = false, review = true, unmatched = false))
        // 反过来：有照片就不再用强调边，否则一屏里两种边同时在说"有事"。
        assertNotEquals(CardEdge.REVIEW, cardEdge(hasPhotos = true, review = true, unmatched = false))
    }
}
