// shared/domain/view/ViewDerivation.kt —— 「人工调整后的有效归属」唯一实现（T07）
//
// 为什么要有这一份：
//   PC `PhotoMgmCoreImpl.effectiveMap/expandedEventMap/effectiveUnmatched` 与安卓
//   `AppViewModel` 里的同名三兄弟是**逐行抄写**的关系，注释互相写着"与另一端一致"，
//   但没有任何机制保证。谁先改了 `passthrough` 的判据，另一端的预览/台账/ZIP 就会
//   悄悄不同，而且这种不同只在特定照片组合下出现（三态 + 人工移动 + 重复组同时命中）。
//
// 本文件只做**纯计算**：
//   · 不做缓存（`dupGroups` 的引用相等缓存在平台层，那是指令集层面的性能优化，不是业务规则）
//   · 不读文件、不碰时间、不依赖 Android/JVM 特定 API
//   · 相同输入必得相同输出 —— 这是双跑对比测试能成立的前提
//
// ★ 顺序语义（不是实现细节，改了产物就变）：
//   1. `eventPhotoMap` 里每个事件的原始照片顺序保持不变；
//   2. 人工移动/复制的照片**追加**到目标事件末尾，追加顺序 = `overrideMap`/`copyMap`
//      的迭代顺序 —— 调用方给什么顺序就是什么顺序。这一点继承了既有实现，
//      没有改成"按 id 排序"，因为那会改变台账选照片的结果（首张/末张）。
//      真正要统一"照片先后口径"的是 [PhotoOrder]，由 T12 切调用时启用。
package domain.view

import algorithm.ClassifyResult
import algorithm.DedupKeepMode
import algorithm.PhotoIngest
import algorithm.expandForKeepMode
import algorithm.model.Photo

/**
 * 人工调整叠加层。
 *
 * @param overrideMap 照片 → 目标事件（唯一归属）。`-1` 表示「移出所有事件」回到未匹配池。
 * @param copyMap 照片 → 额外所属事件集合（「家」仍在 classify / overrideMap 里）。
 *   ★ 安卓端目前在 UI 上不提供「复制到多事件」，因此传 `emptyMap()`；
 *   PC 传真实内容。这是 T07 允许的唯一跨端输入差异（见计划 §T07 风险列），
 *   不是两套逻辑 —— 空集合走的是同一条 `for` 循环，只是不迭代。
 */
data class AdjustmentLayer(
    val overrideMap: Map<Long, Int> = emptyMap(),
    val copyMap: Map<Long, Set<Int>> = emptyMap(),
) {
    companion object {
        /** 目标事件 id 为它表示「移出所有事件、回到未匹配池」。 */
        const val REMOVED: Int = -1

        val EMPTY: AdjustmentLayer = AdjustmentLayer()
    }
}

/**
 * 一次派生得到的全部视图。
 *
 * @param effectiveMap 合并人工移动/复制后的事件照片表（**未做三态展开**）。
 *   预览页「原始归属」、UI 上的复制关系判断读它。
 * @param eventPhotoMap 三态展开后的事件照片表（预览 / 台账 / ZIP 用）。
 * @param unmatched 有效未匹配照片（算法未匹配 + 被移除 − 被移入事件）。
 */
data class DerivedView(
    val effectiveMap: Map<Int, List<Long>>,
    val eventPhotoMap: Map<Int, List<Long>>,
    val unmatched: List<Long>,
)

object ViewDerivation {

    /**
     * 合并人工移动 + 复制后的事件照片表。
     *
     * 顺序是有意义的：先按 `overrideMap` 把照片从旧事件里摘掉、放进新事件，
     * 再叠加 `copyMap` —— 这样「移动」永远优先于「复制」，复制只往表里加、从不摘。
     */
    fun effectiveMap(c: ClassifyResult, adj: AdjustmentLayer): Map<Int, List<Long>> {
        val map = c.eventPhotoMap.mapValues { it.value.toMutableList() }.toMutableMap()
        val all = allPhotoIds(c)
        for ((pid, target) in adj.overrideMap) {
            // 分类结果里没有的 id（陈旧缓存 / 跨批次残留）一律忽略：既不该被移走，也不该被插入。
            if (pid !in all) continue
            map.values.forEach { it.remove(pid) }
            if (target >= 0) map.getOrPut(target) { mutableListOf() }.add(pid)
        }
        for ((pid, targets) in adj.copyMap) {
            if (pid !in all) continue
            // 被移进未匹配池的照片不再保留复制份（否则「移出」后它还挂在别的事件里）。
            if ((adj.overrideMap[pid] ?: 0) < 0) continue
            for (target in targets) {
                if (target < 0) continue
                val list = map.getOrPut(target) { mutableListOf() }
                if (pid !in list) list.add(pid)
            }
        }
        return map
    }

