// app/MediaPermissionsTest —— P0 修复的可执行契约：申请哪些权限、结果怎么解释
//
// 为什么值得单测：这些分支（33 分水岭 / 29 分水岭 / 未授予）在真机上要换系统版本、
// 反复改授权状态才能覆盖一遍；写进纯函数后，JVM 上 1 秒跑完所有组合。
// 真实读写链路（系统是否剥离 GPS）由真机 P-2 复测覆盖，不在本测试职责内。
//
// ★ 用 Robolectric 而不是纯 JVM：纯 JVM 单测跑的是 mockable android.jar，
//   `Manifest.permission.*` 这些常量字段被清空成 null，断言等于在比 null == null。
//   Robolectric 提供带真实常量值的 android.jar（@Config 的 sdk 只影响运行时行为）。
package com.photomgm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaPermissionsTest {

    private val mediaImages = "android.permission.READ_MEDIA_IMAGES"
    private val readStorage = "android.permission.READ_EXTERNAL_STORAGE"
    private val mediaLocation = "android.permission.ACCESS_MEDIA_LOCATION"

    // ── 1. 权限清单随 SDK 版本变化 ───────────────────────────────────────────

    @Test
    fun `API26 只申请旧存储权限_不含位置权限`() {
        val plan = MediaPermissions.plan(26)
        assertEquals(readStorage, plan.media)
        assertNull("API29 之前没有该权限，申请只会被系统忽略", plan.geo)
        assertEquals(listOf(readStorage), plan.all)
    }

    @Test
    fun `API28 仍走旧存储权限_不含位置权限`() {
        val plan = MediaPermissions.plan(28)
        assertEquals(readStorage, plan.media)
        assertNull(plan.geo)
    }

    /** ★ 分水岭：29 起系统会剥离位置 → 必须把 ACCESS_MEDIA_LOCATION 一并申请。 */
    @Test
    fun `API29 起同时申请媒体与位置权限`() {
        val plan = MediaPermissions.plan(29)
        assertEquals(readStorage, plan.media)
        assertEquals(mediaLocation, plan.geo)
        assertEquals(listOf(readStorage, mediaLocation), plan.all)
    }

    @Test
    fun `API33 起媒体权限换成 READ_MEDIA_IMAGES_位置权限不变`() {
        val plan = MediaPermissions.plan(33)
        assertEquals(mediaImages, plan.media)
        assertEquals(mediaLocation, plan.geo)
        assertEquals(listOf(mediaImages, mediaLocation), plan.all)
    }

    /** 本次真机是 Android 16 / API 36，固定住这个组合防止将来改回单一权限。 */
    @Test
    fun `API36 真机组合`() {
        val plan = MediaPermissions.plan(36)
        assertEquals(listOf(mediaImages, mediaLocation), plan.all)
    }

    // ── 2. 授权结果 → 能否读真实位置 ─────────────────────────────────────────

    @Test
    fun `位置权限已授予_可读真实位置`() {
        assertTrue(MediaPermissions.geoAvailable(34, mapOf(mediaImages to true, mediaLocation to true)))
    }

    @Test
    fun `位置权限被拒_判定为不可用_即使照片权限已授予`() {
        assertFalse(
            "照片权限 granted 但位置权限 denied —— 这正是修复前的真机状态（读到 GPS 全 null）",
            MediaPermissions.geoAvailable(34, mapOf(mediaImages to true, mediaLocation to false)),
        )
    }

    @Test
    fun `回调里没有该权限键_一律视为不可用`() {
        assertFalse(MediaPermissions.geoAvailable(34, mapOf(mediaImages to true)))
        assertFalse(MediaPermissions.geoAvailable(34, emptyMap()))
    }

    /** API < 29 不申请该权限，回调里当然也没有 → 但不能判成"不可用"（否则老系统上误报降级）。 */
    @Test
    fun `API28 未申请位置权限_仍视为可用`() {
        assertTrue(MediaPermissions.geoAvailable(28, mapOf(readStorage to true)))
    }

    // ── 3. 降级提示必须说清"降级到什么" ─────────────────────────────────────

    @Test
    fun `降级提示包含位置权限与时间序号口径`() {
        val hint = MediaPermissions.HINT_GEO_UNAVAILABLE
        assertTrue("必须点明缺的是位置权限：$hint", hint.contains("位置"))
        assertTrue("必须说明降级后按什么判定：$hint", hint.contains("文件名序号"))
    }
}
