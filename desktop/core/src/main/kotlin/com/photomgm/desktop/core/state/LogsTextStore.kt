// desktop/core/state/LogsTextStore.kt —— 日志文本落盘（桌面侧**适配器**）
//
// ★ 2026-11-03（T05-C2）：防抖策略已上移到 :shared:app 的 DebouncedTextStore，
//   这里只剩两件平台的事：
//     1. 怎么读：日志文件 → 字符串（[load]）
//     2. 怎么写：临时文件 + 原子改名（[writeNow]，避免半截文件）
//   "多久之后落盘、连打怎么合并、清空时怎么撤销"不再由本文件决定 —— 那三件事两端
//   曾经各写一遍（桌面 ScheduledExecutorService / 安卓 coroutines delay），是 D11 的成因。
//
// 直接对应 Android 端 W3 教训：日志输入框每敲一个字就写盘会卡顿；
// 而"防抖窗口内强杀进程"又会丢最后一次输入 —— 所以保留明确的 [flush] 出口
// （核心 close()/切换日志/开始分析前都会 flush）。
package com.photomgm.desktop.core.state

import app.infra.DebouncedTextStore
import app.infra.DelayHandle
import app.infra.DelayScheduler
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

internal class LogsTextStore(
    private val file: Path,
    scheduler: ScheduledExecutorService,
    debounceMs: Long = DebouncedTextStore.DEFAULT_DEBOUNCE_MS,
) {
    private val store = DebouncedTextStore(
        persist = ::writeNow,
        scheduler = DelayScheduler { delayMs, action ->
            val future = scheduler.schedule(action, delayMs, TimeUnit.MILLISECONDS)
            DelayHandle { future.cancel(false) }
        },
        debounceMs = debounceMs,
    )

    fun load(): String =
        runCatching { if (Files.isRegularFile(file)) Files.readString(file, Charsets.UTF_8) else "" }
            .getOrDefault("")

    /** 文本变化：延迟落盘，连打时只写最后一次。 */
    fun onChanged(text: String) = store.onChanged(text)

    /** 立即落盘（幂等；无待写内容时是空操作）。 */
    fun flush() = store.flush()

    /** 取消待落盘（清空日志时必须先调，否则窗口到期会把刚删掉的旧文本写回去）。 */
    fun cancelPending() = store.cancelPending()

    private fun writeNow(text: String) {
        runCatching {
            Files.createDirectories(file.parent)
            val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
            Files.writeString(
                tmp, text, Charsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE,
            )
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
