// app/SettingsStoreZoneMigrationTest —— issue #2 旧数据迁移（可回滚）的可执行契约
//
// 背景：状态缓存里存的是 **epoch 毫秒**，历史上是用「当时设备的默认时区」把日志/EXIF 里的
// 本地挂钟时间解释出来的。设备当时不在 +08:00 的话，这些 epoch 就是错的，而且**无法从
// epoch 反推原始挂钟**（信息已丢失）。所以处置不是"换算"，而是「丢弃 + 用日志原文按
// Asia/Shanghai 重算」。
//
// 本测试钉死三件事：
//   1. 版本位缺失（= 旧版本写的数据）→ 四份派生缓存全部读不出来；
//   2. 版本位被改成别的时区（= 设备在别处算出来的）→ 同样读不出来；
//   3. 版本位是 Asia/Shanghai → 正常读出；且被丢弃的**只是可重算的派生缓存**，
//      用户数据（日期/源目录/日志文本/人工移动/台账标记）一个都不能丢 —— 这是"可回滚"的前提。
package com.photomgm.app

import algorithm.AppZone
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
class SettingsStoreZoneMigrationTest {

    private fun ctx(): Context = ApplicationProvider.getApplicationContext()

    /** 直接写 prefs（绕过 SettingsStore 的 setter），模拟"旧版本 App 留下的数据"。 */
    private fun prefs() = ctx().getSharedPreferences("photo_mgm_settings", Context.MODE_PRIVATE)

    private val parsedJson = """
        {"events":[{"id":1,"startTimeMs":1788393600000,"endTimeMs":1788393600000,
        "timePoints":[1788393600000],"location":"K1+000M","eventType":"巡查",
        "description":"08时00分 出发巡查"}],"skippedLines":[],"inspectors":null,
        "recorder":null,"vehicle":null,"notices":[]}
    """.trimIndent()

    private fun seedLegacyCache(zoneVersion: String?) {
        prefs().edit().apply {
            putString("cache_parsed", parsedJson)
            putString("cache_classify", """{"eventPhotoMap":{"1":[42]},"unmatched":[],"interventions":[],"pendingEventIds":[]}""")
            putString("cache_photos", """[{"id":42,"sourceRef":"/a.jpg","displayName":"a.jpg",
                "captureTimeMs":1788393600000,"latitude":null,"longitude":null}]""".trimIndent())
            putInt("cache_coarse_count", 7)
            if (zoneVersion != null) putString("cache_zone", zoneVersion) else remove("cache_zone")
            // 用户数据：迁移必须原样保留
            putString("date", LocalDate.of(2026, 9, 3).toString())
            putString("logs_text", "08时00分 出发巡查")
            putString("photo_override", "42=1")
            putString("photo_marked", "1=42,")
        }.commit()
    }

    @Test
    fun `版本位缺失的旧缓存_四份派生缓存全部不可用`() {
        seedLegacyCache(zoneVersion = null)
        val store = SettingsStore(ctx())

        assertNull("解析结果按旧时区算的 epoch 不可信 → 必须丢弃", store.parsedCache)
        assertNull("分类结果同理", store.classifyCache)
        assertTrue("照片元数据（captureTimeMs 也是按旧时区算的）必须丢弃", store.photosCache.isEmpty())
        assertNull("粗筛数一并丢弃，让流水线重跑", store.coarseCountCache)

        // ★ 用户数据必须原样保留 —— 这是"可回滚"的落点：丢的只有可重算的派生数据
        assertEquals(LocalDate.of(2026, 9, 3), store.date)
        assertEquals("08时00分 出发巡查", store.logsText)
        assertEquals(mapOf(42L to 1), store.overrideMap)
        assertEquals(42L, store.markedMap[1]?.first)
    }

    @Test
    fun `版本位是别的时区_同样视为不可信`() {
        seedLegacyCache(zoneVersion = java.time.ZoneId.of("UTC").id)
        val store = SettingsStore(ctx())
        assertNull("设备曾在 UTC 下算出的缓存 → 不可信", store.parsedCache)
        assertNull(store.coarseCountCache)
        assertEquals("用户数据仍保留", "08时00分 出发巡查", store.logsText)
    }

    @Test
    fun `版本位是北京时间_缓存正常读出`() {
        seedLegacyCache(zoneVersion = AppZone.ID.id)
        val store = SettingsStore(ctx())

        val p = store.parsedCache
        assertNotNull("版本位与业务时区一致 → 缓存可用", p)
        assertEquals(1, p!!.events.size)
        assertEquals(1788393600000L, p.events.first().startTimeMs)
        assertEquals(7, store.coarseCountCache)
        assertEquals(1, store.photosCache.size)
        assertEquals(1, store.classifyCache?.eventPhotoMap?.size ?: -1)
    }

    @Test
    fun `clearStateCache 会同步刷新版本位_避免反复失效`() {
        seedLegacyCache(zoneVersion = null)
        val store = SettingsStore(ctx())
        store.clearStateCache()

        assertEquals("清空后版本位必须等于当前业务时区",
            AppZone.ID.id, prefs().getString("cache_zone", null))
        // 清空后再读仍然为空（不是因为版本位不匹配，而是本来就没数据）
        assertNull(store.parsedCache)
    }
}
