// shared/app/usecase/ViewPublisher.kt —— 派生视图的组装与发布（两端唯一一份）
//
// 职责很窄：把「会话状态 + 当前设置」变成 [AppState] 里那几列会变的字段
// （events / photos / eventPhotoIds / unmatchedPhotoIds / ledger / analysis / parsed），
// 一次算齐、一次发布。
//
// ★ 为什么单独成文件而不是藏在某个用例里：
//   分析、人工调整、切换三态、导出收尾**四个入口**都会触发重算。此前这一段写在桌面端的
//   私有方法里，安卓端的对应段落在 ViewModel 里另写一份 —— 两份的差异（例如未匹配池
//   是否扣除被移除的照片、台账照片列要不要加文件夹前缀）只有在对拍时才会发现。
//   把它提成一处，四个入口共享同一份口径，是 T09 的直接收益。
//
// ★ 顺序语义（不是实现细节）：
//   1. 事件按 `parsed.events` 原序（日志顺序），不是按 id；
//   2. 事件照片表来自三态展开后的派生视图（不是原始分类结果）；
//   3. 台账行从**同一份**展开后表构建，所以台账照片 == 预览照片 == 导出照片。
//      （导出时另有一份冻结快照，见 ExportUseCase；两者都走本类的投影函数。）
package app.usecase

import algorithm.ClassifyResult
import algorithm.ParsedLog
import algorithm.isDerivedPhotoName
import app.api.AnalysisSummary
import app.api.AppState
import app.api.CoreStatus
import app.api.EventSnapshot
import app.api.LedgerRowInfo
import app.api.ParsedSummary
import app.api.PhotoInfo
import app.state.AppStateStore
import app.state.PhotoSession
import domain.ledger.LedgerProjection

/** 派生视图的组装与发布（内部工具，不对外暴露）。 */
internal object ViewPublisher {

    /**
     * 重算并发布全部派生视图。
     *
     * @param status 发布后的核心状态（分析中 / 就绪）。
     * @param message 一句话提示；null = 保留既有提示（不覆盖用户还没看到的那条）。
     */
    fun publish(
        store: AppStateStore,
        session: PhotoSession,
        status: CoreStatus,
        message: String? = null,
    ) {
        val parsed = session.parsed
        val classify = session.classify
        if (parsed == null || classify == null) {
            // 还没有分类结果：只更新状态，并**把上一轮的视图清空**。
            //
            // ★ T09 修正了既有实现的两个缺陷（原样搬过来会让"未选源目录"这条路径说谎）：
            //   ① 提示语被吞：原实现此处直接 return，调用方传进来的 message 丢掉 ——
            //      而"未选择源目录，暂不扫描照片"正是用户唯一的解释，丢掉后界面一片空白且无声；
            //   ② 旧视图残留：重新分析时若源目录被清空，`photos` / `eventPhotoIds` 仍是上一批，
            //      状态是 READY、照片却对不上任何事件，用户会以为"照片还在，只是没分类"。
            store.publish {
                it.copy(
                    status = status,
                    progress = null,
                    message = message ?: it.message,
                    events = emptyList(),
                    photos = emptyList(),
                    eventPhotoIds = emptyMap(),
                    unmatchedPhotoIds = emptyList(),
                    ledger = emptyList(),
                    analysis = null,
                )
            }
            return
        }
        val state = store.current()
        val view = session.deriveView(state.dedupMode)
        val events = buildEvents(parsed, view.eventPhotoMap, classify)
        val photos = buildPhotoInfos(session)
        // ★ 命名模板取自当前设置：模板影响文件夹名 → 影响台账照片列前缀，切换后必须立刻重算
        val ledger = buildLedgerRows(session, parsed, view.eventPhotoMap, state.settings.namingTemplate)
        store.publish {
            it.copy(
                status = status,
                progress = null,
                parsed = summaryOf(parsed),
                analysis = buildSummary(session, view.unmatched),
                events = events,
                photos = photos,
                eventPhotoIds = view.eventPhotoMap,
                unmatchedPhotoIds = view.unmatched,
                ledger = ledger,
                message = message ?: it.message,
                // 上一次的失败详情在新提示出现时清掉：用户已经看到新结果了，
                // 还挂着一条旧错误只会让人以为这次也失败了。
                error = if (it.error != null && message != null) null else it.error,
            )
        }
    }

