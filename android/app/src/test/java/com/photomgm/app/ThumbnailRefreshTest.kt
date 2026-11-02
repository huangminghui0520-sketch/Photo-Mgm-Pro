// app/ThumbnailRefreshTest —— 验证「选取巡查日期 → 清旧缩略图缓存 → 读取照片信息 → 缓存本批缩略图」的顺序与内容
//
// 关键断言（顺序错了会静默失效，必须钉死）：
//   1. clear() 必须发生在 EXIF 精读之前  —— 用"清缓存那一刻 state.photos 仍为空"来证明
//   2. preload() 必须发生在读取之后       —— 用"预加载时 state.photos 已就绪"来证明
//   3. preload 拿到的是本批照片的 sourceRef（不是上一批、不是空）
package com.photomgm.app

import android.app.Application
import android.os.Looper
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.fakes.BaseCursor
import java.io.File
import java.nio.file.Files
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThumbnailRefreshTest {

    private lateinit var app: Application
    private lateinit var mediaDir: File

    /**
     * 记录调用顺序的假缓存，并记录"被调用那一刻的照片数量"。
     * 照片数量是判断先后顺序的客观依据：清缓存时应为 0（还没读），预加载时应 > 0（已读完）。
     */
    private class RecordingCache(private val photosNow: () -> Int) : ThumbnailCache {
        val events = mutableListOf<String>()
        var photosAtClear: Int = -1
        var photosAtPreload: Int = -1
        var lastRefs: List<String> = emptyList()
        var lastMaxCount: Int = -1

        override fun clear() {
            events += "clear"
            photosAtClear = photosNow()
        }

        override fun preload(refs: List<String>, maxCount: Int) {
            events += "preload"
            photosAtPreload = photosNow()
            lastRefs = refs
            lastMaxCount = maxCount
        }
    }

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        val root = Files.createTempDirectory("ThumbRefresh").toFile()
        mediaDir = File(root, "DCIM/Camera/9-25").apply { mkdirs() }
        // 本地测试照片源（工作区内的 源目录/9-25；缺失则跳过）
        val src = File("D:/Photo-Mgm/源目录/9-25")
        val files = src.listFiles()?.filter { it.name.lowercase().endsWith(".jpg") } ?: emptyList()
        assumeTrue("测试照片源缺失，跳过", files.isNotEmpty())
        files.take(30).forEach { it.copyTo(File(mediaDir, it.name), overwrite = true) }
    }

    private fun registerMediaStore() {
        val files = mediaDir.listFiles()?.filter { it.name.lowercase().endsWith(".jpg") } ?: emptyList()
        assumeTrue("临时媒体目录无照片，跳过", files.isNotEmpty())
        val cols = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.DATA,
        )
        val rows = files.mapIndexed { i, f ->
            arrayOf<Any?>(
                (i + 1L),
                f.name,
                "DCIM/Camera/9-25/${f.name}",
                f.absolutePath.replace('\\', '/'),
            )
        }
        shadowOf(app.contentResolver)
            .setCursor(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, FakeMediaCursor(cols, rows))
    }

    private class FakeMediaCursor(
        private val cols: Array<String>,
        private val rows: List<Array<Any?>>,
    ) : BaseCursor() {
        private var pos = -1
        override fun getCount(): Int = rows.size
        override fun moveToNext(): Boolean {
            pos++
            if (pos >= rows.size) pos = -1
            return pos >= 0 && pos < rows.size
        }
        override fun getPosition(): Int = pos
        override fun getColumnIndexOrThrow(columnName: String): Int =
            cols.indexOf(columnName).also { require(it >= 0) { "no column $columnName" } }
        override fun getString(columnIndex: Int): String? = rows[pos][columnIndex] as? String
        override fun getLong(columnIndex: Int): Long = (rows[pos][columnIndex] as Number).toLong()
        override fun isNull(columnIndex: Int): Boolean = rows[pos][columnIndex] == null
        override fun close() {}
        override fun isClosed(): Boolean = false
    }

    private fun await(vm: AppViewModel, timeoutMs: Long = 30000, cond: (UiState) -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond(vm.state.value) && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
        }
    }

    @Test
    fun `选日期后先清缩略图缓存再读照片最后预缓存`() {
        registerMediaStore()

        lateinit var vm: AppViewModel
        val cache = RecordingCache(photosNow = { vm.state.value.photos.size })
        // 注意：必须用 AppViewModel(app) —— 框架工厂只认 (Application) 签名。
        // 假缓存通过 internal setter 注入（构造函数不能被扩展，否则真机启动即闪退）。
        vm = AppViewModel(app)
        vm.thumbCache = cache

        // 先配源目录（此阶段也会清一次缓存），再选日期——模拟用户真实操作顺序
        vm.addSourceDir("DCIM/Camera")
        await(vm) { !it.busy }
        val eventsAfterDirSetup = cache.events.toList()

        // ★ 选取巡查日期 → 期望：clear → 粗筛 → 精读 → preload
        vm.setDate(LocalDate.of(2026, 9, 25))
        await(vm) { it.photos.isNotEmpty() && !it.busy }

        val state = vm.state.value
        assumeTrue("未读到照片（MediaStore 模拟未命中），跳过", state.photos.isNotEmpty())

        // ① 选日期后确实清了缓存
        val newEvents = cache.events.drop(eventsAfterDirSetup.size)
        assertTrue(
            "选日期后应调用 clear()，本次新增调用=$newEvents",
            newEvents.contains("clear"),
        )
        // ② 顺序：清缓存时照片还没读出来（photos 为空）
        assertEquals(
            "clear() 必须发生在 EXIF 精读之前（那一刻 photos 应为 0），实际=${cache.photosAtClear}",
            0,
            cache.photosAtClear,
        )
        // ③ 顺序：预加载时照片已就绪
        assertTrue(
            "preload() 必须发生在照片读取之后（那一刻 photos 应 > 0），实际=${cache.photosAtPreload}",
            cache.photosAtPreload > 0,
        )
        // ④ 预加载的正是本批照片
        assertEquals(
            "preload 拿到的应是本批全部照片的 sourceRef",
            state.photos.map { it.sourceRef }.toSet(),
            cache.lastRefs.toSet(),
        )
        assertEquals("preload 上限应为 500", 500, cache.lastMaxCount)
    }
}
