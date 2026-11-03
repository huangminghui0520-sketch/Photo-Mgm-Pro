// app/LogsTextStore.kt —— 日志文本的「内存态立即生效 + 落盘防抖」（W3 / issue #4）
//
// ★ 2026-11-03（T05-C2）：防抖**策略**已上移到 :shared:app 的 DebouncedTextStore，
//   本文件只剩平台适配：把"delayMs 之后执行一次"接到 viewModelScope + dispatcher 上。
//   构造函数保持原样，所以两个既有测试（LogsTextStoreTest / SetLogsTextWiringTest）
//   一行都不用改 —— 它们测的"防抖"现在由共享实现负责，断言意义不变。
//
// 为什么需要防抖（历史成因，保留以便理解为什么不能退回同步写）
// ------------------------------------------------------------
// 日志输入框绑定 `onTextChange`，每敲一个字符就调一次 `setLogsText`。以前是同步写
// SharedPreferences：每次 `apply()` 都会**全量重写整个 XML**，而那个 XML 里还有 MB 级的
// 照片缓存 —— 等于每敲一个字符写一次 MB 级磁盘，输入明显卡顿、且持续磨损闪存。
//
// 两层修法（缺一不可）：
//   1. 存储隔离：日志文本搬到独立 prefs 文件（见 SettingsStore.logsText），
//      写它只重写「只有一个 key」的小文件；
//   2. 落盘防抖：把 N 次连续输入合并成 1 次落盘（现在由共享策略统一实现）。
//
// 依赖全部外置（persist 回调、scope、debounceMs、dispatcher），不依赖 Android 上下文，
// 也不依赖 kotlinx-coroutines-test：测试用短 debounce + 计数回调即可断言「写了几次」。
package com.photomgm.app

import app.infra.DebouncedTextStore
import app.infra.DelayHandle
import app.infra.DelayScheduler
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class LogsTextStore(
    /** 真正落盘的动作（生产传 `{ store.logsText = it }`；测试传计数回调）。 */
    persist: (String) -> Unit,
    /** 承载防抖协程的作用域（生产传 `viewModelScope`）。 */
    scope: CoroutineScope,
    debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    companion object {
        /** 与共享实现同值（400ms）——这里只是转发，避免调用方跨模块引常量。 */
        const val DEFAULT_DEBOUNCE_MS = DebouncedTextStore.DEFAULT_DEBOUNCE_MS
    }

    private val store = DebouncedTextStore(
        persist = persist,
        scheduler = DelayScheduler { delayMs, action ->
            val job = scope.launch(dispatcher) {
                delay(delayMs)
                action()
            }
            DelayHandle { job.cancel() }
        },
        debounceMs = debounceMs,
    )

    /** 输入变化：内存态由调用方立即更新，这里只负责"稍后落盘最后一次值"。 */
    fun onChanged(text: String) = store.onChanged(text)

    /** 立即落盘（离开页面 / ViewModel 销毁前调用）。没有待落盘内容时是空操作。 */
    fun flush() = store.flush()

    /** 取消待落盘（"清空日志"用）：若不取消，用户点了清空后窗口到期会把旧文本写回去。 */
    fun cancelPending() = store.cancelPending()
}
