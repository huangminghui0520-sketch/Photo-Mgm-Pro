// shared/app/state/PhotoSession.kt —— 一次分析会话的可变状态（分析 → 调整 → 导出共用）
//
// 这个类回答的是「本轮分析算出来的东西放在哪」：
//   · 日志解析结果（`parsed`）与分类结果（`classify`）—— 分类只在分析末尾算一次，
//     三态切换 / 人工调整都**不重跑**它（与两端既有行为一致：只重算输出层）；
//   · 照片清单（`photos` / `photosById`）—— id 已在分析阶段定好，后续所有引用都靠它；
//   · 人工调整（`manual`）—— 移动 / 复制 / 台账标记，重置时机由分析流水线决定；
//   · 统计计数 —— 供 [app.api.AnalysisSummary] 用（候选数、合并组数、HEIC 数、簇数）。
//
// ★ 线程约定：所有字段**只在 [AppStateStore] 的锁内读写**。本类自己不持锁——
//   它只是一个数据容器；持锁的是 store，因为"改状态"和"发布快照"必须是一件事
//   （分两把锁就会读到「字段改了但 state 还没发」的中间态）。
//
// ★ 为什么把 `dupGroups` 的缓存放在这里而不是 [domain.view.ViewDerivation]：
//   `PhotoIngest.groupDupContent` 是 O(n²) 级，万张照片每帧重算会让预览卡死；
//   但"怎么缓存"（引用相等即失效）是指令集层面的决定，不是业务规则。
//   domain 层收它必须传入 dupGroups —— 这里就是那个"由调用方缓存"的调用方。
package app.state

import algorithm.AppZone
import algorithm.ClassifyResult
import algorithm.DedupConfig
import algorithm.DedupKeepMode
import algorithm.ParsedLog
import algorithm.PhotoIngest
import algorithm.groupDupContent
import algorithm.model.Photo
import domain.adjust.ManualState
import domain.view.DerivedView
import domain.view.ViewDerivation
import java.time.ZoneId

/**
 * 一轮分析的会话状态（可变，受 [AppStateStore] 的锁保护）。
 *
 * @param zone 业务时区（日志挂钟时间解释、台账日期列、文件夹名编号共用同一个时区）。
 *   默认 [AppZone.ID]；测试可注入固定时区。
 */
class PhotoSession(
    val zone: ZoneId = AppZone.ID,
) {
    /** 日志解析结果；null = 还没解析过。 */
    var parsed: ParsedLog? = null

    /** 事件分类结果；null = 还没扫描过照片（此时人工调整与导出都不可用）。 */
    var classify: ClassifyResult? = null

    /** 本轮全部照片（含未匹配），顺序即扫描顺序。 */
    var photos: List<Photo> = emptyList()
        private set

    /** 照片索引（O(1) 反查；`knownPhotoIds` / 台账标签 / 导出计划都靠它）。 */
    var photosById: Map<Long, Photo> = emptyMap()
        private set

    /** 人工调整叠加层（移动 / 复制 / 台账标记）。 */
    var manual: ManualState = ManualState.EMPTY

    // ── 统计计数（对应 AnalysisSummary 的各列） ───────────────────────────
    /** 粗筛 + 派生去重后的候选张数。 */
    var candidateCount: Int = 0
    /** 派生去重合并掉的组数。 */
    var mergedDerivedGroups: Int = 0
    /** HEIC/HEIF 张数（台账嵌不进图，见 `algorithm.isHeicPhotoName`）。 */
    var heicCount: Int = 0
    /** GPS 聚簇数量。 */
    var clusterCount: Int = 0

    private var dupCacheRef: List<Photo>? = null
    private var dupCache: Map<Long, List<Photo>> = emptyMap()

    /** 本次分类覆盖的照片 id（移动 / 复制的合法性判据）。 */
    val knownPhotoIds: Set<Long> get() = photosById.keys

    /** 换一批照片：索引与判重缓存一起失效（缓存按列表引用判定，漏了这步会拿到上一批的分组）。 */
    fun setPhotos(list: List<Photo>) {
        photos = list
        photosById = list.associateBy { it.id }
        dupCacheRef = null
        dupCache = emptyMap()
    }

    /** 清空照片（重新分析时调用；统计计数由 [resetCounters] 单独负责）。 */
    fun clearPhotos() = setPhotos(emptyList())

    /** 清空人工调整（重新分析 = 全新分配，与两端既有语义一致）。 */
    fun clearManual() {
        manual = ManualState.EMPTY
    }

    /** 统计计数归零（重新分析开始时调用，避免"零照片"时沿用上一轮摘要）。 */
    fun resetCounters() {
        candidateCount = 0
        mergedDerivedGroups = 0
        heicCount = 0
        clusterCount = 0
    }

    /**
     * 内容判重分组（按当前照片列表引用缓存）。
     *
     * 没有派生副本或没有主图时返回空表（`groupDupContent` 内部即如此判定），
     * 因此"全是主图"的常见场景不会付出 O(n²) 的代价去配出一个空结果。
     */
    fun dupGroups(): Map<Long, List<Photo>> {
        val snapshot = photos
        if (dupCacheRef === snapshot) return dupCache
        val groups = PhotoIngest.groupDupContent(snapshot, DedupConfig())
        dupCacheRef = snapshot
        dupCache = groups
        return groups
    }

    /** 按当前人工调整 + 三态模式派生出三个视图（调用方须先确保 [classify] 非空）。 */
    fun deriveView(mode: DedupKeepMode): DerivedView {
        val c = requireNotNull(classify) { "分类结果尚未生成，视图派生不可用" }
        return ViewDerivation.derive(c, manual.toLayer(), mode, dupGroups(), manual.marked)
    }
}
