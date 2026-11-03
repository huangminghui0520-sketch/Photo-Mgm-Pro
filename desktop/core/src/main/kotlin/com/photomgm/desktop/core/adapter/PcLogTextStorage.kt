// desktop/core/adapter/PcLogTextStorage.kt —— 日志原文落盘（桌面侧适配器）
//
// ★ 2026-11-03（T05-C2）：防抖策略已上移到 :shared:app 的 DebouncedTextStore，
//   ★ 2026-11-03（T10）：连"谁来调度那次延迟"也不再由本文件决定 ——
//   本类现在只回答两个问题：怎么读、怎么写。
//     1. 怎么读：日志文件 → 字符串（[load]）
//     2. 怎么写：临时文件 + 原子改名（[persist]，避免半截文件）
//   "多久之后落盘、连打怎么合并、清空时怎么撤销"由共享策略层决定；
//   "用哪个线程池算这 400ms"由装配根（DesktopCore）提供。
//
// 直接对应 Android 端 W3 教训：日志输入框每敲一个字就写盘会卡顿；
// 而"防抖窗口内强杀进程"又会丢最后一次输入 —— 所以保留明确的 flush 出口
// （核心 close() / 开始分析前都会 flush，由共享层调）。
package com.photomgm.desktop.core.adapter

import app.port.LogTextStorage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

internal class PcLogTextStorage(private val file: Path) : LogTextStorage {

    override fun load(): String =
        runCatching { if (Files.isRegularFile(file)) Files.readString(file, Charsets.UTF_8) else "" }
            .getOrDefault("")

    /** 立即落盘。由共享策略层在**锁外**调用（写盘可能很慢）。 */
    override fun persist(text: String) {
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
