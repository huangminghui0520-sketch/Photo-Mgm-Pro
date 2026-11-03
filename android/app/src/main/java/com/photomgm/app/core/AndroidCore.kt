// app/core/AndroidCore.kt —— ★ 安卓端**组装根**（T11：ViewModel 从"实现"退化为"平台编排"）
//
// 这个文件承接的是 `AppViewModel` 里那 1200 行中的**编排部分**：
// 解析日志、扫 MediaStore、粗筛、派生去重、EXIF 精读、GPS 分组、分类、视图重算、台账、
// 三态展开、人工调整 —— 它们现在都在 `shared/app` + `shared/domain`，与桌面端**共用同一份**。
//
// 剩下的事情只有三件，全部是平台形态（与 `DesktopCore` 的判断完全同构）：
//   1. **长任务在哪跑**：安卓是 `viewModelScope` + Job（桌面是 Executor + OperationHandle）；
//   2. **状态发到哪**：`AppStateStore.state` → 由 ViewModel 投影成 `UiState` 给 Compose；
//   3. **关闭时释放什么**：协程作用域由 ViewModel 负责，本类不持线程。
//
// ★ 为什么本类不实现共享的 `PhotoMgmCore` 门面：
//   门面要 `OperationHandle`（可取消句柄），而安卓的取消是 `Job.cancel()`；
//   硬套只会多一层"把 Job 包成句柄"的适配，且安卓 UI 从不需要跨页持有导出句柄。
//   （计划 §15.5 的结论：两端各 ~60 行装配，比一个"线程/协程二选一"都不像的共享实现便宜。）
//
// ★ 装配顺序与桌面端一致，理由也一致：
//   ① 先建存储适配器并**立刻 load()** → 首屏要不要显示历史日志/上次的分析结果由它决定；
//   ② 再建初始快照，`dedupMode` 从 settings 取（两者曾各自初始化，导致预览与台账不同口径）；
//   ③ 恢复缓存里的会话状态（安卓独有：关掉 App 再打开要能看见上次的事件与分类）；
//   ④ 最后接用例。
package com.photomgm.app.core

import algorithm.CandidatePhoto
import algorithm.ClassifyResult
import app.api.AppState
import app.api.CoreStatus
import app.api.ProgressListener
import app.api.Settings
import app.infra.DebouncedTextStore
import app.state.AppStateStore
import app.state.PhotoSession
import app.usecase.AdjustUseCase
import app.usecase.AnalysisPipeline
import app.usecase.PhotoScan
import app.usecase.SettingsUseCase
import app.usecase.ViewPublisher
import android.content.Context
import com.photomgm.app.SettingsStore
import com.photomgm.app.core.adapter.AndroidLogTextStorage
import com.photomgm.app.core.adapter.AndroidPhotoIdSource
import com.photomgm.app.core.adapter.AndroidSettingsStorage
import com.photomgm.data.ExifPhotoReader
import com.photomgm.data.FileHasher
import com.photomgm.data.MediaStorePhotoSourceProvider
import domain.adjust.ManualState
import domain.view.ViewDerivation
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate

/**
 * 安卓端核心（装配根）。
 *
 * @param logsProvider 日志防抖器的**取值函数**。测试会整体替换 ViewModel 上的实例
 *   （`SetLogsTextWiringTest` 注入计数型防抖器证明"接线"），所以每次使用时才取值。
 *   见 `SettingsUseCase` 构造参数的说明 —— 与 T10 的 `verifyMode` 是同一类修正。
 */
