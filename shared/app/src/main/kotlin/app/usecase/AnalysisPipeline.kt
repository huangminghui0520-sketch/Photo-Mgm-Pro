// shared/app/usecase/AnalysisPipeline.kt —— 「一遍流水线」的唯一实现（T09）
//
// 流水线（顺序不要动，每一步的前后依赖都是踩过的坑）：
//
//   解析日志 → 扫描候选 → 目录白名单 + 文件名日期粗筛 → 派生去重（内容哈希）
//   → **分配稳定 id** → EXIF 精读（分批，可取消）→ 按 id 去重 → GPS 分组
//   → 事件分类 → 生成台账行
//
// 三个"看起来可以挪"其实不能挪的位置：
//   1. **id 分配必须在 EXIF / 分类之前**：`PhotoIngest.dedupById` 只对 `id > 0` 去重，
//      而 id 稳定才能跨扫描复现（否则照片增删后人工调整会错位到别的照片上）。
//   2. **派生去重必须在 id 分配之前**：去重是按内容哈希 + 派生后缀判定的，
//      早于 id 分配才能保证"同一组的两个文件不会先各拿一个 id 再被丢弃"。
//   3. **清空人工调整必须在扫描之前、解析之后**：重新分析 = 全新分配（与两端既有语义一致）。
//      放在解析后是因为"解析失败"不该顺手把用户的人工调整清掉。
//
// ★ 本类不持线程：`analyze` 是**阻塞**调用。桌面端在自己的执行器上跑它，
//   安卓端在协程里跑它 —— 线程模型是平台形态，共享层只提供"一口气跑完"。
//   ★ 也不返回句柄：取消靠 `isCancelled` 查询 + [checkCancelled] 抛取消异常，
//     两端已经在用同一个取消信号（T05-C1），不必再造一层句柄抽象。
package app.usecase

import algorithm.AlgorithmApi
import algorithm.ExifReader
import algorithm.ParsedLog
import algorithm.PhotoIngest
import algorithm.PhotoSourceProvider
import algorithm.isHeicPhotoName
import algorithm.model.Photo
import app.api.CoreStatus
import app.api.Progress
import app.api.ProgressListener
import app.infra.DebouncedTextStore
import app.infra.checkCancelled
import app.port.PhotoIdSource
import app.state.AppStateStore
import app.state.PhotoSession
import java.time.LocalDate
import java.util.concurrent.CancellationException

/**
 * 分析流水线。
 *
 * @param logs 日志文本的防抖落盘器。分析前必须 flush：否则"改了日志马上点分析"会解析到上一次的旧文本。
 * @param sourceProvider 照片扫描（平台端口）。
 * @param exifReader EXIF 精读（平台端口）。
 * @param hasher 内容哈希（派生去重用的 SHA-256；读不到返回 null）。平台端口以函数形式传入，
 *   与 `PhotoIngest.dedupDerived` 的签名一致，避免为了一个单方法接口再包一层。
 * @param photoIds 照片 id 分配（平台端口）。
 */
