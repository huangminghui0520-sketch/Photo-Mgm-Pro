// desktop/core/sync/AdbBridge.kt —— 系统 adb 可执行文件的定位与调用（零新库）
//
// adb 来源：随包优先（先读 jpackage 注入的 compose.application.resources.dir，主落点 tools/platform-tools/adb.exe），属性不可用再走 detectAppDir 三层探测，最后兜底 ANDROID_HOME / ANDROID_SDK_ROOT / PATH / 盘符扫描；随包命中先校验 SHA256，失败打印告警并走兜底；随包 adb 由 appResourcesRootDir 注入产物，不入系统 PATH，portable 与 MSI 共用这条链路。
// 随包命中即返回，不再往下探测（避免 PATH 里的旧版 adb 抢先）；全找不到时 [resolve] 返回 null，
// [executable] 才退回裸 adb 名交给系统解析，[requireExecutable] 则抛带排查步骤的异常。
// 用 ProcessBuilder（仓内已有先例：PcShellLauncher.kt:48、ShellActions.kt:21）。
//
// 盘符扫描的存在理由：普通用户双击运行时 ANDROID_HOME 通常是空的、PATH 里也没有 platform-tools，
// 但 SDK 就装在 D:/Android/sdk 这类标准位置。只靠环境变量会退化成裸 "adb" → 必然失败。
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
import domain.hash.Sha256
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

// `adb devices` 的一行：序列号 + 状态（device / unauthorized / offline…）。
// 单独抽出来是因为环境自检要把 unauthorized 与「压根没连」区分开：两种情况的提示语不同。
internal data class AdbDevice(val serial: String, val state: String)

internal fun interface AdbCommand {
    operator fun invoke(args: List<String>): AdbOutput
}

// 定位 adb 只需要五种环境能力：读环境变量、判断普通文件、判别平台、读系统属性、找随包 adb。
// 抽成最小接口后，单测可以完全离线验证解析顺序（不需要真有 SDK、不必真的存在 C:~Z: 各盘）。
internal interface AdbEnv {
    fun getenv(name: String): String?
    fun isRegularFile(path: String): Boolean
    fun osName(): String

    /**
     * 读取 JVM 系统属性；默认 null = 「没有该属性」。
     *
     * 带默认实现，单测的 FakeEnv 零改动即完全离线；生产由 [SystemAdbEnv] 覆写，读 jpackage
     * 在 PhotoMgm.cfg 里注入的 `compose.application.resources.dir`，作为随包资源的权威路径。
     */
    fun systemProperty(name: String): String? = null

    /**
     * 随包 adb 的完整路径；找不到返回 null。
     *
     * 默认返回 null = 「没有随包 adb」：单测的 FakeEnv 不覆写即完全离线、不碰真实文件系统
     * （程序目录一旦有随包 adb，生产探测会命中，但绝不让它污染 T1~T8 的定位结论）。
     * 生产由 [SystemAdbEnv] 覆写，先读资源属性，未命中再走 [detectAppDir] 的三层探测。
     */
    fun bundledAdb(): String? = null
}

internal class SystemAdbEnv : AdbEnv {
    override fun getenv(name: String): String? = System.getenv(name)

    override fun isRegularFile(path: String): Boolean =
        runCatching { Files.isRegularFile(Paths.get(path)) }.getOrDefault(false)

    override fun osName(): String = System.getProperty("os.name").orEmpty()

    override fun systemProperty(name: String): String? = System.getProperty(name)

    override fun bundledAdb(): String? {
        // ① 属性优先：jpackage 在 PhotoMgm.cfg 注入 resources 目录，是随包资源的权威路径。
        val resourcesDir = systemProperty(RESOURCES_DIR_PROPERTY)
        if (!resourcesDir.isNullOrBlank()) {
            val candidate = runCatching {
                Paths.get(resourcesDir, "tools", "platform-tools", "adb.exe")
            }.getOrNull()
            if (candidate != null && isRegularFile(candidate.toString())) {
                return if (verifyBundledSha256(candidate)) {
                    candidate.toString()
                } else {
                    // A 方案：校验失败不抛异常，返回 null 交给 resolve() 的 resolveFallback 兜底。
                    println(CORRUPTED_ADB_LOG)
                    null
                }
            }
        }

        // ② 属性不可用 / 没命中 → 既有 detectAppDir() 三层探测（本轮不改该函数）。
        val appDir = detectAppDir()
        if (appDir.isEmpty()) return null
        val fromResources = runCatching {
            Paths.get(appDir).resolve(APP_RESOURCES_ADB_RELATIVE)
        }.getOrNull() ?: return null
        if (!isRegularFile(fromResources.toString())) return null
        return if (verifyBundledSha256(fromResources)) {
            fromResources.toString()
        } else {
            println(CORRUPTED_ADB_LOG)
            null
        }
    }
}

