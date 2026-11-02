// app/ThumbnailCache.kt —— 缩略图缓存：清旧缓存 → 预加载当日照片
//
// 为什么需要它：
//   Coil 按 model 字符串（这里是照片路径）做内存/磁盘缓存。用户切换巡查日期或更换源目录后，
//   若不主动清缓存，上一批照片的缩略图会滞留在内存里占额度；而新一批照片要等到事件卡片
//   真正滚动到可见位置才开始解码，滚动时会出现明显白块。
//
// 做法（对应用户要求的"清除之前的缓存缩略图然后再缓存相关照片缩略图"）：
//   1. clear()   清空内存缓存 + 磁盘缓存
//   2. preload() 对刚精读完 EXIF 的照片批量解码为缩略图并写入内存缓存
//
// ★ 关键：必须复用 PhotoMgmApp 注册的全局单例 ImageLoader。
//   若此处自建实例，预加载写入缓存 A 而 AsyncImage 读缓存 B，预加载完全无效。
package com.photomgm.app

import android.content.Context
import coil.ImageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest

/**
 * 缩略图请求的**唯一定义**（W5 / issue #3）。
 *
 * 为什么需要它：内存缓存键必须覆盖「解码尺寸」这一维度，否则预加载与界面会用同一份
 * 位图互相覆盖。而"界面"和"预加载"发生在两个地方（Composable / ViewModel），
 * 只要两边各自拼 key，迟早会漂移。所以尺寸常量、键的拼法、请求的构造都收在这里，
 * 两边只允许调这里。
 */
object ThumbnailSpec {
    /**
     * 缩略图边长（dp）。
     *
     * ★ 界面 tile 与预加载**必须共用同一个值**：缓存键里带像素尺寸，尺寸不同就是两个
     *   不同的缓存项 —— 那样预加载写进去的永远命中不到，等于白做。
     *   取 120dp：覆盖事件网格/照片网格/查看器胶片条的实际显示尺寸（44~80dp）并留出
     *   高密度屏的余量；再大就是纯浪费（多解出来的像素没人看）。
     */
    const val TILE_DP = 120

    /** 上面的 dp 换算成当前屏幕的像素边长。 */
    fun sizePx(context: Context): Int {
        val density = context.resources.displayMetrics.density
        return (TILE_DP * density).toInt().coerceAtLeast(1)
    }

    /**
     * 内存缓存键：**把尺寸拼进去**。
     *
     * ★ 为什么选"拼进 key"而不是"去掉裸 ref 键、让 Coil 自动算"：
     *   Coil 自动算键的逻辑在 `RequestService.memoryCacheKey`（internal），测试侧读不到
     *   「这条请求的键是什么」，于是「同 ref 不同尺寸不得命中同一缓存项」这条性质只能靠
     *   真跑一次解码来间接验证 —— 慢、脆、还要起 ImageLoader。显式键把这条性质变成
     *   **可直接断言的对象**，也不随 Coil 版本漂移。
     *
     * 磁盘键仍用裸 ref（见 [CoilThumbnailCache.buildRequest]）：磁盘里存的是原始编码数据，
     * 与解码尺寸无关，按 ref 共享才是对的。
     */
    fun memoryCacheKey(ref: String, sizePx: Int): String = "thumb:${sizePx}:$ref"

    /**
     * 界面侧请求：与预加载的请求**逐字段一致**（同 data、同 size、同内存缓存键），
     * 这样界面才真的能命中预加载结果。
     */
    fun request(context: Context, ref: String, sizePx: Int = sizePx(context)): ImageRequest =
        ImageRequest.Builder(context.applicationContext)
            .data(ref)
            .size(sizePx)
            .memoryCacheKey(memoryCacheKey(ref, sizePx))
            .diskCacheKey(ref)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .build()
}

interface ThumbnailCache {
    /** 清空全部已缓存缩略图（内存 + 磁盘）。 */
    fun clear()

    /**
     * 批量预加载缩略图并写入内存缓存，供事件卡片直接命中。
     * @param refs 照片引用：绝对路径（MediaStore DATA）或相对路径（RELATIVE_PATH）
     * @param maxCount 上限，超过则放弃预加载（交回惰性加载，避免一次解码上百张）
     */
    fun preload(refs: List<String>, maxCount: Int)

    /** 空实现：不需要缩略图缓存的场景（如单元测试）。 */
    object NoOp : ThumbnailCache {
        override fun clear() = Unit
        override fun preload(refs: List<String>, maxCount: Int) = Unit
    }
}

/**
 * Coil 实现。
 *
 * 不持有自己的 ImageLoader，而是取 **Coil 的全局单例**（由 PhotoMgmApp.onCreate 注册）——
 * 这样预加载与界面 AsyncImage 用的是同一份内存/磁盘缓存，预加载才真正生效。
 */
class CoilThumbnailCache(context: Context) : ThumbnailCache {

    private val appContext = context.applicationContext
    private val imageLoader: ImageLoader get() = coil.Coil.imageLoader(appContext)

    override fun clear() {
        imageLoader.memoryCache?.clear()
        imageLoader.diskCache?.clear()
    }

    /**
     * 构造单张照片的预加载请求。
     *
     * ★ 2026-10-31 复核：抽成 internal 方法的唯一理由是让"请求参数"变成**可断言的**——
     *   原先请求在 preload 内部匿名构造，size / 缓存键全是黑盒，W5 的"未限定解码尺寸"
     *   只能靠人肉读代码发现（据此才有 ThumbnailCacheWiringTest 里的那两条用例）。
     *   ⚠ preload 必须且只能走这里入队，否则测试断言的对象与真实入队的请求会分叉。
     *
     * @param sizePx 解码边长（像素）。默认取 [ThumbnailSpec.sizePx]，与界面 tile 同值 ——
     *               这是"预加载能被界面命中"的前提。
     */
    internal fun buildRequest(
        ref: String,
        sizePx: Int = ThumbnailSpec.sizePx(appContext),
    ): ImageRequest = ThumbnailSpec.request(appContext, ref, sizePx)

    override fun preload(refs: List<String>, maxCount: Int) {
        if (refs.isEmpty() || refs.size > maxCount) return
        val loader = imageLoader
        refs.forEach { ref -> loader.enqueue(buildRequest(ref)) }
    }
}
