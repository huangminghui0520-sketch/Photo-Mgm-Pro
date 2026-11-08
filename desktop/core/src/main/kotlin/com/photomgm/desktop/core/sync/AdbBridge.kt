// desktop/core/sync/AdbBridge.kt —— 系统 adb 可执行文件的定位与调用（零新库）
//
// 获取顺序（裁定 F5 + Q4-B）：① 显式配置路径 → ② ANDROID_HOME → ③ ANDROID_SDK_ROOT →
// ④ PATH 上的 adb[.exe] → ⑤【仅 Windows】扫 C: 到 Z: 的 \Android\sdk\platform-tools\adb.exe →
// ⑥ 兜底裸 adb（交给系统解析，找不到时由 [requireExecutable] 抛带排查步骤的异常）。
// **不打包、不下载、不引入 adb 库**。用 ProcessBuilder（仓内已有先例：PcShellLauncher.kt:48、
// ShellActions.kt:21）。
//
// ⑤ 的存在理由：普通用户双击运行时 ANDROID_HOME 通常是空的、PATH 里也没有 platform-tools，
// 但 SDK 就装在 D:/Android/sdk 这类标准位置。只靠 ①②③ 会退化成裸 "adb" → 必然失败。
// macOS / Linux 的同类固定位置（/opt/homebrew/bin/adb、~/Android/Sdk/platform-tools 等）
// 暂不实现，留 TODO。
//
// 可用判据：`adb devices` 输出里至少有一行是「<序列号>\tdevice」；
// unauthorized / offline / 无输出都算不可用 → 由 PhoneConnection 自动降级到盘符方式。
//
// ★ 所有系统调用都经 [AdbCommand] 接口注入：单测可以完全离线验证解析顺序与降级，
//   不需要真机、不需要 adb、不需要网络。
//
// ★ 跨 ROM 读不一致（本轮修复）：`ls -l` 的列数不是协议，是各 ROM 的实现细节。
//   有 SELinux context 的机型多一列（`u:object_r:fuse:s0`），时间列还有 `HH:mm` 与
//   `HH:mm:ss` 两种写法；按固定下标取 token 会在这些机器上整列解析失败（表现为"手机里
//   明明有照片却列不出来"）。因此解析改为正则优先 + 从右往左的 token 兜底，
//   并在 [AdbPhoneSource.list] 里按**单次调用**统计解析成功率，认不全时退回纯文件名列表。
package com.photomgm.desktop.core.sync

import algorithm.AppZone
import com.photomgm.desktop.core.platform.PcPhotoSourceProvider
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

internal data class AdbOutput(val exitCode: Int, val stdout: String, val stderr: String)

// 一条 `ls -l` 行解析出的字段（**不判文件类型**，只看格式是否认得）。
// 与 [RemotePhoto] 分开的理由：解析成功率的统计要把目录行也算进分母，
// 否则一个含子目录的相册目录会被误判成"格式认不全"。
internal data class LsEntry(
    val isRegularFile: Boolean,
    val name: String,
    val size: Long,
    val modifiedMs: Long?,
)

internal fun interface AdbCommand {
    operator fun invoke(args: List<String>): AdbOutput
}

// 定位 adb 只需要三种环境能力：读环境变量、判断普通文件、判别平台。
// 抽成最小接口后，单测可以完全离线验证解析顺序（不需要真有 SDK、不必真的存在 C:~Z: 各盘）。
internal interface AdbEnv {
    fun getenv(name: String): String?
    fun isRegularFile(path: String): Boolean
    fun osName(): String
}

internal class SystemAdbEnv : AdbEnv {
    override fun getenv(name: String): String? = System.getenv(name)

    override fun isRegularFile(path: String): Boolean =
        runCatching { Files.isRegularFile(Paths.get(path)) }.getOrDefault(false)

    override fun osName(): String = System.getProperty("os.name").orEmpty()
}

