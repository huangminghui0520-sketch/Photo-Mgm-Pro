// ui/model/WorkQueue.kt —— 复核队列：把 AppState 算成"还需要人做什么"
//
// ★ 这一层存在的理由：核心只给事实（事件、照片、hints、未匹配 id），
//   而人在复核时关心的是**缺口**——哪个事件没照片、哪个有时间冲突、哪些照片无处可去。
//   缺口的计算放在这里（纯函数、可测），界面只负责画。
//   任何"业务口径"都不许写到 Composable 里——那会让口径散落在几十个 @Composable 中，
//   改一次口径要翻遍界面代码，最终必然出现"事件列表说有 3 个问题、导出时却是 4 个"。
package com.photomgm.desktop.ui.model

import app.api.AppState
import app.api.EventSnapshot
import app.api.PhotoInfo

object WorkQueue {

    /**
     * 某事件是否需要人工确认。
     *
     * 三个来源，全部来自核心的事实而非 UI 猜测：
     *   · hints 非空 —— 核心 classify 阶段判定"GPS 偏远 / 时间重叠 / 簇跨度过大"等；
     *   · 无照片 —— 导出会生成空事件文件夹，台账那一行的两个照片列都是空的；
     *   · 待定事件（isPending，日志里没有时间）—— 照片不会被自动归入，必须人工指定。
     */
    fun needsReview(e: EventSnapshot): Boolean =
        e.hints.isNotEmpty() || e.photoIds.isEmpty() || e.isPending

    /** 事件的"问题标签"：用于列表行上的角标，顺序即优先级。 */
    fun issueLabels(e: EventSnapshot): List<String> = buildList {
        if (e.isPending) add("无时间")
        if (e.photoIds.isEmpty()) add("无照片")
        if (e.hints.isNotEmpty()) add("${e.hints.size} 条提示")
    }

    /** 顶部/状态栏用的总览统计。 */
    data class Stats(
        val events: Int,
        val photos: Int,
        val unmatched: Int,
        val needReview: Int,
        val noPhoto: Int,
        val pending: Int,
        val withHints: Int,
        val matched: Int,
    )

    /**
     * 顶部/状态栏用的总览统计。
     *
     * ★ `matched` 是**照片张数**（去重），不是"事件里照片格的总和"：
     *   一旦有照片被复制到第二个事件，两者就不相等，而状态栏写"已归属 13 / 共 12 张"是自相矛盾的。
     */
    fun stats(state: AppState): Stats = Stats(
        events = state.events.size,
        photos = state.photos.size,
        unmatched = state.unmatchedPhotoIds.size,
        needReview = state.events.count { needsReview(it) },
        noPhoto = state.events.count { it.photoIds.isEmpty() },
        pending = state.events.count { it.isPending },
        withHints = state.events.count { it.hints.isNotEmpty() },
        matched = photoEventCount(state).size,
    )

    /** 筛选 + 搜索。搜索覆盖地点、描述、事件类型（这三个是用户记得住的信息）。 */
    fun filter(state: AppState, filter: ReviewFilter, query: String): List<EventSnapshot> {
        val q = query.trim()
        return state.events.filter { e ->
            val byFilter = when (filter) {
                ReviewFilter.ALL -> true
                ReviewFilter.NEEDS_REVIEW -> needsReview(e)
                ReviewFilter.NO_PHOTO -> e.photoIds.isEmpty()
                ReviewFilter.PENDING -> e.isPending
                // 未匹配照片不属于任何事件；选中这一档时事件列表应清空（照片区显示未匹配池）
                ReviewFilter.UNMATCHED -> false
            }
            byFilter && (q.isEmpty() || matches(e, q))
        }
    }

    private fun matches(e: EventSnapshot, q: String): Boolean =
        e.location.contains(q, ignoreCase = true) ||
            e.description.contains(q, ignoreCase = true) ||
            e.eventType.contains(q, ignoreCase = true)

    /** 当前照片弹窗该显示的照片（按拍摄时间升序，见 [sortedByCapture]）。 */
    fun photosOf(state: AppState, selection: Selection, byId: Map<Long, PhotoInfo>): List<PhotoInfo> =
        when (selection) {
            is Selection.Event -> {
                val ids = state.eventPhotoIds[selection.id] ?: emptyList()
                sortedByCapture(ids.mapNotNull { byId[it] })
            }

            Selection.Unmatched -> sortedByCapture(state.unmatchedPhotoIds.mapNotNull { byId[it] })
            Selection.None -> emptyList()
        }

