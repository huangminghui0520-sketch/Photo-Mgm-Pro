// desktop/core/logic/PhotoMgmCoreImpl.kt —— 逻辑核心实现（流水线编排）
//
// 本类是把 Android 端 AppViewModel 的**业务语义**搬到桌面端的落点，但不是逐行翻译：
//   · Android 的 UiState 混了 UI 关注点（busy/progressStage/缩略图预载/Coil），这里只保留业务状态；
//   · Android 用 viewModelScope + 协程；这里用注入的 Executor + 显式 OperationHandle，
//     因为核心不该依赖 Android 生命周期，也不该假设 UI 有协程作用域；
//   · 人工移动（overrideMap）/ 三态展开（KEEP_WATERMARKED 等）/ 台账标记的语义**逐条对齐**，
//     因为它们是产品语义，不是实现细节（见 effectiveMap / expandedEventMap / effectiveUnmatched）。
//
// 线程模型：所有公开方法可在任意线程调用；内部用一把锁保护会话字段，
// 长任务（分析/导出）在 executor 上跑，状态只通过 StateFlow 对外发布（每次都是完整快照）。
package com.photomgm.desktop.core.logic

import algorithm.AlgorithmApi
import app.infra.checkCancelled
import algorithm.ClassifyResult
import algorithm.DedupConfig
import algorithm.DedupKeepMode
import algorithm.ParsedLog
import algorithm.PhotoIngest
import algorithm.expandForKeepMode
import algorithm.groupDupContent
import algorithm.isDerivedPhotoName
import algorithm.model.LogEvent
import algorithm.model.Photo
import com.photomgm.desktop.core.api.AnalysisSummary
import com.photomgm.desktop.core.api.AppState
import com.photomgm.desktop.core.api.CoreConfig
import com.photomgm.desktop.core.api.CoreStatus
import com.photomgm.desktop.core.api.EventSnapshot
import com.photomgm.desktop.core.api.ExportListener
import com.photomgm.desktop.core.api.ExportProgress
import com.photomgm.desktop.core.api.ExportRequest
import com.photomgm.desktop.core.api.ExportResult
import com.photomgm.desktop.core.api.LedgerRowInfo
import com.photomgm.desktop.core.api.OperationHandle
import com.photomgm.desktop.core.api.ParsedSummary
import com.photomgm.desktop.core.api.PhotoInfo
import com.photomgm.desktop.core.api.PhotoMgmCore
import com.photomgm.desktop.core.api.Progress
import com.photomgm.desktop.core.api.ProgressListener
import com.photomgm.desktop.core.api.Settings
import com.photomgm.desktop.core.api.ShellLauncher
import com.photomgm.desktop.core.export.ExportBlockedException
import com.photomgm.desktop.core.export.PcFolderExporter
import com.photomgm.desktop.core.platform.PcExifReader
import com.photomgm.desktop.core.platform.PcImageEmbedder
import com.photomgm.desktop.core.platform.PcFileHasher
import com.photomgm.desktop.core.platform.PcPaths
import com.photomgm.desktop.core.platform.PcPhotoSourceProvider
import com.photomgm.desktop.core.platform.PcShell
import com.photomgm.desktop.core.platform.PhotoIdAllocator
import com.photomgm.desktop.core.state.LogsTextStore
import com.photomgm.desktop.core.state.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.concurrent.CancellationException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicBoolean

internal class PhotoMgmCoreImpl(private val config: CoreConfig) : PhotoMgmCore {

    private val zone = config.zone
    private val clock = config.clock ?: java.time.Clock.systemUTC()
    private val dataDir: Path = config.dataDir ?: PcPaths.appDataDir()

