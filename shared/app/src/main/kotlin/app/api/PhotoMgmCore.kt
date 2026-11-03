// shared/app/api/PhotoMgmCore.kt —— 逻辑核心唯一对外门面（UI 只依赖本文件 + AppState）
//
// 分层与依赖方向（T09 起共享层也在其中）：
//
//   UI
//    │  只允许依赖：PhotoMgmCore / AppState / ExportModels
//    ▼
//   api（本文件）——门面、状态快照、监听器、可注入扩展点
//    ▼
//   usecase（AnalysisPipeline / AdjustUseCase / ExportUseCase / SettingsUseCase）—— 编排
//    ▼
//   domain + algorithm（业务规则与算法，零平台依赖）
//    ▼
//   平台适配器（Pc* / Android data 层）—— 目录扫描 / EXIF / 图片编码 / 打开文件 / 写盘
//
// ★ 为什么用 StateFlow 而不是回调列表：StateFlow 天然「有当前值 + 可多订阅 + 可去重」，
//   Compose 用 collectAsState() 一行接入，Swing/JavaFX 用 addListener 也能拿到最新快照；
//   且核心内部不需要知道 UI 在哪个线程——UI 自行切回自己的线程即可。
//
// ★ T09：本接口从 `desktop/core/api` 上移，**签名只做两处平台化改造**：
//   · `readLogFile(file: Path)` → `readLogText(ref: String)`（路径/URI 都不进共享层）；
//   · `ExportRequest.outputDir: Path` → `ExportRequest.target: String`（同上）。
//   其余方法名与语义一字未改 —— 这保证 T10 把桌面端切到共享实现时，UI 侧只需要换 import。
package app.api

import algorithm.DedupKeepMode
import kotlinx.coroutines.flow.StateFlow

/** 可取消的异步操作句柄（分析 / 导出）。 */
interface OperationHandle {
    /** 请求取消：导出会在下一个文件边界停止并保留已导出的部分（可续传）。 */
    fun cancel()

    /** 是否已请求取消。 */
    val isCancelled: Boolean
}

/** 分析进度回调（可选；只要状态就传 null，从门面的 state 读）。 */
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
 * 逻辑核心门面。
 *
 * ★ 线程模型：所有方法都可以在任意线程调用；长任务（analyze / export）内部异步执行，
 *   调用方立刻拿到 [OperationHandle] 用于取消。状态变化只通过 [state] 发布，
 *   实现内部保证「每次发布都是完整快照」，UI 不会读到半成品状态。
 *
 * ★ 与 UI 的边界：核心**不**创建窗口、不弹对话框、不读键盘鼠标、不持有任何 View 对象。
 *   需要用户确认的事情（例如「目标路径过长」）以 [AppState.error] 形式交给 UI 呈现。
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
     * ★ 只重算输出层（事件照片 + 台账），不重跑扫描/EXIF/分类 —— 两端一致。
     * 同步完成，返回后 [state] 已是新口径（不存在「快速连切导致晚到覆盖」的竞态）。
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
    fun readLogText(ref: String): String

    /**
     * 批量移动照片到目标事件；[targetEventId] 为 null 表示移入「未匹配照片池」。
     *
     * ★ 移动会**清掉这几张照片的复制关系**（见 [copyPhotos]）：移动表达的是「它归那边」，
     *   如果同时保留旧的复制份，用户会看到「明明移走了却还在原来那条里」。
     *
     * 返回**真正改变了归属的张数**：目标已经是它的归属时会返回 0（重复移动不产生变化）。
     * ★ 为什么要有返回值：导出中、分析结果被替换、目标就是它现在的家，这几种情况下
     *   移动其实什么都没做。若界面一律说「已移动」，等于在系统拒绝执行时装作成功 ——
     *   那比直接报错更糟，因为它会让人以为数据改了。
     */
    fun movePhotos(photoIds: List<Long>, targetEventId: Int?): Int

    /**
     * 把照片**复制**到另一个事件：目标事件多一份，原事件保持不变。
     *
     * ★ 为什么需要「复制」而不只是「移动」：一张照片经常同时是两个事件的证据
     *   （例如同一张图里既有护栏损坏又有路面积水）。移动会毁掉其中一条记录的证据链，
     *   而导出要求每条事件都有照片 —— 所以复制才是对的语义。导出时两处各有一份。
     * ★ 复制不改变「这张照片归谁」：原事件仍是它的家，台账两格仍优先从家里取。
     *
     * 返回实际新增的份数（已属于该事件、或照片 id 不存在的都会被忽略）。
     */
    fun copyPhotos(photoIds: List<Long>, targetEventId: Int): Int

    /** 设置台账照片槽位（slot=1 或 2）；[photoId] 为 null 表示恢复该侧默认（最早/最晚）。 */
    fun markLedgerPhoto(eventId: Int, slot: Int, photoId: Long?)

    /** 清空全部人工调整（恢复自动分类结果）。 */
    fun resetManualAdjustments()

    // ── 导出 ──────────────────────────────────────────────────────────────
    /** 导出（异步）。导出期间 [AppState.status] 为 EXPORTING，其他写操作被忽略。 */
    fun export(request: ExportRequest, listener: ExportListener): OperationHandle

    /** 打开导出目录中的台账（通过平台外壳）；无台账返回 false。 */
    fun openLedger(exportResult: ExportResult): Boolean

    /** 在文件管理器中定位导出目录。 */
    fun revealExportDir(exportResult: ExportResult): Boolean

    /** 消费提示消息（UI 展示完 Snackbar 后调用，避免重复弹出）。 */
    fun clearMessage()

    /** 关闭：刷盘日志文本、释放执行器。关闭后再调用其他方法无效果。 */
    override fun close()
}
