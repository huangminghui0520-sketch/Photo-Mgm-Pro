// ui/app/UiLog.kt —— 渲染/诊断日志
//
// ★ 为什么要一个统一出口：验收时我们要"用日志证明界面真的按这个宽度排版了"，
//   而中文 Windows 控制台是 GBK，重定向到文件必乱码（:cli 已经踩过）。
//   这里统一 UTF-8 落盘，stdout 只做辅助。
package com.photomgm.desktop.ui.app

import java.io.PrintWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

object UiLog {
    private var writer: PrintWriter? = null

    fun attach(file: Path?) {
        if (file == null) return
        runCatching {
            file.parent?.let { Files.createDirectories(it) }
            writer = PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8), true)
        }
    }

    fun line(message: String) {
        println("[UI] $message")
        writer?.println("[UI] $message")
    }

    fun close() {
        writer?.flush()
        writer?.close()
        writer = null
    }
}