    private val lock = Any()

    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r -> daemonThread(r, "photomgm-io") }

    private val settingsRepo = SettingsRepository(dataDir.resolve("settings.json"))
    private val logsStore = LogsTextStore(dataDir.resolve("logs.txt"), scheduler)

    /** 长任务执行器：外部注入（测试用同步执行器）或核心自建单线程。 */
    private val executor: Executor = config.executor ?: Executors.newSingleThreadExecutor { r ->
        daemonThread(r, "photomgm-task")
    }
    private val ownsExecutor = config.executor == null

    private val sourceProvider = config.sourceProvider ?: PcPhotoSourceProvider()
    private val exifReader = config.exifReader ?: PcExifReader(zone)
    private val hasher: (String) -> String? = config.hasher ?: (PcFileHasher()::sha256)
    private val shell: ShellLauncher = config.shell ?: PcShell()
    private val embedder = PcImageEmbedder()

    // ── 会话字段（全部在 lock 保护下读写） ─────────────────────────────────
    private var logText: String = logsStore.load()
    private var parsed: ParsedLog? = null
    private var classifyResult: ClassifyResult? = null
    private var photos: List<Photo> = emptyList()
    private var photosById: Map<Long, Photo> = emptyMap()
    private val overrideMap = HashMap<Long, Int>()

    /**
     * 复制关系：照片 → 它额外所属的事件集合（"家"仍在 overrideMap / classify 里）。
     *
     * ★ 与 overrideMap 分开存，因为两者语义相反：override 是"改归属（唯一）"，
     *   copy 是"多归属（可有多个）"。合成一层会丢掉"哪一份是家"的区分，
     *   而台账选照片、三态展开都要用到它。
     */
    private val copyMap = HashMap<Long, MutableSet<Int>>()
    private val marked = HashMap<Int, Pair<Long?, Long?>>()
    private var candidateCount = 0
    private var mergedDerivedGroups = 0
    private var heicCount = 0
    private var clusterCount = 0

    // 内容判重分组缓存（按 photos 列表引用失效，避免三态切换每次 O(n²)）
    private var dupCacheRef: List<Photo>? = null
    private var dupCache: Map<Long, List<Photo>> = emptyMap()

    private var closed = false

    private val _state = MutableStateFlow(
        AppState(
            settings = settingsRepo.load(),
            logText = logText,
        ).let { it.copy(dedupMode = it.settings.dedupMode) },
    )
    override val state: StateFlow<AppState> = _state.asStateFlow()

    // ── 设置 ──────────────────────────────────────────────────────────────

    override fun updateSettings(block: (Settings) -> Settings) {
        synchronized(lock) {
            val before = _state.value.settings
            val after = block(before)
            settingsRepo.save(after)
            val invalidated = after.patrolDate != before.patrolDate || after.sourceDirs != before.sourceDirs
            _state.update {
                it.copy(
                    settings = after,
                    dedupMode = after.dedupMode,
                    message = when {
                        invalidated -> "设置已更新：请重新分析（巡查日期或源目录已变化）"
                        else -> it.message
                    },
                )
            }
            if (!invalidated) {
                // 命名模板/去重模式不影响分类，但影响台账与导出目录名 → 立即重算视图
                if (classifyResult != null) republishViews(CoreStatus.READY)
            }
        }
    }

    override fun setLogText(text: String) {
        synchronized(lock) {
            logText = text
            logsStore.onChanged(text)
            _state.update { it.copy(logText = text) }
        }
    }

    override fun setDedupMode(mode: DedupKeepMode) {
        synchronized(lock) {
            val s = _state.value
            if (s.dedupMode == mode) return
            val settings = s.settings.copy(dedupMode = mode)
            settingsRepo.save(settings)
            _state.update { it.copy(settings = settings, dedupMode = mode) }
            if (classifyResult != null) {
                // ★ 同步重算：避免"快速连切 A→B→A"时异步任务晚到覆盖（Android P2-1 同类问题）
                republishViews(CoreStatus.READY, message = dedupMessage(mode))
            }
        }
    }

    /** 三态切换的口径提示（文案与 Android 端一致，改口径要两端一起改）。 */
    private fun dedupMessage(mode: DedupKeepMode): String {
        val dup = dupGroups()
        val inGroups = dup.values.sumOf { it.size + 1 }
        val single = photos.size - inGroups
        return when (mode) {
            DedupKeepMode.KEEP_WATERMARKED ->
                "已合并 ${dup.size} 组重复照片（保留带水印版）；单份照片 $single 张不受影响"

            DedupKeepMode.KEEP_PLAIN ->
                "已合并 ${dup.size} 组重复照片（保留不带水印版）；单份照片 $single 张不受影响"

            DedupKeepMode.KEEP_BOTH ->
                "重复照片全部保留（共 ${dup.size} 组，每组带水印/不带水印双份）；单份照片 $single 张不受影响"
        }
    }

    override fun readLogFile(file: Path): String {
        val bytes = runCatching { Files.readAllBytes(file) }.getOrElse {
            throw IllegalArgumentException("无法读取日志文件：${file}（${it.message ?: it.javaClass.simpleName}）")
        }
        return decodeText(bytes)
    }

    /** UTF-8 优先，失败回落 GBK —— 内业同事用记事本另存的日志常是 GBK。 */
    private fun decodeText(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        // UTF-8 BOM
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        val utf8 = runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull()
        if (utf8 != null) return utf8
        return runCatching { String(bytes, charset("GBK")) }.getOrDefault(String(bytes, Charsets.ISO_8859_1))
    }

    // ── 主流程：分析 ───────────────────────────────────────────────────────

    override fun analyze(listener: ProgressListener?): OperationHandle {
        val op = Operation()
        if (_state.value.status == CoreStatus.EXPORTING) return op
        executor.execute {
            synchronized(lock) {
                if (closed) return@synchronized
                try {
                    runAnalysis(op, listener)
                } catch (e: CancellationException) {
                    _state.update {
                        it.copy(status = CoreStatus.READY, progress = null, message = "分析已取消")
                    }
                } catch (e: Throwable) {
                    _state.update {
                        it.copy(
                            status = CoreStatus.FAILED,
                            progress = null,
                            error = "分析失败：${e.message ?: e.javaClass.simpleName}",
                        )
                    }
                }
            }
        }
        return op
    }

    private fun runAnalysis(op: Operation, listener: ProgressListener?) {
        val settings = _state.value.settings
        logsStore.flush()
        // 清掉上一轮的统计，避免"零照片"时沿用旧摘要
        clusterCount = 0
        mergedDerivedGroups = 0
        heicCount = 0
        candidateCount = 0
        _state.update { it.copy(analysis = null) }

        stage(listener, CoreStatus.ANALYZING, "解析日志", 0, 0)
        val p = AlgorithmApi.parseLog(logText, settings.patrolDate, zone)
        parsed = p
        _state.update { it.copy(parsed = summaryOf(p)) }

        // 清空上一轮的人工调整：重新分析 = 全新分配（与 Android parseLogs 语义一致）
        overrideMap.clear()
        copyMap.clear()
        marked.clear()
        classifyResult = null
        photos = emptyList()
        photosById = emptyMap()

        if (settings.sourceDirs.isEmpty()) {
            candidateCount = 0
            mergedDerivedGroups = 0
            heicCount = 0
            republishViews(
                CoreStatus.READY,
                message = "已解析 ${p.events.size} 条事件；未选择源目录，暂不扫描照片",
            )
            return
        }

        stage(listener, CoreStatus.ANALYZING, "扫描照片", 0, 0)
        val all = ArrayList<algorithm.CandidatePhoto>()
        for (dir in settings.sourceDirs) {
            all += sourceProvider.listCandidates(dir)
        }
        // 粗筛（文件名日期区间，闭区间且只看文件名）+ 目录白名单
        val range = settings.patrolDate..settings.patrolDate
        val filtered = PhotoIngest.coarseFilter(all, settings.sourceDirs, range)
        // 派生去重（内容哈希双层判定：保留水印相机 original，合并真正的复制副本）
        val (deduped, mergedGroups) = PhotoIngest.dedupDerived(filtered) { hasher(it) }
        mergedDerivedGroups = mergedGroups

        // ★ id 分配必须在进入 EXIF/分类之前：dedupById 只对 id>0 去重，id 稳定才能跨扫描复现
        val allocator = PhotoIdAllocator()
        val withIds = deduped.map { it.copy(id = allocator.allocate(it.sourceRef)) }
        candidateCount = withIds.size
        heicCount = withIds.count { PcPhotoSourceProvider.isHeic(it.displayName) }

        if (withIds.isEmpty()) {
            // 零照片也要出一份分类结果：否则 events 为空，日志已经解析出来却看不见
            //（与 Android 端一致：解析与扫描是两条独立链路，互不阻断）
            val empty = AlgorithmApi.gpsGroup(emptyList())
            classifyResult = AlgorithmApi.classify(
                events = p.events,
                clusters = empty.clusters,
                singles = empty.singles,
                patrolDate = settings.patrolDate,
                splitCount = empty.splitCount,
                zone = zone,
            )
            clusterCount = 0
            republishViews(
                CoreStatus.READY,
                message = "所选日期（${settings.patrolDate}）在源目录未找到照片，请检查日期或源目录",
            )
            return
        }

        // EXIF 精读：分批调用核心函数（既复用核心实现，又能报进度与响应取消）
        val read = ArrayList<Photo>(withIds.size)
        withIds.chunked(EXIF_BATCH).forEachIndexed { index, batch ->
            checkCancelled { op.isCancelled }
            read += PhotoIngest.readExif(batch, exifReader)
            stage(
                listener, CoreStatus.ANALYZING, "读取照片信息",
                minOf((index + 1) * EXIF_BATCH, withIds.size), withIds.size,
            )
        }

        val finalPhotos = PhotoIngest.dedupById(read)
        photos = finalPhotos
        photosById = finalPhotos.associateBy { it.id }
        dupCacheRef = null
        dupCache = emptyMap()

        checkCancelled { op.isCancelled }

        stage(listener, CoreStatus.ANALYZING, "分类中", withIds.size, withIds.size)
        val g = AlgorithmApi.gpsGroup(finalPhotos)
        val c = AlgorithmApi.classify(
            events = p.events,
            clusters = g.clusters,
            singles = g.singles,
            patrolDate = settings.patrolDate,
            splitCount = g.splitCount,
            zone = zone,
        )
        classifyResult = c
        clusterCount = g.clusters.size
        republishViews(CoreStatus.READY)
    }

    // ── 人工调整 ──────────────────────────────────────────────────────────

    override fun movePhotos(photoIds: List<Long>, targetEventId: Int?): Int {
        synchronized(lock) {
            val c = classifyResult ?: return 0
            if (_state.value.status == CoreStatus.EXPORTING) return 0
            val ids = photoIds.distinct().filter { it in photosById.keys }
            if (ids.isEmpty()) return 0

            // ★ 变更前后各量一次"这张照片现在属于哪些事件"，差集才是真正移动的张数。
            //   直接用 photoIds.size 会高估：目标本来就是它的家时什么都没发生，
            //   而"已移动 1 张照片"在那时候是一句假话。
            val before = ownerSets(c, ids)
            ids.forEach { pid ->
                overrideMap[pid] = targetEventId ?: -1
                // 移动即"归属变更"：旧的复制份必须一起清掉，否则移走之后原事件里还留着一份。
                copyMap.remove(pid)
            }
            val changed = ids.count { pid -> before[pid] != ownerOf(c, pid) }
            // classify 永远保持最初结果，人工移动只是叠加层（与 Android 一致）
            if (changed > 0) republishViews(CoreStatus.READY, message = "已移动 $changed 张照片")
            return changed
        }
    }

    /**
     * 一张照片当前"同属于哪些事件"（含复制份）。
     *
     * 未匹配池里的照片返回空集 —— 它此刻不属于任何事件，这是"能不能复制"的判据之一。
     */
    private fun ownerOf(c: ClassifyResult, photoId: Long): Set<Int> =
        effectiveMap(c).entries.filter { photoId in it.value }.mapTo(java.util.TreeSet()) { it.key }

    private fun ownerSets(c: ClassifyResult, photoIds: List<Long>): Map<Long, Set<Int>> =
        photoIds.associateWith { ownerOf(c, it) }

    override fun copyPhotos(photoIds: List<Long>, targetEventId: Int): Int {
        synchronized(lock) {
            val c = classifyResult ?: return 0
            if (_state.value.status == CoreStatus.EXPORTING) return 0
            if (photoIds.isEmpty()) return 0
            val current = effectiveMap(c)
            // 目标事件已经持有这张（家或已有复制份）就跳过：重复点击不能长出第二份。
            val already = current[targetEventId]?.toSet() ?: emptySet()
            // 未匹配池里的照片"复制"没有意义（它没有家，复制等于复制一份空气，还会同时出现在两处）。
            val inSomeEvent = current.values.flatten().toSet()
            var added = 0
            for (pid in photoIds.distinct()) {
                if (pid !in photosById || pid !in inSomeEvent || pid in already) continue
                if (copyMap.getOrPut(pid) { LinkedHashSet() }.add(targetEventId)) added++
            }
            if (added > 0) republishViews(CoreStatus.READY, message = "已复制 $added 张照片到该事件")
            return added
        }
    }

    override fun markLedgerPhoto(eventId: Int, slot: Int, photoId: Long?) {
        synchronized(lock) {
            if (slot != 1 && slot != 2) return
            val current = marked[eventId] ?: (null to null)
            val next = if (slot == 1) (photoId to current.second) else (current.first to photoId)
            marked[eventId] = next
            if (classifyResult == null) return
            republishViews(CoreStatus.READY)
        }
    }

    override fun resetManualAdjustments() {
        synchronized(lock) {
            val count = overrideMap.size + copyMap.size
            overrideMap.clear()
            copyMap.clear()
            marked.clear()
            if (classifyResult == null) return
            republishViews(
                CoreStatus.READY,
                message = if (count > 0) "已恢复最初分类（清除 $count 处人工调整）" else "已是最初分类状态",
            )
        }
    }

    // ── 视图重算（三态展开 + 台账 + 事件快照） ─────────────────────────────

    /**
     * 合并人工移动 + 复制后的事件照片表（target=-1 → 移入未匹配池）。
     *
     * ★ T07：实现搬到 [PcViewDerivation]，此处只做状态透传。
     *   搬动是为了让「双跑对比测试」能直接调到 PC 现行实现 —— private 方法读实例字段，
     *   测试除了反射没有别的办法，而反射测出来的东西不构成"与 domain 实现等价"的证据。
     *   T12 切换调用后本方法与 [PcViewDerivation] 一并删除。
     */
    private fun effectiveMap(c: ClassifyResult): Map<Int, List<Long>> =
        PcViewDerivation.effectiveMap(c, overrideMap, copyMap)

    /** 三态展开（仅作用于已分类事件照片；未匹配不参与）。语义见 [PcViewDerivation.expandedEventMap]。 */
    private fun expandedEventMap(c: ClassifyResult, mode: DedupKeepMode): Map<Int, List<Long>> =
        PcViewDerivation.expandedEventMap(c, overrideMap, copyMap, mode, dupGroups(), marked)

    private fun effectiveUnmatched(c: ClassifyResult): List<Long> =
        PcViewDerivation.effectiveUnmatched(c, overrideMap)

    private fun dupGroups(): Map<Long, List<Photo>> {
        val snapshot = photos
        if (dupCacheRef === snapshot) return dupCache
        val g = PhotoIngest.groupDupContent(snapshot, DedupConfig())
        dupCacheRef = snapshot
        dupCache = g
        return g
    }

    private fun buildLedgerRows(c: ClassifyResult, eventMap: Map<Int, List<Long>>): List<LedgerRowInfo> {
        val p = parsed ?: return emptyList()
        val rows = AlgorithmApi.buildLedger(
            p.events, eventMap, photosById::get, marked.toMap(),
            _state.value.settings.namingTemplate, zone,
        )
        return rows.map { r ->
            LedgerRowInfo(
                dateTime = r.dateTime,
                endTime = r.endTime,
                location = r.location,
                description = r.description,
                photo1Label = labelOf(r.folderName, r.photo1Ref),
                photo2Label = labelOf(r.folderName, r.photo2Ref),
                folderName = r.folderName,
            )
        }
    }

    private fun labelOf(folderName: String, ref: String?): String? =
        PcViewDerivation.labelOf(folderName, ref, photosById)

    /** 重算并发布全部派生视图；[status] 由调用方决定（READY / ANALYZING）。 */
    private fun republishViews(status: CoreStatus, message: String? = null) {
        val p = parsed
        val c = classifyResult
        if (p == null || c == null) {
            _state.update { it.copy(status = status, progress = null) }
            return
        }
        val mode = _state.value.dedupMode
        val eventMap = expandedEventMap(c, mode)
        val unmatched = effectiveUnmatched(c)
        val hintsByEvent = hintsByEvent(c)

        val events = p.events.map { e ->
            EventSnapshot(
                id = e.id,
                eventType = e.eventType,
                location = e.location,
                description = e.description,
                startTimeMs = e.startTimeMs,
                endTimeMs = e.endTimeMs,
                photoIds = eventMap[e.id] ?: emptyList(),
                isPending = e.startTimeMs == null,
                hints = hintsByEvent[e.id] ?: emptyList(),
            )
        }
        val photoInfos = photos.map { ph ->
            PhotoInfo(
                id = ph.id,
                displayName = ph.displayName,
                sourceRef = ph.sourceRef,
                captureTimeMs = ph.captureTimeMs,
                latitude = ph.latitude,
                longitude = ph.longitude,
                // ★ T04：改调共享层的公开门面。此前这里调的是本文件内的私有拷贝（只认 4 个后缀），
                //   于是 `_optimized`/`_resized`/`_缩略` 三类文件「核心判派生、界面判不派生」——
                //   用户选「仅保留不带水印」时，被选中的副本在界面上没有任何标记。清单只有一处：DerivedNames。
                isDerived = isDerivedPhotoName(ph.displayName),
            )
        }
        val ledger = buildLedgerRows(c, eventMap)
        val prev = _state.value
        _state.update {
            it.copy(
                status = status,
                progress = null,
                parsed = summaryOf(p),
                analysis = AnalysisSummary(
                    candidateCount = candidateCount,
                    photoCount = photos.size,
                    clusterCount = clusterCount,
                    unmatchedCount = unmatched.size,
                    mergedDerivedGroups = mergedDerivedGroups,
                    heicCount = heicCount,
                    noGpsCount = photos.count { ph -> ph.latitude == null || ph.longitude == null },
                ),
                events = events,
                photos = photoInfos,
                eventPhotoIds = eventMap,
                unmatchedPhotoIds = unmatched,
                ledger = ledger,
                message = message ?: it.message,
                error = if (prev.error != null && message != null) null else prev.error,
            )
        }
    }

    private fun hintsByEvent(c: ClassifyResult): Map<Int, List<String>> {
        val out = HashMap<Int, MutableList<String>>()
        c.interventions.forEach { hint ->
            hint.relatedEventIds.forEach { eid ->
                out.getOrPut(eid) { mutableListOf() } += hint.message
            }
        }
        return out.mapValues { it.value.toList() }
    }

    private fun summaryOf(p: ParsedLog) = ParsedSummary(
        eventCount = p.events.size,
        skippedLineCount = p.skippedLines.size,
        inspectors = p.inspectors,
        recorder = p.recorder,
        vehicle = p.vehicle,
        notices = p.notices,
    )

    private fun stage(
        listener: ProgressListener?,
        status: CoreStatus,
        stage: String,
        current: Int,
        total: Int,
    ) {
        val progress = Progress(stage, current, total)
        _state.update { it.copy(status = status, progress = progress) }
        runCatching { listener?.onProgress(progress) }
    }

    // ── 导出 ──────────────────────────────────────────────────────────────

    override fun export(request: ExportRequest, listener: ExportListener): OperationHandle {
        val op = Operation()

        // ★ 导出快照必须在锁内一次性取全：导出过程中用户若继续操作（UI 已被禁用，但核心不能假设）
        val snapshot: ExportSnapshot
        synchronized(lock) {
            if (_state.value.status == CoreStatus.EXPORTING) {
                listener.onFailed(IllegalStateException("导出正在进行，请等待完成"))
                return op
            }
            val p = parsed
            val c = classifyResult
            if (p == null || c == null) {
                listener.onFailed(IllegalStateException("请先完成分析再导出"))
                return op
            }
            val s = _state.value.settings
            val mode = _state.value.dedupMode
            val eventMap = expandedEventMap(c, mode)
            snapshot = ExportSnapshot(
                events = p.events.toList(),
                eventMap = eventMap,
                unmatched = effectiveUnmatched(c),
                photoById = photosById,
                marked = marked.toMap(),
                namingTemplate = s.namingTemplate,
                ledger = buildLedgerRows(c, eventMap),
            )
            _state.update {
                it.copy(
                    status = CoreStatus.EXPORTING,
                    exportProgress = ExportProgress("准备导出", 0, 0),
                    error = null,
                    message = null,
                )
            }
        }

        executor.execute {
            try {
                val exporter = PcFolderExporter(
                    zone = zone,
                    imageEmbedder = { ref -> embedder.embed(ref) },
                    clock = clock,
                )
                val result = exporter.export(
                    PcFolderExporter.Input(
                        outputParent = request.outputDir,
                        events = snapshot.events,
                        eventPhotoIds = snapshot.eventMap,
                        unmatchedIds = snapshot.unmatched,
                        photoById = { id -> snapshot.photoById[id] },
                        marked = snapshot.marked,
                        namingTemplate = snapshot.namingTemplate,
                        includeLedger = request.includeLedger,
                        verifyMode = request.verifyMode,
                        onProgress = { progress ->
                            _state.update { it.copy(exportProgress = progress) }
                            runCatching { listener.onProgress(progress) }
                        },
                        isCancelled = { op.isCancelled },
                    ),
                )
                synchronized(lock) {
                    _state.update {
                        it.copy(
                            status = CoreStatus.READY,
                            exportProgress = null,
                            lastExport = result,
                            ledger = snapshot.ledger,
                            message = exportMessage(result),
                        )
                    }
                }
                runCatching { listener.onFinished(result) }
            } catch (e: ExportBlockedException) {
                synchronized(lock) {
                    _state.update {
                        it.copy(status = CoreStatus.READY, exportProgress = null, error = e.message)
                    }
                }
                runCatching { listener.onFailed(e) }
            } catch (e: Throwable) {
                synchronized(lock) {
                    _state.update {
                        it.copy(
                            status = CoreStatus.FAILED,
                            exportProgress = null,
                            error = "导出失败：${e.message ?: e.javaClass.simpleName}",
                        )
                    }
                }
                runCatching { listener.onFailed(e) }
            }
        }
        return op
    }

    private fun exportMessage(r: ExportResult): String = when {
        r.cancelled -> "导出已取消：已导出 ${r.copied} 个文件，可稍后续传"
        r.resumed -> "上次导出的产物校验一致（${r.skipped} 个文件），本次未重复复制"
        r.failed > 0 -> "导出完成但有 ${r.failed} 个失败，详见 export.log"
        else -> "导出完成：${r.copied} 个文件 → ${r.exportDir}"
    }

    override fun openLedger(exportResult: ExportResult): Boolean {
        val f = exportResult.ledgerFile ?: return false
        return shell.open(f)
    }

    override fun revealExportDir(exportResult: ExportResult): Boolean = shell.reveal(exportResult.exportDir)

    override fun clearMessage() {
        _state.update { it.copy(message = null) }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            logsStore.flush()
            _state.update { it.copy(status = CoreStatus.CLOSED) }
        }
        if (ownsExecutor) runCatching { (executor as? ExecutorService)?.shutdownNow() }
        runCatching { scheduler.shutdownNow() }
    }

    // ── 内部类型 ──────────────────────────────────────────────────────────

    private class Operation : OperationHandle {
        private val cancelled = AtomicBoolean(false)
        override fun cancel() {
            cancelled.set(true)
        }

        override val isCancelled: Boolean get() = cancelled.get()
    }

    private class ExportSnapshot(
        val events: List<LogEvent>,
        val eventMap: Map<Int, List<Long>>,
        val unmatched: List<Long>,
        val photoById: Map<Long, Photo>,
        val marked: Map<Int, Pair<Long?, Long?>>,
        val namingTemplate: Int,
        val ledger: List<LedgerRowInfo>,
    )

    private companion object {
        /** EXIF 精读批量（兼顾进度回调粒度与批量开销）。 */
        const val EXIF_BATCH = 25

        fun daemonThread(r: Runnable, name: String): Thread =
            Thread(r, name).apply { isDaemon = true }
    }
}
