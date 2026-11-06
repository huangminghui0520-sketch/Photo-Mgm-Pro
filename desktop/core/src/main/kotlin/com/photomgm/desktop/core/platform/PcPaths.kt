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

    /** `D:/…` / `D:…` / `d:\…` 统一成 `D:/…`；盘符大小写保持不变（`key()` 会再小写）。 */
    private val DRIVE_RE = Regex("^([A-Za-z]):(.*)$", RegexOption.DOT_MATCHES_ALL)

    /**
     * 规范化引用：绝对路径 + 正斜杠 + 消解 `.`/`..` + 折叠重复分隔符。
     *
     * ★ 这是全模块的**唯一**路径口径，任何进入核心的路径都必须先过这里。
     *   幂等：normalize(normalize(x)) == normalize(x)。
     *
     * ★ 2026-11-06（方向 1 第二步）：改为**字符串级**实现，不再用 `Paths.get(raw)` 的
     *   `isAbsolute` / `toAbsolutePath()` / `normalize()`。原来那套是 `java.nio.file` 的
     *   **平台语义**：Windows 上 `D:\a\c\IMG.jpg` 是绝对路径、`\` 是分隔符；Linux 上
     *   两者都不成立，会被拼到 runner 工作目录后面 → `PcPathsTest` 首跑 CI 即 2 红。
     *   现在 Windows / Linux 对同一输入给出**逐字相同**的字符串，`sourceRef` 才能在
     *   `shared/algorithm/.../PhotoSource.kt:142` 的 `substringBeforeLast('/')` 上稳定分组。
     *
     * ★ 边界（C2 确认）：本函数的输出**同时**用于去重键与真实文件访问
     *   （[toPath] → `Files.readAllBytes` / `Files.newInputStream`）。因此三种绝对根
     *   （盘符 `D:/…`、UNC `//server/share/…`、POSIX `/…`）规范化后仍必须是
     *   `Paths.get()` 在**当前 OS 上可解析的绝对路径**；只在「规范化阶段」去掉平台语义，
     *   「文件访问阶段」仍交给 `Paths.get()`。
     *   相对路径无法脱离工作目录，故用 `user.dir` 的**字符串**显式拼接后递归一次，
     *   不经 `Paths.toAbsolutePath()`；这类输入只出现在测试与用户手输，生产侧
     *   `PcPhotoSourceProvider` 传进来的都是 `Path.toString()` 的绝对路径。
     */
    fun normalize(ref: String): String {
        val raw = ref.trim().replace('\\', '/')
        if (raw.isEmpty()) return ""

        // 1) 盘符：`D:\a` 已变 `D:/a`；`D:a`（Windows 盘符相对）补成 `D:/a`
        val s = DRIVE_RE.matchEntire(raw)?.let { m ->
            m.groupValues[1] + ":/" + m.groupValues[2].trimStart('/')
        } ?: raw

        // 2) 拆「根前缀」与「剩余段」。UNC 前两段是 authority，不参与 `..` 上溢。
        val root: String
        val rest: List<String>
        when {
            s.startsWith("//") && !s.startsWith("///") -> {
                val segs = s.drop(2).split('/').filter { it.isNotEmpty() }
                root = "//" + segs.take(2).joinToString("/")
                rest = segs.drop(2)
            }
            s.length >= 2 && s[0].isLetter() && s[1] == ':' -> {
                root = s.substring(0, 2)
                rest = s.substring(2).split('/').filter { it.isNotEmpty() }
            }
            s.startsWith("/") -> {
                root = ""
                rest = s.split('/').filter { it.isNotEmpty() }
            }
            else -> {
                val cwd = System.getProperty("user.dir").orEmpty().replace('\\', '/')
                return normalize("$cwd/$s")
            }
        }

        // 3) 栈消解 `.` / `..`；根目录上方一律忽略（与 `Path.normalize()` 行为一致）
        val stack = ArrayDeque<String>()
        for (seg in rest) {
            when (seg) {
                ".", "" -> Unit
                ".." -> if (stack.isNotEmpty()) stack.removeLast() else Unit
                else -> stack.addLast(seg)
            }
        }
        val tail = stack.joinToString("/")
        if (tail.isEmpty()) return if (root.isEmpty()) "/" else "$root/"
        return if (root.isEmpty()) "/$tail" else "$root/$tail"
    }

    /** 规范化路径 → Path（文件访问阶段仍由当前 OS 的 `Paths.get` 解析）。 */
    fun toPath(ref: String): Path = Paths.get(normalize(ref))

    /** 去重用的路径键（Windows 文件系统大小写不敏感，键统一小写）。 */
    fun key(ref: String): String = normalize(ref).lowercase()
}
