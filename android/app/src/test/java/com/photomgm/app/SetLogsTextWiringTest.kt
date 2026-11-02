// app/SetLogsTextWiringTest —— W3 / issue #4：接线层面的判据
//
// LogsTextStoreTest 证明"防抖器本身是防抖的"，但它证明不了 AppViewModel **用上了**它：
// 如果有人把 setLogsText 改回同步写 `store.logsText = text`，那个测试依然是绿的
// （它只测类本身）。这里补上接线判据 —— 通过注入计数型防抖器，断言：
//   1. 内存态立即更新（输入框不能等写盘）；
//   2. 落盘**不**在输入时发生（必须等防抖窗口）；
//   3. 窗口到期后只落一次，且是最后一次输入的值。
package com.photomgm.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SetLogsTextWiringTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.coroutineContext[Job]?.cancel()
    }

    private fun app(): Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `setLogsText 必须走防抖器_不能同步写盘`() {
        val vm = AppViewModel(app())
        val writes: MutableList<String> = Collections.synchronizedList(mutableListOf())
        // 500ms 窗口足够宽：循环期间的任何机器抖动都不会让窗口提前到期 → 下面"尚未落盘"的断言不抖
        vm.logsTextStore = LogsTextStore(
            persist = { writes.add(it) },
            scope = scope,
            debounceMs = 500,
        )

        repeat(10) { vm.setLogsText("第 $it 次输入") }

        // ① 内存态必须立即更新：输入框与"立刻点解析"都读这里，不能等写盘
        assertEquals("第 9 次输入", vm.state.value.logsText)
        // ② 此刻不得落盘 —— 若 setLogsText 被改回同步写，这条会失败（writes 立刻非空）
        assertEquals("输入过程中不得写盘（否则每敲一个字符就重写 prefs）", 0, writes.size)

        // ③ 窗口到期后合并成一次，且是最后一次的值
        await { writes.isNotEmpty() }
        Thread.sleep(600)
        assertEquals("多次输入必须合并成 1 次落盘", 1, writes.size)
        assertEquals("第 9 次输入", writes[0])
    }

    @Test
    fun `clearLogs 取消待落盘_删掉的日志不会被写回`() {
        val vm = AppViewModel(app())
        val writes: MutableList<String> = Collections.synchronizedList(mutableListOf())
        vm.logsTextStore = LogsTextStore(
            persist = { writes.add(it) },
            scope = scope,
            debounceMs = 200,
        )

        vm.setLogsText("要被删掉的日志")
        vm.clearLogs()

        Thread.sleep(400)
        assertTrue("clearLogs 必须先取消待落盘，否则防抖到期会把旧文本写回去（删了又回来）", writes.isEmpty())
        assertEquals("", vm.state.value.logsText)
    }

    private fun await(timeoutMs: Long = 3000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(10)
        }
        fail("等待条件超时（${timeoutMs}ms）—— 说明 setLogsText 没有经过防抖器")
    }
}