class AnalysisPipeline(
    private val store: AppStateStore,
    private val session: PhotoSession,
    private val logs: DebouncedTextStore,
    private val sourceProvider: PhotoSourceProvider,
    private val exifReader: ExifReader,
    private val hasher: (String) -> String?,
    private val photoIds: PhotoIdSource,
) {

    /**
     * 跑一遍完整流水线（阻塞）。
     *
     * 幂等：可重复调用（改了日期/源目录后重跑）。
     * 前置条件：无 —— 源目录为空时只解析日志，不报错（解析与扫描是两条独立链路，互不阻断）。
     *
     * ★ 异常在**本函数内**被收口成状态：取消 → `READY + "分析已取消"`；
     *   其他异常 → `FAILED + "分析失败：…"`。这样两端的用户提示一字不差，
     *   也不会出现"安卓吞了异常、桌面抛到 UI"这种分叉。
     */
    fun analyze(listener: ProgressListener?, isCancelled: () -> Boolean) {
        store.withLock {
            try {
                runAnalysis(listener, isCancelled)
            } catch (e: CancellationException) {
                // ★ 取消不是失败：分析可能已经扫了一半，把状态退回 READY（而不是 FAILED），
                //   用户再点一次分析就是重跑，不必先理解"为什么失败了"。
                store.publish { it.copy(status = CoreStatus.READY, progress = null, message = "分析已取消") }
            } catch (e: Throwable) {
                store.publish {
                    it.copy(
                        status = CoreStatus.FAILED,
                        progress = null,
                        error = "分析失败：${e.message ?: e.javaClass.simpleName}",
                    )
                }
            }
        }
    }

    private fun runAnalysis(listener: ProgressListener?, isCancelled: () -> Boolean) {
        if (store.current().status == CoreStatus.EXPORTING) return
        val settings = store.current().settings

        // 开始前先把待落盘的日志写掉：用户「改了日志立刻点分析」时，解析的必须是屏幕上那段文字
        logs.flush()
        session.resetCounters()
        store.publish { it.copy(analysis = null) }

        stage(listener, CoreStatus.ANALYZING, "解析日志", 0, 0)
        val parsed = AlgorithmApi.parseLog(store.current().logText, settings.patrolDate, session.zone)
        session.parsed = parsed
        store.publish { it.copy(parsed = ViewPublisher.summaryOf(parsed)) }

        // 重新分析 = 全新分配：清掉上一轮的人工调整与照片
        session.clearManual()
        session.classify = null
        session.clearPhotos()

        if (settings.sourceDirs.isEmpty()) {
            session.resetCounters()
            ViewPublisher.publish(
                store, session, CoreStatus.READY,
                message = "已解析 ${parsed.events.size} 条事件；未选择源目录，暂不扫描照片",
            )
            return
        }

        stage(listener, CoreStatus.ANALYZING, "扫描照片", 0, 0)
        val finalPhotos = scanAndRead(settings, listener, isCancelled)

        if (finalPhotos.isEmpty()) {
            publishEmptyClassification(parsed, settings.patrolDate)
            return
        }

        checkCancelled(isCancelled)

        stage(listener, CoreStatus.ANALYZING, "分类中", finalPhotos.size, finalPhotos.size)
        val grouped = AlgorithmApi.gpsGroup(finalPhotos)
        session.classify = AlgorithmApi.classify(
            events = parsed.events,
            clusters = grouped.clusters,
            singles = grouped.singles,
            patrolDate = settings.patrolDate,
            splitCount = grouped.splitCount,
            zone = session.zone,
        )
        session.clusterCount = grouped.clusters.size
        ViewPublisher.publish(store, session, CoreStatus.READY)
    }

    /**
     * 只扫描照片：粗筛 → 派生去重 → 分配 id → EXIF 精读 → 按 id 去重。
     *
     * 从 [runAnalysis] 的扫描段**原样提取**（T11），供 [scanPhotos] 复用。
     * 提取时不改任何判定顺序 —— 尤其是"派生去重先于 id 分配""id 分配先于 EXIF"这两条，
     * 见文件头第 1、2 条说明。
     *
     * @return 最终照片列表（可能为空 = 本日期无照片）；本函数不改 `parsed`、不分类。
     */
    private fun scanAndRead(
        settings: app.api.Settings,
        listener: ProgressListener?,
        isCancelled: () -> Boolean,
    ): List<Photo> {
        // 粗筛（候选合并 → 目录白名单 + 文件名日期 → 派生去重）走 `PhotoScan`：
        // 安卓的「防线一」照片增删校验要的是同一套判定，两处各写一份必然漂移。
        val coarse = PhotoScan.coarseCandidates(
            sourceProvider = sourceProvider,
            sourceDirs = settings.sourceDirs,
            date = settings.patrolDate,
            hasher = hasher,
        )
        session.mergedDerivedGroups = coarse.mergedGroups
        val deduped = coarse.photos

        photoIds.reset()
        val withIds = deduped.map { it.copy(id = photoIds.allocate(it.sourceRef, it.id)) }
        session.candidateCount = withIds.size
        session.heicCount = withIds.count { isHeicPhotoName(it.displayName) }

        if (withIds.isEmpty()) {
            session.setPhotos(emptyList())
            return emptyList()
        }

        // EXIF 精读：分批（既复用核心函数，又能报进度与响应取消）
        val read = ArrayList<Photo>(withIds.size)
        withIds.chunked(EXIF_BATCH).forEachIndexed { index, batch ->
            checkCancelled(isCancelled)
            read += PhotoIngest.readExif(batch, exifReader)
            stage(
                listener, CoreStatus.ANALYZING, "读取照片信息",
                minOf((index + 1) * EXIF_BATCH, withIds.size), withIds.size,
            )
        }

        val finalPhotos = PhotoIngest.dedupById(read)
        session.setPhotos(finalPhotos)
        return finalPhotos
    }

    /**
     * 只扫描照片，**不解析日志、不分类**（安卓端「改巡查日期 / 改源目录」走这条）。
     *
     * ★ 为什么需要它（T11 的一个硬约束）：
     *   安卓的既有语义是"选日期只把照片扫出来，分类统一由『解析日志』触发"——
     *   旧日期的事件时间戳对新日期的照片没有意义，自动分类只会把整批照片塞进未匹配池。
     *   更实际的原因是：`parseLogs` 有一条"日志没改过 → 只清人工移动"的快路径，
     *   它的前提是 `parsed` 来自**用户点解析**那一刻。若自动扫描顺手解析了日志，
     *   `parsed` 会提前变成非空，快路径会误判"已经解析过了"，用户点解析只得到一句
     *   "已是最初分类状态"，事件列表永远是空的。
     *
     * ★ 与 [analyze] 的关系：完全独立的入口，共用 [scanAndRead] 这一段。
     *   两者的区别只有"要不要解析日志 / 要不要分类"，扫描部分一个字都不差。
     */
    fun scanPhotos(listener: ProgressListener?, isCancelled: () -> Boolean) {
        store.withLock {
            try {
                if (store.current().status == CoreStatus.EXPORTING) return
                val settings = store.current().settings
                if (settings.sourceDirs.isEmpty()) {
                    session.resetCounters()
                    session.classify = null
                    session.clearPhotos()
                    ViewPublisher.publishScan(store, session, message = null)
                    return
                }
                // 照片换了 → 旧的分类结果与照片对不上，必须一起清掉（保留 parsed：事件列表与照片无关）
                session.resetCounters()
                session.classify = null
                session.clearPhotos()
                // ★ 必须先置 ANALYZING 再扫：这个状态是"扫描进行中"的唯一对外信号
                //   （UI 的进度条/禁用态、以及调用方的"等它跑完"都靠它）。
                //   漏了这一步，外部会看到"状态还是 READY、照片却是空的"，
                //   于是"等扫描结束"变成"立刻返回"，后续步骤拿到的是一批空照片。
                stage(listener, CoreStatus.ANALYZING, "扫描照片", 0, 0)

                val photos = scanAndRead(settings, listener, isCancelled)
                val message = if (photos.isEmpty()) {
                    "所选日期（${settings.patrolDate}）在源目录未找到照片，请检查日期或源目录"
                } else {
                    // ★ P2：该日期尚无解析结果时给引导
                    if (session.parsed == null) "已加载 ${settings.patrolDate} 的照片 ${photos.size} 张，粘贴日志后点「解析日志」即自动分类"
                    else null
                }
                ViewPublisher.publishScan(store, session, message)
            } catch (e: CancellationException) {
                // ★ 取消不是失败：退回 READY，用户再点一次就是重跑
                store.publish { it.copy(status = CoreStatus.READY, progress = null, message = "扫描已取消") }
                throw e
            } catch (e: Throwable) {
                store.publish {
                    it.copy(
                        status = CoreStatus.FAILED,
                        progress = null,
                        error = "扫描照片失败：${e.message ?: e.javaClass.simpleName}",
                    )
                }
            }
        }
    }

    /**
     * 零照片分支：仍然要出一份分类结果。
     *
     * ★ 否则 `events` 为空 —— 日志明明解析出来了却一条都看不见，用户会以为"日志没导入成功"。
     *   解析与扫描是两条独立链路，彼此不阻断（两端既有行为一致）。
     */
    private fun publishEmptyClassification(parsed: ParsedLog, patrolDate: LocalDate) {
        val empty = AlgorithmApi.gpsGroup(emptyList())
        session.classify = AlgorithmApi.classify(
            events = parsed.events,
            clusters = empty.clusters,
            singles = empty.singles,
            patrolDate = patrolDate,
            splitCount = empty.splitCount,
            zone = session.zone,
        )
        session.clusterCount = 0
        ViewPublisher.publish(
            store, session, CoreStatus.READY,
            message = "所选日期（$patrolDate）在源目录未找到照片，请检查日期或源目录",
        )
    }

    private fun stage(
        listener: ProgressListener?,
        status: CoreStatus,
        stage: String,
        current: Int,
        total: Int,
    ) {
        val progress = Progress(stage, current, total)
        store.publish { it.copy(status = status, progress = progress) }
        // listener 是 UI 传进来的，抛异常不该让整条流水线失败
        runCatching { listener?.onProgress(progress) }
    }

    private companion object {
        /** EXIF 精读批量：兼顾进度回调粒度与批量开销（与桌面端既有取值一致）。 */
        const val EXIF_BATCH = 25
    }
}