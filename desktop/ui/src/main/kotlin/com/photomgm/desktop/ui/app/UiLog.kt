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

    /**
     * 是否输出诊断日志。默认 false：正常双击运行（args 为空）时 [line] 是空操作，
     * 既不刷控制台也不写文件 —— AppShell 的渲染日志每帧都调 [line]，若默认开，
     * 窗口缩放/悬停切换会高频阻塞 UI 线程（尤其 println 在中文 Windows 控制台是慢的）。
     *
     * 只在「诊断/走查模式」打开（--shot / --smoke / --demo / --log-file 任一），
     * 由 Main 启动时调用 [enable] 一次性决定。
     */
    private var enabled = false

    val isEnabled: Boolean get() = enabled

    fun enable(on: Boolean) {
        enabled = on
    }

    fun attach(file: Path?) {
        if (file == null) return
        runCatching {
            file.parent?.let { Files.createDirectories(it) }
            writer = PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8), true)
        }
    }

    fun line(message: String) {
        if (!enabled) return
        println("[UI] $message")
        writer?.println("[UI] $message")
    }

    fun close() {
        writer?.flush()
        writer?.close()
        writer = null
    }
}
