// desktop/core/test/sync/SyncDeciderTest.kt —— 续传判据（R2~R4 / R6 / R7）
package com.photomgm.desktop.core.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SyncDeciderTest {

    private fun decide(
        name: String = "IMG_001.jpg",
        incomingSize: Long = 1000L,
        existingSize: Long? = null,
        incomingHash: String? = "aaa",
        existingHash: String? = "aaa",
        occupied: Set<String> = emptySet(),
    ): Pair<SyncDecision, Int> {
        var hashCalls = 0
        val decision = SyncDecider.decide(
            name = name,
            incomingSize = incomingSize,
            existingSize = existingSize,
            incomingHash = { hashCalls++; incomingHash },
            existingHash = { hashCalls++; existingHash },
            occupied = occupied,
        )
        return decision to hashCalls
    }

    // ── 文件不存在 → COPY ────────────────────────────────────────────────

    @Test
    fun `目标不存在则正常写入且不计算哈希`() {
        val (d, calls) = decide(existingSize = null)
        assertEquals(SyncAction.COPY, d.action)
        assertEquals("IMG_001.jpg", d.targetName)
        assertEquals(0, calls, "文件不存在时不该算哈希")
    }

    // ── 大小不同 → REWRITE，且不算哈希（性能规则） ──────────────────────

    @Test
    fun `R4 大小不同判为半截文件删除重写且不算哈希`() {
        val (d, calls) = decide(incomingSize = 1000L, existingSize = 400L)
        assertEquals(SyncAction.REWRITE, d.action)
        assertEquals("IMG_001.jpg", d.targetName)
        assertEquals(0, calls, "先比大小，不同就不该算哈希")
    }

    // ── 大小相同 + 哈希相同 → SKIP（R2 / R6） ───────────────────────────

    @Test
    fun `R2 大小与哈希都相同则跳过不重写`() {
        val (d, calls) = decide(incomingSize = 1000L, existingSize = 1000L, incomingHash = "aaa", existingHash = "aaa")
        assertEquals(SyncAction.SKIP, d.action)
        assertEquals("IMG_001.jpg", d.targetName)
        assertEquals(2, calls, "大小相同必须真的算了两个哈希")
    }

    @Test
    fun `R6 同步完成后再同步全部跳过不产生副本`() {
        repeat(3) {
            val (d, _) = decide(existingSize = 1000L, occupied = setOf("IMG_001.jpg"))
            assertEquals(SyncAction.SKIP, d.action)
        }
    }

    // ── 大小相同 + 哈希不同 → RENAME（R7） ──────────────────────────────

    @Test
    fun `R7 同名不同内容写为name_1不覆盖原文件`() {
        val (d, _) = decide(
            incomingSize = 1000L, existingSize = 1000L,
            incomingHash = "new", existingHash = "old",
            occupied = setOf("IMG_001.jpg"),
        )
        assertEquals(SyncAction.RENAME, d.action)
        assertEquals("IMG_001_1.jpg", d.targetName)
        assertFalse(d.targetName == "IMG_001.jpg", "绝不覆盖已存在的完整照片")
    }

    @Test
    fun `RENAME 后缀依次递增直到不冲突`() {
        val (d, _) = decide(
            incomingSize = 1000L, existingSize = 1000L,
            incomingHash = "new", existingHash = "old",
            occupied = setOf("IMG_001.jpg", "IMG_001_1.jpg", "IMG_001_2.jpg"),
        )
        assertEquals(SyncAction.RENAME, d.action)
        assertEquals("IMG_001_3.jpg", d.targetName)
    }

    @Test
    fun `哈希读不出时保守另存不覆盖`() {
        val (d, _) = decide(incomingSize = 1000L, existingSize = 1000L, incomingHash = null)
        assertEquals(SyncAction.RENAME, d.action)
        assertEquals("IMG_001_1.jpg", d.targetName)
    }

    // ── 命名边界 ────────────────────────────────────────────────────────

    @Test
    fun `无扩展名与点文件也能递增且保留后缀形态`() {
        assertEquals("IMG_001_1", SyncDecider.nextAvailableName("IMG_001", setOf("IMG_001")))
        assertEquals(".nomedia_1", SyncDecider.nextAvailableName(".nomedia", setOf(".nomedia")))
    }

    @Test
    fun `多段扩展名只按最后一个点拆分`() {
        assertEquals("a.b_1.jpg", SyncDecider.nextAvailableName("a.b.jpg", setOf("a.b.jpg")))
    }

    @Test
    fun `R3 手动删掉一张后该文件被识别为不存在并补回`() {
        val (d, _) = decide(existingSize = null, occupied = emptySet())
        assertEquals(SyncAction.COPY, d.action)
        assertTrue(d.targetName == "IMG_001.jpg")
    }
}
