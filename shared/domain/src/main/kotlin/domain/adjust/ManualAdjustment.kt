// shared/domain/adjust/ManualAdjustment.kt —— 人工调整的纯状态变换（T07）
//
// 这一段逻辑两端原本各自写在 ViewModel 里（PC `movePhotos`/`copyPhotos`/`markLedgerPhoto`/
// `resetManualAdjustments`，安卓 `movePhotos`/`removePhotosFromEvent`/`markLedgerPhoto`/
// `resetOverrides`），差异藏在细节里：
//   · PC 移动后 `copyMap.remove(pid)`（移动即归属变更，旧的复制份必须清掉），安卓没有 copyMap；
//   · PC 移动返回「真正变更的张数」（前后 owner 集合的差集），安卓直接 update 不计数；
//   · PC 复制会跳过「已在目标」「不在任何事件」「未知照片」，安卓根本没有复制入口。
//
// ★ 本文件**只做状态变换**，不发布状态、不落盘、不提示消息。
//   「改完要不要 republish / 弹什么话」是编排层的事 —— 那是平台形态，不是业务规则。
//   `changed`/`added` 这类计数在这里算好返回，是因为它们由业务判据决定（差集、去重），
//   放在 ViewModel 里重算必然分叉。
//
// ★ 顺序语义：`overrideMap` 用 [LinkedHashMap] 累积，后加的照片排在后面。
//   这直接决定「移动到同一事件的多张照片」在台账里的先后（选首张/末张），不要换成 HashMap。
package domain.adjust

import algorithm.ClassifyResult
import domain.view.AdjustmentLayer
import domain.view.ViewDerivation

/** 人工调整的全量状态：移动 / 复制 / 台账标记。 */
data class ManualState(
    val overrideMap: Map<Long, Int> = emptyMap(),
    val copyMap: Map<Long, Set<Int>> = emptyMap(),
    val marked: Map<Int, Pair<Long?, Long?>> = emptyMap(),
) {
    /** 拆成视图派生要的叠加层。 */
    fun toLayer(): AdjustmentLayer = AdjustmentLayer(overrideMap, copyMap)

    companion object {
        val EMPTY: ManualState = ManualState()
    }
}

/**
 * 移动结果：新状态 + 受影响的照片张数（**不是**「归属真正变了」的张数）。
 *
 * 两者别混：受影响张数 = 传进来且存在的 id 数；真正变更张数要用 [ManualAdjustment.changedCount]
 * 比较移动前后的 owner 集合。PC 的提示语与「要不要重发视图」都用后者。
 */
data class MoveOutcome(val state: ManualState, val affected: Int)

/** 复制结果：新状态 + 实际新增的复制份数量（重复点击、已在目标都不计）。 */
data class CopyOutcome(val state: ManualState, val added: Int)

object ManualAdjustment {

    /**
     * 把照片移动到目标事件（从原位置移除，含未匹配池）。
     *
     * @param targetEventId `null` 表示移出所有事件回到未匹配池（落库为 `-1`）。
     * @param knownPhotoIds 本次分类结果里真实存在的照片 id；其余一律忽略
     *   （陈旧缓存 / 跨批次残留的 id 不该被写进 overrideMap）。
     */
    fun move(
        state: ManualState,
        photoIds: List<Long>,
        targetEventId: Int?,
        knownPhotoIds: Set<Long>,
    ): MoveOutcome {
        val ids = photoIds.distinct().filter { it in knownPhotoIds }
        if (ids.isEmpty()) return MoveOutcome(state, 0)
        val target = targetEventId ?: AdjustmentLayer.REMOVED
        val overrides = LinkedHashMap(state.overrideMap)
        val copies = HashMap(state.copyMap)
        ids.forEach { pid ->
            overrides[pid] = target
            // 移动即「归属变更」：旧的复制份必须一起清掉，否则移走之后原事件里还留着一份。
            copies.remove(pid)
        }
        return MoveOutcome(state.copy(overrideMap = overrides, copyMap = copies), ids.size)
    }

    /**
     * 计算「这次移动真正改变了几张照片的归属」。
     *
     * PC 的 `movePhotos` 用它决定提示语与是否重发视图。直接用 `photoIds.size` 会高估：
     * 目标本来就是它的家时什么都没发生，而「已移动 1 张照片」在那时候是一句假话。
     */
    fun changedCount(
        c: ClassifyResult,
        before: AdjustmentLayer,
        after: AdjustmentLayer,
        photoIds: List<Long>,
    ): Int {
        val ids = photoIds.distinct()
        return ids.count { pid ->
            ViewDerivation.ownersOf(c, before, pid) != ViewDerivation.ownersOf(c, after, pid)
        }
    }

    /**
     * 把照片「复制」到目标事件（多归属，原事件保留一份）。
     *
     * 三个跳过条件都是产品语义，不是防御性代码：
     *   · 未知照片 —— 分类结果里没有它，复制出来的是一份谁也读不到的 id；
     *   · 不在任何事件 —— 未匹配池里的照片没有「家」，「复制」等于同时出现在两处；
     *   · 已在目标 —— 重复点击不能长出第二份。
     *
     * @param effectiveMap 当前**已合并**人工移动的事件照片表（[ViewDerivation.effectiveMap] 的结果），
     *   用它判断「已在某事件」「已在目标」，而不是拿原始分类结果判断。
     */
    fun copy(
        state: ManualState,
        photoIds: List<Long>,
        targetEventId: Int,
        effectiveMap: Map<Int, List<Long>>,
        knownPhotoIds: Set<Long>,
    ): CopyOutcome {
        if (targetEventId < 0 || photoIds.isEmpty()) return CopyOutcome(state, 0)
        val already = effectiveMap[targetEventId]?.toSet() ?: emptySet()
        val inSomeEvent = effectiveMap.values.flatten().toSet()
        val copies = LinkedHashMap<Long, MutableSet<Int>>()
        state.copyMap.forEach { (k, v) -> copies[k] = LinkedHashSet(v) }
        var added = 0
        for (pid in photoIds.distinct()) {
            if (pid !in knownPhotoIds || pid !in inSomeEvent || pid in already) continue
            if (copies.getOrPut(pid) { LinkedHashSet() }.add(targetEventId)) added++
        }
        return CopyOutcome(state.copy(copyMap = copies), added)
    }

    /**
     * 设置台账照片（事件 + 槽位 1/2）。
     *
     * @param photoId `null` = 清空该槽位。
     * @return slot 非 1/2 时原样返回（既有实现的静默忽略语义，不改）。
     */
    fun mark(state: ManualState, eventId: Int, slot: Int, photoId: Long?): ManualState {
        if (slot != 1 && slot != 2) return state
        val current = state.marked[eventId] ?: (null to null)
        val next = if (slot == 1) (photoId to current.second) else (current.first to photoId)
        return state.copy(marked = state.marked + (eventId to next))
    }

    /** 恢复最初分类：清掉移动、复制、标记三类人工痕迹。 */
    fun reset(): ManualState = ManualState.EMPTY

    /** 重置前统计「清掉了多少处人工调整」，用于提示语（PC 既有口径：移动 + 复制，不含标记）。 */
    fun adjustmentCount(state: ManualState): Int = state.overrideMap.size + state.copyMap.size
}