/** jpackage 在 PhotoMgm.cfg 里注入的权威资源目录属性名。 */
private const val RESOURCES_DIR_PROPERTY = "compose.application.resources.dir"

/** 随包 adb.exe 的 SHA256（B-1 诊断实测值；CI `Verify bundled adb integrity` 用同一值）。 */
private const val BUNDLED_ADB_SHA256 =
    "b4a6b455702684652cccf7b46258b29e653538904359a58fd4931cf3ef286b3f"

/** V3 人工验收锚点：故意改坏随包 adb 后，日志应出现这一行。 */
private const val CORRUPTED_ADB_LOG = "ADB-RESOLVE: bundled adb corrupted, falling back"

/**
 * 随包 adb 的唯一候选落点（相对程序目录）。
 *
 * `appResourcesRootDir` 把 `appResources/common/` 同步到 app-image 的 `app/resources/`；
 * `createDistributable` 与 `packageMsi` 共用 `prepareAppResources`，故 portable 与 MSI 都带这一份 adb。
 * 旧 `tools/platform-tools/adb.exe`（方案 1 的内部迁移落点）已随 B-3 删除：产物顶层从未有过
 * `PhotoMgm/tools/`，且随包 adb 从未进入任何已发布 tag。
 */
private val APP_RESOURCES_ADB_RELATIVE: Path =
    Paths.get("app", "resources", "tools", "platform-tools", "adb.exe")

/** 目录里是否真的放着主候选的随包 adb（路径非法 / 无权限一律按「没有」处理，不抛异常）。 */
private fun hasBundledAdb(dir: Path): Boolean =
    runCatching { Files.isRegularFile(dir.resolve(APP_RESOURCES_ADB_RELATIVE)) }.getOrDefault(false)

/** 命中随包 adb 后先校验字节；读失败 / 哈希不符一律 false，绝不执行未校验的二进制。 */
private fun verifyBundledSha256(path: Path): Boolean = runCatching {
    Files.newInputStream(path).use { Sha256.ofStream(it) } == BUNDLED_ADB_SHA256
}.getOrDefault(false)

/**
 * 三层探测程序目录：① `jpackage.app-path` → ② 当前进程可执行文件 → ③ `user.dir`。
 *
 * 每层都先确认该目录下存在唯一的随包 adb 主候选才返回该 dir；
 * `Paths.get(appPath).parent == null`（相对路径 / 根级路径）时跳过该层；
 * 三层全没命中返回 userDir —— 由调用方再判一次文件，保证「没随包」就是没随包。
 */