    private fun buildEvents(
        parsed: ParsedLog,
        eventPhotoMap: Map<Int, List<Long>>,
        classify: ClassifyResult,
    ): List<EventSnapshot> {
        val hints = hintsByEvent(classify)
        return parsed.events.map { e ->
            EventSnapshot(
                id = e.id,
                eventType = e.eventType,
                location = e.location,
                description = e.description,
                startTimeMs = e.startTimeMs,
                endTimeMs = e.endTimeMs,
                photoIds = eventPhotoMap[e.id] ?: emptyList(),
                isPending = e.startTimeMs == null,
                hints = hints[e.id] ?: emptyList(),
            )
        }
    }

    private fun buildPhotoInfos(session: PhotoSession): List<PhotoInfo> = session.photos.map { p ->
        PhotoInfo(
            id = p.id,
            displayName = p.displayName,
            sourceRef = p.sourceRef,
            captureTimeMs = p.captureTimeMs,
            latitude = p.latitude,
            longitude = p.longitude,
            // ★ 调共享层公开门面，不再用私有拷贝：私有拷贝只认 4 个后缀，
            //   于是 `_optimized`/`_resized`/`_缩略` 三类文件「核心判派生、界面判不派生」，
            //   用户选「仅保留不带水印」时，被选中的副本在界面上没有任何标记。
            isDerived = isDerivedPhotoName(p.displayName),
        )
    }

    /**
     * 台账行（含 `文件夹名/文件名` 标签）。
     *
     * @param namingTemplate 命名模板（0=事件类型 / 1=巡查日志内容）。**必须**与导出用的一致，
     *   否则预览里的文件夹名和磁盘上的不一致（用户会以为导出错了）。
     */
    fun buildLedgerRows(
        session: PhotoSession,
        parsed: ParsedLog,
        eventPhotoMap: Map<Int, List<Long>>,
        namingTemplate: Int,
    ): List<LedgerRowInfo> =
        LedgerProjection.buildRows(
            events = parsed.events,
            eventPhotoMap = eventPhotoMap,
            photos = session.photos,
            marked = session.manual.marked,
            namingTemplate = namingTemplate,
            zone = session.zone,
        ).map { r ->
            LedgerRowInfo(
                dateTime = r.dateTime,
                endTime = r.endTime,
                location = r.location,
                description = r.description,
                photo1Label = r.photo1Label,
                photo2Label = r.photo2Label,
                folderName = r.folderName,
            )
        }

    private fun buildSummary(session: PhotoSession, unmatched: List<Long>): AnalysisSummary =
        AnalysisSummary(
            candidateCount = session.candidateCount,
            photoCount = session.photos.size,
            clusterCount = session.clusterCount,
            unmatchedCount = unmatched.size,
            mergedDerivedGroups = session.mergedDerivedGroups,
            heicCount = session.heicCount,
            noGpsCount = session.photos.count { it.latitude == null || it.longitude == null },
        )

    /** 干预提示按事件展开（一条提示可能关联多个事件）。 */
    fun hintsByEvent(classify: ClassifyResult): Map<Int, List<String>> {
        val out = HashMap<Int, MutableList<String>>()
        classify.interventions.forEach { hint ->
            hint.relatedEventIds.forEach { eid ->
                out.getOrPut(eid) { mutableListOf() } += hint.message
            }
        }
        return out.mapValues { it.value.toList() }
    }

    /** 日志解析摘要（解析完立刻发布一次，用户不必等照片扫完就能看见事件条数）。 */
    fun summaryOf(parsed: ParsedLog): ParsedSummary = ParsedSummary(
        eventCount = parsed.events.size,
        skippedLineCount = parsed.skippedLines.size,
        inspectors = parsed.inspectors,
        recorder = parsed.recorder,
        vehicle = parsed.vehicle,
        notices = parsed.notices,
    )
}
