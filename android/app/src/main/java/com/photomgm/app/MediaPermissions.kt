// app/MediaPermissions.kt —— 媒体权限申请计划（纯逻辑，无 Android 运行时依赖，可 JVM 单测）
//
// ★ P0 修复（2026-11-02）：此前只申请 READ_MEDIA_IMAGES / READ_EXTERNAL_STORAGE，
//   漏了 ACCESS_MEDIA_LOCATION。Android 10+ 的 MediaProvider/FUSE 对**没有**该权限的 App
//   在读取媒体字节时剥离 EXIF GPS（值区清零、文件长度不变），导致：
//     Photo.latitude/longitude 恒 null → 去重走「时间 + 文件名序号」降级路径、
//     导出 ZIP 里的照片位置信息全为 0。
//   该剥离发生在**读取那一刻**，所以只改 manifest 声明不够，必须运行时授予。
//
// 本文件把「申请哪些权限 / 结果如何解释」从 MainActivity 里抽出来，成为可断言的纯函数：
//   · 权限清单随 SDK 版本变化（33 分水岭、29 分水岭）—— 这类分支写在 Composable 里只能靠真机试；
//   · 降级口径（能否读真实位置）必须由同一处判定，避免申请与判定各写一套而漂移。
package com.photomgm.app

import android.Manifest

/**
 * 一次启动要申请的权限集合。
 * @param media 媒体读取权限（33+ READ_MEDIA_IMAGES / 更低 READ_EXTERNAL_STORAGE）
 * @param geo 位置元数据权限；null = 该系统版本不存在此权限概念（API < 29）
 * @param all 实际提交给系统对话框的数组（含 [media]，geo 非空时也含 geo）
 */
data class MediaPermissionPlan(
    val media: String,
    val geo: String?,
    val all: List<String>,
)

object MediaPermissions {

    /** 未授予位置权限时的降级口径提示（必须说清"降级到什么"，否则用户会以为去重结果是准的）。 */
    const val HINT_GEO_UNAVAILABLE =
        "未授予「照片位置信息」权限：重复判定降级为按拍摄时间 + 文件名序号，导出照片不含定位"

    /** 媒体读取权限：33 起 READ_MEDIA_IMAGES 取代 READ_EXTERNAL_STORAGE。 */
    fun mediaPermission(sdkInt: Int): String =
        if (sdkInt >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE

    /**
     * 位置元数据权限是否适用于该系统版本。
     * ACCESS_MEDIA_LOCATION 是 API 29 引入的运行时权限；26~28 的存储模型不做位置剥离，
     * 申请一个不存在的权限只会被系统拒绝（且真机上弹不出对话框），所以按版本裁剪。
     */
    fun supportsGeo(sdkInt: Int): Boolean = sdkInt >= 29

    fun plan(sdkInt: Int): MediaPermissionPlan {
        val media = mediaPermission(sdkInt)
        val geo = if (supportsGeo(sdkInt)) Manifest.permission.ACCESS_MEDIA_LOCATION else null
        return MediaPermissionPlan(media, geo, listOfNotNull(media, geo))
    }

    /**
     * 本次授权结果下，**是否能读到真实位置信息**。
     * - API < 29：没有剥离机制，恒 true（不申请也不影响 GPS）；
     * - API >= 29：必须显式授予 ACCESS_MEDIA_LOCATION。
     * @param granted RequestMultiplePermissions 的回调结果（未申请的权限不会出现在 map 里）
     */
    fun geoAvailable(sdkInt: Int, granted: Map<String, Boolean>): Boolean =
        !supportsGeo(sdkInt) || granted[Manifest.permission.ACCESS_MEDIA_LOCATION] == true
}
