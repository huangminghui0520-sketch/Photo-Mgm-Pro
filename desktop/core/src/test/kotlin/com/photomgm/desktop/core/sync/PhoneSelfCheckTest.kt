// desktop/core/test/sync/PhoneSelfCheckTest.kt —— DesktopCore.selfCheck() 的纯逻辑单测
//
// ★ 这段代码的分支几乎全部是**环境相关**的（adb 在不在、版本够不够、手机授没授权、
//   相册目录读不读得到）。若靠本机真环境去测，同一用例在开发机与 CI 上会得出相反结论，
//   所以这里一律经 `DesktopCoreConfig` 的假件注入：
//     · `adbResolvedPath` 接管 `AdbBridge.resolve()`（返回 null 即"找不到 adb"）；
//     · `adbCommandOutput` 接管 argv → (exitCode, stdout, stderr)；
//     · `usbRoots = { emptyList() }` 避免真去扫宿主盘符；
//     · 同步 `Executor` + 固定 `Clock` 让结论可复现。
//   **不跑真 adb、不启 GUI、不连真机。**
//
// ★ 断言口径：只断言「类型 + 关键词」。提示文案是给用户看的产品文案，会随版本漂移；
//   把整句写进断言等于给文案改动加一道假红灯。判据固定在两处稳定锚点上：
//     ① 结构量（ok / devicesOnline / unauthorized / photoDirs / hints.size）；
//     ② 语义关键词（"版本过低""允许 USB 调试""PTP""USB 调试（安全设置）""没有受支持的照片"），
//        以及直接引用 `AdbBridge.ADB_NOT_FOUND_HINT` / `MIN_ADB_VERSION` 常量而非抄全文。
package com.photomgm.desktop.core.sync

import algorithm.AppZone
import app.port.ShellLauncher
import com.photomgm.desktop.core.DesktopCore
import com.photomgm.desktop.core.DesktopCoreConfig
import com.photomgm.desktop.core.SelfCheckResult
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.util.concurrent.Executor

class PhoneSelfCheckTest {

    @TempDir
    lateinit var tmp: Path

    /** 本用例创建的 core，`@AfterEach` 统一 close（否则单线程 scheduler 会拦着 JVM 退出）。 */
    private val opened = mutableListOf<DesktopCore>()

    @AfterEach
    fun tearDown() {
        opened.forEach { runCatching { it.close() } }
        opened.clear()
    }

    // ── 假 adb ────────────────────────────────────────────────────────────

    /**
     * 假 adb 的**全部**应答都在这里，按 `argv[1]` 分派。
     *
     * 分派键取 `argv[1]` 而不是整条 argv：`argv[0]` 是 `resolve()` 的返回值
     * （Windows 上真实进程名会是 `adb.exe`，而测试里注入的是 `"adb"`），
     * 用它当键会让用例的结论依赖宿主平台。
     *
     * @param versionStdout `adb version` 的 stdout（版本号由 `AdbBridge` 用正则取第一处 `x.y.z`）
     * @param devicesStdout `adb devices` 的 stdout（含表头，与真机输出同形）
     * @param devicesExit   `adb devices` 的 exitCode
     * @param onShell       所有 `adb shell …` 的统一应答；默认「失败且无输出」= 目录里什么都没有
     */
    private class FakeAdb(
        private val versionStdout: String = "Android Debug Bridge version 1.0.41",
        private val devicesStdout: String = "List of devices attached\n",
        private val devicesExit: Int = 0,
        private val onShell: (List<String>) -> Triple<Int, String, String> = { Triple(1, "", "") },
    ) {
        fun respond(argv: List<String>): Triple<Int, String, String> {
            return when (argv.getOrNull(1)) {
                "version" -> Triple(0, versionStdout, "")
                "start-server" -> Triple(0, "", "")
                "devices" -> Triple(devicesExit, devicesStdout, "")
                "shell" -> onShell(argv)
                else -> Triple(1, "", "unexpected argv: $argv")
            }
        }
    }

