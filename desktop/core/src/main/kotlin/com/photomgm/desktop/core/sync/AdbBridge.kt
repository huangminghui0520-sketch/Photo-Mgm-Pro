// desktop/core/sync/AdbBridge.kt —— 系统 adb 可执行文件的定位与调用（零新库）
//
// 获取方式（裁定 F5）：① 显式配置路径 → ② ANDROID_HOME / ANDROID_SDK_ROOT 下的
// platform-tools/adb[.exe] → ③ PATH 上的 adb。**不打包、不下载、不引入 adb 库**。
// 用 ProcessBuilder（仓内已有先例：PcShellLauncher.kt:48、ShellActions.kt:21）。
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
    private val env: (String) -> String? = { System.getenv(it) },
    private val isWindows: Boolean = System.getProperty("os.name").orEmpty()
        .contains("Windows", ignoreCase = true),
    private val fileExists: (String) -> Boolean = { p ->
        runCatching { Files.isRegularFile(Paths.get(p)) }.getOrDefault(false)
    },
    private val command: AdbCommand = ProcessAdbCommand(),
) {

    // 返回可执行文件路径；返回「adb」表示交给 PATH 解析（不保证存在）。
    fun executable(): String {
        if (!explicitPath.isNullOrBlank() && fileExists(explicitPath)) return explicitPath
        val exe = if (isWindows) "adb.exe" else "adb"
        for (key in listOf("ANDROID_HOME", "ANDROID_SDK_ROOT")) {
            val home = env(key)?.takeIf { it.isNotBlank() } ?: continue
            val candidate = Paths.get(home, "platform-tools", exe).toString()
            if (fileExists(candidate)) return candidate
        }
        return exe
    }

    fun devicesOutput(): AdbOutput = command(listOf(executable(), "devices"))

    // 至少一行状态恰为 device 才算可用。
    fun hasDevice(output: AdbOutput = devicesOutput()): Boolean =
        output.exitCode == 0 && output.stdout.lineSequence().any { DEVICE_LINE.matches(it.trimEnd()) }

    fun listRemote(dir: String): AdbOutput =
        command(listOf(executable(), "shell", "ls", "-l", dir))

    fun pull(remotePath: String, localTarget: Path): AdbOutput =
        command(listOf(executable(), "pull", remotePath, localTarget.toString()))

    companion object {
        private val DEVICE_LINE = Regex("""^\S+\s+device\s*$""")

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

    override fun pullTo(photo: RemotePhoto, staging: Path): Boolean {
        val remote = remoteDir.trimEnd('/') + "/" + photo.name
        val out = bridge.pull(remote, staging)
        return out.exitCode == 0 && runCatching { Files.isRegularFile(staging) }.getOrDefault(false)
    }
}