class AndroidCore(
    context: Context,
    private val logsProvider: () -> DebouncedTextStore,
) {
    private val appContext = context.applicationContext

    // ── 存储适配器（介质层在 :data，业务语义在 SettingsStore） ────────────
    /** 设置 + 状态缓存的业务层。本类**直接**用它存取派生缓存（那不是 `Settings` 契约的一部分）。 */
    val settingsStore = SettingsStore(appContext)
    private val settingsStorage = AndroidSettingsStorage(settingsStore)
    private val logStorage = AndroidLogTextStorage(settingsStore)

    // ── 照片端口（T06 已归位到 :data） ────────────────────────────────────
    private val sourceProvider = MediaStorePhotoSourceProvider(appContext.contentResolver)
    private val exifReader = ExifPhotoReader()
    private val hasher: (String) -> String? = FileHasher()::sha256
    private val photoIds = AndroidPhotoIdSource()

    // ── 状态与会话 ────────────────────────────────────────────────────────
    /** 一轮分析的会话状态（ViewModel 只读它做投影，不直接改）。 */
    val session = PhotoSession()

    val store: AppStateStore = AppStateStore(
        AppStateStore.initial(settings = settingsStorage.load(), logText = logStorage.load()),
    )

    val state: StateFlow<AppState> = store.state

    // ── 用例 ──────────────────────────────────────────────────────────────
    private val adjust = AdjustUseCase(store, session)
    private val settingsUseCase = SettingsUseCase(store, session, settingsStorage) { logsProvider() }

    init {
        restoreFromCache()
    }

    // ── 分析 ──────────────────────────────────────────────────────────────

    /**
     * 跑一遍完整流水线（**阻塞**，调用方决定跑在哪个协程上）。
     *
     * ★ 每次调用都新建 [AnalysisPipeline]：它是**无状态的依赖聚合**（构造只做字段赋值），
     *   而日志防抖器可以在运行期被替换。把 pipeline 缓存成字段会让防抖器回到"构造期冻结"
     *   的老问题（见 [logsProvider] 说明）。构造开销是一次对象分配，可忽略。
     */
    fun analyze(listener: ProgressListener?, isCancelled: () -> Boolean) {
        AnalysisPipeline(
            store = store,
            session = session,
            logs = logsProvider(),
            sourceProvider = sourceProvider,
            exifReader = exifReader,
            hasher = hasher,
            photoIds = photoIds,
        ).analyze(listener, isCancelled)
    }

    /**
     * 只扫描照片（**不**解析日志、**不**分类）：安卓「改巡查日期 / 改源目录」走这条。
     *
     * ★ 为什么不能直接用 [analyze]：安卓的既有语义是"选日期只扫照片，分类由『解析日志』触发"。
     *   用 [analyze] 会顺手解析日志并分类，于是 `parsed` 提前变成非空，
     *   `parseLogs` 的"日志没改过 → 只清人工移动"快路径会误判，用户点解析只得到一句
     *   "已是最初分类状态"，事件列表永远空。详见 `AnalysisPipeline.scanPhotos` 的说明。
     */
    fun scanPhotos(listener: ProgressListener?, isCancelled: () -> Boolean) {
        AnalysisPipeline(
            store = store,
            session = session,
            logs = logsProvider(),
            sourceProvider = sourceProvider,
            exifReader = exifReader,
            hasher = hasher,
            photoIds = photoIds,
        ).scanPhotos(listener, isCancelled)
    }

    /**
     * 当前照片的 id 集合（实时查询 MediaStore）。
     *
     * 用途只有一处：**「只清人工移动」快路径**的照片增删校验 ——
     * 日志文本没改但照片被删/加了，旧 classify 会引用失效 URI（界面显示灰色方块），
     * 必须落回完整流水线。集合比对是这条判定唯一可靠的依据。
     *
     * ★ 判定走共享的 [PhotoScan.coarseCandidates]，与 `AnalysisPipeline` 内部是**同一份实现**
     *   （T11 前安卓另有一个 `PhotoRepository` 各写一份，连 provider/hasher 都是各自的实例；
     *   判定一旦漂移就会出现"校验说没变、重扫却扫出另一批"这种最难排查的故障，
     *   表现为人工调整错位到别的照片上）。该类已随 T11 删除。
     */
    fun currentPhotoIds(date: LocalDate, sourceDirs: List<String>): Set<Long> =
        candidates(date, sourceDirs).mapTo(HashSet()) { it.id }

    /** 粗筛候选（与 `AnalysisPipeline` 同源；`AppViewModelTest` 的冒烟链路也走这里）。 */
    fun candidates(date: LocalDate, sourceDirs: List<String>): List<CandidatePhoto> =
        if (sourceDirs.isEmpty()) emptyList()
        else PhotoScan.coarseCandidates(sourceProvider, sourceDirs, date, hasher).photos

    // ── 设置 ──────────────────────────────────────────────────────────────

    /** 原子更新设置（含落盘 + 「已失效，请重新分析」提示）。 */
    fun updateSettings(block: (Settings) -> Settings) = settingsUseCase.updateSettings(block)

    /**
     * 静默更新「扫描范围」（巡查日期 / 源目录）——**不发**"请重新分析"提示。
     *
     * ★ 为何不复用 [updateSettings]：那个入口面向"用户在设置页改了一项"，
     *   会给出一句"设置已更新：请重新分析"。而安卓的日期/源目录变更会**立即自动重跑**流水线
     *   （`AppViewModel.autoPipeline`），提示是多余的 —— 更糟的是它会被后续
     *   `ViewPublisher.publish(message = null)` 原样保留（那里不覆盖既有提示），
     *   用户会看到一条永远不消失的"请重新分析"，紧接着照片已经刷出来了。
     */
    fun updateScanScope(block: (Settings) -> Settings) = store.withLock {
        val after = block(store.current().settings)
        settingsStorage.save(after)
        store.publish { it.copy(settings = after, dedupMode = after.dedupMode) }
    }

    /** 三态去重模式切换（同步重算输出层，不重跑扫描/EXIF/分类）。 */
    fun setDedupMode(mode: algorithm.DedupKeepMode) = settingsUseCase.setDedupMode(mode)

    fun setLogText(text: String) = settingsUseCase.setLogText(text)

    /**
     * 清空日志文本（只清内存态与状态快照，**不**触发防抖落盘）。
     *
     * ★ 不能复用 [setLogText]：那会调 `DebouncedTextStore.onChanged("")` 排一次落盘，
     *   而"清空日志"的语义是**取消**待落盘 + 立刻把空串写下去。混用会多排一次写，
     *   `SetLogsTextWiringTest` 的「删了又回来」判据正是盯着这个窗口。
     */
    fun clearLogText() = store.withLock {
        store.publish { it.copy(logText = "") }
    }

    fun flushLogs() = settingsUseCase.flushLogs()

    // ── 人工调整（§8.3） ──────────────────────────────────────────────────

    /** 批量移动；`targetEventId = null` 表示移出所有事件回到未匹配池。 */
    fun movePhotos(photoIds: List<Long>, targetEventId: Int?): Int =
        adjust.movePhotos(photoIds, targetEventId)

    fun markLedgerPhoto(eventId: Int, slot: Int, photoId: Long?) =
        adjust.markLedgerPhoto(eventId, slot, photoId)

    /** 清空全部人工调整（恢复自动分类结果）。 */
    fun resetManualAdjustments() = adjust.resetManualAdjustments()

    /**
     * 记录人工移动，**不经** [AdjustUseCase] 的准入判据。
     *
     * `AdjustUseCase.movePhotos` 在 `session.classify == null` 时直接返回 0 ——
     * 那条判据说的是"没有分类结果就没有归属概念，移动没有意义"。生产路径上确实等价：
     * UI 只在有分类时才给出移动入口。
     *
     * 但 `overrideMap` 本身是"照片 id → 目标事件"的独立叠加层，**记录它不需要分类结果**。
     * T11 之前安卓的实现是无条件写入的，`ViewDerivationParityTest` 正是对着这个行为写的
     * （它不跑分析、直接构造 `ClassifyResult` 后调 `movePhoto`）。
     * 为不改动既有语义，这里保留写入能力；分类已就绪时仍走 [AdjustUseCase]（带计数与提示）。
     *
     * ★ 刻意不做 `knownPhotoIds` 过滤 —— 那个集合来自分类结果，此刻它为空，
     *   过滤会把所有 id 都滤掉（等于什么都不做）。
     */
    fun recordManualMove(photoIds: List<Long>, targetEventId: Int?) {
        if (photoIds.isEmpty()) return
        store.withLock {
            val target = targetEventId ?: -1
            val map = LinkedHashMap(session.manual.overrideMap)
            photoIds.forEach { map[it] = target }
            // 移动即归属变更：旧的复制份必须一起清掉（与 ManualAdjustment.move 同一条语义）
            val copies = HashMap(session.manual.copyMap)
            photoIds.forEach { copies.remove(it) }
            session.manual = session.manual.copy(overrideMap = map, copyMap = copies)
        }
    }

    // ── 派生视图（UI 只读这两个，与导出/台账同源） ────────────────────────

    /**
     * 三态展开后的事件照片表（预览用，与导出 ZIP 用同一份口径）。
     *
     * @param c 分类结果。传参而不是取 `session.classify`，是为了保住既有签名语义：
     *   UI 从 `state.classify` 拿到的那份快照，与"当前会话"理论上是同一个引用。
     */
    fun previewEventMap(c: ClassifyResult): Map<Int, List<Long>> = store.withLock {
        ViewDerivation.expandedEventMap(
            c = c,
            adj = session.manual.toLayer(),
            mode = store.current().dedupMode,
            dupGroups = session.dupGroups(),
            marked = session.manual.marked,
        )
    }

    /** 有效未匹配照片（算法未匹配 + 被人工移除 − 被人工移入事件）。 */
    fun effectiveUnmatched(c: ClassifyResult): List<Long> = store.withLock {
        ViewDerivation.effectiveUnmatched(c, session.manual.toLayer())
    }

    /** 仅合并人工移动（不含三态展开）的事件照片表；用于"三态折叠掉几张"这类统计日志。 */
    fun effectiveMap(c: ClassifyResult): Map<Int, List<Long>> = store.withLock {
        ViewDerivation.effectiveMap(c, session.manual.toLayer())
    }

    /**
     * 原地重发一次视图（会话没变、只是想让 `AppState` 与 `session` 对齐）。
     *
     * 对应旧实现的 `buildLedger()`：早先台账由 ViewModel 异步重建，还要"取消旧任务 + isActive 检查"
     * 防晚到覆盖。共享层的发布是**同步且一次性**的，这些竞态措施随之不存在。
     */
    fun republish() = store.withLock {
        if (session.classify != null && session.parsed != null) {
            ViewPublisher.publish(store, session, CoreStatus.READY)
        }
    }

    /**
     * 清空本轮分析结果（日志/日期/源目录/照片集合发生变化时调用）。
     *
     * ★ 必须**连 `parsed` 一起清**：事件时间戳是基于旧日期算出来的 epoch，
     *   换日期后继续显示旧事件，用户点"导出"会把新日期的照片配旧日期的事件名 —— 产物是错的。
     *   ★ 同步清空 `AppState` 里的派生列：只清会话不清状态，界面会停留在上一批事件的渲染结果上。
     */
    fun invalidateAnalysis() = store.withLock {
        session.parsed = null
        session.classify = null
        session.clearPhotos()
        session.resetCounters()
        store.publish {
            it.copy(
                status = CoreStatus.IDLE,
                progress = null,
                parsed = null,
                analysis = null,
                events = emptyList(),
                photos = emptyList(),
                eventPhotoIds = emptyMap(),
                unmatchedPhotoIds = emptyList(),
                ledger = emptyList(),
            )
        }
    }

    // ── 提示 ──────────────────────────────────────────────────────────────

    fun notify(message: String) = store.withLock {
        store.publish { it.copy(message = message) }
    }

    fun clearMessage() = store.withLock {
        store.publish { it.copy(message = null, error = null) }
    }

    // ── 状态缓存（§8.4.2：打开 App 恢复关闭前的分析结果） ────────────────

    /**
     * 把当前会话写进持久缓存（分析完成 / 人工调整后调用）。
     *
     * ★ 先在锁内**取出快照**，再在锁外写盘：写 SharedPreferences 是毫秒级 IO，
     *   持锁写会把 UI 线程的 `publish` 一起堵住（旧实现也是锁外写）。
     *   取快照用一次 `withLock` 而不是四次，避免读到"新 parsed + 旧 classify"的拼接态。
     */
    fun persistCache() {
        val snap = store.withLock {
            CacheSnapshot(
                parsed = session.parsed,
                classify = session.classify,
                photos = session.photos,
                coarseCount = session.candidateCount,
            )
        }
        runCatching {
            settingsStore.parsedCache = snap.parsed
            settingsStore.classifyCache = snap.classify
            settingsStore.photosCache = snap.photos
            settingsStore.coarseCountCache = snap.coarseCount
            // ★ issue #2 / P0：缓存里是 epoch 毫秒与 GPS 值，必须打上"这批数据是在什么语义下算的"
            //   版本位（业务时区 + 媒体位置权限）。不打位的话，改过时区或修过权限的老用户
            //   会一直吃旧缓存，修复对他们完全无效。
            settingsStore.markCacheVersion()
        }
    }

    /** 清空派生缓存（巡查日期 / 源目录变更、清空日志时调用；不动人工调整与设置）。 */
    fun clearCache() {
        runCatching { settingsStore.clearStateCache() }
    }

    private data class CacheSnapshot(
        val parsed: algorithm.ParsedLog?,
        val classify: ClassifyResult?,
        val photos: List<algorithm.model.Photo>,
        val coarseCount: Int,
    )

    /**
     * 从持久缓存恢复会话状态（构造时调用一次）。
     *
     * ★ 恢复后**必须**重发一次视图：`session` 有数据但 `AppState` 还是空的，
     *   否则界面拿到的是"会话有 30 张照片、状态里一个事件都没有"的半成品。
     *   这里直接复用 `ViewPublisher`（T11 起公开）—— 与用例内部是同一份投影，
     *   不做第二套实现。
     *
     * ★ 不重跑流水线：恢复的意义就是"打开就能看"，重扫 MediaStore + 重读 EXIF 要好几秒。
     *   照片增删的感知交给调用方（ViewModel 启动后做一次 id 集合校验）。
     *
     * ★ 人工调整（移动 / 台账标记）**必须在此处、且在任何 early-return 之前**载入。
     *   它是独立于分析结果的叠加层，落盘在 `photo_override` / `photo_marked` 两个 key 里。
     *   T11 第一提交搬运恢复逻辑时漏了这一段（旧实现在 `AppViewModel.init` 里无条件读回），
     *   真机 12 项清单第 ⑪ 项当场抓到：杀进程重开后移动与标记全部失效、界面回到自动分类，
     *   而 prefs 里的数据其实还在 —— 属于**静默丢用户操作**，比崩溃更难发现。
     *   `copyMap` 不落盘（与旧实现一致），恢复后为 empty。
     */
    private fun restoreFromCache() {
        session.manual = ManualState(
            overrideMap = runCatching { settingsStore.overrideMap }.getOrNull().orEmpty(),
            marked = runCatching { settingsStore.markedMap }.getOrNull().orEmpty(),
        )
        val parsed = runCatching { settingsStore.parsedCache }.getOrNull() ?: return
        val classify = runCatching { settingsStore.classifyCache }.getOrNull()
        val photos = runCatching { settingsStore.photosCache }.getOrNull().orEmpty()
        val coarse = runCatching { settingsStore.coarseCountCache }.getOrNull()

        store.withLock {
            session.parsed = parsed
            session.classify = classify
            session.setPhotos(photos)
            session.candidateCount = coarse ?: photos.size
            if (classify != null) {
                ViewPublisher.publish(store, session, CoreStatus.READY)
            } else {
                store.publish { it.copy(status = CoreStatus.IDLE) }
            }
        }
    }

    // ── 供测试与排障 ──────────────────────────────────────────────────────

    /** 当前设置的快照（UI 不需要，测试与装配检查用）。 */
    fun currentSettings(): Settings = store.current().settings
}
