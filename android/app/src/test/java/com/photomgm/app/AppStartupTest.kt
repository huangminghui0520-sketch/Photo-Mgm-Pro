// app/AppStartupTest —— 启动链路验证（本用例若失败，说明真机也会闪退）
//
// 背景：本轮新增了 PhotoMgmApp（Application 子类），它在 onCreate 里构建 Coil ImageLoader。
// 应用一启动就会执行这段代码，任何异常都会导致"打开即闪退"。
// 而此前的测试全部使用 Robolectric 默认 Application —— 从未执行过 PhotoMgmApp.onCreate()，
// 因此这个风险点完全没有被覆盖。本用例补上。
package com.photomgm.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import coil.Coil
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppStartupTest {

    private fun app(): Application = ApplicationProvider.getApplicationContext()

    /** PhotoMgmApp 必须是真正的启动 Application（manifest 已注册）。 */
    @Test
    fun `manifest注册的是PhotoMgmApp`() {
        val a = app()
        assertTrue(
            "启动 Application 应为 PhotoMgmApp，实际 ${a::class.java.name}",
            a is PhotoMgmApp,
        )
    }

    /**
     * ★ 核心：Application.onCreate 里的 Coil 初始化必须成功。
     * 若这里抛异常，真机表现为"打开 App 直接闪退"，且不进入任何界面。
     */
    @Test
    fun `Application初始化Coil成功且缓存可用`() {
        // ApplicationProvider 触发 Application 创建（onCreate 已执行）
        val a = app() as PhotoMgmApp
        val loader = a.imageLoader          // lateinit：若 onCreate 抛出，这里会抛 UninitializedPropertyAccessException
        assertNotNull("imageLoader 应在 onCreate 中完成初始化", loader)
        assertNotNull("内存缓存应可用", loader.memoryCache)
        assertNotNull("磁盘缓存应可用", loader.diskCache)
        // 注册为全局单例（AsyncImage 走的就是它）
        assertTrue("应与全局单例是同一实例", Coil.imageLoader(a) === loader)
    }

    /** 磁盘缓存目录必须可创建（cacheDir 不可写会抛异常）。 */
    @Test
    fun `磁盘缓存目录可创建`() {
        val a = app()
        val dir = File(a.cacheDir, "thumb_cache")
        assertTrue("cacheDir 应可写", a.cacheDir.exists() || a.cacheDir.mkdirs())
        assertTrue("thumb_cache 目录应可创建", dir.exists() || dir.mkdirs())
    }

    /** ThumbnailCache 在真实 Application 上下文下可构造并执行（它在 ViewModel 默认参数里被调用）。 */
    @Test
    fun `ThumbnailCache在真实上下文下可用`() {
        val cache = CoilThumbnailCache(app())
        cache.clear()
        cache.preload(listOf("nonexistent.jpg"), maxCount = 10)
    }

    /**
     * ★ 回归守卫：AppViewModel 必须能被框架工厂创建。
     *
     * 背景：曾给 AppViewModel 增加第二个构造参数（`thumbCache`，即使有默认值），
     * 导致 `AndroidViewModelFactory` 反射找不到 `(Application)` 构造函数，抛出
     * `RuntimeException: Cannot create an instance of class AppViewModel`。
     * 而 MainActivity 第一行就是 viewModel() —— 真机表现为「打开 App 直接闪退」。
     *
     * 该缺陷逃过了当时全部测试：测试都是直接 new AppViewModel(app, cache)，
     * 唯独绕过了框架。本用例专门走框架路径，钉死这个签名。
     */
    @Test
    fun `AppViewModel能被框架工厂创建_防闪退回归`() {
        val a = app()
        // 1) 只暴露一个 (Application) 构造函数（多参数会破坏框架反射）
        val ctor = AppViewModel::class.java.constructors.firstOrNull { c ->
            c.parameterTypes.size == 1 && c.parameterTypes[0] == Application::class.java
        }
        assertNotNull(
            "AppViewModel 必须保留 (Application) 单参构造函数，否则真机启动会闪退",
            ctor,
        )
        // 2) 真正走框架的 AndroidViewModelFactory 创建一次
        val factory = androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(a)
        val vm = factory.create(AppViewModel::class.java)
        assertNotNull("AndroidViewModelFactory 应能创建 AppViewModel", vm)
    }
}
