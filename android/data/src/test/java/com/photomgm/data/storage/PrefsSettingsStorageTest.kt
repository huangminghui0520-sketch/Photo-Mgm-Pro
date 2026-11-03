// data/storage/PrefsSettingsStorageTest.kt —— T06 新增：字符串编码规则的往返护栏
//
// 为什么需要这组测试：`overrideMap` / `markedMap` 的编解码从 `:app` 搬到了 `:data`，
// 而搬动前**没有任何测试直接覆盖它们**（`SettingsStoreZoneMigrationTest` 只顺带断言了
// `42=1` 能读回来）。编码规则是纯字符串拼接，写错一个分隔符不会编译失败，
// 只会让"人工移动"或"台账标记"在用户升级后静默丢失 —— 这是本项目最贵的一类 bug。
//
// 其中一条是 **characterization 测试**（`单个坏条目会让整张表失效`）：
// 它记录的是既有缺陷，不是期望行为。T06 是归位任务，不在此处改语义；
// 但把它写成可执行断言，等于给"将来要修的人"留下确切的行为基线。
package com.photomgm.data.storage

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrefsSettingsStorageTest {

    private fun storage(): PrefsSettingsStorage =
        PrefsSettingsStorage(ApplicationProvider.getApplicationContext<Application>())

    /** 每次新建实例，确保读的是 prefs 里的真实内容，而不是某个字段的内存残留。 */
    private fun roundTrip(block: (PrefsSettingsStorage) -> Unit): PrefsSettingsStorage =
        storage().also(block)

    @Test
    fun `overrideMap 往返_多条目与负数目标都能原样读回`() {
        roundTrip { it.overrideMap = mapOf(42L to 1, 43L to -1, 900L to 12) }

        assertEquals(
            "人工移动的键是 MediaStore _ID，值是事件 id；-1 表示「移出所有事件」，必须能存能读",
            mapOf(42L to 1, 43L to -1, 900L to 12),
            storage().overrideMap,
        )
    }

    @Test
    fun `overrideMap 单个坏条目会让整张表失效_记录既有行为`() {
        // 手工写入一条"值不是数字"的条目，模拟存储被写坏 / 旧版本格式变更。
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        ctx.getSharedPreferences("photo_mgm_settings", 0)
            .edit().putString("photo_override", "42=1;43=x;44=2").commit()

        val got = storage().overrideMap

        // ★ 这是**既有缺陷**的行为基线，不是期望语义：解码整体包在一个 runCatching 里，
        //   一条坏数据把 42 与 44 两条好数据一起带走了。修法（逐条容错）属于行为变更，
        //   需要单独提交 + CHANGELOG，不在 T06 归位任务里做。
        assertTrue(
            "现状：一条坏条目 → 整张人工移动表归零。若本条失败，说明有人改成了逐条容错，" +
                "那是好事，但必须同步更新本测试与 CHANGELOG",
            got.isEmpty(),
        )
    }

    @Test
    fun `markedMap 往返_空侧解成null而不是0`() {
        roundTrip { it.markedMap = mapOf(1 to (42L to null), 2 to (null to 88L), 3 to (7L to 9L)) }

        val got = storage().markedMap
        assertEquals("台账标记的事件数", 3, got.size)
        assertEquals("照片1 有值、照片2 空", 42L to null, got[1])
        assertEquals("照片1 空、照片2 有值", null to 88L, got[2])
        assertEquals("两侧都有值", 7L to 9L, got[3])
        // 为什么单独断言 null：编码里空侧写成空串，若解码用 toLongOrNull 之外的方式（如 toLong），
        // 空串会变成 0L —— 0 是一个语法合法、实际不存在的光片 id，界面会显示成"已选了一张空图"。
        assertNull("空侧必须是 null，不能是 0L", got[1]?.second)
    }

    @Test
    fun `namingTemplate 未设置时是null_设置后能读回`() {
        assertNull("未设置 = null，由调用方回落到默认档", storage().namingTemplate)

        roundTrip { it.namingTemplate = 3 }
        assertEquals(3, storage().namingTemplate)
    }

    @Test
    fun `清除派生缓存不动用户数据`() {
        roundTrip {
            it.dateText = "2026-09-03"
            it.overrideMap = mapOf(42L to 1)
            it.markedMap = mapOf(1 to (42L to null))
            it.parsedLogsText = "08时00分 出发巡查"
            it.parsedCacheRaw = "{}"
            it.coarseCount = 7
        }

        storage().clearDerivedCaches()

        val after = storage()
        assertNull("派生缓存清空", after.parsedCacheRaw)
        assertNull("粗筛数一并清空，让流水线重扫", after.coarseCount)
        assertEquals("日志快照也是派生数据，随缓存一起清", "", after.parsedLogsText)
        assertEquals("用户数据：日期必须保留", "2026-09-03", after.dateText)
        assertEquals("用户数据：人工移动必须保留", mapOf(42L to 1), after.overrideMap)
        assertEquals("用户数据：台账标记必须保留", 42L to null, after.markedMap[1])
    }
}
