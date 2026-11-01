// desktop/core/api/PhotoMgmCore.kt —— ★ 逻辑核心唯一对外门面（UI 只依赖本文件 + AppState）
//
// ┌─────────────────────────────── 分层与依赖方向 ───────────────────────────────┐
// │  UI（未来单独开发）                                                            │
// │      │  只允许依赖：PhotoMgmCore / AppState / ExportModels                    │
// │      ▼                                                                        │
// │  api（本文件）——门面、状态快照、监听器、可注入扩展点                          │
// │      ▼                                                                        │
// │  logic（PhotoMgmCoreImpl）—— 流水线编排                                      │
// │      ▼                                                                        │
// │  platform（Pc*）—— 目录扫描 / EXIF / 哈希 / 图片编码 / 打开文件（可整体替换）  │
// │      ▼                                                                        │
// │  :algorithm（原工程算法核心，零改动，外部目录挂载）                           │
// └──────────────────────────────────────────────────────────────────────────────┘
//
// ★ 为什么用 StateFlow 而不是回调列表：StateFlow 天然"有当前值 + 可多订阅 + 可去重"，
//   Compose 用 collectAsState() 一行接入，Swing/JavaFX 用 addListener 也能拿到最新快照；
//   且核心内部不需要知道 UI 在哪个线程——UI 自行切回自己的线程即可。
package com.photomgm.desktop.core.api

import algorithm.DedupKeepMode
import algorithm.ExifReader
import algorithm.PhotoSourceProvider
import com.photomgm.desktop.core.logic.PhotoMgmCoreImpl
import kotlinx.coroutines.flow.StateFlow
import java.nio.file.Path
import java.time.ZoneId
import java.util.concurrent.Executor

/** 可取消的异步操作句柄（分析 / 导出）。 */
interface OperationHandle {
    /** 请求取消：导出会在下一个文件边界停止并保留已导出的部分（可续传）。 */
    fun cancel()

    /** 是否已请求取消。 */
    val isCancelled: Boolean
}

/** 分析进度回调（可选；只要状态就传 null，从 [PhotoMgmCore.state] 读）。 */
fun interface ProgressListener {
    fun onProgress(progress: Progress)
}

/** 导出回调。三个方法都在后台线程被调用，UI 需自行切回 UI 线程。 */
interface ExportListener {
    fun onProgress(progress: ExportProgress)

    fun onFinished(result: ExportResult)

    fun onFailed(error: Throwable)
}

/**
 * 系统外壳动作（打开文件/在资源管理器中定位）。
 *
 * ★ 独立成接口的原因：核心逻辑不该直接调 `java.awt.Desktop`——
 *   ① 无头环境/测试会炸；② 未来换 UI 框架时可能要用自己的实现；
 *   ③ 这是核心唯一"有副作用到操作系统"的地方，隔离后便于审计与替换。
 */
interface ShellLauncher {
    /** 用系统默认程序打开（台账 xlsx）。返回是否成功发起。 */
    fun open(path: Path): Boolean

    /** 在资源管理器中定位该文件/文件夹（选中该文件）。返回是否成功发起。 */
    fun reveal(path: Path): Boolean

    companion object {
        /** 空实现：测试与无头环境用。 */
        val NOOP: ShellLauncher = object : ShellLauncher {
            override fun open(path: Path): Boolean = false
            override fun reveal(path: Path): Boolean = false
        }
    }
}

/**
 * 核心配置——★ 所有平台相关件都可替换，这是"逻辑与平台/UI 解耦"的落点。
 *
 * @param dataDir 数据目录；null = `%APPDATA%\PhotoMgm`（测试传临时目录）
 * @param zone 业务时区，默认 [algorithm.AppZone.ID]（北京时间）。**禁止**依赖系统默认时区
 * @param executor 长任务执行器；null = 核心自建单线程守护执行器（测试传同步执行器可确定性地断言）
 * @param shell 系统外壳动作；null = 真实 Windows 实现
 * @param exifReader 覆盖 EXIF 读取实现（测试注入桩）
 * @param sourceProvider 覆盖照片扫描实现（测试注入桩）
 * @param hasher 覆盖文件哈希实现（派生去重的内容哈希；测试注入可构造"哈希相同/不同"场景）
 * @param clock 时钟（导出目录时间戳、日志时间）；null = 系统时钟
 */
