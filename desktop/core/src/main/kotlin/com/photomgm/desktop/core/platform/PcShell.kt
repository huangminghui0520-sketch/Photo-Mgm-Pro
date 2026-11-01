// desktop/core/platform/PcShell.kt —— 系统外壳动作（打开台账 / 在资源管理器定位）
//
// 这是核心唯一"对操作系统有副作用"的地方，隔离在 ShellLauncher 接口之后：
//   · 无头/测试环境注入 ShellLauncher.NOOP，核心逻辑照样可测；
//   · 未来若改用别的实现（如自绘启动器），只换这一个类。
// 注意：java.awt.Desktop 只在 java.desktop 模块存在时可用（jlink 裁剪运行时必须包含该模块）。
package com.photomgm.desktop.core.platform

import com.photomgm.desktop.core.api.ShellLauncher
import java.awt.Desktop
import java.nio.file.Files
import java.nio.file.Path

class PcShell : ShellLauncher {

    override fun open(path: Path): Boolean {
        if (!Files.exists(path)) return false
        return runCatching {
            if (!Desktop.isDesktopSupported()) return false
            val desktop = Desktop.getDesktop()
            if (!desktop.isSupported(Desktop.Action.OPEN)) return false
            desktop.open(path.toFile())
            true
        }.getOrDefault(false)
    }

    override fun reveal(path: Path): Boolean = runCatching {
        if (!Files.exists(path)) return false
        val target = path.toAbsolutePath().toString()
        val cmd = if (Files.isDirectory(path)) {
            listOf("explorer.exe", target)
        } else {
            // explorer 的 /select 语法要求"文件名紧跟逗号，且整体作为一个参数"
            listOf("explorer.exe", "/select,$target")
        }
        ProcessBuilder(cmd).start()
        true
    }.getOrDefault(false)
}
