// app/AppViewModel.kt —— UI 状态 + 编排（§8）
// ★ 核心调用一律经 AlgorithmApi 单向调用（§2.4）；本类只做状态聚合与平台侧编排
package com.photomgm.app

import algorithm.AlgorithmApi
import algorithm.AppZone
import algorithm.ClassifyResult
import algorithm.CandidatePhoto
import algorithm.DedupConfig
import algorithm.DedupKeepMode
import algorithm.ParsedLog
import algorithm.PhotoIngest
import algorithm.expandForKeepMode
import algorithm.groupDupContent
import algorithm.model.Photo
import algorithm.model.LedgerRow
import algorithm.model.LogEvent
import android.app.Application
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

data class UiState(
    val date: LocalDate = LocalDate.now(AppZone.ID),
    val sourceDirs: List<String> = emptyList(),
    val outputDir: String? = null,
    val namingTemplate: Int = 1,          // 0=事件类型，1=巡查日志内容（默认1）
    /** ★ 2026-10-24 B1：重复照片保留策略（带水印/不带水印/都保留），默认仅保留带水印。 */
    val dedupKeepMode: DedupKeepMode = DedupKeepMode.KEEP_WATERMARKED,
    val coarseCount: Int? = null,          // 粗筛「已筛出 N 张」
    val logsText: String = "",
    val parsed: ParsedLog? = null,
    val photos: List<Photo> = emptyList(),
    val classify: ClassifyResult? = null,
    val ledger: List<LedgerRow>? = null,
    val busy: Boolean = false,
    /** ★ 2026-10-07 P2：流水线分阶段进度文案（busy 时在进度条下方显示；完成/失败置 null） */
    val progressStage: String? = null,
    val message: String? = null,
    /** 人工移动：photoId → 目标事件（§8.3），持久化在 SettingsStore */
    val overrideMap: Map<Long, Int> = emptyMap(),
    /** 台账照片标记：eventId → (照片1,照片2)，null 侧=默认最早+最晚（§7/§8.2） */
    val marked: Map<Int, Pair<Long?, Long?>> = emptyMap(),
    /** 导出进度 0..1；null=无进行中导出（§8.2 进度条） */
    val exportProgress: Float? = null,
    /** 导出完成信息（§7.4 完成页「成功N/失败M」） */
    val exportMessage: String? = null,
    /**
     * ★ 2026-10-31 审查修复：本次导出是否成功（null=尚未导出过）。
     *   [exportMessage] 在成功与失败时都非 null，底栏无法据此区分；失败却显示「导出完成 ✓」
     *   会让用户误以为产物已生成并据此外发。本字段是唯一的成功/失败判据。
     */
    val exportOk: Boolean? = null,
    /** 最近一次导出文件（用户可见位置：content:// 或本地路径），供「打开」入口 */
    val lastExport: String? = null,
    /**
     * ★ P0（2026-11-02）：本次会话**能否读到照片的真实位置信息**。
     *   null = 尚未判定（权限对话框还没回来）；
     *   false = 系统会剥离 EXIF GPS → 去重降级为「时间 + 文件名序号」、导出照片无定位。
     *   仅用于「说清降级口径」（设置页提示 / 权限回调提示），不参与算法分支：
     *   算法侧看的是 [Photo.latitude] 本身是否为 null，这条只是让用户知道**为什么**。
     */
    val geoAvailable: Boolean? = null,
)

/**
 * 缩略图预加载上限。
 * 超过这个数量就放弃预加载、交回事件卡片的惰性加载：
 * 一次解码上百张小图会长时间占用内存与主线程，收益不如让用户滚动时按需解码。
 */
private const val THUMB_PRELOAD_MAX = 500

/**
 * ★ 2026-10-08 P1-4：台账缩略图解码并发数（CPU 密集）。
 * 单张解码峰值约 2MB（已采样 800×600），4 并发瞬时 ≈ 8MB，安全；真机可调。
 */
private const val THUMB_DECODE_CONCURRENCY = 4

