// desktop/core/test/sync/SyncStateTest.kt —— sync-state.json 读写、损坏与冲突
package com.photomgm.desktop.core.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime

class SyncStateTest {

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = LocalDateTime.of(y, mo, d, h, mi)

    private val start = at(2026, 11, 4, 23, 0)
    private val end = at(2026, 11, 5, 23, 59)

    private fun state(
        phoneDir: String = "D:/DCIM/Camera",
        startText: String = "2026-11-04T23:00:00+08:00",
        endText: String = "2026-11-05T23:59:00+08:00",
    ) = SyncState(
        range = SyncRangeStamp(startText, endText),
        phoneDir = phoneDir,
        files = listOf(SyncFileRecord("IMG_001.jpg", 1000L, "abc")),
        updatedAt = "2026-11-05T10:00:00Z",
    )

    // ── 序列化 ──────────────────────────────────────────────────────────

    @Test
    fun `F7 挂钟时间序列化时补业务时区偏移且含完整年份`() {
        val stamp = SyncState.stamp(start, end)
        assertEquals("2026-11-04T23:00:00+08:00", stamp.start)
        assertEquals("2026-11-05T23:59:00+08:00", stamp.end)
    }

    @Test
    fun `JSON 往返保持字段完整`() {
        val original = state()
        val parsed = SyncState.parse(original.toJson())
        assertEquals(original, parsed)
    }

    @Test
    @Suppress("UNUSED")
    fun `文件读写往返`(@TempDir dir: Path) {
        val original = state()
        SyncState.write(dir, original)
        assertTrue(Files.isRegularFile(dir.resolve(SyncState.FILE_NAME)))
        assertEquals(original, SyncState.read(dir))
    }

    @Test
    fun `文件不存在返回null`(@TempDir dir: Path) {
        assertNull(SyncState.read(dir))
    }

    // ── 损坏 / 不完整 ───────────────────────────────────────────────────

    @Test
    fun `损坏JSON返回null退化为全量重同步而不是崩溃`(@TempDir dir: Path) {
        Files.writeString(dir.resolve(SyncState.FILE_NAME), "{ this is not json", Charsets.UTF_8)
        assertNull(SyncState.read(dir))
    }

    @Test
    fun `缺range或phoneDir返回null`() {
        assertNull(SyncState.parse("""{"phoneDir":"x"}"""))
        assertNull(SyncState.parse("""{"range":{"start":"a","end":"b"}}"""))
        assertNull(SyncState.parse("""{"range":{"start":"a"}}"""))
    }

    @Test
    fun `files缺字段的条目被跳过其余保留`() {
        val text = """{"range":{"start":"2026-11-04T23:00:00+08:00","end":"2026-11-05T23:59:00+08:00"},""" +
            """"phoneDir":"p","files":[{"name":"ok.jpg","size":1,"sha256":"x"},{"name":"bad.jpg"}]}"""
        val parsed = SyncState.parse(text)
        assertEquals(1, parsed?.files?.size)
        assertEquals("ok.jpg", parsed?.files?.first()?.name)
    }

    // ── 冲突（R5 / 跨年同名） ──────────────────────────────────────────

    @Test
    fun `R5 同一区间换手机目录时报冲突不静默合并`() {
        val conflict = SyncStateGuard.conflict(state(), "E:/OtherDCIM", start, end)
        assertEquals(SyncConflict.PHONE_DIR, conflict)
        val prompt = SyncStateGuard.prompt(conflict!!, state(), "E:/OtherDCIM")
        assertTrue(prompt.contains("另一目录"), prompt)
        assertTrue(prompt.contains("D:/DCIM/Camera"), prompt)
    }

    @Test
    fun `同区间同目录无冲突`() {
        assertNull(SyncStateGuard.conflict(state(), "D:/DCIM/Camera", start, end))
    }

    @Test
    fun `文件夹名不含年份时记录年份不同报冲突`() {
        // 31_2200__01_0200：去年与今年同名
        val lastYear = state(startText = "2025-12-31T22:00:00+08:00", endText = "2026-01-01T02:00:00+08:00")
        val thisStart = at(2026, 12, 31, 22, 0)
        val thisEnd = at(2027, 1, 1, 2, 0)
        assertEquals(SyncConflict.YEAR, SyncStateGuard.conflict(lastYear, "D:/DCIM/Camera", thisStart, thisEnd))
        assertTrue(SyncStateGuard.prompt(SyncConflict.YEAR, lastYear, "D:/DCIM/Camera").contains("年份"))
    }

    @Test
    fun `用户确认覆盖后不再报冲突`() {
        assertNull(SyncStateGuard.conflict(state(), "E:/Other", start, end, overwrite = true))
    }

    @Test
    fun `没有旧状态不报冲突`() {
        assertNull(SyncStateGuard.conflict(null, "D:/DCIM/Camera", start, end))
    }

    @Test
    fun `年份解析不了时按无冲突保守处理`() {
        val broken = state(startText = "not-a-date", endText = "also-bad")
        assertNull(SyncStateGuard.yearOf(broken.range.start))
        assertNull(SyncStateGuard.conflict(broken, "D:/DCIM/Camera", start, end))
    }

    @Test
    fun `取消的状态complete为false但保留文件记录`() {
        val s = state().copy(complete = false)
        val parsed = SyncState.parse(s.toJson())
        assertFalse(parsed?.complete ?: true)
        assertEquals(1, parsed?.files?.size)
    }
}
