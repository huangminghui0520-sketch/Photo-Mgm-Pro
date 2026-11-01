// ui/platform/NativeDialogs.kt —— 系统原生选择对话框
//
// ★ 为什么允许 :ui 直接碰 AWT：这是**界面**职责。核心（:core）不弹窗、不读输入，
//   它只接收 Path；选择路径这件事必须由界面层完成，否则核心就要依赖 Desktop API，
//   在无头测试环境里直接炸。
//
// 目录选择用 JFileChooser（Windows 的 FileDialog 不支持选目录），
// 文件选择用 FileDialog（原生「打开」对话框，带最近位置与类型过滤，比 JFileChooser 顺手）。
package com.photomgm.desktop.ui.platform

import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser
import javax.swing.UIManager

object NativeDialogs {

    /** 选择目录；返回规范化的绝对路径（正斜杠，与 core 的路径口径一致）。 */
    fun pickDirectory(title: String, initial: String? = null): String? {
        runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
        val chooser = JFileChooser().apply {
            dialogTitle = title
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            isAcceptAllFileFilterUsed = false
            initial?.let { raw ->
                val f = File(raw)
                if (f.isDirectory) currentDirectory = f else f.parentFile?.let { currentDirectory = it }
            }
        }
        if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return null
        val picked = chooser.selectedFile ?: return null
        return normalize(picked.absolutePath)
    }

    /** 选择日志文件（.txt）。返回原始绝对路径。 */
    fun pickLogFile(title: String = "选择巡查日志（.txt）", initial: String? = null): String? {
        val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD).apply {
            setFilenameFilter { _, name -> name.endsWith(".txt", ignoreCase = true) }
            file = "*.txt"
            initial?.let { raw ->
                val f = File(raw)
                if (f.isDirectory) directory = f.absolutePath else f.parentFile?.let { directory = it.absolutePath }
            }
        }
        dialog.isVisible = true
        val dir = dialog.directory ?: return null
        val name = dialog.file ?: return null
        return normalize(File(dir, name).absolutePath)
    }

    private fun normalize(path: String): String = path.replace('\\', '/')
}
