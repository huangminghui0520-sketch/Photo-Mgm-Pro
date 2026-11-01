// desktop/core/platform/PcPaths.kt —— Windows 路径与数据目录约定
//
// ★ 核心职责之一：**路径规范化**。原算法模块 PhotoIngest.dedupDerived 用
//   `sourceRef.substringBeforeLast('/')` 取所在目录作为分组键；Windows 路径是反斜杠时
//   每个文件都会得到互不相同的"目录"键 → 主图/_original 派生去重全部失效（静默丢照片的镜像问题：
//   该合并的没合并，导出里出现重复照片）。
//   桌面端的解法（不改原工程任何代码）：**所有 sourceRef 在进入核心前统一规范化为
//   「绝对路径 + 正斜杠 + 已消解 . / ..」**，全链路只用这一种写法。
package com.photomgm.desktop.core.platform

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

internal object PcPaths {

    private const val APP_FOLDER = "PhotoMgm"

    /** `%APPDATA%\PhotoMgm`（设置、日志文本）。非 Windows 环境退化到 `~/.config/PhotoMgm`。 */
    fun appDataDir(): Path {
        val appData = System.getenv("APPDATA")
        return if (!appData.isNullOrBlank()) Paths.get(appData, APP_FOLDER)
        else Paths.get(System.getProperty("user.home"), ".config", APP_FOLDER)
    }

    /** `%LOCALAPPDATA%\PhotoMgm`（缓存、运行日志）。 */
    fun localAppDataDir(): Path {
        val local = System.getenv("LOCALAPPDATA")
        return if (!local.isNullOrBlank()) Paths.get(local, APP_FOLDER)
        else Paths.get(System.getProperty("user.home"), ".cache", APP_FOLDER)
    }

    /** 默认导出父目录：`%USERPROFILE%\Documents\照片分类导出`。 */
    fun defaultExportDir(): Path {
        val home = System.getProperty("user.home")
        val docs = Paths.get(home, "Documents")
        return if (Files.isDirectory(docs)) docs.resolve("照片分类导出")
        else Paths.get(home, "照片分类导出")
    }

    /**
     * 规范化引用：绝对路径 + 正斜杠 + 消解 `.`/`..`。
     *
     * ★ 这是全模块的**唯一**路径口径，任何进入核心的路径都必须先过这里。
     *   幂等：normalize(normalize(x)) == normalize(x)。
     */
    fun normalize(ref: String): String {
        val raw = ref.trim()
        if (raw.isEmpty()) return ""
        val p = Paths.get(raw)
        val abs = if (p.isAbsolute) p else p.toAbsolutePath()
        return abs.normalize().toString().replace('\\', '/')
    }

    /** 规范化路径 → Path。 */
    fun toPath(ref: String): Path = Paths.get(normalize(ref))

    /** 去重用的路径键（Windows 文件系统大小写不敏感，键统一小写）。 */
    fun key(ref: String): String = normalize(ref).lowercase()
}
