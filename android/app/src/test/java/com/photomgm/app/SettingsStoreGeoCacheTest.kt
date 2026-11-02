// app/SettingsStoreGeoCacheTest —— P0（GPS 被系统剥离）修复的缓存迁移契约
//
// 背景：旧版本 APK 没有 ACCESS_MEDIA_LOCATION，读到的 latitude/longitude 恒 null —— 那是
// **系统剥离**的结果，不是照片本身没有定位。修好权限后，这些 null 还躺在 cache_photos 里，
// 而"打开 App 从缓存恢复"不会重读 EXIF → 修复对老用户完全无效。
// 所以用 cache_geo_version 这个版本位一次性作废旧缓存。
//
// 本测试钉死四件事：
//   1. 没有 GPS 版本位（= 旧版本写的）→ 四份派生缓存全部读不出来；
//   2. 只有时区位、没有 GPS 位（= 上一个版本写的）→ 同样读不出来（这是最容易漏的一种）；
//   3. 两个位都对上 → 正常读出；
//   4. 丢的只是可重算的派生缓存，用户数据（日期/日志/人工移动/台账标记）一个都不能丢。
package com.photomgm.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsStoreGeoCacheTest {

    private fun ctx(): Context = ApplicationProvider.getApplicationContext()
    private fun prefs() = ctx().getSharedPreferences("photo_mgm_settings", Context.MODE_PRIVATE)

    private val parsedJson = """
        {"events":[{"id":1,"startTimeMs":1788393600000,"endTimeMs":1788393600000,
        "timePoints":[1788393600000],"location":"K1+000M","eventType":"巡查",
        "description":"08时00分 出发巡查"}],"skippedLines":[],"inspectors":null,
        "recorder":null,"vehicle":null,"notices":[]}
    """.trimIndent()

    /** 直接写 prefs，模拟"旧版本 App 留下的数据"（照片 GPS 为 null = 被旧版读成空）。 */
    private fun seed(geoVersion: String?, zoneVersion: String? = AppZoneVersion) {
        prefs().edit().apply {
            putString("cache_parsed", parsedJson)
            putString("cache_classify", """{"eventPhotoMap":{"1":[42]},"unmatched":[],"interventions":[],"pendingEventIds":[]}""")
            putString("cache_photos", """[{"id":42,"sourceRef":"/a.jpg","displayName":"a.jpg",
                "captureTimeMs":1788393600000,"latitude":null,"longitude":null}]""".trimIndent())
            putInt("cache_coarse_count", 7)
            if (zoneVersion != null) putString("cache_zone", zoneVersion) else remove("cache_zone")
            if (geoVersion != null) putString("cache_geo_version", geoVersion) else remove("cache_geo_version")
            // 用户数据：迁移必须原样保留
            putString("date", LocalDate.of(2026, 9, 3).toString())
            putString("logs_text", "08时00分 出发巡查")
            putString("photo_override", "42=1")
            putString("photo_marked", "1=42,")
        }.commit()
    }

    private companion object {
        /** 业务时区（与 SettingsStore 内写的是同一个来源，测试里显式取，避免依赖 private companion）。 */
        val AppZoneVersion: String = algorithm.AppZone.ID.id

        /**
         * GPS 语义版本位的**字面量**。刻意不引用 SettingsStore 的常量（那是 private）：
         * 测试里钉死字面量，别处若改了版本值，这里会红——迫使改动者同时确认"旧缓存作废"的语义。
         */
        const val GEO_V: String = "media-location-v1"
    }

    @Test
    fun `没有GPS版本位_旧缓存全部作废`() {
        seed(geoVersion = null)
        val store = SettingsStore(ctx())

        assertNull("旧包读到的 GPS 是系统剥离后的空值，缓存不可信 → 丢弃重算", store.parsedCache)
        assertNull(store.classifyCache)
        assertTrue(store.photosCache.isEmpty())
        assertNull(store.coarseCountCache)
    }

    @Test
    fun `只有时区位没有GPS位_同样作废`() {
        seed(geoVersion = null, zoneVersion = AppZoneVersion)
        val store = SettingsStore(ctx())
        assertNull("上一个版本写过 cache_zone 但没写 cache_geo_version → 仍是旧数据", store.parsedCache)
        assertTrue(store.photosCache.isEmpty())
    }

    @Test
    fun `GPS版本位不匹配_作废`() {
        seed(geoVersion = "media-location-v0")
        val store = SettingsStore(ctx())
        assertNull(store.parsedCache)
    }

    @Test
    fun `两个版本位都对上_缓存正常读出`() {
        seed(geoVersion = GEO_V)
        val store = SettingsStore(ctx())
        assertNotNull("版本位齐备 → 缓存可用", store.parsedCache)
        assertEquals(7, store.coarseCountCache)
        assertEquals(1, store.photosCache.size)
    }

    @Test
    fun `新写入会自动打上GPS版本位`() {
        val store = SettingsStore(ctx())
        store.photosCache = emptyList()
        store.markCacheVersion()
        assertEquals(
            "写缓存必须同时落版本位，否则下次启动立刻被判为旧数据（死循环重算）",
            GEO_V,
            prefs().getString("cache_geo_version", null),
        )
    }

    @Test
    fun `clearStateCache 会补齐GPS版本位`() {
        seed(geoVersion = null)
        val store = SettingsStore(ctx())
        store.clearStateCache()
        assertEquals(GEO_V, prefs().getString("cache_geo_version", null))
        assertNull(store.parsedCache)
    }

    @Test
    fun `缓存作废不牵连用户数据`() {
        seed(geoVersion = null)
        val store = SettingsStore(ctx())
        assertEquals(LocalDate.of(2026, 9, 3), store.date)
        assertEquals("08时00分 出发巡查", store.logsText)
        assertEquals(mapOf(42L to 1), store.overrideMap)
        assertEquals(42L, store.markedMap[1]?.first)
    }
}
