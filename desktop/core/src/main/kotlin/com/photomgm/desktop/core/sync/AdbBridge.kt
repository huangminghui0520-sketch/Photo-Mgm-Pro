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

        // 找不到 adb / 找不到设备共用这一条（Q3 合并、Q5-② 分步排查）：
        // 一句结论 + 三步排查，既不区分「未安装」与「未连接」，又让用户知道从哪查起。
        const val ADB_NOT_FOUND_HINT: String =
            "未检测到手机。请依次排查：① USB 线是否连接；" +
                "② 手机是否开启「USB 调试」并授权；" +
                "③ 是否已安装 Android SDK（或将 adb 加入 PATH）。"

        // 解析 `adb shell ls -l` 的一行；格式不认识就返回 null（不猜测、不误判）。
        fun parseLsLine(line: String): RemotePhoto? {
            val tokens = line.trim().split(Regex("""\s+"""))
            if (tokens.size < 7 || !tokens[0].startsWith("-")) return null
            val size = tokens[4].toLongOrNull() ?: return null
            val name = tokens.last()
            if (name.isBlank() || name == "." || name == "..") return null
            val modifiedMs = parseLsTime(tokens[5], tokens[6])
            return RemotePhoto(name = name, size = size, modifiedMs = modifiedMs)
        }

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

    override fun list(): List<RemotePhoto> {
        val out = bridge.listRemote(remoteDir)
        if (out.exitCode != 0) return emptyList()
        return out.stdout.lineSequence()
            .mapNotNull(AdbBridge::parseLsLine)
            .filter { PcPhotoSourceProvider.isSupportedImage(it.name) }
            .sortedBy { it.name }
            .toList()
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
}
