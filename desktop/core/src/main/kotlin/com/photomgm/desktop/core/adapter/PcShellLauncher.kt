// desktop/core/adapter/PcShellLauncher.kt —— 系统外壳动作（打开台账 / 在资源管理器定位）
//
// 这是核心唯一"对操作系统有副作用"的地方，隔离在 `app.port.ShellLauncher` 之后：
//   · 无头/测试环境注入 `ShellLauncher.NOOP`，核心逻辑照样可测；
//   · 未来若改用别的实现（如自绘启动器），只换这一个类。
//
// 注意：java.awt.Desktop 只在 java.desktop 模块存在时可用（jlink 裁剪运行时必须包含该模块）。
//
// ★ 端口的返回类型是三态（STARTED / UNAVAILABLE / NO_VIEWER），桌面端只会返回前两种：
//   AWT Desktop 只能告诉"我发起没发起"，不能告诉"系统里有没有能打开它的程序"
//   （那件事由 Windows 自己弹"打开方式"对话框）。硬造 NO_VIEWER 是撒谎 ——
//   用户会看到"没有能打开 xlsx 的程序"，而实际上只是双击时选错了默认程序。
package com.photomgm.desktop.core.adapter

import app.port.ShellLauncher
import app.port.ShellOutcome
import com.photomgm.desktop.core.platform.PcPaths
import java.awt.Desktop
import java.nio.file.Files
import java.nio.file.Path

internal class PcShellLauncher : ShellLauncher {

    override fun open(target: String): ShellOutcome {
        val path = pathOf(target) ?: return ShellOutcome.UNAVAILABLE
        if (!Files.exists(path)) return ShellOutcome.UNAVAILABLE
        val started = runCatching {
            if (!Desktop.isDesktopSupported()) return@runCatching false
            val desktop = Desktop.getDesktop()
            if (!desktop.isSupported(Desktop.Action.OPEN)) return@runCatching false
            desktop.open(path.toFile())
            true
        }.getOrDefault(false)
        return if (started) ShellOutcome.STARTED else ShellOutcome.UNAVAILABLE
    }

    override fun reveal(target: String): ShellOutcome {
        val path = pathOf(target) ?: return ShellOutcome.UNAVAILABLE
        if (!Files.exists(path)) return ShellOutcome.UNAVAILABLE
        val started = runCatching {
            val absolute = path.toAbsolutePath().toString()
            val cmd = if (Files.isDirectory(path)) {
                listOf("explorer.exe", absolute)
            } else {
                // explorer 的 /select 语法要求"文件名紧跟逗号，且整体作为一个参数"
                listOf("explorer.exe", "/select,$absolute")
            }
            ProcessBuilder(cmd).start()
            true
        }.getOrDefault(false)
        return if (started) ShellOutcome.STARTED else ShellOutcome.UNAVAILABLE
    }

    private fun pathOf(target: String): Path? =
        runCatching { PcPaths.toPath(target) }.getOrNull()
}
