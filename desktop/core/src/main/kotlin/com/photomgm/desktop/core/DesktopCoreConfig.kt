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
)