    /** 事件卡片里的照片（卡片只用 photoIds，不用 eventPhotoIds —— 二者对事件行是同一个集合）。 */
    fun photosOfEvent(event: EventSnapshot, byId: Map<Long, PhotoInfo>): List<PhotoInfo> =
        sortedByCapture(event.photoIds.mapNotNull { byId[it] })

    /**
     * 事件内照片的顺序：**拍摄时间升序**，无时间的排最后。
     *
     * ★ 为什么在 UI 层排、而不是让 core 直接按时间发 photoIds：
     *   core 的 photoIds 顺序同时决定导出文件名序号（`01_`、`02_`…）和台账的"照片1/照片2"，
     *   换顺序会连带改导出产物 —— 那是另一件事，要改得连台账一起评审。
     *   这里只解决"缩略条和卡片上看到的先后"：现场复核是按时间读照片的
     *   （先到桥面、再下桥底、最后拍护栏），按 id 顺序读会来回跳。
     * ★ 同一时刻（连拍、同一次快门的多份派生文件）按 id 兜底：id 由 sourceRef 稳定分配，
     *   所以两次打开弹窗的顺序一致 —— 顺序不稳定会让"第三张"这种口头沟通失效。
     * ★ 无拍摄时间的排最后而不是排最前：它们是需要人工判断的少数，
     *   排最前会把"这一组照片"的起点让给一张看不清时刻的图。
     */
    fun sortedByCapture(photos: List<PhotoInfo>): List<PhotoInfo> =
        photos.sortedWith(
            compareBy<PhotoInfo> { it.captureTimeMs ?: Long.MAX_VALUE }.thenBy { it.id },
        )

    /** 弹窗标题：事件用「地点 + 描述」，未匹配池用「未匹配照片」。 */
    fun titleOf(state: AppState, target: Selection): String = when (target) {
        is Selection.Event -> {
            val e = state.events.firstOrNull { it.id == target.id }
            when {
                e == null -> "事件"
                e.description.isBlank() -> e.location
                else -> "${e.location} · ${e.description}"
            }
        }

        Selection.Unmatched -> "未匹配照片"
        Selection.None -> ""
    }

    /** 事件在列表里的序号（1 起，按 core 给的日志顺序）。 */
    fun indexOf(state: AppState, eventId: Int): Int =
        state.events.indexOfFirst { it.id == eventId }.let { if (it < 0) 0 else it + 1 }

    fun byId(state: AppState): Map<Long, PhotoInfo> = state.photos.associateBy { it.id }

    /**
     * 每个事件持有几张照片（`eventPhotoIds` 的直接投影，用于"张数"与"多归属"判断）。
     *
     * ★ 不用 `events.sumOf { photoIds.size }` 的地方必须用这张表 ——
     *   复制到另一个事件后，同一张照片会出现在两个事件里，"总数"与"已归属"就不再相等。
     */
    fun photoEventCount(state: AppState): Map<Long, Int> {
        val out = HashMap<Long, Int>()
        for (ids in state.eventPhotoIds.values) {
            for (id in ids.toSet()) out[id] = (out[id] ?: 0) + 1
        }
        return out
    }

    /**
     * 某张照片同时属于哪些事件（按列表顺序；用于侧栏的"本次归属"）。
     *
     * ★ 查 `eventPhotoIds` 而不是各事件的 `photoIds`：前者是核心发布的权威映射，
     *   后者只是它在事件视图里的投影。两个都读会让"复制"这类改动隔一拍才生效。
     */
    fun eventsOfPhoto(state: AppState, photoId: Long): List<Int> =
        state.events.map { it.id }.filter { id -> state.eventPhotoIds[id]?.contains(photoId) == true }

    /** 导出前的阻断项（顺序即修复顺序）。空列表 = 可以导出。 */
    fun exportBlockers(state: AppState): List<String> = buildList {
        if (state.events.isEmpty()) add("还没有解析出任何事件，先导入巡查日志")
        if (state.settings.outputDir == null) add("还没有选择导出目录")
        if (state.status != app.api.CoreStatus.READY) add("当前状态不可导出（${state.status}）")
    }
}
