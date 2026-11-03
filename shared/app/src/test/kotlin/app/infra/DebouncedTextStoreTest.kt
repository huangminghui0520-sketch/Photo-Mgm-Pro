// DebouncedTextStoreTest —— 防抖策略的三条不变量，逐条钉死（T05-C2）
//
// 测试手法：绝大多数用例用**手控调度器**（[FakeScheduler]）而不是真定时器——
// "窗口到期"变成一次显式调用，断言就不会随机器快慢抖动；只有最后一条用真实
// ScheduledExecutorService，证明端口接线在真实定时器下同样成立。
//
// ★ 这套用例的前身是安卓 app/LogsTextStoreTest（W3 / issue #4），断言语义一一对应；
//   上移后两端共用同一份策略，测一次即可覆盖两端。
package app.infra

import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DebouncedTextStoreTest {

    private val writes: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** 手控调度器：记录待执行动作，由用例显式触发"窗口到期"。 */
    private class FakeScheduler : DelayScheduler {
        var scheduleCount = 0
        var cancelCount = 0
        var lastDelayMs = -1L
        private var pending: (() -> Unit)? = null

        override fun schedule(delayMs: Long, action: () -> Unit): DelayHandle {
            scheduleCount++
            lastDelayMs = delayMs
            pending = action
            return DelayHandle {
                // 只撤销"自己那一次"：旧句柄迟到取消时不能把新动作一并抹掉
                if (pending === action) pending = null
                cancelCount++
            }
        }

        val hasPending: Boolean get() = pending != null

        fun fireDue() {
            val action = pending ?: return
            pending = null
            action()
        }
    }

    private fun storeWith(scheduler: DelayScheduler, debounceMs: Long = 400) =
        DebouncedTextStore(persist = { writes.add(it) }, scheduler = scheduler, debounceMs = debounceMs)

    // ── 不变量 1：窗口内多次输入只落盘一次，且是最后一次的值 ─────────────

    @Test
    fun `窗口内5次输入只落盘1次且内容是最后一次`() {
        val sch = FakeScheduler()
        val store = storeWith(sch)

        repeat(5) { store.onChanged("v$it") }

        assertEquals(0, writes.size, "窗口未到期时不得落盘（否则每敲一个字符写一次盘）")
        assertEquals(5, sch.scheduleCount, "每次输入都应重排调度")
        assertEquals(4, sch.cancelCount, "前 4 次调度应被后一次输入撤销")

        sch.fireDue()
        assertEquals(1, writes.size, "5 次输入必须合并成 1 次落盘")
        assertEquals("v4", writes[0], "落盘的必须是最后一次值，不能是中间值")

        sch.fireDue()
        assertEquals(1, writes.size, "已落盘后不得补写第二次")
    }

    @Test
    fun `单次输入在窗口到期后落盘_不丢数据`() {
        val sch = FakeScheduler()
        val store = storeWith(sch)

        store.onChanged("08时00分 出发巡查")
        assertTrue(sch.hasPending, "窗口内必须挂着一次待落盘")
        sch.fireDue()

        assertEquals(listOf("08时00分 出发巡查"), writes.toList())
    }

    // ── 不变量 2：flush 立即落盘，且之后不再补写 ─────────────────────────

    @Test
    fun `flush立即落盘_Close前兜底不丢最后一次输入`() {
        val sch = FakeScheduler()
        val store = storeWith(sch)

        repeat(5) { store.onChanged("v$it") }
        store.flush() // 不等窗口：核心 close / 切日志 / 开始分析前都会这样调用

        assertEquals(listOf("v4"), writes.toList(), "flush 必须同步生效（进程随时可能被杀）")
        assertFalse(sch.hasPending, "flush 后不得留下待执行动作")

        sch.fireDue()
        assertEquals(1, writes.size, "flush 之后窗口到期不得补写")
    }

    @Test
    fun `没有待落盘内容时flush是空操作`() {
        val store = storeWith(FakeScheduler())

        store.flush()

        assertTrue(writes.isEmpty(), "无待写内容时 flush 不得写盘")
    }

    // ── 不变量 3：cancelPending 之后绝不落盘 ────────────────────────────

    @Test
    fun `cancelPending之后窗口到期也不落盘_清空日志不能删了又回来`() {
        val sch = FakeScheduler()
        val store = storeWith(sch)

        store.onChanged("要被删掉的旧日志")
        store.cancelPending()

        assertFalse(sch.hasPending, "cancelPending 必须撤销待执行动作")
        sch.fireDue()
        assertTrue(writes.isEmpty(), "取消后不得落盘：否则用户点了清空，窗口到期又把旧文本写回去")
    }

    // ── 端口与常量 ──────────────────────────────────────────────────────

    @Test
    fun `防抖窗口按参数透传到调度器_默认400ms`() {
        val sch = FakeScheduler()
        storeWith(sch, debounceMs = 1234).onChanged("x")
        assertEquals(1234L, sch.lastDelayMs, "窗口时长必须透传，不能被实现写死")

        val sch2 = FakeScheduler()
        storeWith(sch2).onChanged("x")
        assertEquals(400L, sch2.lastDelayMs)
        assertEquals(400L, DebouncedTextStore.DEFAULT_DEBOUNCE_MS, "两端曾各写一份，现在是唯一的 400ms")
    }

    @Test
    fun `真实定时器下100次连打也只落盘1次`() {
        // 用手控调度器证明"策略对"，再用真定时器证明"接线对"——两者缺一都可能放过 bug。
        val pool = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "debounce-test").apply { isDaemon = true }
        }
        try {
            val store = DebouncedTextStore(
                persist = { writes.add(it) },
                scheduler = { delayMs, action ->
                    val f = pool.schedule(action, delayMs, TimeUnit.MILLISECONDS)
                    DelayHandle { f.cancel(false) }
                },
                debounceMs = 60,
            )

            repeat(100) { store.onChanged("line $it") }

            val deadline = System.currentTimeMillis() + 3000
            while (System.currentTimeMillis() < deadline && writes.isEmpty()) Thread.sleep(10)
            Thread.sleep(150) // 再等一个完整窗口，确认不会补写第二次

            assertEquals(1, writes.size, "100 次连打必须合并成 1 次落盘")
            assertEquals("line 99", writes[0], "落盘的必须是最后一次输入")
        } finally {
            pool.shutdownNow()
        }
    }
}
