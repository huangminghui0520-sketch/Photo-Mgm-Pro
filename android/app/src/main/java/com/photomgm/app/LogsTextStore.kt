// app/LogsTextStore.kt —— 日志文本的「内存态立即生效 + 落盘防抖」（W3 / issue #4）
//
// 为什么需要它
// ------------
// 日志输入框绑定 `onTextChange`，每敲一个字符就调一次 `setLogsText`。以前是同步写
// SharedPreferences：每次 `apply()` 都会**全量重写整个 XML**，而那个 XML 里还有 MB 级的
// 照片缓存 —— 等于每敲一个字符写一次 MB 级磁盘，输入明显卡顿、且持续磨损闪存。
//
// 两层修法（缺一不可）：
//   1. 存储隔离：日志文本搬到独立 prefs 文件（见 SettingsStore.logsText），
//      写它只重写「只有一个 key」的小文件；
//   2. 落盘防抖：本类把 N 次连续输入合并成 1 次落盘。
//
// 为什么不干脆丢掉输入（只留内存态）
// ----------------------------------
// 输入框的即时反馈必须来自内存态（否则受写盘延迟影响会丢字符），但**落盘不能省**：
// 进程随时可能被杀。所以这里保证「最后一次输入一定会落盘」——防抖窗口到期落一次，
// 页面离开 / ViewModel 销毁时由 [flush] 兜底。
//
// 可测试性
// --------
// 依赖全部外置（persist 回调、scope、debounceMs、dispatcher），不依赖 Android 上下文，
// 也不依赖 kotlinx-coroutines-test：测试用短 debounce + 计数回调即可断言「写了几次」。
package com.photomgm.app

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class LogsTextStore(
    /** 真正落盘的动作（生产传 `{ store.logsText = it }`；测试传计数回调）。 */
    private val persist: (String) -> Unit,
    /** 承载防抖协程的作用域（生产传 `viewModelScope`）。 */
    private val scope: CoroutineScope,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    companion object {
        /** 300~500ms 是输入停顿的自然区间：既能把连打合并，又不会让"停下后还等很久"。 */
        const val DEFAULT_DEBOUNCE_MS = 400L
    }

    /** ★ 待落盘文本与协程句柄用同一把锁保护：两处状态必须一起读写，否则会出现
     *   「新文本已就位、但旧协程先醒并把新文本写掉」或「pending 被读空后新值丢失」。 */
    private val lock = Any()
    private var pending: String? = null
    private var job: Job? = null

    /** 输入变化：内存态由调用方立即更新，这里只负责"稍后落盘最后一次值"。 */
    fun onChanged(text: String) {
        val prev = synchronized(lock) {
            pending = text
            job.also { job = null }
        }
        prev?.cancel()
        val j = scope.launch(dispatcher) {
            delay(debounceMs)
            flushPending()
        }
        synchronized(lock) { job = j }
    }

    /** 立即落盘（离开页面 / ViewModel 销毁前调用）。没有待落盘内容时是空操作。 */
    fun flush() {
        val j = synchronized(lock) { job.also { job = null } }
        j?.cancel()
        flushPending()
    }

    /**
     * 取消待落盘（"清空日志"用）：若不取消，用户点了清空后防抖窗口到期会把旧文本写回去，
     * 表现为"删了又回来"。
     */
    fun cancelPending() {
        synchronized(lock) {
            job?.cancel()
            job = null
            pending = null
        }
    }

    private fun flushPending() {
        val v = synchronized(lock) { pending.also { pending = null } } ?: return
        persist(v)
    }
}
