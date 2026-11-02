// app/ThumbnailCacheWiringTest —— 缩略图缓存接线验证
//
// 存在的意义：这里最容易犯、且最不容易被发现的错误是「预加载写进缓存 A，界面读缓存 B」——
// 代码能编译、能跑、界面也不报错，只是预加载完全没效果。
// 因此用测试真正钉死：Application 注册的实例必须就是 Coil 全局单例，且预加载与界面
// 发出的请求必须逐字段一致（同 data / 同解码尺寸 / 同内存缓存键）。
package com.photomgm.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import coil.Coil
import coil.memory.MemoryCache
import coil.size.Size
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThumbnailCacheWiringTest {

    private fun app(): Application = ApplicationProvider.getApplicationContext()

    private val sampleRef = "/sdcard/DCIM/Camera/a.jpg"

    /**
     * 核心断言：全局单例必须就是 PhotoMgmApp 构建的那个实例。
     * 若有人以后改成在别处 new 一个 ImageLoader，此用例会失败。
     */
    @Test
    fun `Application 注册的 ImageLoader 就是 Coil 全局单例`() {
        val application = app()
        assertTrue(
            "manifest 的 android:name 应指向 PhotoMgmApp，实际拿到 " + application::class.java.name,
            application is PhotoMgmApp,
        )
        val owned = (application as PhotoMgmApp).imageLoader
        val global = Coil.imageLoader(application)
        assertSame(
            "预加载与 AsyncImage 必须共用同一实例，否则缓存互不相通、预加载等于白做",
            owned,
            global,
        )
    }

    @Test
    fun `全局单例同时具备内存缓存与磁盘缓存`() {
        val loader = Coil.imageLoader(app())
        assertTrue("内存缓存应可用（供缩略图命中）", loader.memoryCache != null)
        assertTrue("磁盘缓存应可用（跨进程重启复用）", loader.diskCache != null)
    }

    @Test
    fun `preload 超过上限时静默放弃`() {
        val cache = CoilThumbnailCache(app())
        val many = (1..50).map { "/nonexistent/photo_$it.jpg" }
        cache.preload(many, maxCount = 10)   // 超限 → 不应抛异常
        cache.clear()
    }

    @Test
    fun `clear 与 preload 在空输入下不崩溃`() {
        val cache = CoilThumbnailCache(app())
        cache.clear()
        cache.preload(emptyList(), maxCount = 200)
        ThumbnailCache.NoOp.clear()
        ThumbnailCache.NoOp.preload(listOf("x"), 200)
    }

    /**
     * ★ W5(#3) 主判据：内存缓存键**必须含解码尺寸**，不能再是裸 ref。
     *
     * 历史问题：预加载显式 `memoryCacheKey(ref)`，而显式键会**整串替换** Coil 自动算的键，
     * 于是"尺寸"这一维度被挤出缓存键：预加载按整屏尺寸解出来的位图，与界面按卡片尺寸的请求
     * 共用同一个键 → 互相覆盖。要么预加载白做（被界面那份顶掉），要么界面拿到尺寸不匹配的
     * 位图（首帧糊、再解码）。
     *
     * 修复取向：把尺寸拼进键（`thumb:<px>:<ref>`），而不是"去掉显式键让 Coil 自动算"。
     * 理由见 [ThumbnailSpec.memoryCacheKey] 的 KDoc：Coil 的自动算键在 internal 的
     * `RequestService`，测试侧读不到"这条请求的键"，性质只能靠真解码间接验证。
     */
    @Test
    fun `预加载请求的内存缓存键必须含解码尺寸_不能是裸 ref`() {
        val cache = CoilThumbnailCache(app())
        val sizePx = ThumbnailSpec.sizePx(app())
        val req = cache.buildRequest(sampleRef)

        assertNotEquals(
            "内存缓存键不能等于裸 ref —— 裸 ref 不含尺寸维度，不同解码尺寸会撞成同一项互相覆盖",
            MemoryCache.Key(sampleRef),
            req.memoryCacheKey,
        )
        assertEquals(
            "键必须由 ThumbnailSpec.memoryCacheKey 统一生成（界面与预加载同源，否则迟早漂移）",
            MemoryCache.Key(ThumbnailSpec.memoryCacheKey(sampleRef, sizePx)),
            req.memoryCacheKey,
        )
        assertEquals(
            "磁盘里存的是原始编码数据，与解码尺寸无关，磁盘键应继续按 ref 共享",
            sampleRef,
            req.diskCacheKey,
        )
    }

    /**
     * ★ W5(#3)：「同一个 ref、不同解码尺寸」**不得命中同一缓存项**。
     *
     * 这条是上一条的目的所在。单看"键里有尺寸"只是形状，真正要的性质是：
     * 两份不同尺寸的位图在内存缓存里互不覆盖 —— 用两个尺寸构造键直接比对即可断言，
     * 不需要起 ImageLoader 真解码。
     */
    @Test
    fun `同 ref 不同尺寸不得命中同一缓存项`() {
        val cache = CoilThumbnailCache(app())
        val small = 120
        val large = 480
        val reqSmall = cache.buildRequest(sampleRef, sizePx = small)
        val reqLarge = cache.buildRequest(sampleRef, sizePx = large)

        assertNotEquals(
            "同 ref 不同解码尺寸必须是两个不同的缓存项，否则大图与缩略图互相覆盖",
            reqSmall.memoryCacheKey,
            reqLarge.memoryCacheKey,
        )
        // 反向对照：同尺寸必须同键，否则缓存永远不命中（等于没缓存）
        assertEquals(
            "同 ref 同尺寸必须落在同一缓存项，否则预加载与界面命中不到彼此",
            reqSmall.memoryCacheKey,
            cache.buildRequest(sampleRef, sizePx = small).memoryCacheKey,
        )
    }

    /**
     * 界面侧请求与预加载请求必须**逐字段一致**，否则预加载写进去的界面命中不到。
     *
     * 用 [ThumbnailSpec.request]（界面走的就是它）与 [CoilThumbnailCache.buildRequest]
     * （preload 的唯一入口）对撞，比逐个字段人肉核对可靠。
     */
    @Test
    fun `界面请求与预加载请求逐字段一致`() {
        val context = app()
        val uiReq = ThumbnailSpec.request(context, sampleRef)
        val preloadReq = CoilThumbnailCache(context).buildRequest(sampleRef)

        assertEquals("data 必须一致（同一张照片）", uiReq.data, preloadReq.data)
        assertEquals("内存缓存键必须一致", uiReq.memoryCacheKey, preloadReq.memoryCacheKey)
        assertEquals("磁盘缓存键必须一致", uiReq.diskCacheKey, preloadReq.diskCacheKey)
        assertEquals(
            "内存缓存策略必须一致（界面端若关掉内存缓存，预加载同样白做）",
            uiReq.memoryCachePolicy,
            preloadReq.memoryCachePolicy,
        )

        val uiSize = runBlocking { uiReq.defined.sizeResolver?.size() }
        val preloadSize = runBlocking { preloadReq.defined.sizeResolver?.size() }
        assertNotNull("界面请求必须显式限定解码尺寸", uiSize)
        assertEquals("界面与预加载的解码尺寸必须相同，否则缓存键里的尺寸段失去意义", uiSize, preloadSize)
    }

    /**
     * ★ W5(#3)：预加载请求**必须显式**限定解码尺寸。
     *
     * 从前的状态（实测 Coil 2.5 + Robolectric 打印确认）：[CoilThumbnailCache.buildRequest]
     * 没有 `.size(...)` 时，sizeResolver 是 Coil 默认的 **DisplaySizeResolver** —— 即"整屏尺寸"
     * （实测解析出 Size(470×470)），不是原图尺寸，也不是卡片尺寸。后果是事件网格里 100dp 见方的
     * 卡片，解码出的却是整屏级别的位图，几十张就能吃掉几十 MB。
     */
    @Test
    fun `预加载请求必须显式限定解码尺寸_不能依赖默认解析器`() {
        val req = CoilThumbnailCache(app()).buildRequest(sampleRef)
        // ★ 判据用 req.defined.sizeResolver（Coil 的公开 API：只有**显式**调用过 .size(...)
        //   才会非空），而不是去猜内部解析器类型（RealSizeResolver 是 internal，测试侧碰不到）。
        val defined = req.defined.sizeResolver
        assertNotNull(
            "预加载请求必须显式 `.size(w, h)`。当前 defined.sizeResolver 为空，即依赖 Coil 默认解析器" +
                "（DisplaySizeResolver = 整屏尺寸），几十张缩略图就能吃掉几十 MB",
            defined,
        )
        val size = runBlocking { defined!!.size() }   // 上一行断言已保证非空
        assertNotEquals("显式限定的尺寸必须是确定像素值，不能是 Original", Size.ORIGINAL, size)
    }
}
