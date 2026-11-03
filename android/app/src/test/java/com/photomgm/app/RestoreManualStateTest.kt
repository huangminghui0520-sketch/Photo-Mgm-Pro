// app/RestoreManualStateTest —— 12 项真机清单第 ⑪ 项的自动化护栏
//
// 背景（2026-11-03 真机实测抓到）：
//   T11 第一提交把「启动恢复」从 `AppViewModel.init` 搬进 `AndroidCore.restoreFromCache()`，
//   搬运时**漏掉了人工调整（overrideMap / markedMap）**这一段。
//   现象：杀进程重开后，事件列表回来了、照片数也回来了，但人工移动全部失效、
//   界面回到自动分类；台账标记的角标也不见了。
//   更隐蔽的是 prefs 里 `photo_override` / `photo_marked` **其实还在** ——
//   数据没丢，只是启动时从不读回，属于"静默丢用户操作"。
//
// 为什么既有测试没拦住：
//   `ViewDerivationParityTest` 只验"同一会话内 movePhoto 后派生结果对不对"，
//   `SettingsStore` 的用例只验"写进去了没有"，**没有任何用例跨越"进程重启"这条边界**。
//   本文件补的就是这条边界 —— 断言的是"新建 core 实例后，上一次会话落盘的人工调整必须还在"。
package com.photomgm.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.photomgm.app.core.AndroidCore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RestoreManualStateTest {

    private fun ctx(): Context = ApplicationProvider.getApplicationContext()

    /** 与生产同款取值：`AndroidCore(app) { logsTextStore.delegate }`（取值函数，非实例）。 */
    private fun logs() = LogsTextStore({}, CoroutineScope(Dispatchers.Unconfined)).delegate

    @Test
    fun `重启后人工移动与台账标记必须恢复`() {
        // 上一轮会话留下的两笔人工调整（实际是「批量移动到事件 3」+「把某张设为台账照片1」）
        SettingsStore(ctx()).apply {
            overrideMap = mapOf(1000011119L to 3, 1000011121L to 3)
            markedMap = mapOf(3 to (1000011122L to null))
        }

        // 模拟冷启动：全新 core 实例，只读持久层
        val core = AndroidCore(ctx()) { logs() }

        assertEquals(
            "人工移动必须随进程重启恢复；为空则界面回到自动分类，用户会以为自己的调整白做了",
            mapOf(1000011119L to 3, 1000011121L to 3),
            core.session.manual.overrideMap,
        )
        assertEquals(
            "台账标记同理：丢了会让导出选错照片，而界面上完全看不出来",
            mapOf(3 to (1000011122L to null)),
            core.session.manual.marked,
        )
    }

    @Test
    fun `没有分析缓存时人工调整也要载入`() {
        // 边界：parsedCache 为空（全新安装后只做过移动？或缓存被清），
        // restoreFromCache 会 early-return —— 人工调整的载入**不能**排在这个 return 之后，
        // 否则下次解析日志后，上一轮的移动仍然不生效。
        SettingsStore(ctx()).overrideMap = mapOf(42L to 7)

        val core = AndroidCore(ctx()) { logs() }

        assertEquals(mapOf(42L to 7), core.session.manual.overrideMap)
    }

    @Test
    fun `没有任何人工调整时恢复为空而不是抛异常`() {
        val core = AndroidCore(ctx()) { logs() }

        assertEquals(emptyMap<Long, Int>(), core.session.manual.overrideMap)
        assertEquals(emptyMap<Int, Pair<Long?, Long?>>(), core.session.manual.marked)
    }
}