internal fun detectAppDir(): String {
    val userDir: String = System.getProperty("user.dir").orEmpty()
    val appPaths: List<String> = listOfNotNull(
        System.getProperty("jpackage.app-path"),
        runCatching { ProcessHandle.current().info().command().orElse(null) }.getOrNull(),
    )
    for (appPath in appPaths) {
        if (appPath.isBlank()) continue
        // parent == null → 该层给的是相对/根级路径，取不到程序目录，跳过。
        val dir: Path = runCatching { Paths.get(appPath).parent }.getOrNull() ?: continue
        if (hasBundledAdb(dir)) return dir.toString()
    }
    if (userDir.isNotEmpty()) {
        val userPath = runCatching { Paths.get(userDir) }.getOrNull()
        if (userPath != null && hasBundledAdb(userPath)) return userDir
    }
    return userDir
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
    // 定位结果的注入口：**仅测试与特殊部署使用，生产恒为 null**。
    // 为什么需要它：`adbEnv` 与 `command` 已经可注入，但 `resolve()` 仍会走真实环境 ——
    // 开发机命中 `D:\Android\sdk\…\adb.exe`、CI 上返回 null，同一个用例在两平台结论相反。
    // 给了这个函数就完全接管定位（返回 null = 找不到 adb），①~⑤ 的真实探测不再执行。
    private val resolveOverride: (() -> String?)? = null,
    // 设备定向：非 null 时**与设备交互**的命令都带 `-s <serial>`。
    // ★ 为什么必须有：`adb devices` 里出现多台（旧机没拔、模拟器、无线调试残留）时，
    //   不带 `-s` 的 `adb shell` / `adb pull` 会被 adb 自己拒绝（多设备歧义），
    //   表现为目录全空、拉取全失败 —— 代码层原本没有任何手段指定"这次操作的是哪台"。
    // ★ `devices` / `version` / `start-server` 是 **server 级**命令，加了 `-s` 会报错，所以不走 [adbArgv]。
    // ★ 默认 null = 不定向：单机场景的 argv 与旧行为逐字一致。
    internal val serial: String? = null,
) {

    private val isWindows: Boolean = adbEnv.osName().contains("Windows", ignoreCase = true)
    private val exe: String = if (isWindows) "adb.exe" else "adb"
    private val pathSeparator: String = if (isWindows) "\\" else "/"

    // ── adb server 自检结果（**懒求值**，至多算一次） ──────────────────────
    // ★ 为什么是 `by lazy` 而不是属性初始化器：构造 `AdbBridge` 的代价必须保持"零外部进程"。
    //   初始化器写法会让每次 `DesktopCore` 构造真的去起 `adb version` / `start-server`，
    //   连单测（`CorePipelineTest` / `WindowMemoryTest` 构造真 core、`AdbBridgeTest` 的 FakeEnv
    //   恰好写了 D:\Android\sdk 这类真实存在的路径）都会在开发机上真起进程 ——
    //   实测一次 `:desktop:core:test` 会起 57 个 adb 进程，单测从此不再离线自足。
    // ★ 谁来触发求值：唯一读者是 `DesktopCore.selfCheck()`，而它只跑在
    //   `PhoneImportSheet` 的 LaunchedEffect + Dispatchers.IO 路径上 → 首次求值发生在后台线程，
    //   既不阻塞 UI，也不会在启动/单测阶段发生。
    // ★ 结论是进程级的（`adb version` 与"这次插的是哪台手机"无关），所以整个 bridge 生命周期
    //   只有一份（`by lazy` 默认 LazyThreadSafetyMode.SYNCHRONIZED，多线程下也只算一次）。
    // ★ 找不到 adb 时直接失败、**不**去裸跑 "adb version"：定位已经按 显式路径 → 环境变量
    //   → PATH → 盘符 全试过了（见 resolve），再交给系统解析一次没有意义。
    val adbServerStatus: Result<String> by lazy { ensureAdbServer() }

    // ADB-RESOLVE 日志去重：resolve() 会被 executable() 高频调用（每条 adb 命令一次），
    // 逐次打印会刷屏；只在「实际用的 adb」发生变化时打一行，返回值语义完全不变。
    private var lastResolveLog: String? = null

    // 「随包优先 → ①~⑤ 兜底」两段式定位，全找不到返回 null（「找不到」是正常状态，不是异常）。
    fun resolve(): String? {
        // 测试/特殊部署注入：给了就完全接管定位，返回 null 即"找不到 adb"，不再走真实探测。
        // ★ 保持最高优先级：注入口不能被随包探测绕过，否则开发机/CI 上仓库根一旦有
        //   app/resources/tools/platform-tools/ 就会读到真实随包路径，T1~T6 的结论随宿主文件系统漂移。
        // ★ 注入口天然跳过 SHA256 校验（校验只在 bundledAdb() 内），测试用的假路径不会被误判为损坏。
        resolveOverride?.let { override -> return logResolve(override(), "fallback") }
        // 随包优先：命中且通过 SHA256 校验才返回，不再往下探测，避免 PATH / SDK 里的旧版 adb 抢先。
        // 只剩一条主落点（appResourcesRootDir），日志统一记 resources。
        adbEnv.bundledAdb()?.let { return logResolve(it, "resources") }
        return logResolve(resolveFallback(), "fallback")
    }

    // ①~⑤ 兜底：显式路径 → ANDROID_HOME → ANDROID_SDK_ROOT → PATH → 盘符扫描。
    // 与改造前 resolve() 的主体逐字等价（优先级不变），只抽成私有方法给随包让出第一顺位。
    private fun resolveFallback(): String? {
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

    // 标明实际用的 adb；同一条结论只打一次（synchronized 保证多线程下也不重复刷屏）。
    // 只做日志副作用，原样返回 path —— 不缓存定位结果，环境变化后下一次 resolve 立刻生效。
    private fun logResolve(path: String?, tag: String): String? {
        val line = if (path == null) "ADB-RESOLVE: not found" else "ADB-RESOLVE: $tag $path"
        synchronized(this) {
            if (lastResolveLog != line) {
                lastResolveLog = line
                println(line)
            }
        }
        return path
    }

    // ⑥ 兜底：返回裸命令名交给系统 PATH 解析（保持既有语义，不保证存在）。
    fun executable(): String = resolve() ?: exe

    // 需要「找到」语义的调用方用这个：空手而归时抛一条带分步排查的异常，避免静默降级。
    fun requireExecutable(): String = resolve() ?: throw IllegalStateException(ADB_NOT_FOUND_HINT)

    // 设备级命令的 argv：`adb [-s <serial>] <args…>`。
    // ★ serial == null 时不插任何东西，argv 与改造前**逐字一致**（单机零影响）。
    // ★ 只在 `-s` 之前插入，不重排其它参数：`adb pull REMOTE… LOCAL` 的变参顺序不能动。
    private fun adbArgv(vararg args: String): List<String> {
        val base = ArrayList<String>(args.size + 3)
        base += executable()
        serial?.let {
            base += "-s"
            base += it
        }
        base += args
        return base
    }

    /**
     * 返回绑定到指定设备的**新实例**（本类不可变，注入件原样复制）。
     *
     * @param s 设备序列号；null = 不定向（单机 / 兜底路径）。
     */
    internal fun withSerial(s: String?): AdbBridge = AdbBridge(
        explicitPath = explicitPath,
        adbEnv = adbEnv,
        command = command,
        resolveOverride = resolveOverride,
        serial = s,
    )

    /**
     * 取第一台 `device` 状态设备的序列号；没有在线设备时返回 null。
     *
     * 只有 `device` 状态可用：`unauthorized` / `offline` 的条目带 `-s` 也执行不了，
     * 选了等于把"没权限"变成"命令失败"，提示反而更难懂。
     * 多台在线时取第一台 —— 与 adb 自己的歧义报错相反，这里**显式**选一台，不靠 adb 猜。
     */
    internal fun primarySerial(): String? =
        deviceStates().firstOrNull { it.state == "device" }?.serial

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

    // `adb devices` 逐行解析成 (序列号, 状态)。过滤表头、空行与 `* daemon …` 提示行。
    // 与 [hasDevice] 分开：那个只要一个布尔（"能不能用"），自检要区分 unauthorized / offline。
    fun deviceStates(output: AdbOutput = devicesOutput()): List<AdbDevice> =
        output.stdout.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("*") && !it.startsWith("List of devices") }
            .mapNotNull { line ->
                val parts = line.split(WHITESPACE)
                if (parts.size < 2) null else AdbDevice(parts[0], parts[1])
            }
            .toList()

    /**
     * 确保本机 adb server 可用：版本门槛 + 重启 server。
     *
     * 顺序：`adb version` 取版本号 → 低于 [MIN_ADB_VERSION] 直接失败（不重启旧 server）→
     * `start-server`（幂等）。
     * **不做 `kill-server`**：避免瞬断用户其它 adb 工具（Android Studio / logcat / scrcpy）的现有连接。
     *
     * 版本号解析不出来时记「版本未知」**不报错**：厂商魔改版/预览版的输出千奇百怪，
     * 因为正则没认出格式就拒绝整个功能，得不偿失。
     *
     * @return 成功时带一句可展示的结论（含版本号与 server 状态），失败时带可读的排查提示。
     */
    fun ensureAdbServer(): Result<String> {
        val exePath = resolve() ?: return Result.failure(
            IllegalStateException("未找到 adb 可执行文件，已跳过 adb server 自检。$ADB_NOT_FOUND_HINT"),
        )
        val versionOut = command(listOf(exePath, "version"))
        val version = VERSION.find(versionOut.stdout)?.value
        if (version != null && !atLeastVersion(version, MIN_ADB_VERSION)) {
            return Result.failure(
                IllegalStateException(
                    "随包 adb 版本过低（$version < $MIN_ADB_VERSION），" +
                        "请重新安装本应用或联系技术支持。",
                ),
            )
        }
        val versionText = version ?: "版本未知"
        // ★ 刻意**不** `kill-server`：它会把用户正在用的其它 adb 工具（Android Studio、logcat、
        //   scrcpy）的连接瞬断一次，换来的是"重启一个本来就能用/自己会起来的 server"。
        //   只做 `start-server`：没在跑就拉起来，在跑就原样返回（幂等）。
        val start = command(listOf(exePath, "start-server"))
        if (start.exitCode != 0) println("ADB-DIAG: start-server 未成功：${start.stderr.trim()}")
        val detail = if (start.exitCode == 0) "server 已就绪" else "server 未就绪"
        println("ADB-DIAG: adb $versionText · $detail")
        return Result.success("adb $versionText（$detail）")
    }

    fun listRemote(dir: String): AdbOutput =
        command(adbArgv("shell", "ls", "-l", dir))

    // 通用 shell 入口：`adb shell <args...>`，参数**原样**塞进 argv（不经宿主 shell）。
    // 两个用途：① `ls -d <dir>/*/` 数一层子目录（glob 由**设备侧** shell 展开）；
    //          ② 不带 `-l` 的 `ls` 只取文件名（`ls -l` 格式认不全时的回退）。
    // ★ 不经宿主 shell 展开也顺手避开了 MSYS/Git-Bash 把 `/sdcard/x` 改写成本机路径的老问题：
    //   ProcessBuilder 直接给 argv，参数不经过 bash 的路径转换（仓内既有调用同一姿势）。
    fun shellRaw(vararg args: String): AdbOutput =
        command(adbArgv("shell") + args.toList())

    fun pull(remotePath: String, localTarget: Path): AdbOutput =
        command(adbArgv("pull", remotePath, localTarget.toString()))

    // 一次 adb pull 拉多个源文件到同一目录：`pull [-a] REMOTE... LOCAL` 的 REMOTE 是变参。
    // 实测（V2232A / adb 37.0.1）：14 个源一次 678 ms，逐张 5616 ms（8.3×），
    // 单次调用固定开销约 300 ms —— 瓶颈是进程启动，不是带宽。
    // 注意：批内个别文件缺失时整批 exitCode 会是 1，但同批其余文件仍照拉；
    // 逐文件成败一律由调用方按「暂存区有没有这个文件」判定，不要看 exitCode。
    // 50 个源约 3 KB 命令行，远低于 Windows 32 KB 的 CreateProcess 上限。
    fun pullMultiple(remotePaths: List<String>, localDir: Path): AdbOutput =
        command(adbArgv("pull") + remotePaths + localDir.toString())

    companion object {
        private val DEVICE_LINE = Regex("""^\S+\s+device\s*$""")
        private val SDK_HOME_KEYS = listOf("ANDROID_HOME", "ANDROID_SDK_ROOT")
        private val WHITESPACE = Regex("""\s+""")

        // adb server 的版本门槛。低于它的 platform-tools 在 `ls -d` 与 `pull` 变参上有已知缺陷，
        // 表现正好是"列不出目录 / 只拉到第一张"这类难以归因的症状。
        const val MIN_ADB_VERSION: String = "1.0.40"

        // `adb version` 首行形如 "Android Debug Bridge version 1.0.41" 或
        // "Version 37.0.1-1234567"：都取第一处 `x.y.z`。
        private val VERSION = Regex("""\d+\.\d+\.\d+""")

        // 找不到 adb / 找不到设备共用这一条（Q3 合并、Q5-② 分步排查）：
        // 一句结论 + 三步排查，既不区分「未安装」与「未连接」，又让用户知道从哪查起。
        const val ADB_NOT_FOUND_HINT: String =
            "未检测到手机。请依次排查：① USB 线是否连接；" +
                "② 手机是否开启「USB 调试」并授权；" +
                "③ 手机是否在「传输文件（MTP）」模式下。"

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

        // 严格正则失配时的兜底：**先定位 日期+时间 这一对邻列**，再据此切出大小与名字。
        // 为什么不是"从右往左数第 2/3 个 token"：文件名允许含空格（`my photo.jpg`），
        // 从右数就永远对不上时间列，于是 strict 能给出 `my photo.jpg`、兜底却整行丢弃 ——
        // 同一个输入两条解析路径结论不一致，正是本轮要修的跨 ROM/跨路径不一致。
        // 左边无论多了多少列（SELinux context、ACL、将来的新列）都不影响：日期列只在
        // 大小为数字、名字之前的那个位置出现一次，扫到第一对 (日期, 时间) 就是它。
        private fun parseLsEntryByTokens(text: String): LsEntry? {
            val tokens = text.split(WHITESPACE)
            if (tokens.size < 8) return parseFailure(text)
            if (!LS_PERM.matches(tokens[0])) return parseFailure(text)
            // 日期列的下标：从第 2 列（token[0] 是权限）开始找第一处 `日期` 且**下一列是时间**。
            val dateIdx = (1 until tokens.size - 1).firstOrNull { i ->
                LS_DATE.matches(tokens[i]) && LS_TIME.matches(tokens[i + 1])
            } ?: return parseFailure(text)
            val timeIdx = dateIdx + 1
            val sizeIdx = dateIdx - 1
            if (sizeIdx < 1) return parseFailure(text)
            val size = tokens[sizeIdx].toLongOrNull() ?: return parseFailure(text)
            // 名字 = 时间列之后的**原文子串**。
            // 不能用 `tokens.drop(...).joinToString(" ")` 拼回：`split(WHITESPACE)` 已经把
            // 连续空格折叠成一个，join 只会还原成单空格 —— 文件名含连续空格
            // （`my  photo.jpg`）时得到的 name 与远端真实名字不符，`adb pull` 直接拉不到，
            // 表现为该文件记 failed，比"整行丢弃"更难归因。
            val name = restAfterToken(text, timeIdx)
            if (!isPlausibleName(name)) return parseFailure(text)
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

        // 跳过 `text` 里前 `index + 1` 个 token（连同 token 之间/之前的空白），返回剩余原文并 trim。
        // 为什么不用 `split`：`split(WHITESPACE)` 会把连续空格折叠，再 `join` 只能还原成单空格，
        // 而文件名里的连续空格是远端真实名字的一部分。这里按字符游标走一遍，原文一个字节都不改。
        // token 数不足时返回空串，交由调用方用 [isPlausibleName] 判掉。
        private fun restAfterToken(text: String, index: Int): String {
            var pos = 0
            var skipped = 0
            while (skipped <= index) {
                while (pos < text.length && text[pos].isWhitespace()) pos++
                if (pos >= text.length) return ""
                while (pos < text.length && !text[pos].isWhitespace()) pos++
                skipped++
            }
            return text.substring(pos).trim()
        }

        // 逐段比较 `a.b.c`：段数不同时缺的按 0 补齐（"1.0.40" vs "1.0.41-rc1" 已被 VERSION 截断）。
        private fun atLeastVersion(actual: String, min: String): Boolean {
            val left = actual.split('.').map { it.toIntOrNull() ?: 0 }
            val right = min.split('.').map { it.toIntOrNull() ?: 0 }
            for (index in 0 until maxOf(left.size, right.size)) {
                val mine = left.getOrElse(index) { 0 }
                val need = right.getOrElse(index) { 0 }
                if (mine != need) return mine > need
            }
            return true
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
// ★ [id] 还带上了 bridge 的设备序列号：`SyncStateGuard` 只比 id 字符串，
//   不带 serial 时两台手机的 `/sdcard/DCIM` 是同一个 id —— 换机不会报冲突，
//   旧机的续传记录会被静默沿用。serial 为 null（单机 / 未定向）时保持旧格式，
//   避免升级后单机用户也吃一次迁移冲突。
internal class AdbPhoneSource(
    private val bridge: AdbBridge,
    private val remoteDir: String,
) : PhoneSource {

    override val id: String = bridge.serial?.let { "adb:$it:$remoteDir" } ?: "adb:$remoteDir"
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
    // ★ 影响面（与实现核对过，别按直觉猜）：
    //   · `size = 0L` **基本不影响**续传去重：PhoneSyncExecutor 的 `incomingSize` 取的是
    //     暂存区文件的 `Files.size(staged)` 实测值，`photo.size` 只在 `Files.size()` 抛异常时
    //     才作为兜底；`RemotePhoto.size` 全仓也只有这一处消费。
    //   · 真正的影响是 `modifiedMs = null`：DesktopCore 的 `timeOf` 是
    //     `EXIF 拍摄时间 ?: remote.modifiedMs`，两者都没有时 PhoneSyncExecutor 会把该文件
    //     计入 filtered 并跳过 —— 会中招的是**无 EXIF `DateTimeOriginal` 的格式**
    //     （PNG / WEBP / HEIC 等），以及被剥离了 EXIF 的转存图；JPG 截图通常带 EXIF，
    //     不受影响（vivo 实测 `Screenshot_20260416_191513.jpg → 1776338113000`）。
    //     这个目录下这些文件会被静默丢掉（本轮未授权修复，记为已知限制）。
    private fun listNamesOnly(): List<RemotePhoto> =
        listNames(remoteDir)
            .filter { it != "." && it != ".." }
            .filter { PcPhotoSourceProvider.isSupportedImage(it) }
            .distinct()
            .sorted()
            .map { RemotePhoto(name = it, size = 0L, modifiedMs = null) }
}