// 真实进程调用；15 秒超时防止未授权设备把 UI 卡住。
// 两个流必须并发排空：先 waitFor 再读、或先读完 stdout 再读 stderr，
// 只要子进程把管道缓冲区写满就会互等死锁。这里用两个异步任务同时读，
// 超时则强杀进程并取消读取（ForkJoinPool 的线程是守护线程，不会拖住 JVM 退出）。
internal class ProcessAdbCommand : AdbCommand {
    override fun invoke(args: List<String>): AdbOutput = runCatching {
        val process = ProcessBuilder(args).redirectErrorStream(false).start()
        val stdout = CompletableFuture.supplyAsync {
            process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
        val stderr = CompletableFuture.supplyAsync {
            process.errorStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            stdout.cancel(true)
            stderr.cancel(true)
            return@runCatching AdbOutput(-1, "", "adb 命令超时")
        }
        AdbOutput(process.exitValue(), stdout.join(), stderr.join())
    }.getOrElse { AdbOutput(-1, "", it.message ?: it.javaClass.simpleName) }
}

internal class AdbBridge(
    private val explicitPath: String? = null,
    private val adbEnv: AdbEnv = SystemAdbEnv(),
    private val command: AdbCommand = ProcessAdbCommand(),
) {

    private val isWindows: Boolean = adbEnv.osName().contains("Windows", ignoreCase = true)
    private val exe: String = if (isWindows) "adb.exe" else "adb"
    private val pathSeparator: String = if (isWindows) "\\" else "/"

    // 按 ①~⑤ 顺序定位，全找不到返回 null（「找不到」是正常状态，不是异常）。
    fun resolve(): String? {
        if (!explicitPath.isNullOrBlank() && adbEnv.isRegularFile(explicitPath)) return explicitPath
        for (key in SDK_HOME_KEYS) {
            val home = adbEnv.getenv(key)?.takeIf { it.isNotBlank() } ?: continue
            val candidate = join(home, "platform-tools", exe)
            if (adbEnv.isRegularFile(candidate)) return candidate
        }
        findOnPath()?.let { return it }
        if (isWindows) findOnDriveRoots()?.let { return it }
        return null
    }

    // ⑥ 兜底：返回裸命令名交给系统 PATH 解析（保持既有语义，不保证存在）。
    fun executable(): String = resolve() ?: exe

    // 需要「找到」语义的调用方用这个：空手而归时抛一条带分步排查的异常，避免静默降级。
    fun requireExecutable(): String = resolve() ?: throw IllegalStateException(ADB_NOT_FOUND_HINT)

    // ④ PATH：按平台分隔符切开，去掉首尾空白与 Windows 习惯的引号包裹，再逐个拼 adb[.exe]。
    private fun findOnPath(): String? {
        val raw = adbEnv.getenv("PATH")?.takeIf { it.isNotBlank() } ?: return null
        val delimiter = if (isWindows) ';' else ':'
        return raw.split(delimiter)
            .asSequence()
            .map { it.trim().trim('"') }
            .filter { it.isNotEmpty() }
            .map { join(it, exe) }
            .firstOrNull { adbEnv.isRegularFile(it) }
    }

    // ⑤ Q4-B：只探标准相对位置，26 次 isRegularFile、不递归、不全盘搜。
    // 不用 Paths.get 拼路径：单测在 Linux runner 上也要能验证 Windows 分支，必须与宿主分隔符无关。
    private fun findOnDriveRoots(): String? {
        for (letter in 'C'..'Z') {
            val candidate = "${letter}:\\Android\\sdk\\platform-tools\\$exe"
            if (adbEnv.isRegularFile(candidate)) return candidate
        }
        return null
    }

    private fun join(base: String, vararg rest: String): String =
        (listOf(base.trimEnd('\\', '/')) + rest).joinToString(pathSeparator)

    fun devicesOutput(): AdbOutput = command(listOf(executable(), "devices"))

    // 至少一行状态恰为 device 才算可用。
    fun hasDevice(output: AdbOutput = devicesOutput()): Boolean =
        output.exitCode == 0 && output.stdout.lineSequence().any { DEVICE_LINE.matches(it.trimEnd()) }

    fun listRemote(dir: String): AdbOutput =
        command(listOf(executable(), "shell", "ls", "-l", dir))

    // 通用 shell 入口：`adb shell <args...>`，参数**原样**塞进 argv（不经宿主 shell）。
    // 两个用途：① `ls -d <dir>/*/` 数一层子目录（glob 由**设备侧** shell 展开）；
    //          ② 不带 `-l` 的 `ls` 只取文件名（`ls -l` 格式认不全时的回退）。
    // ★ 不经宿主 shell 展开也顺手避开了 MSYS/Git-Bash 把 `/sdcard/x` 改写成本机路径的老问题：
    //   ProcessBuilder 直接给 argv，参数不经过 bash 的路径转换（仓内既有调用同一姿势）。
    fun shellRaw(vararg args: String): AdbOutput =
        command(listOf(executable(), "shell") + args.toList())

    fun pull(remotePath: String, localTarget: Path): AdbOutput =
        command(listOf(executable(), "pull", remotePath, localTarget.toString()))

    // 一次 adb pull 拉多个源文件到同一目录：`pull [-a] REMOTE... LOCAL` 的 REMOTE 是变参。
    // 实测（V2232A / adb 37.0.1）：14 个源一次 678 ms，逐张 5616 ms（8.3×），
    // 单次调用固定开销约 300 ms —— 瓶颈是进程启动，不是带宽。
    // 注意：批内个别文件缺失时整批 exitCode 会是 1，但同批其余文件仍照拉；
    // 逐文件成败一律由调用方按「暂存区有没有这个文件」判定，不要看 exitCode。
    // 50 个源约 3 KB 命令行，远低于 Windows 32 KB 的 CreateProcess 上限。
    fun pullMultiple(remotePaths: List<String>, localDir: Path): AdbOutput =
        command(listOf(executable(), "pull") + remotePaths + localDir.toString())

    companion object {
        private val DEVICE_LINE = Regex("""^\S+\s+device\s*$""")
        private val SDK_HOME_KEYS = listOf("ANDROID_HOME", "ANDROID_SDK_ROOT")
        private val WHITESPACE = Regex("""\s+""")

        // 找不到 adb / 找不到设备共用这一条（Q3 合并、Q5-② 分步排查）：
        // 一句结论 + 三步排查，既不区分「未安装」与「未连接」，又让用户知道从哪查起。
        const val ADB_NOT_FOUND_HINT: String =
            "未检测到手机。请依次排查：① USB 线是否连接；" +
                "② 手机是否开启「USB 调试」并授权；" +
                "③ 是否已安装 Android SDK（或将 adb 加入 PATH）。"

        // 相机/相册目录的**兜底**清单：动态发现全空时用它，保证下拉不空。
        // 单一事实来源：DesktopCore.kt 的 ADB_CAMERA_DIRS 直接引用本常量，不另抄一份。
        val DEFAULT_CAMERA_DIRS: List<String> = listOf(
            "/sdcard/DCIM/Camera",
            "/sdcard/DCIM",
            "/sdcard/Pictures",
        )

        // 动态发现的第一梯队：先看标准挂载点。
        val DISCOVERY_ROOTS: List<String> = listOf("/sdcard/DCIM", "/sdcard/Pictures")

        // 兜底梯队（修复 5）：少数 ROM 上 /sdcard 指向别处或权限不同，但 /storage/emulated/0
        // 始终是内部存储的真实挂载点。只在第一梯队一张照片都没找到时才探。
        val EMULATED_FALLBACK_ROOTS: List<String> = listOf(
            "/storage/emulated/0/DCIM",
            "/storage/emulated/0/Pictures",
        )

        // ls -l 的"标准"形态（严格正则，优先尝试）：
        //   <类型><9 位权限>[ACL 标记] 链数 用户 组 [SELinux context] 大小 日期 时间 名字
        // 时间列 `HH:mm` 与 `HH:mm:ss` 都接受；context 那列是可选的（有它的 ROM 多一列）。
        private val LS_STRICT = Regex(
            """^([-dl])[rwxsStT-]{9}[.+@]?\s+(\d+)\s+(\S+)\s+(\S+)\s+(?:(\S+)\s+)?(\d+)\s+""" +
                """(\d{4}-\d{2}-\d{2})\s+(\d{2}:\d{2}(?::\d{2})?)\s+(.+)$""",
        )

        // 兜底用的形态判据：只认"看起来像"的字段，再按位置拼回去。
        private val LS_PERM = Regex("""^[-dl][rwxsStT-]{9}[.+@]?$""")
        private val LS_DATE = Regex("""^\d{4}-\d{2}-\d{2}$""")
        private val LS_TIME = Regex("""^\d{2}:\d{2}(?::\d{2})?$""")

        // 解析 `adb shell ls -l` 的一行；格式不认识就返回 null（不猜测、不误判）。
        // 只认普通文件（类型位 `-`）：目录行不是照片候选，交给调用方按需忽略。
        fun parseLsLine(line: String): RemotePhoto? {
            val entry = parseLsEntry(line) ?: return null
            if (!entry.isRegularFile) return null
            return RemotePhoto(name = entry.name, size = entry.size, modifiedMs = entry.modifiedMs)
        }

        // 不判类型、只判格式：解析成功率的分子分母都靠它（目录行也算"认出来了"）。
        fun parseLsEntry(line: String): LsEntry? {
            val text = line.trim()
            if (text.isEmpty()) return null
            if (text == "total" || text.startsWith("total ")) return null
            return parseLsEntryStrict(text) ?: parseLsEntryByTokens(text)
        }

        private fun parseLsEntryStrict(text: String): LsEntry? {
            val m = LS_STRICT.find(text) ?: return null
            val name = m.groupValues[9].trim()
            if (!isPlausibleName(name)) return null
            val size = m.groupValues[6].toLongOrNull() ?: return null
            return LsEntry(
                isRegularFile = m.groupValues[1] == "-",
                name = name,
                size = size,
                modifiedMs = parseLsTime(m.groupValues[7], m.groupValues[8]),
            )
        }

        // 严格正则失配时的兜底：**从右往左**认字段（名字 ← 时间 ← 日期 ← 大小），
        // 这样无论左边多了多少列（SELinux context、ACL、将来的新列）都不影响结果。
        private fun parseLsEntryByTokens(text: String): LsEntry? {
            val tokens = text.split(WHITESPACE)
            if (tokens.size < 8) return parseFailure(text)
            if (!LS_PERM.matches(tokens[0])) return parseFailure(text)
            val name = tokens.last()
            if (!isPlausibleName(name)) return parseFailure(text)
            val timeIdx = tokens.size - 2
            val dateIdx = tokens.size - 3
            val sizeIdx = dateIdx - 1
            if (sizeIdx < 1) return parseFailure(text)
            if (!LS_TIME.matches(tokens[timeIdx]) || !LS_DATE.matches(tokens[dateIdx])) {
                return parseFailure(text)
            }
            val size = tokens[sizeIdx].toLongOrNull() ?: return parseFailure(text)
            return LsEntry(
                isRegularFile = tokens[0][0] == '-',
                name = name,
                size = size,
                modifiedMs = parseLsTime(tokens[dateIdx], tokens[timeIdx]),
            )
        }

        // 认不出来就留一条诊断（与仓内既有的 `println("ADB-DIAG: …")` 同一姿势），返回 null。
        // 不抛异常：一行怪格式不该让整个目录列不出来。
        private fun parseFailure(line: String): LsEntry? {
            println("ADB-DIAG: parse fail: $line")
            return null
        }

        private fun isPlausibleName(name: String): Boolean =
            name.isNotBlank() && name != "." && name != ".."

        // 手机侧时间格式常见两种：`2026-11-04 23:55` 与 `2026-11-04 23:55:12`。
        private fun parseLsTime(date: String, time: String): Long? = runCatching {
            val text = "$date $time"
            val fmt = if (time.count { it == ':' } == 2) {
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            } else {
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            }
            LocalDateTime.parse(text, fmt).atZone(AppZone.ID).toInstant().toEpochMilli()
        }.getOrNull()
    }
}

// ADB 数据源：一次同步里 [PhoneSource] 的唯一实例，list 与 pull 都走同一台设备。
// remoteDir 作为 [id] 写进 sync-state.json，换目录即被 SyncStateGuard 判为冲突。
internal class AdbPhoneSource(
    private val bridge: AdbBridge,
    private val remoteDir: String,
) : PhoneSource {

    override val id: String = "adb:$remoteDir"
    override val kind: ConnectionKind = ConnectionKind.ADB

    // 列候选照片。
    // ★ 解析成功率按**单次调用**统计（每个目录独立判断）：`ls -l` 的输出里成功认出的行数
    //   不足可解析行数的一半时，说明这台 ROM 的列布局两种解析都吃不下，
    //   于是改用不带 `-l` 的 `ls` 只取文件名 —— 至少"能列出照片"，代价是拿不到大小与 mtime
    //   （见下方 [listNamesOnly] 的说明）。别的目录不受影响。
    override fun list(): List<RemotePhoto> {
        val out = bridge.listRemote(remoteDir)
        if (out.exitCode != 0) return emptyList()
        val photos = ArrayList<RemotePhoto>()
        var candidates = 0
        var matched = 0
        for (raw in out.stdout.lineSequence()) {
            val text = raw.trim()
            if (text.isEmpty()) continue
            if (text == "total" || text.startsWith("total ")) continue
            candidates++
            val entry = AdbBridge.parseLsEntry(raw) ?: continue
            matched++
            if (!entry.isRegularFile) continue
            if (!PcPhotoSourceProvider.isSupportedImage(entry.name)) continue
            photos += RemotePhoto(name = entry.name, size = entry.size, modifiedMs = entry.modifiedMs)
        }
        if (candidates > 0 && matched * 2 < candidates) {
            println("ADB-DIAG: ls -l 解析率 $matched/$candidates（$remoteDir），回退为纯文件名列表")
            return listNamesOnly()
        }
        return photos.sortedBy { it.name }
    }

    override fun pullTo(photo: RemotePhoto, targetFile: Path): Boolean {
        val remote = remoteDir.trimEnd('/') + "/" + photo.name
        val out = bridge.pull(remote, targetFile)
        return out.exitCode == 0 && runCatching { Files.isRegularFile(targetFile) }.getOrDefault(false)
    }

    // 覆盖成变参批拉：一次 adb 调用拉整批（省掉每张约 300 ms 的进程启动开销）。
    // 注意收的是**目录**：`adb pull REMOTE... LOCAL` 的 LOCAL 是多源共同的落点。
    override fun pullMany(photos: List<RemotePhoto>, stagingDir: Path): Boolean {
        if (photos.isEmpty()) return true
        val remotes = photos.map { remoteDir.trimEnd('/') + "/" + it.name }
        return bridge.pullMultiple(remotes, stagingDir).exitCode == 0
    }

    /**
     * 动态发现手机上的照片目录（**只看两层：根目录 + 一层子目录，绝不递归**）。
     *
     * 顺序：① `/sdcard/DCIM` 根（有照片才纳入）→ ② `/sdcard/DCIM` 的一层子目录（同上）
     *       → ③ `/sdcard/Pictures` 根 → ④ `/sdcard/Pictures` 的一层子目录。
     * 全空 → 兜底探 `/storage/emulated/0/{DCIM,Pictures}`（修复 5：`/sdcard` 在个别 ROM 上
     * 指向别处或权限不同，内部存储的真实挂载点始终是 `/storage/emulated/0`）。
     * 仍空 → 返回 [AdbBridge.DEFAULT_CAMERA_DIRS]（除非 [useFallback] = false）。
     *
     * 不递归的理由：`DCIM/.thumbnails`、`Pictures/.cache` 这类目录里也有图片，
     * 但它们不是用户要导入的原图；深度放开就会把缩略图当照片。
     *
     * @param useFallback false 时「一张都没找到」返回空列表，交给调用方区分
     *   「没找到」与「回退到了默认目录」（环境自检需要这个区分）。
     */
    internal fun discoverPhotoDirs(useFallback: Boolean = true): List<String> {
        val found = LinkedHashSet<String>()
        collectPhotoDirs(AdbBridge.DISCOVERY_ROOTS, found)
        if (found.isEmpty()) collectPhotoDirs(AdbBridge.EMULATED_FALLBACK_ROOTS, found)
        if (found.isNotEmpty()) return found.toList()
        return if (useFallback) AdbBridge.DEFAULT_CAMERA_DIRS else emptyList()
    }

    private fun collectPhotoDirs(roots: List<String>, into: MutableSet<String>) {
        for (root in roots) {
            if (root !in into && hasPhoto(root)) into += root
            for (sub in listSubDirs(root)) {
                if (sub !in into && hasPhoto(sub)) into += sub
            }
        }
    }

    // 目录里至少有一张**受支持的图片**才算「有照片」。
    // 用与 list() 同一个判据（而不是只数 *.jpg）：两边口径不一致时，
    // 会出现"发现了目录、同步时却一张都不认"的错位。
    private fun hasPhoto(dir: String): Boolean =
        listNames(dir).any { PcPhotoSourceProvider.isSupportedImage(it) }

    // 不带 `-l` 的 ls：一行一个文件名。失败（不存在 / 权限不足）一律当作「没有」。
    private fun listNames(dir: String): List<String> {
        val out = bridge.shellRaw("ls", dir)
        if (out.exitCode != 0) return emptyList()
        return out.stdout.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
    }

    // 一层子目录：`ls -d <dir>/*/`（glob 由设备侧 shell 展开，宿主不经手）。
    // 过滤隐藏目录（`.` 开头）与重复项；没有子目录时设备侧 exitCode != 0，按空处理。
    private fun listSubDirs(dir: String): List<String> {
        val out = bridge.shellRaw("ls", "-d", "$dir/*/")
        if (out.exitCode != 0) return emptyList()
        val prefix = dir.trimEnd('/') + "/"
        return out.stdout.lineSequence()
            .map { it.trim().trimEnd('/') }
            .filter { it.startsWith(prefix) && it != dir }
            .filter { path ->
                val leaf = path.substringAfterLast('/')
                leaf.isNotEmpty() && !leaf.startsWith(".")
            }
            .distinct()
            .toList()
    }

    // `ls -l` 认不全时的回退：只取文件名。
    // ★ 大小只能给 0L、mtime 只能给 null —— [RemotePhoto.size] 是非空 Long（定义在
    //   PhoneConnection.kt，本轮不在允许改动范围内），所以"大小未知"只能用 0 表达。
    //   影响：SyncDecider 先比大小后哈希，size=0 与真实大小几乎必然不等 →
    //   该目录下已存在的文件会被判为"大小不同"而重新拉取一次（见本轮修复报告"遗留风险"）。
    private fun listNamesOnly(): List<RemotePhoto> =
        listNames(remoteDir)
            .filter { it != "." && it != ".." }
            .filter { PcPhotoSourceProvider.isSupportedImage(it) }
            .distinct()
            .sorted()
            .map { RemotePhoto(name = it, size = 0L, modifiedMs = null) }
}