    /**
     * 三态展开（仅作用于已分类事件照片；未匹配不参与）。
     *
     * 判定优先级、`passthrough` 的两处历史修正（P1 原地移动 / P2 幽灵照片 / C 冒出备份）
     * 全在 [PhotoIngest.expandForKeepMode] 里，本函数只负责准备它要的三个集合。
     *
     * @param dupGroups 内容判重分组，**由调用方传入**（平台层按照片集合引用缓存）。
     *   ★ 不在这里算：`groupDupContent` 是 O(n²) 级，每帧重算会让万张照片的预览卡死；
     *   而缓存策略（引用相等 / AtomicReference 一次发布）是平台层的并发模型，不属于业务规则。
     * @param marked 人工台账标记（事件 → 照片 1/2），其 id 一律强制保留。
     */
    fun expandedEventMap(
        c: ClassifyResult,
        adj: AdjustmentLayer,
        mode: DedupKeepMode,
        dupGroups: Map<Long, List<Photo>>,
        marked: Map<Int, Pair<Long?, Long?>>,
    ): Map<Int, List<Long>> {
        val effective = effectiveMap(c, adj)
        if (mode == DedupKeepMode.KEEP_BOTH) return effective
        val keepAlways = marked.values.flatMap { listOfNotNull(it.first, it.second) }.toSet()
        val originEventOf = HashMap<Long, Int>()
        for ((eventId, ids) in c.eventPhotoMap) for (pid in ids) originEventOf[pid] = eventId
        // ★ passthrough 只收「真正离开原事件」的移动：
        //   target == 原所属事件 = 原地移动（UI 上等于没动），不算人工意图；若算直通，该照片会绕过
        //   三态过滤（KEEP_PLAIN 下「原地移动主图」会让主图与 _original 同时展示）。
        //   target < 0（移除到未匹配）视同离开；原本在未匹配池、被移入事件（origin=null）也算离开。
        val passthrough = adj.overrideMap
            .filter { (pid, target) -> target < 0 || originEventOf[pid] != target }
            .keys
        return effective.mapValues { (_, ids) ->
            PhotoIngest.expandForKeepMode(ids, dupGroups, mode, keepAlways, passthrough)
        }
    }

    /**
     * 有效未匹配照片：算法未匹配 + 被人工「从事件移除」，再扣除已人工「移动到事件」的。
     *
     * 导出 ZIP / 预览页未匹配池都以它为准，避免被移除的照片丢失、已移动的照片重复显示。
     */
    fun effectiveUnmatched(c: ClassifyResult, adj: AdjustmentLayer): List<Long> {
        val all = allPhotoIds(c)
        val moved = adj.overrideMap.filterValues { it >= 0 }.keys
        val removed = adj.overrideMap.filterValues { it < 0 }.keys.filter { it in all }
        return (c.unmatched.filter { it !in moved } + removed).distinct()
    }

    /** 一次算齐三个视图，避免调用方各自拼装时漏掉某一层（成本相同，只多一次 map 复用）。 */
    fun derive(
        c: ClassifyResult,
        adj: AdjustmentLayer,
        mode: DedupKeepMode,
        dupGroups: Map<Long, List<Photo>>,
        marked: Map<Int, Pair<Long?, Long?>>,
    ): DerivedView = DerivedView(
        effectiveMap = effectiveMap(c, adj),
        eventPhotoMap = expandedEventMap(c, adj, mode, dupGroups, marked),
        unmatched = effectiveUnmatched(c, adj),
    )

    /**
     * 一张照片当前「同属于哪些事件」（含复制份），升序。
     *
     * 未匹配池里的照片返回空集 —— 它此刻不属于任何事件，这是「能不能复制」的判据之一，
     * 也是人工移动后计算「真正移动了几张」的差集来源。
     */
    fun ownersOf(c: ClassifyResult, adj: AdjustmentLayer, photoId: Long): Set<Int> =
        effectiveMap(c, adj).entries.filter { photoId in it.value }.mapTo(sortedSetOf()) { it.key }

    /** 分类结果覆盖的全部照片 id（事件内 + 未匹配）。 */
    private fun allPhotoIds(c: ClassifyResult): Set<Long> =
        (c.eventPhotoMap.values.flatten() + c.unmatched).toSet()
}
