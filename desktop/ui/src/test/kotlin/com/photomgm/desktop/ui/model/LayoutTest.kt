// ui/test/LayoutTest —— 单页布局的纯函数
//
// 这些断言值就是设计口径：改布局先改这里，再改实现，不要反过来。
package com.photomgm.desktop.ui.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LayoutTest {


    @Test
    fun `卡片高度由栅格推算`() {
        // 14 + 30 + 8 + 80 = 146：卡片内边距 14、头部 30、头部与照片条间距 8、照片格 80
        assertEquals(146, cardHeight(80))
        assertEquals(138, cardHeight(72))
        // 无照片的占位行固定 40，所以空卡片一定比同宽度的有照片卡片矮
        assertEquals(106, emptyCardHeight())
        assertTrue(emptyCardHeight() < cardHeight(72))
    }

    @Test
    fun `卡片头部三列固定宽，最窄窗口下每列都放得下`() {
        // 头部：序号 26 + 时间 96 + 标题(weight 1) + 标签 + 张数 56 + 箭头 16
        assertEquals(26, CARD_INDEX_W)
        assertEquals(96, CARD_TIME_W)
        assertEquals(56, CARD_COUNT_W)
        assertEquals(30, CARD_HEADER_H)
        val usable = 640 - PAGE_PAD * 2 - CARD_PAD * 2
        val fixed = CARD_INDEX_W + CARD_TIME_W + CARD_COUNT_W + 16 + CARD_PAD
        assertTrue(fixed < usable * 2 / 3, "固定列 $fixed 应远小于可用宽 $usable，标题才有地方")
    }

    @Test
    fun `照片格按宽度分四档`() {
        assertEquals(72, tileSize(700))
        assertEquals(80, tileSize(886))
        assertEquals(88, tileSize(1100))
        assertEquals(96, tileSize(1400))
    }

    @Test
    fun `照片格随宽度单调不减`() {
        var last = 0
        var w = 640
        while (w <= 3840) {
            val t = tileSize(w)
            assertTrue(t >= last, "w=$w tile=$t 应不小于上一档 $last")
            last = t
            w += 7
        }
    }

    @Test
    fun `卡片内照片条永不超出卡片宽度`() {
        var w = 640
        while (w <= 3840) {
            val tile = tileSize(w)
            val n = maxTiles(w, tile)
            assertTrue(n >= 1, "w=$w 至少要放 1 格")
            assertTrue(n <= 10, "w=$w 最多 10 格")
            val usable = w - PAGE_PAD * 2 - CARD_PAD * 2
            assertTrue(stripWidth(tile, n) <= usable, "w=$w 排 $n 格需要 ${stripWidth(tile, n)}，可用只有 $usable")
            w += 7
        }
    }

    @Test
    fun `最小窗口 900 宽时一行放得下 9 格`() {
        // 内容宽 = 900 - 14（窗口边框）= 886 是内业机最窄的真实值
        val tile = tileSize(886)
        assertEquals(80, tile)
        assertEquals(9, maxTiles(886, tile))
    }

    @Test
    fun `弹窗永远落在窗口内`() {
        for (w in listOf(900, 984, 1152, 1229, 1382, 1440, 1920, 2560)) {
            for (h in listOf(540, 574, 612, 655, 734, 900, 1040)) {
                val dw = dialogWidth(w)
                val dh = dialogHeight(h)
                assertTrue(dw <= w, "w=$w 弹窗 $dw 越界")
                assertTrue(dw >= 640, "w=$w 弹窗 $dw 过窄")
                assertTrue(dh <= h, "h=$h 弹窗 $dh 越界")
                assertTrue(dh >= 420, "h=$h 弹窗 $dh 过矮")
            }
        }
    }

    @Test
    fun `内业机最小窗下弹窗为 830x455`() {
        // 900×540 窗口，客户区 886×503（DWM 边框各 7）
        assertEquals(830, dialogWidth(886))
        assertEquals(455, dialogHeight(503))
    }

    @Test
    fun `照片弹窗在最小窗下仍有侧栏与缩略条`() {
        val l = photosSheetLayout(886, 503)
        assertTrue(l.sideW > 0, "侧栏必须有，否则台账格设置没有落点")
        assertTrue(l.width - l.sideW >= 350, "大图区至少留 350")
        assertTrue(stripColumns(l) >= 3, "缩略条至少要放 3 张")
    }

    @Test
    fun `照片弹窗的信息区被压到最薄`() {
        // 侧栏 240（原 280）、缩略条 92/72（原 108/84）：这三条是"给照片让出空间"的口径。
        val small = photosSheetLayout(886, 503)
        assertEquals(240, small.sideW)
        assertEquals(72, small.stripH)
        assertEquals(52, small.stripTile)
        assertEquals(38, PHOTOS_HEADER_H)
        assertEquals(42, PHOTOS_ACTION_H)
        // 455 - 38 - 72 - 42 - 3 = 300：最小窗下大图区仍拿到 300（原来 ~229 可用）
        assertEquals(300, previewHeight(small))

        val large = photosSheetLayout(1366, 740)
        assertEquals(240, large.sideW)
        assertEquals(92, large.stripH)
        assertEquals(64, large.stripTile)
        // 692 - 38 - 92 - 42 - 3 = 517
        assertEquals(517, previewHeight(large))
        assertTrue(previewHeight(large) > previewHeight(small))
    }

    @Test
    fun `大图区永远不低于 240，且随窗口变高而变大`() {
        // 窗口每高 10，大图就高 10；唯一会"回落"的地方是缩略条换档（72 → 92），
        // 换档时大图让出 20 给缩略条、同时窗口给了 10，于是净回落 10。
        // 允许 20 的容差，超出就说明有人往这条链里加了固定的高度开销。
        var last = 0
        for (h in 540..1200 step 10) {
            val p = previewHeight(photosSheetLayout(886, h))
            assertTrue(p >= 240, "h=$h 大图区只有 $p")
            assertTrue(p >= last - 20, "h=$h 大图高 $p 比上一档 $last 回落超过 20（缩略条换档的回落在内）")
            last = p
        }
    }

    @Test
    fun `照片弹窗宽度增加时侧栏不侵占大图到不可用`() {
        for (w in listOf(900, 1200, 1440, 1920)) {
            val l = photosSheetLayout(w, 900)
            val preview = l.width - l.sideW
            assertTrue(preview >= 350, "w=$w 大图区只有 $preview")
        }
    }

    @Test
    fun `移动弹窗至少能显示两行事件`() {
        assertTrue(moveListRows(503) >= 2)
        assertTrue(moveListRows(900) > moveListRows(503))
    }

    @Test
    fun `页面留白与卡片内边距是常量而不是散落的魔法数字`() {
        assertEquals(16, PAGE_PAD)
        assertEquals(14, CARD_PAD)
        assertEquals(8, TILE_GAP)
    }
}
