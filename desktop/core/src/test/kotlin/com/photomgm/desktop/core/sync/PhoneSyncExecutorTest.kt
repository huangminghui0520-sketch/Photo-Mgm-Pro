// desktop/core/test/sync/PhoneSyncExecutorTest.kt —— 全链路（@TempDir 假手机目录，不依赖真机）
//
// 覆盖：V3 / R1~R7 / F3（EXIF 优先、mtime 兜底）/ A+B 降级 / 不混用 / 冲突 / 取消。
// 连接方式状态机的纯判定也放在本文件的 PhoneConnectionTest 里（方案 D 未列独立文件，
// 多开一个文件会超出改动清单）。
package com.photomgm.desktop.core.sync

import algorithm.AppZone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.LocalDateTime

private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): LocalDateTime =
    LocalDateTime.of(y, mo, d, h, mi)

private fun epoch(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
    at(y, mo, d, h, mi).atZone(AppZone.ID).toInstant().toEpochMilli()

private fun sha(p: Path): String? = runCatching {
    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}.getOrNull()

// 假手机目录：list 读本地文件，pull 用 Files.copy，全部次数可查（不混用判据）。
private class FakeSource(
    override val id: String,
    override val kind: ConnectionKind,
    private val dir: Path,
    private val failPull: Set<String> = emptySet(),
    private val nullMtime: Boolean = false,
) : PhoneSource {
    val pulled = ArrayList<String>()

    override fun list(): List<RemotePhoto> {
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.list(dir).use { s ->
            s.filter { Files.isRegularFile(it) }
                .map { p ->
                    RemotePhoto(
                        name = p.fileName.toString(),
                        size = Files.size(p),
                        // nullMtime 用来构造「EXIF 与 mtime 都读不到」的硬判据。
                        modifiedMs = if (nullMtime) null else Files.getLastModifiedTime(p).toMillis(),
                    )
                }
                .toList()
                .sortedBy { it.name }
        }
    }

    override fun pullTo(photo: RemotePhoto, staging: Path): Boolean {
        pulled += photo.name
        if (photo.name in failPull) return false
        return runCatching {
            Files.copy(dir.resolve(photo.name), staging, StandardCopyOption.REPLACE_EXISTING)
            true
        }.getOrDefault(false)
    }
}

private class Harness(val temp: Path) {
    val phone: Path = Files.createDirectories(temp.resolve("phone"))
    val sourceDir: Path = Files.createDirectories(temp.resolve("source"))
    val staging: Path get() = temp.resolve("staging")

    var adbAvailable = true
    var usbVisible = true
    var factoryCalls = 0
    var lastKind: ConnectionKind? = null
    var sources = ArrayList<FakeSource>()
    var exif: (Path) -> Long? = { null }
    var clockText = "2026-11-05T10:00:00Z"
    var nullMtime = false
    var failPull: Set<String> = emptySet()

    fun photo(name: String, content: String = "content-$name") {
        Files.writeString(phone.resolve(name), content, Charsets.UTF_8)
    }

    // F3：EXIF 优先，缺失才用手机侧 mtime。
    private fun timeOf(path: Path, remote: RemotePhoto): Long? = exif(path) ?: remote.modifiedMs

    private fun factory(kind: ConnectionKind, phoneDir: String): PhoneSource? {
        factoryCalls++
        lastKind = kind
        if (kind == ConnectionKind.NONE) return null
        val s = FakeSource("$kind:$phoneDir", kind, phone, failPull, nullMtime)
        sources += s
        return s
    }

    fun executor() = PhoneSyncExecutor(
        hasher = ::sha,
        timeOf = ::timeOf,
        sourceFactory = ::factory,
        probe = { ConnectionProbe(adbAvailable, usbVisible) },
        stagingDir = { staging },
        nowText = { clockText },
        normalize = { it },
    )

    fun job(
        start: LocalDateTime = at(2026, 11, 4, 23, 0),
        end: LocalDateTime = at(2026, 11, 5, 23, 59),
        phoneDir: String = "phone",
        mode: ConnectionMode = ConnectionMode.AUTO,
        confirmConflict: Boolean = false,
    ) = PhoneSyncJob(sourceDir.toString(), start, end, phoneDir, mode, confirmConflict)

    fun run(
        start: LocalDateTime = at(2026, 11, 4, 23, 0),
        end: LocalDateTime = at(2026, 11, 5, 23, 59),
        mode: ConnectionMode = ConnectionMode.AUTO,
        phoneDir: String = "phone",
        confirmConflict: Boolean = false,
        cancelAfter: Int = Int.MAX_VALUE,
    ): PhoneSyncOutcome {
        var done = 0
        return executor().run(
            job(start, end, phoneDir, mode, confirmConflict),
            isCancelled = { done >= cancelAfter },
        ) { done++ }
    }
}

class PhoneSyncExecutorTest {

    // ── V3 区间 → 命名 → 落盘 ──────────────────────────────────────────

    @Test
    fun `V3 跨日区间落入04_2300__05_2359且包含1104的2355照片`(@TempDir temp: Path) {
        val h = Harness(temp)
        h.photo("IMG_001.jpg")
        h.exif = { epoch(2026, 11, 4, 23, 55) }
        val out = h.run()
        assertEquals("04_2300__05_2359", out.folderName)
        assertNotNull(out.targetDir)
        assertTrue(Files.isRegularFile(out.targetDir!!.resolve("IMG_001.jpg")))
        assertEquals(1, out.written)
        assertTrue(Files.isRegularFile(out.targetDir!!.resolve(SyncState.FILE_NAME)))
    }

    @Test
    fun `同一区间重复同步落到同一文件夹`(@TempDir temp: Path) {
        val h = Harness(temp)
        h.photo("IMG_001.jpg")
        h.exif = { epoch(2026, 11, 5, 10, 0) }
        val a = h.run()
        val b = h.run()
        assertEquals(a.targetDir, b.targetDir)
    }

    @Test
    fun `跨年区间命名为31_2200__01_0200且不报错`(@TempDir temp: Path) {
        val h = Harness(temp)
        h.photo("IMG_001.jpg")
        h.exif = { epoch(2026, 12, 31, 23, 0) }
        val out = h.run(
            start = at(2026, 12, 31, 22, 0),
            end = at(2027, 1, 1, 2, 0),
        )
        assertEquals("31_2200__01_0200", out.folderName)
        assertEquals(1, out.written)
    }

    // ── F3 筛选：EXIF 优先 / mtime 兜底 ────────────────────────────────

    @Test
    fun `F3 EXIF优先于mtime 区间外mtime仍导入`(@TempDir temp: Path) {
        val h = Harness(temp)
        h.photo("IMG_001.jpg")
        Files.setLastModifiedTime(h.phone.resolve("IMG_001.jpg"),
            java.nio.file.attribute.FileTime.fromMillis(epoch(2020, 1, 1, 0, 0)))
        h.exif = { epoch(2026, 11, 5, 12, 0) }
        val out = h.run()
        assertEquals(1, out.written, "EXIF 在区间内就该导入，不能被 mtime 覆盖")
        assertEquals(0, out.filtered)
    }

    @Test
    fun `F3 EXIF缺失时mtime兜底`(@TempDir temp: Path) {
        val h = Harness(temp)
        h.photo("IMG_001.jpg")
        Files.setLastModifiedTime(h.phone.resolve("IMG_001.jpg"),
            java.nio.file.attribute.FileTime.fromMillis(epoch(2026, 11, 5, 12, 0)))
        h.exif = { null }
        val out = h.run()
        assertEquals(1, out.written)
    }

    @Test
    fun `F3 时间都读不到则跳过且筛选发生在暂存区拉取之后`(@TempDir temp: Path) {
        val h = Harness(temp)
        h.photo("IMG_001.jpg")
        h.exif = { null }
        h.nullMtime = true
        val out = h.run()
        assertEquals(0, out.written)
        assertEquals(1, out.filtered)
        assertEquals(0, out.failed)
        assertFalse(Files.isRegularFile(out.targetDir!!.resolve("IMG_001.jpg")))
        assertTrue(h.sources.single().pulled.contains("IMG_001.jpg"), "必须先拉到暂存区再判定")
        assertTrue(out.logs.any { it.contains("无拍摄时间") })
    }

    @Test
    fun `F3 区间外照片同样先拉暂存区再被过滤`(@TempDir temp: Path) {
        val h = Harness(temp)
        h.photo("IMG_001.jpg")
        h.exif = { epoch(2026, 11, 6, 12, 0) }
        val out = h.run()
        assertEquals(0, out.written)
        assertEquals(1, out.filtered)
        assertTrue(h.sources.single().pulled.contains("IMG_001.jpg"), "先拉暂存区再判定")
    }

    // ── pull 前文件名粗筛（零误杀：解析不出时间一律放行）──────────────────

    @Test
    fun `T1 文件名时间落在区间内则拉取并导入`(@TempDir temp: Path) {
        val h = Harness(temp)
        h.photo("IMG_20261104_235500.jpg")
        h.exif = { epoch(2026, 11, 4, 23, 55) }
        val out = h.run(start = at(2026, 11, 4, 23, 0), end = at(2026, 11, 4, 23, 59))
        assertEquals(1, out.written)
        assertEquals(0, out.filtered)
        assertTrue(h.sources.single().pulled.contains("IMG_20261104_235500.jpg"))
    }

    @Test
    fun `T2 文件名时间在区间外则完全不拉取`(@TempDir temp: Path) {
        val h = Harness(temp)
        h.photo("IMG_20261104_220000.jpg")
        h.exif = { epoch(2026, 11, 4, 22, 0) }
        val out = h.run(start = at(2026, 11, 4, 23, 0), end = at(2026, 11, 4, 23, 59))
        assertEquals(0, out.written)
        assertEquals(1, out.filtered)
        assertEquals(0, out.failed)
        assertTrue(h.sources.single().pulled.isEmpty(), "粗筛不通过就不该发生任何 pull")
    }

    @Test
    fun `T3 文件名无时间则放行交给EXIF终判`(@TempDir temp: Path) {
        val h = Harness(temp)
        h.photo("photo.jpg")
        // 夹具 mtime 是「当下」（2026-11-07，区间外）；无文件名时间时必须放行，不能被 mtime 拦下。
        h.exif = { epoch(2026, 11, 4, 23, 10) }
        val out = h.run(start = at(2026, 11, 4, 23, 0), end = at(2026, 11, 4, 23, 59))
        assertEquals(1, out.written)
        assertEquals(0, out.filtered)
        assertTrue(h.sources.single().pulled.contains("photo.jpg"))
    }

    @Test
    fun `T4 文件名的派生后缀不影响解析`(@TempDir temp: Path) {
        val h = Harness(temp)
        h.photo("IMG_20261104_235500_original.jpg")
        h.exif = { epoch(2026, 11, 4, 23, 55) }
        val out = h.run(start = at(2026, 11, 4, 23, 0), end = at(2026, 11, 4, 23, 59))
        assertEquals(1, out.written)
        assertTrue(h.sources.single().pulled.contains("IMG_20261104_235500_original.jpg"))
    }

    @Test
    fun `V5 开始不早于结束被拒绝且不创建子文件夹`(@TempDir temp: Path) {
        val h = Harness(temp)
        h.photo("IMG_001.jpg")
        val out = h.run(start = at(2026, 11, 5, 12, 0), end = at(2026, 11, 5, 10, 0))
        assertEquals("开始时间必须早于结束时间", out.error)
        assertEquals(0, out.written)
        assertFalse(Files.isDirectory(h.sourceDir.resolve("05_1200__05_1000")))
    }

    // ── R1 取消 ────────────────────────────────────────────────────────

    @Test
    fun `R1 中途取消保留已写入文件与状态且可稍后继续`(@TempDir temp: Path) {
        val h = Harness(temp)
        (1..5).forEach { h.photo("IMG_%03d.jpg".format(it)) }
        h.exif = { epoch(2026, 11, 5, 12, 0) }
        val out = h.run(cancelAfter = 2)
        assertTrue(out.cancelled)
        assertEquals(2, out.written)
        val state = SyncState.read(out.targetDir!!)
        assertNotNull(state)
        assertFalse(state!!.complete)
        assertEquals(2, state.files.size)
        assertEquals(2L, Files.list(out.targetDir!!).use { s -> s.count() } - 1L, "只写了 2 张照片 + 状态文件")
    }

    // ── R2 / R6 续传 ───────────────────────────────────────────────────

    @Test
    fun `R2 取消后同区间同目录再同步跳过已完成的无副本`(@TempDir temp: Path) {
        val h = Harness(temp)
        (1..5).forEach { h.photo("IMG_%03d.jpg".format(it)) }
        h.exif = { epoch(2026, 11, 5, 12, 0) }
        h.run(cancelAfter = 2)
        val again = h.run()
        assertEquals(3, again.written)
        assertEquals(2, again.skipped)
        assertEquals(5L, Files.list(again.targetDir!!).use { s ->
            s.map { it.fileName.toString() }.filter { it.endsWith(".jpg") }.count()
        })
        assertFalse(Files.list(again.targetDir!!).use { s ->
            s.anyMatch { it.fileName.toString().contains("_1.jpg") }
        }, "不该出现 _1 副本")
    }

    @Test
    fun `R6 同步完成后再同步全部跳过显示已是最新`(@TempDir temp: Path) {
        val h = Harness(temp)
        h.photo("IMG_001.jpg")
        h.exif = { epoch(2026, 11, 5, 12, 0) }
        assertEquals(1, h.run().written)
        val again = h.run()
        assertEquals(0, again.written)
        assertEquals(1, again.skipped)
    }

    // ── R3 手动删一张 ──────────────────────────────────────────────────

    @Test
    fun `R3 手动删掉一张后仅补回那一张`(@TempDir temp: Path) {
        val h = Harness(temp)
        h.photo("IMG_001.jpg")
        h.photo("IMG_002.jpg")
        h.exif = { epoch(2026, 11, 5, 12, 0) }
        val first = h.run()
        Files.delete(first.targetDir!!.resolve("IMG_001.jpg"))
        val again = h.run()
        assertEquals(1, again.written)
        assertEquals(1, again.skipped)
    }

    // ── R4 半截文件 ────────────────────────────────────────────────────

    @Test
    fun `R4 截断成半截文件被识别并删除重写`(@TempDir temp: Path) {
        val h = Harness(temp)
        h.photo("IMG_001.jpg", "0123456789")
        h.exif = { epoch(2026, 11, 5, 12, 0) }
        val first = h.run()
        Files.writeString(first.targetDir!!.resolve("IMG_001.jpg"), "0123", Charsets.UTF_8)
        val again = h.run()
        assertEquals(1, again.rewritten)
        assertEquals("0123456789", Files.readString(again.targetDir!!.resolve("IMG_001.jpg")))
    }

    // ── R7 同名不同内容 ────────────────────────────────────────────────

    @Test
    fun `R7 同名不同内容大小相同哈希不同写为name_1不覆盖`(@TempDir temp: Path) {
        val h = Harness(temp)
        h.photo("IMG_001.jpg", "AAAA")
        h.exif = { epoch(2026, 11, 5, 12, 0) }
        val first = h.run()
        Files.writeString(first.targetDir!!.resolve("IMG_001.jpg"), "BBBB", Charsets.UTF_8)
        val again = h.run()
        assertEquals(1, again.renamed)
        assertTrue(Files.isRegularFile(again.targetDir!!.resolve("IMG_001_1.jpg")))
        assertEquals("BBBB", Files.readString(again.targetDir!!.resolve("IMG_001.jpg")), "原文件不被覆盖")
        assertTrue(again.logs.any { it.contains("另存为") })
    }

    // ── R5 换手机目录 → 冲突提示 ───────────────────────────────────────

    @Test
    fun `R5 换手机目录同区间报冲突且不传输`(@TempDir temp: Path) {
        val h = Harness(temp)
        h.photo("IMG_001.jpg", "AAAA")
        h.exif = { epoch(2026, 11, 5, 12, 0) }
        assertEquals(1, h.run(phoneDir = "dirA").written)
        val out = h.run(phoneDir = "dirB")
        assertNotNull(out.conflict)
        assertTrue(out.conflict!!.contains("另一目录"))
        assertEquals(0, out.written)
        assertEquals(0, out.failed)
    }

    @Test
    fun `用户确认冲突后可继续且不静默合并`(@TempDir temp: Path) {
        val h = Harness(temp)
        h.photo("IMG_001.jpg", "AAAA")
        h.exif = { epoch(2026, 11, 5, 12, 0) }
        h.run(phoneDir = "dirA")
        val out = h.run(phoneDir = "dirB", confirmConflict = true)
        assertNull(out.conflict)
        assertEquals(1, out.skipped)
    }

    // ── 子文件夹边界 ───────────────────────────────────────────────────

    @Test
    fun `子文件夹已存在时不重建不清空`(@TempDir temp: Path) {
        val h = Harness(temp)
        Files.createDirectories(h.sourceDir.resolve("04_2300__05_2359"))
        Files.writeString(h.sourceDir.resolve("04_2300__05_2359/keep.txt"), "keep", Charsets.UTF_8)
        h.photo("IMG_001.jpg")
        h.exif = { epoch(2026, 11, 5, 12, 0) }
        val out = h.run()
        assertEquals("keep", Files.readString(out.targetDir!!.resolve("keep.txt")))
        assertEquals(1, out.written)
    }

    @Test
    fun `sync-state被手删后退化为全量重同步仍靠判据去重`(@TempDir temp: Path) {
        val h = Harness(temp)
        h.photo("IMG_001.jpg")
        h.exif = { epoch(2026, 11, 5, 12, 0) }
        val first = h.run()
        Files.delete(first.targetDir!!.resolve(SyncState.FILE_NAME))
        val again = h.run()
        assertEquals(0, again.written, "文件仍在且内容一致 → 跳过，不产生副本")
        assertEquals(1, again.skipped)
    }

    // ── A+B 降级 / 不混用 ─────────────────────────────────────────────

    @Test
    fun `AUTO先B后A ADB可用时走ADB`(@TempDir temp: Path) {
        val h = Harness(temp)
        h.photo("IMG_001.jpg")
        h.exif = { epoch(2026, 11, 5, 12, 0) }
        val out = h.run()
        assertEquals(ConnectionKind.ADB, out.connection)
        assertEquals(ConnectionKind.ADB, h.lastKind)
    }

    @Test
    fun `AUTO下ADB不可用时不致命自动落盘符`(@TempDir temp: Path) {
        val h = Harness(temp)
        h.photo("IMG_001.jpg")
        h.exif = { epoch(2026, 11, 5, 12, 0) }
        h.adbAvailable = false
        val out = h.run()
        assertNull(out.error)
        assertEquals(ConnectionKind.USB_DRIVE, out.connection)
        assertEquals(1, out.written)
    }

    @Test
    fun `两者都不可用才报失败且不创建子文件夹`(@TempDir temp: Path) {
        val h = Harness(temp)
        h.adbAvailable = false
        h.usbVisible = false
        val out = h.run()
        assertEquals(ConnectionKind.NONE, out.connection)
        assertNotNull(out.error)
        assertTrue(out.error!!.contains("未检测到手机"))
        assertNull(out.targetDir)
        assertEquals(0, out.written)
    }

    @Test
    fun `显式选盘符时以用户选择为准不混用`(@TempDir temp: Path) {
        val h = Harness(temp)
        h.photo("IMG_001.jpg")
        h.exif = { epoch(2026, 11, 5, 12, 0) }
        val out = h.run(mode = ConnectionMode.USB_DRIVE)
        assertEquals(ConnectionKind.USB_DRIVE, out.connection, "ADB 可用也要尊重用户选择")
        assertEquals(1, h.factoryCalls, "整批只解析一次数据源")
        assertEquals(1, h.sources.size, "不混用：只有一个数据源实例")
    }

    @Test
    fun `显式选ADB但不可用时降级到盘符`(@TempDir temp: Path) {
        val h = Harness(temp)
        h.photo("IMG_001.jpg")
        h.exif = { epoch(2026, 11, 5, 12, 0) }
        h.adbAvailable = false
        val out = h.run(mode = ConnectionMode.ADB)
        assertEquals(ConnectionKind.USB_DRIVE, out.connection)
        assertEquals(1, out.written)
    }

    @Test
    fun `拉取失败记为失败但不中断后续文件`(@TempDir temp: Path) {
        val h = Harness(temp)
        h.photo("IMG_001.jpg")
        h.photo("IMG_002.jpg")
        h.exif = { epoch(2026, 11, 5, 12, 0) }
        h.failPull = setOf("IMG_001.jpg")
        val out = h.run()
        assertEquals(1, out.failed, "第一张拉取失败要单独计数")
        assertEquals(1, out.written, "后续文件不能被中断")
        assertFalse(Files.isRegularFile(out.targetDir!!.resolve("IMG_001.jpg")))
        assertTrue(Files.isRegularFile(out.targetDir!!.resolve("IMG_002.jpg")))
        assertTrue(out.logs.any { it.contains("拉取失败") })
    }
}

// A+B 状态机的纯判定（方案 D 未列独立测试文件，归入 Executor 测试同一份文件）。
class PhoneConnectionTest {

    @Test
    fun `AUTO优先级先ADB后盘符`() {
        assertEquals(ConnectionKind.ADB, PhoneConnection.resolve(ConnectionMode.AUTO, ConnectionProbe(true, true)))
        assertEquals(ConnectionKind.ADB, PhoneConnection.resolve(ConnectionMode.AUTO, ConnectionProbe(true, false)))
        assertEquals(ConnectionKind.USB_DRIVE, PhoneConnection.resolve(ConnectionMode.AUTO, ConnectionProbe(false, true)))
        assertEquals(ConnectionKind.NONE, PhoneConnection.resolve(ConnectionMode.AUTO, ConnectionProbe(false, false)))
    }

    @Test
    fun `显式覆盖与降级`() {
        assertEquals(ConnectionKind.ADB, PhoneConnection.resolve(ConnectionMode.ADB, ConnectionProbe(true, true)))
        assertEquals(ConnectionKind.USB_DRIVE, PhoneConnection.resolve(ConnectionMode.USB_DRIVE, ConnectionProbe(true, true)))
        assertEquals(ConnectionKind.USB_DRIVE, PhoneConnection.resolve(ConnectionMode.ADB, ConnectionProbe(false, true)))
        assertEquals(ConnectionKind.ADB, PhoneConnection.resolve(ConnectionMode.USB_DRIVE, ConnectionProbe(true, false)))
        assertEquals(ConnectionKind.NONE, PhoneConnection.resolve(ConnectionMode.ADB, ConnectionProbe(false, false)))
    }

    @Test
    fun `失败文案按方式分开不合并成一句连不上手机`() {
        assertTrue(PhoneConnection.failureText(ConnectionKind.ADB).contains("USB 调试"))
        assertTrue(PhoneConnection.failureText(ConnectionKind.USB_DRIVE).contains("MTP"))
        val none = PhoneConnection.failureText(ConnectionKind.NONE)
        // 盘符侧提示仍在；ADB 侧不再单独写一份，改为引用合并提示（引用式校验，防两处文案漂移）。
        assertTrue(none.startsWith(AdbBridge.ADB_NOT_FOUND_HINT) && none.contains("传输文件"))
    }

    @Test
    fun `ADB不可用且盘符不可见时文案为合并提示加分步排查`() {
        // 真机断手机 + adb 找不到的真实落点：DesktopCore 用 hasDevice() 当 adbAvailable，
        // 两者都 false → resolve(AUTO) = NONE → 用户看到的就是这条文案。
        val none = PhoneConnection.failureText(ConnectionKind.NONE)
        assertTrue(none.startsWith(AdbBridge.ADB_NOT_FOUND_HINT), "必须以合并提示开头（接线生效）")
        for (mark in listOf("①", "②", "③")) {
            assertTrue(none.contains(mark), "缺少分步排查 $mark")
        }
        assertTrue(none.contains("传输文件"), "盘符用户的「切传输文件」提示不能丢")
    }

    @Test
    fun `ADB可用但用户选盘符以用户选择为准`() {
        assertEquals(
            ConnectionKind.USB_DRIVE,
            PhoneConnection.resolve(ConnectionMode.USB_DRIVE, ConnectionProbe(true, true)),
        )
    }
}