    /** 组装一个只连假件的 core；`resolvedPath = null` 表示「本机找不到 adb」。 */
    private fun newCore(resolvedPath: String?, fake: FakeAdb): DesktopCore {
        val core = DesktopCore(
            DesktopCoreConfig(
                dataDir = tmp.resolve("data"),
                zone = AppZone.ID,
                executor = Executor { it.run() },
                shell = ShellLauncher.NOOP,
                clock = Clock.fixed(Instant.parse("2026-08-14T02:15:00Z"), AppZone.ID),
                adbCommandOutput = fake::respond,
                adbResolvedPath = { resolvedPath },
                usbRoots = { emptyList() },
            ),
        )
        opened += core
        return core
    }

    /** 一台在线设备的 `adb devices` 正文（真机输出是 `<serial>\tdevice`，含表头）。 */
    private val oneDeviceOnline: String = "List of devices attached\n10AD2G1FLK001AQ\tdevice\n"

    /** shell 探针：只有「直接 ls /sdcard/DCIM」能列出一张 .jpg，其余一律「没有」。 */
    private fun directoryProbe(stdout: String = "IMG_20260101_120000.jpg") =
        { argv: List<String> ->
            // argv = ["adb", "shell", "ls", <dir>] 或 ["adb", "shell", "ls", "-d", "<dir>/*/"]
            // 只有「直接 ls 某一个具体目录」才可能命中照片；`ls -d …/*/` 一律当作无子目录，
            // 免得把 glob 结果误算成照片目录。
            val isSubDirQuery = argv.any { it.endsWith("/*/") }
            if (!isSubDirQuery && argv.lastOrNull() == "/sdcard/DCIM") {
                Triple(0, stdout, "")
            } else {
                Triple(1, "", "")
            }
        }

    // ── ① 全绿：adb 可用、设备在线、目录里有照片 ─────────────────────────

    @Test
    fun `S1 全绿时不产生任何提示`() {
        val fake = FakeAdb(devicesStdout = oneDeviceOnline, onShell = directoryProbe())
        val result = newCore("adb", fake).selfCheck()

        assertTrue(result.ok, "设备在线且发现了照片目录，ok 必须为 true")
        assertEquals(1, result.devicesOnline, "恰好一台状态为 device 的设备")
        assertFalse(result.unauthorized, "全绿场景不该有未授权设备")
        assertTrue(
            result.photoDirs.contains("/sdcard/DCIM"),
            "发现结果应包含 /sdcard/DCIM，实测 ${result.photoDirs}",
        )
        assertTrue(result.hints.isEmpty(), "全绿时 hints 必须为空，实测 ${result.hints}")
    }

    // ── ② adb 版本低于门槛 ───────────────────────────────────────────────

    @Test
    fun `S2 adb 版本过低时提示更新 platform-tools`() {
        // 1.0.30 < 1.0.40：ensureAdbServer 直接失败，不去 start-server。
        val fake = FakeAdb(versionStdout = "Android Debug Bridge version 1.0.30", devicesExit = 1)
        val result = newCore("adb", fake).selfCheck()

        assertFalse(result.ok)
        val hint = result.hints.firstOrNull { it.contains("版本过低") }
        assertTrue(hint != null, "应给出「版本过低」提示，实测 ${result.hints}")
        assertTrue(hint!!.contains("1.0.30"), "提示应带上实测版本号，实测 $hint")
        assertTrue(hint.contains(AdbBridge.MIN_ADB_VERSION), "提示应带上版本门槛常量，实测 $hint")
    }

    // ── ③ 本机找不到 adb ─────────────────────────────────────────────────

    @Test
    fun `S3 找不到 adb 时只提示一条不重复`() {
        // 定位返回 null → ensureAdbServer 早退（**不会**打 ADB-DIAG 那行），
        // 且「未找到 adb」不进 hints —— 由 online == 0 分支统一讲一次。
        val fake = FakeAdb(devicesExit = 1)
        val result = newCore(null, fake).selfCheck()

        assertFalse(result.ok)
        assertEquals(0, result.devicesOnline)
        assertEquals(1, result.hints.size, "同一句排查提示只能出现一次，实测 ${result.hints}")
        assertTrue(
            result.hints[0].contains(AdbBridge.ADB_NOT_FOUND_HINT),
            "唯一那条提示应是 ADB_NOT_FOUND_HINT，实测 ${result.hints[0]}",
        )
    }

