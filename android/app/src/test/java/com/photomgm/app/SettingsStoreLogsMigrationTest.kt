// app/SettingsStoreLogsMigrationTest —— W3 / issue #4：日志文本迁到独立 prefs 文件
//
// 验收标准（issue #4）：「读侧能读出旧 KEY_LOGS 里已存的日志文本（升级不丢数据）」。
//
// 为什么必须独立文件：SharedPreferences 的 apply() 会**全量重写该文件的 XML**。
// 旧实现把日志文本和 MB 级照片缓存放在同一个 XML 里 → 输入框每敲一个字符写一次 MB。
// 隔离之后，写日志只重写「只有一个 key」的小文件。
package com.photomgm.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsStoreLogsMigrationTest {

    private fun ctx(): Context = ApplicationProvider.getApplicationContext()
    private fun mainPrefs() = ctx().getSharedPreferences("photo_mgm_settings", Context.MODE_PRIVATE)
    private fun logsPrefs() = ctx().getSharedPreferences("photo_mgm_logs", Context.MODE_PRIVATE)

    @Test
    fun `旧主prefs里的日志文本可读出_并迁移到独立文件后删除旧key`() {
        val legacy = "08时00分 出发巡查\n09时30分 处置完毕"
        mainPrefs().edit().putString("logs_text", legacy).commit()
        assertFalse("前置：新文件此时还没有日志", logsPrefs().contains("logs_text"))

        val store = SettingsStore(ctx())

        assertEquals("升级后必须读得出旧数据（不丢日志）", legacy, store.logsText)
        assertEquals("读的同时完成迁移", legacy, logsPrefs().getString("logs_text", null))
        assertFalse(
            "旧 key 必须从主 prefs 删除：否则「日志文本」会永远留在 MB 级 XML 里被反复全量重写",
            mainPrefs().contains("logs_text"),
        )
    }

    @Test
    fun `写入日志只落独立文件_主prefs里不再出现日志key`() {
        // 模拟主 prefs 里有大块照片缓存（真实场景就是它让主 XML 变成 MB 级）
        mainPrefs().edit().putString("cache_photos", "[{\"id\":1,\"sourceRef\":\"/a.jpg\"}]").commit()

        val store = SettingsStore(ctx())
        store.logsText = "新写入的日志"

        assertEquals("新写入的日志", logsPrefs().getString("logs_text", null))
        assertFalse(
            "主 prefs 不得再出现日志 key —— 这是「写日志不重写 MB 级 XML」的判定依据",
            mainPrefs().contains("logs_text"),
        )
        assertTrue("前置/旁证：主 prefs 里的照片缓存仍在（迁移没有误伤其他数据）",
            mainPrefs().contains("cache_photos"))
    }

    @Test
    fun `迁移只发生一次_第二次读取不再改动主prefs`() {
        mainPrefs().edit().putString("logs_text", "只该迁移一次的文本").commit()
        val store = SettingsStore(ctx())
        assertEquals("只该迁移一次的文本", store.logsText)

        // 迁移后主 prefs 已无旧 key；再读只能命中新文件
        assertFalse(mainPrefs().contains("logs_text"))
        assertEquals("只该迁移一次的文本", store.logsText)
        // 再写一次也不会把 key 写回主 prefs
        store.logsText = "改过的文本"
        assertFalse(mainPrefs().contains("logs_text"))
        assertEquals("改过的文本", logsPrefs().getString("logs_text", null))
    }
}