data class CoreConfig(
    val dataDir: Path? = null,
    val zone: ZoneId = algorithm.AppZone.ID,
    val executor: Executor? = null,
    val shell: ShellLauncher? = null,
    val exifReader: ExifReader? = null,
    val sourceProvider: PhotoSourceProvider? = null,
    val hasher: ((String) -> String?)? = null,
    val clock: java.time.Clock? = null,
)

/**
 * 逻辑核心门面。
 *
 * ★ 线程模型：所有方法都可以在任意线程调用；长任务（[analyze] / [export]）内部异步执行，
 *   调用方立刻拿到 [OperationHandle] 用于取消。状态变化只通过 [state] 发布，
 *   实现内部保证"每次发布都是完整快照"，UI 不会读到半成品状态。
 *
 * ★ 与 UI 的边界：核心**不**创建窗口、不弹对话框、不读键盘鼠标、不持有任何 View 对象。
 *   需要用户确认的事情（例如"目标路径过长"）以 [AppState.error] 形式交给 UI 呈现。
 */
interface PhotoMgmCore : AutoCloseable {

    /** 当前状态快照流（首次订阅立即收到当前值）。 */
    val state: StateFlow<AppState>

    // ── 设置 ──────────────────────────────────────────────────────────────
    /** 原子更新设置并持久化；变更源目录/巡查日期会使既有分析结果失效（自动重算由调用方决定）。 */
    fun updateSettings(block: (Settings) -> Settings)

    /** 日志文本变化（内存态即时生效；落盘由核心负责，调用方无需关心）。 */
    fun setLogText(text: String)

    /**
     * 切换三态去重模式。
     * ★ 只重算输出层（事件照片 + 台账），不重跑扫描/EXIF/分类 —— 与 Android 端一致。
     * 同步完成，返回后 [state] 已是新口径（不存在"快速连切导致晚到覆盖"的竞态）。
     */
    fun setDedupMode(mode: DedupKeepMode)

    // ── 主流程 ────────────────────────────────────────────────────────────
    /**
     * 分析：解析日志 → 扫描源目录 → 文件名日期粗筛 → 派生去重 → EXIF 精读
     *      → GPS 分组 → 事件分类 → 生成台账行。
     *
     * 幂等：可重复调用（例如改了日期/源目录后重跑）。
     * 前置条件：无（源目录为空时只解析日志，不报错）。
     */
    fun analyze(listener: ProgressListener? = null): OperationHandle

    /** 读日志文件内容（UTF-8 / GBK 自动识别）。UI 拖入 .txt 时用；不触发解析。 */
    fun readLogFile(file: Path): String

    /** 批量移动照片到目标事件；[targetEventId] 为 null 表示移入"未匹配照片池"。 */
    fun movePhotos(photoIds: List<Long>, targetEventId: Int?)

    /** 设置台账照片槽位（slot=1 或 2）；[photoId] 为 null 表示恢复该侧默认（最早/最晚）。 */
    fun markLedgerPhoto(eventId: Int, slot: Int, photoId: Long?)

    /** 清空全部人工调整（恢复自动分类结果）。 */
    fun resetManualAdjustments()

    // ── 导出 ──────────────────────────────────────────────────────────────
    /** 导出（异步）。导出期间 [AppState.status] 为 EXPORTING，其他写操作被忽略。 */
    fun export(request: ExportRequest, listener: ExportListener): OperationHandle

    /** 打开导出目录中的台账（通过 [ShellLauncher]）；无台账返回 false。 */
    fun openLedger(exportResult: ExportResult): Boolean

    /** 在资源管理器中定位导出目录。 */
    fun revealExportDir(exportResult: ExportResult): Boolean

    /** 消费提示消息（UI 展示完 Snackbar 后调用，避免重复弹出）。 */
    fun clearMessage()

    /** 关闭：刷盘日志文本、释放执行器。关闭后再调用其他方法无效果。 */
    override fun close()
}

/** 创建入口（UI 唯一需要的工厂）。 */
object PhotoMgmCoreFactory {
    fun create(config: CoreConfig = CoreConfig()): PhotoMgmCore =
        PhotoMgmCoreImpl(config)
}
