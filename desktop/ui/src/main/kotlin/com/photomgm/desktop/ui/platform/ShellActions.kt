// ui/platform/ShellActions.kt —— 界面侧的"在资源管理器中打开/定位"
//
// ★ 核心其实有 ShellLauncher（用于打开台账、定位导出目录），但它**不接收任意路径**：
//   核心只对自己产出的东西负责。源目录是用户随手选的，不该让核心持有它的生命周期，
//   所以源目录的"打开"由界面层直接用系统命令完成。
package com.photomgm.desktop.ui.platform

import java.io.File

object ShellActions {

    /** 在资源管理器中定位文件/文件夹（Windows 用 explorer /select）。 */
    fun reveal(path: String): Boolean = runCatching {
        val f = File(path)
        if (!f.exists()) return false
        val cmd = if (f.isDirectory) {
            arrayOf("explorer.exe", f.absolutePath)
        } else {
            arrayOf("explorer.exe", "/select,", f.absolutePath)
        }
        ProcessBuilder(*cmd).start()
        true
    }.getOrDefault(false)
}
