// desktop/core/state/LogsTextStore.kt —— 巡查日志文本落盘（含防抖）
//
// ★ 直接对应 Android 端 W3 教训：日志输入框每敲一个字就写盘会卡顿；
//   而"防抖窗口内强杀进程"又会丢最后一次输入。
//   桌面端的解法：防抖写 + 明确的 [flush] 出口（核心 close()/切换日志/开始分析前都会 flush）。
package com.photomgm.desktop.core.state

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class LogsTextStore(
    private val file: Path,
    private val scheduler: ScheduledExecutorService,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
) {
    private val lock = Any()
    private var pending: ScheduledFuture<*>? = null
    private var latest: String? = null

    fun load(): String =
        runCatching { if (Files.isRegularFile(file)) Files.readString(file, Charsets.UTF_8) else "" }
            .getOrDefault("")

    /** 文本变化：延迟落盘，连打时只写最后一次。 */
    fun onChanged(text: String) {
        synchronized(lock) {
            latest = text
            pending?.cancel(false)
            pending = scheduler.schedule({ flush() }, debounceMs, TimeUnit.MILLISECONDS)
        }
    }

    /** 立即落盘（幂等；无待写内容时是空操作）。 */
    fun flush() {
        val text: String
        synchronized(lock) {
            text = latest ?: return
            latest = null
            pending?.cancel(false)
            pending = null
        }
        writeNow(text)
    }

    /** 取消待落盘（清空日志时必须先调，否则窗口到期会把刚删掉的旧文本写回去）。 */
    fun cancelPending() {
        synchronized(lock) {
            latest = null
            pending?.cancel(false)
            pending = null
        }
    }

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

    companion object {
        /** 与 Android 端 LogsTextStore.DEFAULT_DEBOUNCE_MS 同值（400ms）。 */
        const val DEFAULT_DEBOUNCE_MS = 400L
    }
}
