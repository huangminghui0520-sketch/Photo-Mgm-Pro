// app/LogsTextStoreTest —— W3 / issue #4：日志文本落盘必须防抖，且最后一次输入不能丢
//
// 验收标准（issue #4）：「连续 N 次 setLogsText，落盘次数远小于 N」。
// 这里直接对落盘回调计数，不依赖 Android，也不依赖 kotlinx-coroutines-test
// （debounceMs 可注入，用短窗口 + 轮询即可确定性断言）。
package com.photomgm.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Collections

class LogsTextStoreTest {

    private val writes: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = LogsTextStore(
        persist = { writes.add(it) },
        scope = scope,
        debounceMs = 80,
    )

    @After
    fun tearDown() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    private fun await(timeoutMs: Long = 3000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(10)
        }
        fail("等待条件超时（${timeoutMs}ms）")
    }

    @Test
    fun `连续50次输入只落盘1次_且内容是最后一次`() {
        repeat(50) { store.onChanged("line $it") }

        await { writes.isNotEmpty() }
        // 再等一个完整防抖窗口，确认不会补写第二次
        Thread.sleep(200)

        assertEquals("50 次输入必须合并成 1 次落盘（否则每敲一个字符就全量重写 prefs）",
            1, writes.size)
        assertEquals("落盘的必须是最后一次输入，不能是中间值", "line 49", writes[0])
    }

    @Test
    fun `单次输入最终会落盘_不丢数据`() {
        store.onChanged("08时00分 出发巡查")
        await { writes.size == 1 }
        assertEquals("08时00分 出发巡查", writes[0])
    }

    @Test
    fun `flush 立即落盘且不再重复落盘`() {
        repeat(5) { store.onChanged("v$it") }
        store.flush()   // 不等防抖窗口，直接刷

        assertEquals("flush 必须是同步生效的（页面离开/进程可能被杀）", listOf("v4"), writes.toList())
        Thread.sleep(200)
        assertEquals("flush 之后待落盘已被清空，不得再写第二次", 1, writes.size)
    }

    @Test
    fun `cancelPending 之后不落盘_对应清空日志不能删了又回来`() {
        store.onChanged("要被删掉的旧日志")
        store.cancelPending()

        Thread.sleep(200)
        assertTrue("取消后不得落盘：否则用户点了清空，防抖到期又把旧文本写回去", writes.isEmpty())
    }
}