    // ── ④ 已连接但手机上没点授权 ─────────────────────────────────────────

    @Test
    fun `S4 未授权时提示点允许 USB 调试`() {
        val fake = FakeAdb(devicesStdout = "List of devices attached\nXYZ789\tunauthorized\n")
        val result = newCore("adb", fake).selfCheck()

        assertTrue(result.unauthorized, "unauthorized 设备必须被识别出来")
        assertFalse(result.ok)
        assertTrue(
            result.hints.any { it.contains("允许 USB 调试") },
            "应提示去手机上点授权，实测 ${result.hints}",
        )
        // 未授权 ≠ 没连上：再补一句「未检测到手机」会把原因说错。
        assertFalse(
            result.hints.any { it.contains("未检测到手机") },
            "未授权场景不该说「未检测到手机」，实测 ${result.hints}",
        )
    }

    // ── ⑤ adb 可用但一台设备都没有 ───────────────────────────────────────

    @Test
    fun `S5 adb 可用但无设备行时提示未检测到手机`() {
        // 只有表头：deviceStates 会把 `List of devices attached` 过滤掉，剩 0 条。
        val fake = FakeAdb(devicesStdout = "List of devices attached\n")
        val result = newCore("adb", fake).selfCheck()

        assertEquals(0, result.devicesOnline)
        assertFalse(result.ok)
        assertTrue(
            result.hints.any { it.contains(AdbBridge.ADB_NOT_FOUND_HINT) },
            "应给出未检测到手机的排查提示，实测 ${result.hints}",
        )
    }

    // ── ⑥ 设备在线、目录读不到（Permission denied）───────────────────────

    @Test
    fun `S6 目录无权限时提示切 PTP 模式`() {
        val fake = FakeAdb(
            devicesStdout = oneDeviceOnline,
            onShell = { Triple(1, "", "ls: /sdcard/DCIM: Permission denied") },
        )
        val result = newCore("adb", fake).selfCheck()

        assertFalse(result.ok)
        assertTrue(result.photoDirs.isEmpty(), "读不到目录时不该编出目录，实测 ${result.photoDirs}")
        assertTrue(
            result.hints.any { it.contains("PTP") },
            "Permission denied 应指向「切到 PTP / 传输照片」，实测 ${result.hints}",
        )
    }

    // ── ⑦ 设备在线、文件夹压根不存在 ─────────────────────────────────────

    @Test
    fun `S7 没有 DCIM 目录时提示检查 USB 调试安全设置`() {
        val fake = FakeAdb(
            devicesStdout = oneDeviceOnline,
            onShell = { Triple(1, "", "ls: /sdcard/DCIM: No such file or directory") },
        )
        val result = newCore("adb", fake).selfCheck()

        assertFalse(result.ok)
        assertTrue(
            result.hints.any { it.contains("USB 调试（安全设置）") },
            "目录不存在应指向开发者选项的安全设置，实测 ${result.hints}",
        )
    }

    // ── ⑧ 设备在线、目录可读但一张受支持的照片都没有 ─────────────────────

    @Test
    fun `S8 目录可读但无照片时提示没有受支持的照片`() {
        // exitCode 0 但 stdout 为空：目录读得到，只是空的（或全是文件名一堆的非图片文件）。
        val fake = FakeAdb(devicesStdout = oneDeviceOnline, onShell = { Triple(0, "", "") })
        val result = newCore("adb", fake).selfCheck()

        assertFalse(result.ok)
        assertTrue(result.photoDirs.isEmpty(), "空目录不该算作照片目录，实测 ${result.photoDirs}")
        assertTrue(
            result.hints.any { it.contains("没有受支持的照片") },
            "目录可读但无照片时应明说，实测 ${result.hints}",
        )
    }
}
