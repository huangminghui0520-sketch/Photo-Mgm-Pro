// desktop/core/DesktopCoreConfig.kt —— 桌面端装配配置（★ 所有平台相关件都可替换）
//
// 这张表是 T10 之后"桌面端还剩什么"的清单：全是**平台形态**的选择，一条业务规则也没有。
// 业务规则都在 shared:app 的用例里；这里只回答"用哪个执行器、哪个外壳、哪个时钟"。
//
// ★ 与 T09 之前的 `CoreConfig` 只差两处：
//   · 去掉了"装配"意味（原来是直接 new PhotoMgmCoreImpl），现在是喂给 `DesktopCore` 的料；
//   · 新增 `verifyMode` —— `ResumeVerifyMode` 已从 `ExportRequest` 上摘掉（安卓没有续传机制），
//     于是它变成桌面导出执行器的**构造参数**，由这里给初值、由 `DesktopCore.setVerifyMode` 改。
package com.photomgm.desktop.core

import algorithm.AppZone
import algorithm.ExifReader
import algorithm.PhotoSourceProvider
import app.api.ResumeVerifyMode
import app.port.ShellLauncher
import java.nio.file.Path
import java.time.Clock
import java.time.ZoneId
import java.util.concurrent.Executor

/**
 * 桌面端装配配置。
 *
 * @param dataDir 数据目录（settings.json / logs.txt）；null = `%APPDATA%\PhotoMgm`（测试传临时目录）
 * @param zone 业务时区，默认 [AppZone.ID]（北京时间）。**禁止**依赖系统默认时区
 * @param executor 长任务执行器；null = 核心自建单线程守护执行器（测试传同步执行器可确定性地断言）
 * @param shell 系统外壳动作；null = 真实 Windows 实现
 * @param exifReader 覆盖 EXIF 读取实现（测试注入桩）
 * @param sourceProvider 覆盖照片扫描实现（测试注入桩）
 * @param hasher 覆盖文件哈希实现（派生去重的内容哈希；测试注入可构造"哈希相同/不同"场景）
 * @param clock 时钟（导出目录时间戳、日志时间）；null = 系统时钟
 * @param verifyMode 断点续传的产物校验强度（桌面独有，见 [ResumeVerifyMode]）
 * @param photoIdSource 覆盖照片 id 分配（默认 `SHA-256(规范化路径)` + 线性探测）
 * @param imageEncoder 覆盖台账嵌图编码（默认 AWT ImageIO → JPEG）
 *
 * 手机导入的假件（第 3 步追加）：
 *   core/sync 包整体是 internal，**不能**出现在 public data class 的签名里
 *   （public property exposes its internal type）。所以注入的是它们的行为：
 *   一个假 adb 进程输出、一组假盘符、一个假时钟文本、一个假暂存区。
 *   DesktopCore 拿这些原始值去构造 internal 的 AdbBridge / UsbDriveScanner / PhoneSyncExecutor，
 *   既保留「全链路可离线测」，又不把 internal 类型泄漏给 :ui。
 *
 * @param adbCommandOutput 假 `adb` 调用：入参是命令行，返回 (exitCode, stdout, stderr)；null = 真实进程。
 *   三元组而不是二元组，是因为自检要区分「没权限（stderr 含 Permission denied）」与「没这个目录」，
 *   而真实 adb 的失败原因只出现在 stderr 上 —— 只给 stdout + 空 stderr 的话那条分支永远测不到
 * @param adbResolvedPath 假 adb 解析结果；**null = 真实探测**
 *   （显式路径 → ANDROID_HOME/ANDROID_SDK_ROOT → PATH → 仅 Windows 扫盘符）。
 *   **非 null 时完全接管** `AdbBridge.resolve()`，返回 null 表示"找不到 adb"，
 *   用于离线测试与特殊部署（免去"本机恰好装了 / 没装 SDK 导致两平台结论相反"的不可测性）
 * @param usbRoots 假盘符根列表；null = `File.listRoots()`
 * @param usbReadableDir 假「该目录可读」判定；null = 真实 Files 判定
 * @param phoneSyncNowText 假 `sync-state.json` 的 updatedAt；null = `clock.instant()`
 * @param phoneSyncStaging 假临时暂存区；null = `Files.createTempDirectory`
 */
data class DesktopCoreConfig(
    val dataDir: Path? = null,
    val zone: ZoneId = AppZone.ID,
    val executor: Executor? = null,
    val shell: ShellLauncher? = null,
    val exifReader: ExifReader? = null,
    val sourceProvider: PhotoSourceProvider? = null,
    val hasher: ((String) -> String?)? = null,
    val clock: Clock? = null,
    val verifyMode: ResumeVerifyMode = ResumeVerifyMode.SIZE_AND_MTIME,
    val photoIdSource: app.port.PhotoIdSource? = null,
    val imageEncoder: app.port.ImageEncoder? = null,
    val adbCommandOutput: ((List<String>) -> Triple<Int, String, String>)? = null,
    val adbResolvedPath: (() -> String?)? = null,
    val usbRoots: (() -> List<String>)? = null,
    val usbReadableDir: ((String) -> Boolean)? = null,
    val phoneSyncNowText: (() -> String)? = null,
    val phoneSyncStaging: (() -> Path)? = null,
)
