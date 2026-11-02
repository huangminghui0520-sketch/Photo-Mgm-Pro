// app/PhotoMgmApp.kt —— Application：构建并注册全局唯一的 Coil ImageLoader
//
// 为什么必须唯一（这是一个会让预加载白做的坑）：
//   Coil 的 AsyncImage 默认使用 Coil.imageLoader(context) —— 即全局单例。
//   如果缩略图预加载另建一个 ImageLoader 实例，两者的内存缓存互不相通：
//   预加载写了缓存 A，界面读的却是缓存 B，于是预加载完全没有效果。
//   所以：在这里构建唯一实例 → 注册为全局单例 → 预加载与 AsyncImage 共用同一份缓存。
package com.photomgm.app

import android.app.Application
import coil.Coil
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import java.io.File

class PhotoMgmApp : Application() {

    /** 全局唯一的 ImageLoader（预加载与 AsyncImage 共用）。 */
    lateinit var imageLoader: ImageLoader
        private set

    override fun onCreate() {
        super.onCreate()
        imageLoader = buildImageLoader()
        Coil.setImageLoader(imageLoader)
    }

    private fun buildImageLoader(): ImageLoader = ImageLoader.Builder(this)
        // 缩略图是低分辨率小图，命中率优先于单张保真度
        .memoryCache {
            MemoryCache.Builder(this)
                .maxSizePercent(0.25)
                .build()
        }
        // 独立磁盘缓存目录，便于单独清理；与内存缓存一同由 ThumbnailCache.clear() 清空
        .diskCache {
            DiskCache.Builder()
                .directory(File(cacheDir, "thumb_cache"))
                .maxSizeBytes(96L * 1024 * 1024)
                .build()
        }
        .build()
}