/**
 * ★ 构造函数必须**只有 (Application) 一个参数**。
 *
 * 原因：框架的 `AndroidViewModelFactory` 通过反射查找恰好为 `(Application)` 的构造函数。
 * 一旦增加第二个参数（即使它有默认值），工厂就找不到构造函数，抛出
 * `RuntimeException: Cannot create an instance of class AppViewModel`，
 * 而 MainActivity 第一行就是 `viewModel()` —— 结果是打开即闪退（2026-10-07 实际踩过）。
 *
 * 需要注入依赖时，用次级构造函数或工厂方法，**不要**动这个签名。
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SettingsStore(app)
    private val repo = PhotoRepository(app)

    /**
     * 日志文本落盘器（W3 / issue #4）：输入时只更新内存态，落盘防抖 400ms。
     * 见 [LogsTextStore] 头部说明——以前每敲一个字符就 MB 级全量重写 SharedPreferences。
     */
    internal var logsTextStore: LogsTextStore = LogsTextStore(
        persist = { store.logsText = it },
        scope = viewModelScope,
    )

    /**
     * 缩略图缓存。默认接 Coil 全局单例（见 [CoilThumbnailCache]）。
     *
     * ★ `internal` setter 仅供单元测试注入假实现以验证"先清后缓存"的顺序——
     *   因为构造函数签名不能被扩展（见上方注释），这是唯一安全的注入点。
     */
    internal var thumbCache: ThumbnailCache = CoilThumbnailCache(app)

    /**
     * ★ 供平台组件获取 applicationContext（§2.4 核心不碰平台，本类负责提供）。 */
    fun getApp(): Application = getApplication()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** ★ 自动流水线任务句柄：日期/源目录变更后触发，新变更取消旧任务，避免并发扫描。 */
    private var pipelineJob: Job? = null

    /** ★ P2-1：台账行重建任务句柄（parseLogs/runClassify 完成后触发）；避免并发重建晚到覆盖。 */
    private var ledgerJob: Job? = null

    /**
     * ★ 2026-10-31 审查修复：导出重入门禁（原子的 check-then-act）。
     *   false=空闲，true=正在导出。由 exportZip 在 launch 之前 CAS 置位；复位有**两处**：
     *   协程体的 finally（正常/异常/取消路径）+ 导出 Job 的 `invokeOnCompletion`
     *   （协程还没开始执行就被取消的路径 —— 那时 finally 根本不会跑）。
     *   不用 UiState.exportProgress 兼作门禁的原因见 exportZip 内注释（读-改-写非原子）。
     *
     *   `internal` 只为让测试能直接断言"取消后门禁确实释放了"。
     */
    internal val exportBusy = AtomicBoolean(false)

    /**
     * 导出协程所用的调度器（E / issue #6）。
     *
     * ★ 默认 `Dispatchers.IO` 即生产行为；注入点存在的唯一理由是**可测性**：
     *   原先 `viewModelScope.launch(Dispatchers.IO)` 写死，测试只能靠 `await { ... }` 轮询真实墙钟
     *   （本机 ~7.8s 且不确定），也无法构造"协程还没开始就被取消"这种时序。
     *   测试把它换成 `StandardTestDispatcher(testScheduler)`，配 `runTest` 精确推进虚拟时间。
     * ★ 与 [thumbCache]、[logsTextStore] 一样用 `internal var` 注入，不动构造函数签名
     *   （AndroidViewModelFactory 只认 `(Application)` 单参构造，见类头注释）。
     */
    internal var exportDispatcher: CoroutineDispatcher = Dispatchers.IO

    /**
     * ★ 2026-10-31 复核：导出 / 解析互斥的**另一半**（补全此前只有半边的门禁）。
     *
     *   已有的一半：exportZip 开头检查 `state.value.busy` —— 解析流水线跑着就不许导出。
     *   缺失的一半："导出跑着就不许重启解析"。不补全的后果：导出过程中改日期/改源目录/点解析，
     *   流水线会把 photos / classify / ledger 清空重算，而导出协程手里握的是**入口时抓的快照 s**，
     *   仍按 s.photos 逐张读盘打包——于是 ZIP 与界面、与台账三方不再对应；更糟的是断点续传
     *   状态文件按日期命名，新旧两批照片会在同一份续传状态里交叉，下次导出跳过本该打包的照片。
     *
     *   ★ 判据用 [exportBusy] 而不是 state.value.exportProgress：后者是**UI 状态**，
     *     提前返回分支会把它置 null，语义是"进度显示"，不是"是否正在导出"。
     *   ★ 只做同步判断 + 提示，不引入锁：解析与导出各跑各的协程，这里只是拒绝"启动"这一刻，
     *     成本是一次 volatile 读。
     *
     * @return true = 被导出挡住，调用方应直接 return（不启动流水线）
     */
    private fun blockedByExport(): Boolean {
        if (!exportBusy.get()) return false
        _state.update { it.copy(message = "导出进行中，请等待导出完成后再操作") }
        return true
    }

    /**
     * ★ P2-2：dupGroupsOf 结果缓存——照片 List 引用未变（集合未变）则复用，
     *   避免三态切换/预览/导出每次全量 O(n²) 重算。groupDupContent 为纯函数（幂等），
     *   重复计算一次的结果一致。
     *
     * ★ 2026-10-31 审查修复：ref 与 result 封装成**不可变** [DupGroupsCache]，
     *   用单个 [AtomicReference] 整体替换——一次引用赋值发布，读侧永远拿到一致快照。
     *   原实现是「ref / result 两个独立 @Volatile 字段」：写入顺序为 ref 先、result 后，
     *   而 previewEventMap 在 UI 线程、导出在 IO 线程并发调用——线程 A 写完 ref 尚未写 result 时，
     *   线程 B 看到 ref 相同就会返回**上一批照片**的分组结果，三态展开据此算出错的 keep/drop 集合
     *   （台账与 ZIP 照片错乱）。
     *   ★ 注：@Volatile + 不可变对象本就已构成安全发布（与 AtomicReference.set 等价），
     *     改用 AtomicReference 是为了让"整体原子替换"的意图显式化，并为将来改成
     *     compareAndSet 的失效策略留出接口；当前行为不变。
     */
    private data class DupGroupsCache(
        val ref: List<Photo>,
        val result: Map<Long, List<Photo>>,
    )
    private val dupGroupsCache = AtomicReference<DupGroupsCache?>(null)

    /** ★ 单日照片缓存（进程内）：只保留**当前巡查日期**一份，换日期即覆盖（2026-10-07 采纳建议）。
     *   跨进程重启由 SettingsStore 状态缓存兜底。 */
    private data class DatePhotoCache(val date: LocalDate, val photos: List<Photo>, val coarseCount: Int)
    private var photoCache: DatePhotoCache? = null

    init {
        _state.update {
            it.copy(
                date = store.date, sourceDirs = store.sourceDirs,
                outputDir = store.outputDir, namingTemplate = store.namingTemplate,
                dedupKeepMode = store.dedupKeepMode,
                logsText = store.logsText, overrideMap = store.overrideMap,
                marked = store.markedMap,
                // ★ 2026-10-07 需求：打开 App 从缓存恢复关闭前的事件列表 + 分类 + 照片，
                //   恢复后 coarseCount 非 null → ensurePipeline 视为已粗筛而跳过，不重跑流水线
                parsed = store.parsedCache,
                classify = store.classifyCache,
                photos = store.photosCache,
                coarseCount = store.coarseCountCache,
                // ★ P0：权限落盘是持久的，init 即可同步判定（不必等权限对话框回调），
                //   让设置页在首帧就显示正确的降级口径。
                geoAvailable = currentGeoAvailable(),
            )
        }
        // ★ 2026-10-07 防线一：恢复的照片**异步强制校验**——进程重启期间照片可能被删除/新增
        //   （ContentObserver 收不到），靠这里发现：实时粗筛 ID 集合不一致 → 清照片/分类缓存 → 自动重扫。
        //   parsed（事件列表）与照片无关，保留。
        viewModelScope.launch(Dispatchers.IO) {
            val s0 = state.value
            if (s0.sourceDirs.isEmpty() || s0.photos.isEmpty()) return@launch
            val latest = repo.coarseCandidates(s0.date, s0.sourceDirs)
            if (latest.map { it.id }.toSet() != s0.photos.map { it.id }.toSet()) {
                store.clearStateCache()
                _state.update {
                    it.copy(photos = emptyList(), coarseCount = null,
                        classify = null, exportMessage = null)
                }
                autoPipeline()
            }
        }
    }

    // ---------- 设置（§8.4 设置即存） ----------
    /**
     * ★ 巡查日期变更：自动触发 粗筛→精读（无需手动点击）；同时清空下游避免旧分类配新日期导出错乱。
     * 清粗筛缓存（§4.4）→ 确保从 MediaStore 重新查询最新照片，再自动重新匹配。
     *
     * ★ 同时清空缩略图缓存：换日期后上一批照片的缩略图已无用，且会白占内存额度。
     *   顺序为「清缓存 → 读取照片信息 → 预缓存新缩略图」，见 clearThumbnailCache / autoPipeline。
     *
     * ★ 2026-10-07（P1）：日期变更 = 旧解析/分类缓存全部失效（事件时间戳基于旧日期）。
     *   内存 parsed 一并清空——界面回到"该日期尚未解析日志"，不残留旧日期事件；
     *   日期照片缓存（datePhotoCache）保留，切回该日期秒恢复、不重扫。
     */
    fun setDate(d: LocalDate) {
        // ★ 日期没变就不重跑：避免重复选同一天时清掉刚缓存的缩略图、导致界面闪一下重新解码
        if (d == store.date) return
        store.date = d
        clearThumbnailCache()
        store.clearStateCache()
        _state.update {
            it.copy(date = d, parsed = null, classify = null, ledger = null, exportMessage = null,
                photos = emptyList(), coarseCount = null)
        }
        autoPipeline()
    }

    /** ★ 源目录变更：自动触发 粗筛→精读（照片集合已变，清空下游）。清缓存后重新查询最新照片。
     *  ★ 单日缓存：源目录变化 → 照片缓存失效（同日期不同源目录照片不同），清空 photoCache。 */
    fun addSourceDir(dir: String) {
        val dirs = (state.value.sourceDirs + dir).distinct()
        store.sourceDirs = dirs
        clearThumbnailCache()
        store.clearStateCache()
        photoCache = null
        _state.update { it.copy(sourceDirs = dirs, parsed = null, classify = null, ledger = null, exportMessage = null) }
        autoPipeline()
    }

    fun removeSourceDir(dir: String) {
        val dirs = state.value.sourceDirs - dir
        store.sourceDirs = dirs
        clearThumbnailCache()
        store.clearStateCache()
        photoCache = null
        _state.update { it.copy(sourceDirs = dirs, parsed = null, classify = null, ledger = null, exportMessage = null) }
        autoPipeline()
    }

    /**
     * ★ 自动流水线：**粗筛必跑（强制实时）** → 校验单日缓存 ID 集合 → 一致复用 photos（跳过 EXIF）/
     *   不一致或无缓存重新精读。**不做自动分类**（2026-10-07 需求：分类统一由「解析日志」触发）。
     * 变更即触发；取消旧任务防止快速连续操作导致旧结果覆盖新结果。
     */
    private fun autoPipeline() {
        // ★ 2026-10-31 复核：与导出互斥的半边（导出期间不重启扫描流水线）
        if (blockedByExport()) return
        pipelineJob?.cancel()
        pipelineJob = viewModelScope.launch(Dispatchers.IO) {
            try {
            val s = state.value
            if (s.sourceDirs.isEmpty()) {
                _state.update { it.copy(coarseCount = null, photos = emptyList(), busy = false, progressStage = null) }
                return@launch
            }
            _state.update {
                it.copy(busy = true, coarseCount = null, photos = emptyList(),
                    classify = null, ledger = null, exportMessage = null,
                    overrideMap = emptyMap(), progressStage = "扫描照片中…")
            }
            // ★ 2026-10-07 与 parseLogs 同构：日期/源目录变化 = 全新一批照片与事件，上一批人工移动作废
            store.overrideMap = emptyMap()
            // ★ 防线一：强制实时粗筛（P3-4：粗筛已无 TTL 缓存，每次直接查 MediaStore）——拿最新 ID 集合，删除/新增照片立即感知
            val cands = repo.coarseCandidates(s.date, s.sourceDirs)
            if (cands.isEmpty()) {
                photoCache = null
                _state.update {
                    it.copy(coarseCount = 0, busy = false, progressStage = null,
                        message = "所选日期（${s.date}）在源目录未找到照片，请检查日期或源目录")
                }
                saveStateCache(state.value.parsed, null, emptyList(), 0)
                return@launch
            }
            // ★ 校验：单日缓存命中且 ID 集合一致 → 复用（跳过 EXIF 精读）；否则重新精读
            val cached = photoCache
            val cachedIds = cached?.photos?.map { it.id }?.toSet() ?: emptySet()
            val latestIds = cands.map { it.id }.toSet()
            val photos = if (cached != null && cached.date == s.date && latestIds == cachedIds) {
                cached.photos
            } else {
                _state.update { it.copy(progressStage = "读取照片信息中…") }
                repo.readExif(cands)
            }
            _state.update { it.copy(coarseCount = cands.size, photos = photos) }
            // ★ 照片信息读取完成 → 预缓存本批缩略图（旧缓存已在 setDate/addSourceDir 阶段清空）
            preloadThumbnailCache(photos)
            // ★ 单日缓存：覆盖为当前日期
            photoCache = DatePhotoCache(s.date, photos, cands.size)
            // ★ 2026-10-07 需求：选取日期/源目录变更后**不做自动重新分类**。
            //   旧日志事件的时间戳基于旧日期，对新日期照片分类无意义（会大量误入未匹配）。
            //   分类统一由「解析日志」动作触发（parseLogs 内自动重新分类并写缓存）。
            //   这里仅缓存照片元数据；classify 保持 null（界面显示"照片尚未就绪"引导重新解析）。
            val parsed = state.value.parsed
            _state.update {
                it.copy(busy = false, progressStage = null,
                    // ★ P2：该日期尚无解析结果时给引导
                    message = if (parsed == null && photos.isNotEmpty())
                        "已加载 ${s.date} 的照片 ${photos.size} 张，粘贴日志后点「解析日志」即自动分类"
                    else null)
            }
            saveStateCache(parsed, null, photos, cands.size)
            } catch (e: CancellationException) {
                throw e   // ★ 协程取消必须重抛（结构化并发），不吞
            } catch (e: Exception) {
                // ★ 2026-10-31 崩溃防御：照片扫描流水线任何未预期异常不得杀死应用
                android.util.Log.w("Photo-Mgm", "扫描照片失败", e)
                _state.update {
                    it.copy(busy = false, progressStage = null,
                        message = "扫描照片失败：${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
    }

    /**
     * ★ 清空缩略图缓存（内存 + 磁盘）。
     *
     * 调用时机：巡查日期变更、源目录增删——即"上一批照片已经作废"的时刻。
     * 顺序：clearThumbnailCache() → 粗筛 → 精读 EXIF → preloadThumbnailCache()
     * 必须先清后读，否则新照片的预缓存会和上一批的残留混在同一份缓存里，旧图白占额度。
     */
    private fun clearThumbnailCache() {
        runCatching { thumbCache.clear() }
    }

    /**
     * ★ 预缓存本批照片的缩略图，写入内存缓存供事件卡片直接命中。
     * 时机：EXIF 精读之后（此时才知道本批照片的真实路径）。
     * 预加载在 Coil 内部异步进行，此处不阻塞流水线。
     */
    private fun preloadThumbnailCache(photos: List<Photo>) {
        runCatching {
            thumbCache.preload(
                refs = photos.map { it.sourceRef },
                maxCount = THUMB_PRELOAD_MAX,
            )
        }
    }

    fun setOutputDir(d: String?) { store.outputDir = d; _state.update { it.copy(outputDir = d) } }

    fun setNamingTemplate(t: Int) { store.namingTemplate = t; _state.update { it.copy(namingTemplate = t) } }

    /**
     * ★ 2026-10-24 B1：重复照片保留策略切换（带水印 / 不带水印 / 都保留）。
     * 仅作用于输出层（台账/ZIP）：不重跑粗筛/EXIF/分组/分类；已分类事件照片按三态展开，
     * 人工标记（marked）优先保留；未匹配照片不参与、全量保留。
     */
    fun setDedupKeepMode(mode: DedupKeepMode) {
        if (mode == state.value.dedupKeepMode) return
        store.dedupKeepMode = mode
        // ★ P2-1：台账与模式在同一事务内同步重算（当前规模毫秒级），替代原异步 launch——
        //   原实现每次切换异步重建台账，快速连切（A→B→A）时旧协程晚到覆盖新协程，
        //   导致底栏台账行与当前模式错配。同步计算天然无竞态。
        _state.update { cur ->
            val next = cur.copy(dedupKeepMode = mode)
            if (next.classify != null) next.copy(ledger = computeLedger(next)) else next
        }
        // 口径提示：合并 N 组 / 单份 M 张不受影响
        val s = state.value
        val dup = dupGroupsOf(s)
        val inGroups = dup.values.sumOf { it.size + 1 }
        val single = s.photos.size - inGroups
        // ★ P3-7：KEEP_BOTH 实际"全部保留、不合并"，文案不得再说"已合并"（避免误导）
        val msg = when (mode) {
            DedupKeepMode.KEEP_WATERMARKED ->
                "已合并 ${dup.size} 组重复照片（保留带水印版）；单份照片 $single 张不受影响"
            DedupKeepMode.KEEP_PLAIN ->
                "已合并 ${dup.size} 组重复照片（保留不带水印版）；单份照片 $single 张不受影响"
            DedupKeepMode.KEEP_BOTH ->
                "重复照片全部保留（共 ${dup.size} 组，每组带水印/不带水印双份）；单份照片 $single 张不受影响"
        }
        _state.update { it.copy(message = msg) }
    }

    // ---------- SAF 目录选择（§8.2 系统选择器选取，不再手打路径） ----------

    /**
     * 源目录选择：OpenDocumentTree 返回 content://…/tree/primary%3ADCIM%2FCamera，
     * 从 document id 提取相对路径（primary:DCIM/Camera → DCIM/Camera）供 MediaStore 查询。
     */
    fun pickSourceDir(uri: Uri) {
        val seg = uri.lastPathSegment ?: return
        val rel = Uri.decode(seg).substringAfter(':').trim('/')
        if (rel.isNotBlank()) addSourceDir(rel)
    }

    /** 输出目录选择：持久化 SAF 树授权（重启后可继续读写）。 */
    fun pickOutputDir(uri: Uri) {
        runCatching {
            getApp().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        setOutputDir(uri.toString())
    }

    // ---------- 照片流程（§4：粗筛 → 精读；被动触发，无手动按钮） ----------
    /**
     * ★ 被动触发兜底（切换到日志页时调用）：源目录 + 日期就绪且「尚未完成粗筛」时，
     * 补一次 粗筛→精读→分类 流水线，避免首次进入日志页时照片列表为空。
     * 已粗筛（coarseCount != null）则跳过——日期/源目录变更已由 setDate/addSourceDir/removeSourceDir 触发，
     * 防止每次切页重复扫描 MediaStore 与 EXIF。
     */
    fun ensurePipeline() {
        val s = state.value
        if (s.sourceDirs.isEmpty() || s.busy || s.coarseCount != null) return
        autoPipeline()
    }

    // ---------- 日志（§3：粘贴 → 解析，如实显示不脱敏） ----------
    /**
     * 日志文本变化（W3 / issue #4）。
     *
     * ★ 只更新内存态，落盘交给 [logsTextStore] 防抖：输入框的即时反馈不受写盘影响，
     *   连打时只在停手后写一次。解析用的是 `state.logsText`（内存态），因此"刚打完就点解析"
     *   拿到的一定是最新文本，不依赖落盘是否已完成。
     */
    fun setLogsText(text: String) {
        _state.update { it.copy(logsText = text) }
        logsTextStore.onChanged(text)
    }

    /** ★ 删除日志：清文本 + 清解析/匹配结果（P3-4：粗筛已无 TTL 缓存，无需再清），避免旧匹配残留。 */
    fun clearLogs() {
        // ★ W3：必须先取消待落盘，否则防抖窗口到期会把刚删掉的旧文本写回去（表现为"删了又回来"）
        logsTextStore.cancelPending()
        store.logsText = ""
        store.clearStateCache()
        _state.update { it.copy(logsText = "", parsed = null, classify = null, ledger = null, exportMessage = null) }
    }

    /**
     * W3：ViewModel 销毁前把待落盘的日志文本刷下去 —— 防抖窗口还没到期就退出 App，
     * 也不能丢最后一次输入。
     */
    override fun onCleared() {
        logsTextStore.flush()
        super.onCleared()
    }

    /**
     * ★ 2026-10-07 需求重定义「解析日志」（P0：照片流水线与日志解析解耦）：
     *   - 快路径：日志已解析、文本未改动、已有分类、**照片未增删**（防线一校验）→ **只清人工移动表
     *     （overrideMap）**，分类恢复为最初自动分类状态；**不重跑粗筛/精读/分类**（classify 从未被
     *     overrideMap 修改，它只是叠加层，清掉即恢复最初分类）。
     *   - 照片复用：**强制实时粗筛校验一致**（防线一：删除/新增照片立即感知）→ 只重新解析文本 +
     *     用现有照片重新分类，**不重扫 MediaStore / 不重读 EXIF / 不清缩略图缓存**（毫秒级）。
     *   - 完整流水线：照片未就绪或**校验不一致（照片已增删）**→ 清缓存重新 粗筛→精读→分类。
     * ★ 2026-10-08 修复：快路径前置照片校验——否则删除照片后文本未改仍命中快路径，
     *   旧 classify 引用已删照片 URI → 事件网格显示灰色方块缩略图。
     */
    fun parseLogs() {
        // ★ 2026-10-31 复核：与导出互斥的半边（导出期间不重启解析流水线）
        if (blockedByExport()) return
        val s = state.value
        pipelineJob?.cancel()
        pipelineJob = viewModelScope.launch(Dispatchers.IO) {
            try {
            // ── 快路径：已解析 + 已分类 + 文本未变 + 照片未增删 → 只清人工移动表 ──
            if (s.parsed != null && s.classify != null && s.logsText == store.parsedLogsText) {
                // ★ 防线一：照片校验——删除/新增照片后即使文本未改也要重扫重分类，
                //   避免旧 classify 引用失效 URI（灰块）。
                val photosUnchanged = s.sourceDirs.isEmpty() || s.photos.isEmpty() ||
                    repo.coarseCandidates(s.date, s.sourceDirs)
                        .map { it.id }.toSet() == s.photos.map { it.id }.toSet()
                if (photosUnchanged) {
                    val n = s.overrideMap.size
                    store.overrideMap = emptyMap()
                    _state.update {
                        it.copy(
                            overrideMap = emptyMap(),
                            message = if (n > 0) "已恢复最初分类（清除 $n 条人工移动）" else "已是最初分类状态",
                        )
                    }
                    // ★ P3-8：人工移动被清空 → 分类恢复最初状态，同步刷新台账行（底栏计数随最新分类）
                    buildLedger()
                    return@launch
                }
                // 照片已增删 → 落入下方完整流水线（清缓存重扫 + 重新解析分类）
            }
            store.overrideMap = emptyMap()
            // ★ 记录本次解析的文本快照（供"只清人工移动"分支判定日志是否被修改）
            store.parsedLogsText = s.logsText
            _state.update {
                it.copy(busy = true, parsed = null, classify = null, ledger = null,
                    exportMessage = null, overrideMap = emptyMap(), progressStage = "解析日志中…")
            }
            val p = AlgorithmApi.parseLog(s.logsText, s.date, AppZone.ID)
            // ── 照片获取：防线一（强制实时粗筛）决定 复用 or 完整重扫 ──
            if (s.sourceDirs.isEmpty()) {
                _state.update { it.copy(parsed = p, busy = false, progressStage = null) }
                saveStateCache(p, null, emptyList(), 0)
                return@launch
            }
            val latestCands = repo.coarseCandidates(s.date, s.sourceDirs)
            if (latestCands.isEmpty()) {
                photoCache = null
                _state.update {
                    it.copy(parsed = p, coarseCount = 0, photos = emptyList(), busy = false,
                        progressStage = null,
                        message = "所选日期（${s.date}）在源目录未找到照片，请检查日期或源目录")
                }
                saveStateCache(p, null, emptyList(), 0)
                return@launch
            }
            val latestIds = latestCands.map { it.id }.toSet()
            val photos: List<Photo>
            val coarse: Int
            if (s.photos.isNotEmpty() && latestIds == s.photos.map { it.id }.toSet()) {
                // ★ 校验一致 → 复用现有照片（不重扫、不清缩略图缓存）
                photos = s.photos
                coarse = s.coarseCount ?: s.photos.size
            } else {
                // 照片未就绪或已增删 → 完整精读（粗筛已实时跑过）
                clearThumbnailCache()
                _state.update { it.copy(parsed = p, progressStage = "读取照片信息中…") }
                photos = repo.readExif(latestCands)
                coarse = latestCands.size
            }
            // ★ 同步进程内单日缓存
            photoCache = DatePhotoCache(s.date, photos, coarse)
            preloadThumbnailCache(photos)
            // ★ 日志已解析 + 照片已就绪 → 自动重新分类（重新解析日志后无需手动点「生成分类」）
            _state.update { it.copy(parsed = p, progressStage = "分类中…") }
            val g = AlgorithmApi.gpsGroup(photos)
            val c = AlgorithmApi.classify(p.events, g.clusters, g.singles, s.date, g.splitCount, zone = AppZone.ID)
            _state.update {
                it.copy(coarseCount = coarse, photos = photos, classify = c, busy = false,
                    progressStage = null)
            }
            // ★ 流水线完成 → 写状态缓存（打开 App 恢复用）
            saveStateCache(p, c, photos, coarse)
            // ★ P3-8：解析日志自动分类完成 → 同步生成台账行（底栏「台账 N 行」不再等导出才回填）
            buildLedger()
            } catch (e: CancellationException) {
                throw e   // ★ 协程取消必须重抛（结构化并发），不吞
            } catch (e: Exception) {
                // ★ 2026-10-31 崩溃防御：解析流水线任何未预期异常（含事件列表为空时 classify 抛
                //   NoSuchElementException）都不得杀死应用——记录日志 + 置状态为可读错误提示
                android.util.Log.w("Photo-Mgm", "解析日志失败", e)
                _state.update {
                    it.copy(busy = false, progressStage = null,
                        message = "解析日志失败：${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
    }

    /**
     * ★ 2026-10-07 防线二：手动刷新照片缓存（设置页入口）。
     * 清进程内单日缓存 + 缩略图缓存 + 持久化状态缓存，强制完整重扫（P3-4：粗筛已无 TTL 缓存）。
     * 事件列表（parsed）保留——照片变化不影响日志事件；重新解析即自动重新分类。
     */
    fun refreshPhotoCache() {
        photoCache = null
        clearThumbnailCache()
        store.clearStateCache()
        _state.update {
            it.copy(photos = emptyList(), coarseCount = null,
                classify = null, ledger = null, exportMessage = null)
        }
        autoPipeline()
    }

    // ---------- 分类 + 台账（§5/§6/§7） ----------
    fun runClassify() {
        // ★ 2026-10-31 复核：与导出互斥的半边（导出期间不重算分类，否则导出手里的快照与状态错位）
        if (blockedByExport()) return
        viewModelScope.launch(Dispatchers.Default) {
            if (state.value.busy) return@launch
            val s = state.value
            val parsed = s.parsed ?: run { _state.update { it.copy(message = "请先解析日志") }; return@launch }
            if (s.photos.isEmpty()) { _state.update { it.copy(message = "无照片（请先粗筛并精读）") }; return@launch }
            // ★ 2026-10-07 修复：手动「生成分类」= 全新分配，上一批人工移动（overrideMap）作废
            store.overrideMap = emptyMap()
            _state.update { it.copy(busy = true, classify = null, overrideMap = emptyMap(), progressStage = "分类中…") }
            val g = AlgorithmApi.gpsGroup(s.photos)
            val c = AlgorithmApi.classify(parsed.events, g.clusters, g.singles, s.date, g.splitCount, zone = AppZone.ID)
            _state.update { it.copy(classify = c, busy = false, progressStage = null) }
            // ★ 2026-10-07：手动生成分类也写状态缓存（打开 App 恢复最新分类）
            saveStateCache(parsed, c, s.photos, s.coarseCount ?: s.photos.size)
            // ★ P3-8：手动分类完成 → 同步刷新台账行
            buildLedger()
        }
    }

    /**
     * ★ 2026-10-08 技术债统一：台账行生成**唯一入口**（photoById 建表 O(1) + null 保护）。
     * buildLedger() 与 prepareLedger() 都走这里，避免两处生成逻辑分叉。
     */
    private fun buildRows(s: UiState, eventPhotoMap: Map<Int, List<Long>>): List<LedgerRow>? {
        val c = s.classify ?: return null
        val parsed = s.parsed ?: return null
        val photoById: Map<Long, Photo> = s.photos.associateBy { it.id }
        // ★ 传入人工台账标记，否则「设为台账」在导出时被静默忽略；
        //   namingTemplate 保证台账照片列"文件夹名/文件名"前缀与 ZIP 分类文件夹一致（P1-3）
        return AlgorithmApi.buildLedger(parsed.events, eventPhotoMap, photoById::get, s.marked, s.namingTemplate, AppZone.ID)
    }

    // ---------- ★ 2026-10-24 B1：输出层三态展开（分类不变，仅台账/ZIP 生效） ----------

    /** 判重配置：时间容差 1s + GPS 距离 10m（Dedup.kt 默认值）。 */
    private fun dedupConfig(): DedupConfig = DedupConfig()

    /**
     * 内容判重分组（全量照片）。
     * ★ P2-2：结果按照片 List 引用缓存——照片集合未变（同引用）直接复用，
     *   三态切换/预览/导出多次调用不再每次 O(n²) 重算；集合变化（新 List）自动失效重算。
     */
    private fun dupGroupsOf(s: UiState): Map<Long, List<Photo>> {
        val photos = s.photos
        // ★ 读侧（2026-10-31 复核点）：**只 get() 一次**，拿到的是一个不可变快照对象，
        //   再从这同一个对象上取 ref 与 result —— 两者必然同生共死。
        //   ⚠ 严禁写成下面这种两次 get() 的形式：
        //       if (dupGroupsCache.get()?.ref === photos) return dupGroupsCache.get()!!.result
        //     两次 get() 之间缓存可能已被别的线程整体替换，于是"新 photos 配旧 result"，
        //     写侧的一次性原子发布就被读侧抵消了（症状与修复前完全一样，且更难复现）。
        val snapshot = dupGroupsCache.get()
        if (snapshot != null && snapshot.ref === photos) return snapshot.result
        val g = PhotoIngest.groupDupContent(photos, dedupConfig())
        // ★ 一次引用赋值发布（ref 与 result 同生共死），杜绝"ref 已更新、result 仍陈旧"的中间态
        dupGroupsCache.set(DupGroupsCache(photos, g))
        return g
    }

    /**
     * 已分类事件照片按三态展开（人工标记优先；未匹配不参与）。
     * @param c 分类结果（含人工移动后的 effectiveMap 作为基准）
     */
    private fun expandedEventMap(s: UiState, c: ClassifyResult, mode: DedupKeepMode): Map<Int, List<Long>> {
        val effective = effectiveMap(c)
        if (mode == DedupKeepMode.KEEP_BOTH) return effective   // 都保留 = 不去重
        val dup = dupGroupsOf(s)
        val keepAlways = s.marked.values.flatMap { listOfNotNull(it.first, it.second) }.toSet()
        // ★ 2026-10-31 B：人工移动过的照片（overrideMap 键）作为直通集合——
        //   三态展开不再改写它们（P1），且"保留侧代表被移走 → 整组不展示"（P2 幽灵照片 / C 冒出备份）。
        // ★ 2026-10-31 C：passthrough 只收「真正离开原事件」的移动——
        //   target == 原所属事件 = 原地移动（UI 上等于没动），不算人工意图；若算直通，该照片会绕过
        //   三态过滤（KEEP_PLAIN 下"原地移动主图"会让主图与 _original 同时展示）。
        //   target < 0（移除到未匹配）视同离开；原本在未匹配池、被移入事件（origin=null）也算离开。
        val originEventOf = HashMap<Long, Int>()
        for ((eventId, ids) in c.eventPhotoMap) for (pid in ids) originEventOf[pid] = eventId
        val passthrough = s.overrideMap.filter { (pid, target) -> target < 0 || originEventOf[pid] != target }.keys
        return effective.mapValues { (_, ids) -> PhotoIngest.expandForKeepMode(ids, dup, mode, keepAlways, passthrough) }
    }

    /**
     * ★ 2026-10-24 B1：预览数据源（事件卡片 + 事件照片弹窗）。
     * 与导出完全一致：人工移动 + 三态展开（已分类事件照片），未匹配不参与。
     * UI 读本函数而非 effectiveMap，三态切换即时反映到预览。
     */
    fun previewEventMap(c: ClassifyResult): Map<Int, List<Long>> =
        expandedEventMap(state.value, c, state.value.dedupKeepMode)

    /**
     * ★ P2-1：台账行计算（纯函数，供 setDedupKeepMode 同步调用与 buildLedger 异步调用共用，
     *   避免两处生成逻辑分叉；以传入快照为准，保证 ledger 与调用时刻的 mode 一致）。
     */
    private fun computeLedger(s: UiState): List<LedgerRow>? {
        val c = s.classify ?: return null
        return buildRows(s, expandedEventMap(s, c, s.dedupKeepMode))
    }

    /**
     * 台账行异步重建（parseLogs/runClassify 完成后调用）。
     * ★ P2-1：先取消旧任务 + update 前 isActive 检查，防止并发重建晚到覆盖。
     * 注意：三态切换不走本函数（setDedupKeepMode 同步计算），避免快速连切竞态。
     */
    fun buildLedger() {
        ledgerJob?.cancel()
        ledgerJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                val rows = computeLedger(state.value) ?: return@launch
                if (isActive) _state.update { it.copy(ledger = rows) }
            } catch (e: CancellationException) {
                throw e   // ★ 协程取消必须重抛（结构化并发），不吞
            } catch (e: Exception) {
                // ★ 2026-10-31 崩溃防御：台账重算任何未预期异常不得杀死应用
                android.util.Log.w("Photo-Mgm", "台账生成失败", e)
            }
        }
    }

    // ---------- 人工调整（§8.3） ----------
    /** 把照片移动到目标事件（从原位置移除，含 unmatched）。 */
    fun movePhoto(photoId: Long, targetEventId: Int) {
        val map = state.value.overrideMap.toMutableMap()
        map[photoId] = targetEventId
        store.overrideMap = map
        _state.update { it.copy(overrideMap = map) }
    }

    /** ★ 批量移动照片到目标事件（§8.3 多选移动）。 */
    fun movePhotos(photoIds: List<Long>, targetEventId: Int) {
        if (photoIds.isEmpty()) return
        val map = state.value.overrideMap.toMutableMap()
        photoIds.forEach { map[it] = targetEventId }
        store.overrideMap = map
        _state.update { it.copy(overrideMap = map) }
    }

    /**
     * ★ 从事件移除照片 → 回到未匹配照片池（§8.3 弹窗「移除」）。
     * 仅调整分组归属，不删除源文件；overrideMap 记 -1 表示「移出所有事件」。
     */
    fun removePhotosFromEvent(photoIds: List<Long>) {
        if (photoIds.isEmpty()) return
        val map = state.value.overrideMap.toMutableMap()
        photoIds.forEach { map[it] = -1 }
        store.overrideMap = map
        _state.update { it.copy(overrideMap = map) }
    }

    /** ★ 重置全部人工移动（2026-10-01 新增）：清空 overrideMap，恢复纯自动分类结果（撤销所有手动调整）。 */
    fun resetOverrides() {
        store.overrideMap = emptyMap()
        _state.update { it.copy(overrideMap = emptyMap(), message = "已重置全部人工移动，恢复自动分类结果") }
    }

    fun clearMessage() { _state.update { it.copy(message = null, exportMessage = null) } }

    /** ★ UI 轻提示（Snackbar）：供界面层校验类提示（如禁止跨事件移动）使用。 */
    fun notify(msg: String) { _state.update { it.copy(message = msg) } }

    // ---------- 权限（★ P0，2026-11-02） ----------

    /** 当前是否持有「照片位置信息」权限（API < 29 无此概念，视为可用）。 */
    private fun currentGeoAvailable(): Boolean {
        val geo = MediaPermissions.plan(Build.VERSION.SDK_INT).geo ?: return true
        return ContextCompat.checkSelfPermission(getApp(), geo) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 媒体权限申请结果回调（MainActivity 的 RequestMultiplePermissions 转交）。
     *
     * 职责有两个，缺一不可：
     *  1. 记录 [UiState.geoAvailable]，供设置页说明"为什么重复判定降级了"；
     *  2. 未授予位置权限时**主动提示降级口径**——静默降级会让用户把"去重结果"当准的用。
     *
     * ★ 刻意不在"新获得权限"时自动重扫：本轮照片已按旧口径解析完毕，静默重扫会打断用户
     *   （还可能要重新下载缩略图）。改成提示用户重新解析一次，见 [HINT_GEO_UNAVAILABLE] 的通知路径。
     */
    fun onMediaPermissionsResult(granted: Map<String, Boolean>, sdkInt: Int) {
        val available = MediaPermissions.geoAvailable(sdkInt, granted)
        val wasAvailable = state.value.geoAvailable
        _state.update { it.copy(geoAvailable = available) }
        if (!available) {
            notify(MediaPermissions.HINT_GEO_UNAVAILABLE)
        } else if (wasAvailable == false) {
            // 用户刚在系统设置里补授了权限 → 上一轮照片是按"无 GPS"解析的，提示重解析
            notify("已获得照片位置信息权限，请重新解析一次以更新定位与去重判定")
        }
    }

    // ---------- 状态缓存（§8.4.2：打开 App 恢复事件列表 + 分类） ----------

    /** ★ 写入状态缓存（事件列表 + 分类 + 照片 + 粗筛数）。JSON 序列化由 SettingsStore 完成。 */
    private fun saveStateCache(
        parsed: algorithm.ParsedLog?,
        classify: algorithm.ClassifyResult?,
        photos: List<algorithm.model.Photo>,
        coarseCount: Int,
    ) {
        runCatching {
            store.parsedCache = parsed
            store.classifyCache = classify
            store.photosCache = photos
            store.coarseCountCache = coarseCount
            // ★ issue #2：这四份缓存里的 epoch 都是按业务时区算出来的，写缓存时打上版本位；
            //   读侧发现版本位与当前业务时区不一致 → 视为无缓存，重算。
            // ★ P0（2026-11-02）：同一处再打 GPS 语义版本位——旧包读不到定位却把 null 当真值缓存在案，
            //   不打这个位的话，老用户升级后打开 App 直接吃旧缓存，权限修了也白修。
            store.markCacheVersion()
        }
    }

    // ---------- 导出（§7.3 Excel / §7.4 ZIP，经 AlgorithmApi 单向调用核心） ----------

    /**
     * 台账内容准备（§7.3，ZIP 导出使用）：
     * buildRows → 并行解码照片字节（4:3 裁剪 400px JPEG50）→ 返回 (rows, 图片字节缓存, 调试统计)。
     * 失败返回 null。
     * @param eventPhotoMap 打包前一刻的最终分类快照（台账与 ZIP 共用，保证照片一致）
     * @param onProgress 图片预收集进度回调（0.1..0.7 区间；★ 2026-10-08 并行 + 每 5 张节流）
     */
    private suspend fun prepareLedger(
        s: UiState,
        eventPhotoMap: Map<Int, List<Long>>,
        onProgress: (Float) -> Unit = {},
    ): Triple<List<LedgerRow>, Map<String, ByteArray?>, String>? {
        return try {
            val rows = buildRows(s, eventPhotoMap) ?: return null
            // ★ 分区存储：sourceRef 是 MediaStore DATA 物理路径，Android 10+ 无法直接 decodeFile，
            //   用 Photo.id 构造 content://media/... URI 读取（App 有 READ_MEDIA_IMAGES 权限）。
            val ctx = getApp()
            val photoByRef: Map<String, Photo> = s.photos.associateBy { it.sourceRef }
            val refs = rows.flatMap { listOfNotNull(it.photo1Ref, it.photo2Ref) }.distinct()
            if (refs.isEmpty()) return Triple(rows, emptyMap(), "图片:成功0/失败0")
            // ★ 2026-10-08 P1-4/P1-6：并行解码（固定并发，Dispatchers.Default 自然限流）
            //   async 任务按 refs 顺序 await 保序；计数/容器并发安全；进度每 5 张一跳。
            val done = AtomicInteger(0); val ok = AtomicInteger(0); val fail = AtomicInteger(0)
            val failures = ConcurrentLinkedQueue<String>()
            val bytesCache = ConcurrentHashMap<String, ByteArray?>()
            val total = refs.size
            coroutineScope {
                refs.map { ref ->
                    async(Dispatchers.Default) {
                        val photo = photoByRef[ref]
                        val readRef = photo?.let { p ->
                            if (ref.startsWith("/") || ref.startsWith("file:"))
                                ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, p.id).toString()
                            else ref
                        } ?: ref
                        val bytes = ImageUtils.process(ctx, readRef)
                        if (bytes != null && bytes.isNotEmpty()) ok.incrementAndGet() else {
                            fail.incrementAndGet()
                            failures.add("${ref.substringAfterLast('/')}(found=${photo != null},id=${photo?.id})")
                        }
                        bytesCache[ref] = bytes
                        val n = done.incrementAndGet()
                        if (n % 5 == 0 || n == total) onProgress(0.1f + 0.6f * n / total.coerceAtLeast(1))
                    }
                }.forEach { it.await() }
            }
            val imgDebug = "图片:成功${ok.get()}/失败${fail.get()}" +
                if (failures.isNotEmpty()) " 失败[${failures.joinToString(",")}]" else ""
            Triple(rows, bytesCache, imgDebug)
        } catch (e: CancellationException) {
            // ★ 2026-10-31 审查修复：取消必须重抛（同 exportZip）。
            //   否则协程已取消，本函数却返回 null 让 exportZip 继续跑完剩余的图片解码与打包。
            throw e
        } catch (e: Exception) {
            // ★ P3-9：记录异常（此前静默吞错，真机"台账未生成"无日志可查）
            android.util.Log.w("Photo-Mgm", "prepareLedger 失败（台账未生成）", e)
            null
        }
    }

    /** ★ 打开最近导出的台账文件（导出Excel 2.0 方案 §4.4：成功后提供「打开」）。
     *  用 Toast 直接提示（不依赖页面滚动位置），用 createChooser 不预检 resolveActivity。 */
    fun openExportedFile() {
        val shown = state.value.lastExport ?: return
        val app = getApp()
        val type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        val uri = if (shown.startsWith("content://")) {
            Uri.parse(shown)
        } else {
            runCatching {
                androidx.core.content.FileProvider.getUriForFile(
                    app, "${app.packageName}.fileprovider", File(shown)
                )
            }.getOrNull()
        }
        if (uri == null) {
            android.widget.Toast.makeText(app, "无法打开：$shown", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, type)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching {
            app.startActivity(Intent.createChooser(intent, "打开台账"))
        }.onFailure {
            android.widget.Toast.makeText(app, "未找到可打开表格的应用", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /** 导出 ZIP：按事件分类文件夹打包（命名模板由设置页选择），未匹配→未分类（§7.4）。
     *  ★ 2026-09-08 需求：压缩包同时包含 照片分类文件夹 + 台账 Excel（extraFiles 打入 ZIP）。
     *  核心 ZipExporter 仅收 File（PC 可移植）→ Android 侧先写临时文件，再复制到输出目标（SAF/路径）。 */
    fun exportZip() {
        // ★ 2026-10-31 审查修复：导出重入门禁，且必须在 launch 之外**同步**完成。
        //   原实现把 check-then-act 放在协程体内（Dispatchers.IO），两次快速点击会先后通过检查：
        //   两个导出协程共用同一个 tmpZip（文件名精确到秒，同秒同名）与同一个 stateFile，
        //   先结束者执行 tmpZip.delete() 会删掉对方正在写的文件 → ZIP 损坏或"导出失败"，
        //   断点续传状态也会互相覆盖。busy 只由解析流水线置位、导出期间为 false，拦不住重入。
        //
        // ★ 2026-10-31 复核补充：门禁改用 [exportBusy].compareAndSet 做**原子** check-then-act。
        //   原写法是「读 state.value → _state.update」两步：StateFlow 各自的读/写是原子的，
        //   但两步之间不是——若 exportZip 将来被非主线程调用（如外部触发），仍有并发窗口。
        //   CAS 把"检查 + 置位"压成一个操作，跨线程、跨调用点都成立，且不引入锁。
        //   门禁内**不含任何 IO**：只读内存态 state.value、写 StateFlow、CAS 布尔量，
        //   因此放在调用线程（主线程）执行无卡顿风险。
        //
        //   ★ 为何没有把 UiState.busy 本身改成 AtomicBoolean：busy 是 UI 状态，被
        //     AppNav / LogInputSection / UnifiedScreen 直接读取渲染，改成原子量会穿透到
        //     UI 层并破坏状态提升（state 必须是不可变快照）。它的可见性由 MutableStateFlow
        //     保证（update 是 CAS 循环、value 读取拿到最新引用），作为**判据**是安全的；
        //     真正的"互斥"由 exportBusy 承担。
        if (state.value.busy) return
        if (!exportBusy.compareAndSet(false, true)) return   // 已有导出在跑 → 直接丢弃本次点击
        // 同时清掉上一次结果，避免旧文案误导
        _state.update { it.copy(exportProgress = 0f, exportMessage = null, exportOk = null) }
        // ★ E(#6)：调度器可注入（默认 Dispatchers.IO），测试用 StandardTestDispatcher 推进虚拟时间
        val exportJob = viewModelScope.launch(exportDispatcher) {
            // ★ 2026-10-31 复核补充：进度复位与门禁释放统一收口在 finally，
            //   不再依赖"每个提前 return 都记得手写复位"（漏一处 → 导出入口永久禁用）。
            //   取消时 finally 照常执行；exportBusy.set 是原子操作，不依赖协程是否还活着。
            try {
            val s = state.value
            // ★ 修复：未设置输出目录禁止导出（★ 提前返回必须复位 exportProgress，否则导出入口永久禁用）
            if (s.outputDir.isNullOrBlank()) {
                _state.update {
                    it.copy(message = "未设置输出目录，请先在「设置」页选择或输入输出目录后再导出",
                        exportProgress = null)
                }
                return@launch
            }
            val c = s.classify ?: run {
                _state.update { it.copy(message = "请先在预览页生成分类", exportProgress = null) }; return@launch
            }
            val parsed = s.parsed ?: run {
                _state.update { it.copy(message = "请先解析日志", exportProgress = null) }; return@launch
            }
        // ★ 2026-10-08 P0-3：临时文件声明移到 try 外——导出失败也必须清理（含原图，可达几十 MB）
        val fileName = "照片分类_${s.date.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"))}_" +
                java.time.LocalDateTime.now(AppZone.ID)
                    .format(java.time.format.DateTimeFormatter.ofPattern("HHmmss")) + ".zip"
        // ★ 断点续传状态文件与输出位置解耦（放 app 私有 files 目录），避免输出目录切换丢失
        val stateFile = File(getApp().filesDir, ".export_state_${s.date}.json")
        val tmpZip = File(getApp().cacheDir, fileName)
        try {
            // ★ 2026-10-08 P0-2：photoById 建表 O(1)（替代线性 find，与 photoByRef 对齐）
            val photoById: Map<Long, Photo> = s.photos.associateBy { it.id }
            // ★ 打包前一刻计算最终分类快照（合并人工移动/移除），台账与打包共用同一份，保证照片一致
            // ★ 2026-10-24 B1：已分类事件照片按三态展开（带水印/不带水印/都保留）；
            //   未匹配照片不参与三态、全量保留（effectiveUnmatched 原样）。
            //   ★ 已知边界（2026-10-31）：因此「仅保留带水印」时，未匹配或被人工移除（target<0）的
            //     _original 仍会进入 ZIP 的「未分类」文件夹。属既有设计约定（未匹配=待人工处理），
            //     收窄未匹配池属语义变更、需产品确认，此处先标注，勿误当 bug 改。
            val finalMap = expandedEventMap(s, c, s.dedupKeepMode)
            val finalUnmatched = effectiveUnmatched(c)
            val expandedTotal = finalMap.values.flatten().size + finalUnmatched.size   // 去重后张数
            // ★ 2026-10-31 C：记录被三态折叠/整组抑制掉的照片数——"照片怎么不见了"时的定位线索。
            //   仅在导出一刻记一次（预览会高频重算，避免日志刷屏）。
            if (s.dedupKeepMode != DedupKeepMode.KEEP_BOTH) {
                val beforeCount = effectiveMap(c).values.sumOf { it.size }
                val afterCount = finalMap.values.sumOf { it.size }
                if (beforeCount > afterCount)
                    android.util.Log.d("Photo-Mgm", "三态[${s.dedupKeepMode}] 折叠+整组抑制 ${beforeCount - afterCount} 张")
            }

            // ★ 台账 Excel 准备（用同一个 finalMap，保证台账照片 = 实际打包的照片）
            val prep = prepareLedger(s, finalMap) { frac -> _state.update { it.copy(exportProgress = 0.6f * frac) } }
            val xlsxBytes = prep?.let { (rows, cache, _) ->
                val bos = ByteArrayOutputStream()
                // ★ 2026-10-31：协程体内改用 runCatchingCancellable（取消信号不被吞）。
                //   本处包裹的是非 suspend 调用，今天不会抛取消；但它是导出协程里最重的一步，
                //   将来一旦加入挂起点（如切线程写盘）就会静默复现 S1，故按统一约定写。
                runCatchingCancellable { AlgorithmApi.exportLedgerXlsx(rows, bos) { ref -> cache[ref] } }
                    .getOrNull()?.let { bos.toByteArray() }
            }
            // ★ 台账文件名含巡查日期 + 时分秒：区分"导出时刻"与"巡查日期"，
            //   并避免同日二次导出时同名被静默覆盖（SAF findFile 会复用同名文档）。
            val now = java.time.LocalDateTime.now(AppZone.ID)
            val stamp = now.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
            val xlsxName = "巡查台账_${s.date.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"))}_$stamp.xlsx"
            val extraFiles = if (xlsxBytes != null && xlsxBytes.isNotEmpty()) mapOf(xlsxName to xlsxBytes) else emptyMap()
            // ★ 2026-10-18 P0-2：台账 xlsx 单独落缓存（App 外部私有目录，FileProvider 可打开），
            //   供导出后「打开台账」直接打开；ZIP 内的 xlsx 不便直开。台账失败时为 null。
            val xlsxCachePath = if (xlsxBytes != null && xlsxBytes.isNotEmpty()) {
                runCatchingCancellable {
                    val dir = File(getApp().getExternalFilesDir(null), "export").apply { mkdirs() }
                    val f = File(dir, xlsxName)
                    f.writeBytes(xlsxBytes)
                    f.absolutePath
                }.getOrNull()
            } else null

            val r = AlgorithmApi.exportZip(parsed.events, finalMap, finalUnmatched,
                photoById::get, s.namingTemplate, tmpZip, stateFile, extraFiles = extraFiles,
                zone = AppZone.ID) { frac, _ ->
                _state.update { it.copy(exportProgress = 0.6f + 0.4f * frac) }
            }
            // ★ 2026-10-18 P0-2：copyToOutput 返回 null = 输出目录不可写，结果里需提示；
            //   tmpZip 无论是否复制统一清理（断点续传跳过时也会残留空文件）。
            val shown = if (r.copied > 0 || r.failed > 0) copyToOutput(tmpZip, fileName) else null
            // ★ 2026-10-31：协程体内一律用 runCatchingCancellable（三处 tmpZip 清理统一）。
            //   delete() 是非挂起调用，今天不会抛取消；统一写法的好处是 CI 规则零例外，
            //   将来谁在这里插入一个挂起调用，取消语义仍是对的。
            runCatchingCancellable { tmpZip.delete() }
            val skipNote = if (r.skippedFromState > 0) "（跳过已完成 ${r.skippedFromState}）" else ""
            val writeNote = if (r.copied > 0 && shown == null) "（输出目录不可写）" else ""
            // ★ 2026-10-18 P0-3：打包失败明细摘要（前 2 条 + 总数），让用户知道缺了哪些照片、为什么
            val failNote = if (r.failedDetails.isNotEmpty()) {
                val shown2 = r.failedDetails.take(2).joinToString("；")
                val more = r.failedDetails.size - 2
                " 失败[${shown2}${if (more > 0) " 等${r.failedDetails.size}条" else ""}]"
            } else ""
            // ★ 2026-10-08 P0-1：Excel 生成结果附图片统计/失败明细，失败可诊断（不再静默降级）
            val xlsxNote = if (extraFiles.isNotEmpty()) {
                val dbg = prep?.third.orEmpty()
                if (dbg.isNotBlank()) " + 台账Excel（$dbg）" else " + 台账Excel"
            } else {
                "（台账未生成：${prep?.third ?: "准备失败"}）"
            }
            // ★ 2026-10-31 审查修复：成功判据——正常打包且写出到输出目录，或断点续传判定已完成
            //   （ZipExporter 跳过前已校验产物存在）。输出目录不可写（shown=null）仍属失败，
            //   不能因为"没抛异常"就报成功。
            val ok = (r.copied > 0 && shown != null) || r.skippedFromState > 0
            _state.update {
                it.copy(
                    // ★ 2026-10-24 B1：导出消息附去重后张数（三态展开后的实际打包数）
                    exportMessage = "ZIP 导出：成功 ${r.copied} / 失败 ${r.failed}（去重后共 $expandedTotal 张）$failNote$skipNote$xlsxNote$writeNote",
                    exportProgress = null,
                    exportOk = ok,
                    // ★ 2026-10-31：失败时一并把原因送进 Snackbar 通道（底栏只有一行，放不下失败明细），
                    //   否则界面只在底栏标红、用户拿不到任何可执行的信息。成功时不动既有提示。
                    message = if (ok) it.message else
                        "ZIP 导出未完成：${if (shown == null) "输出目录不可写，请在设置中重新选择" else "有照片未打包，请查看底栏明细"}",
                    // ★ 回填台账行：底部操作条要显示「台账 N 行」，也需要它判断导出完成。
                    //   以前此处不回填，导致 state.ledger 永远为 null、底栏永远显示"台账待生成"。
                    ledger = prep?.first ?: it.ledger,
                    // ★ 2026-10-18 P0-2：回填台账 xlsx 缓存路径（此前从未赋值 → 「打开台账」永远无响应）；
                    //   台账失败时为 null → UI 不显示「打开台账」入口。
                    lastExport = xlsxCachePath,
                )
            }
        } catch (e: CancellationException) {
            // ★ 2026-10-31 审查修复：取消必须重抛（结构化并发）。
            //   JVM 上 kotlinx.coroutines.CancellationException 是 java.util.concurrent.CancellationException
            //   的 typealias，继承 IllegalStateException → 会被下方 catch (Exception) 吞掉：
            //   协程已取消却仍把状态写成"ZIP 导出失败"，外层也感知不到取消。
            //   与 autoPipeline / parseLogs / buildLedger 的处理保持一致。
            runCatchingCancellable { tmpZip.delete() }
            _state.update { it.copy(exportProgress = null) }
            throw e
        } catch (e: Exception) {
            // ★ 2026-10-08 P0-3：失败也清理临时 ZIP（可能含全部原图，几十 MB）
            runCatchingCancellable { tmpZip.delete() }
            _state.update {
                it.copy(exportMessage = "ZIP 导出失败：${e.message}", exportProgress = null, exportOk = false)
            }
        }
        } finally {
            // ★ 2026-10-31 复核补充：**外层** try 的唯一出口释放门禁。
            //   必须包住提前 return 的分支（未设输出目录 / 未生成分类 / 未解析日志），否则这些分支
            //   return 后 exportBusy 永远不释放 → 导出按钮再也点不动（比原 bug 更隐蔽）。
            //   同时兜住 Error/Throwable：catch(Exception) 抓不到 OOM，此前会让 exportProgress
            //   卡在非 null，导出永久不可用且无任何提示。
            exportBusy.set(false)
            if (state.value.exportProgress != null) {
                _state.update { it.copy(exportProgress = null) }
            }
        }
        // ★ E(#6)：finally 只兜得住"body 至少跑过一次"的情形。协程在开始执行之前就被取消
        //   （ViewModel 销毁、或测试里 cancel 后直接推进调度器）时 body 一次都不跑，finally 不会触发，
        //   门禁会永远停在 true → 导出入口永久禁用且无任何提示。故再挂一层 Job 级兜底。
        }
        bindExportGateTo(exportJob)
    }

    /**
     * 门禁与进度的 **Job 级**兜底复位（与 exportZip 里 finally 的复位互为补充，重复执行无副作用）。
     *
     * 为什么必须在 Job 上再挂一次：`viewModelScope.launch(...)` 之后、协程真正开始执行之前，
     * Job 就可能被取消。此时协程体**一次都不跑**，写在体里的 finally 自然不触发 ——
     * 门禁从此永久占用，导出按钮再也点不动。`invokeOnCompletion` 挂在 Job 生命周期上：
     * body 跑没跑、正常结束、抛异常、被取消，都必定回调一次（对已完成 Job 注册也会立即回调）。
     */
    private fun bindExportGateTo(job: Job) {
        job.invokeOnCompletion {
            exportBusy.set(false)
            if (state.value.exportProgress != null) {
                _state.update { it.copy(exportProgress = null) }
            }
        }
    }

    private fun exportDir(): File {
        val custom = state.value.outputDir
        return if (!custom.isNullOrBlank() && !custom.startsWith("content://")) File(custom).also { it.mkdirs() }
        else File(getApp().getExternalFilesDir(null), "export").also { it.mkdirs() }
    }

    /** 打开输出写入流：SAF 树 URI → contentResolver；否则本地文件流。返回 (流, 用户可见位置)。 */
    private fun openOutputSink(fileName: String): Pair<java.io.OutputStream?, String> {
        val custom = state.value.outputDir
        if (!custom.isNullOrBlank() && custom.startsWith("content://")) {
            val parent = DocumentFile.fromTreeUri(getApp(), Uri.parse(custom)) ?: return null to custom
            val doc = parent.findFile(fileName) ?: parent.createFile("application/octet-stream", fileName)
                ?: return null to custom
            val os = getApp().contentResolver.openOutputStream(doc.uri)
            return os to doc.uri.toString()
        }
        val dir = exportDir().apply { mkdirs() }
        val f = File(dir, fileName)
        return f.outputStream() to f.absolutePath
    }

    /** 把已生成的临时文件复制到输出目标（SAF 树或本地路径）。返回用户可见位置；失败（目录不可写）返回 null。 */
    private fun copyToOutput(tmp: File, fileName: String): String? {
        val (os, shown) = openOutputSink(fileName)
        if (os == null) return null
        os.use { out -> tmp.inputStream().use { it.copyTo(out) } }
        return shown
    }

    private companion object {
        val BASIC_DATE: java.time.format.DateTimeFormatter =
            java.time.format.DateTimeFormatter.BASIC_ISO_DATE
    }

    /** 台账照片标记：slot=1 照片1 / slot=2 照片2；photoId=null 表示恢复默认（最早+最晚）。 */
    fun markLedgerPhoto(eventId: Int, slot: Int, photoId: Long?) {
        val cur = state.value.marked[eventId] ?: (null to null)
        val next = if (slot == 1) (photoId to cur.second) else (cur.first to photoId)
        val map = state.value.marked + (eventId to next)
        store.markedMap = map
        _state.update { it.copy(marked = map) }
    }

    /** ★ 2026-10-31 审查：原「台账照片字节供给」段（readPhotoBytes / compressForExcel）已删除。
     *  全仓库无任何调用方（台账图片实际走 prepareLedger → ImageUtils.process），属死代码；
     *  且它与现行实现重复定义了一套压缩参数（480px JPEG85 vs 400×300 JPEG50），
     *  留着会误导后续维护者改错地方。 */

    /** 合并 overrideMap 后的事件照片表（§8.3 移动兜底）。target=-1（移除）→ 从所有事件移除、不归入任何事件。 */
    fun effectiveMap(c: ClassifyResult): Map<Int, List<Long>> {
        val map = c.eventPhotoMap.mapValues { it.value.toMutableList() }.toMutableMap()
        val all = (c.eventPhotoMap.values.flatten() + c.unmatched).toSet()
        for ((pid, target) in state.value.overrideMap) {
            if (pid !in all) continue
            map.forEach { (_, v) -> v.remove(pid) }
            if (target >= 0) map.getOrPut(target) { mutableListOf() }.add(pid)
        }
        return map
    }

    /**
     * 有效未匹配照片（§8.3 移除兜底）：算法未匹配 + 被人工「从事件移除」（overrideMap=-1）的照片，
     * 再扣除已人工「移动到事件」（overrideMap>=0）的照片。
     * 导出 ZIP / 预览页未匹配池均以它为准，避免被移除的照片丢失、已移动的照片重复显示。
     */
    fun effectiveUnmatched(c: ClassifyResult): List<Long> {
        val all = (c.eventPhotoMap.values.flatten() + c.unmatched).toSet()
        val moved = state.value.overrideMap.filterValues { it >= 0 }.keys.toSet()
        val removed = state.value.overrideMap.filterValues { it < 0 }.keys.filter { it in all }
        return (c.unmatched.filter { it !in moved } + removed).distinct()
    }
}
