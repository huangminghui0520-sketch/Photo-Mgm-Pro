// desktop/core/logic/PcViewDerivation.kt —— PC 现行视图派生（双跑期的"旧实现"）
//
// 为什么单独成文件：
//   T07 要求「同一组输入分别喂给 PC 现行实现与新 domain 实现，断言输出逐字段相等」。
//   原来的四处逻辑是 `PhotoMgmCoreImpl` 的 private 方法，读实例字段，测试根本调不到 ——
//   要么用反射（脆且掩盖签名），要么在测试里抄一份副本（那不是"现行实现"，抄错了也测不出来）。
//   所以把这段**逐字搬出来、参数化**，核心类改为委托。搬动本身零语义变化，
//   由黄金样本（72 行 / 504 单元格 / 50 嵌图 0 差异）与双跑测试共同兜底。
//
// ★ 这是**过渡形态**：T12 切换调用后整个文件删除。不要在这里加新能力、不要修 bug ——
//   任何行为变更都会让"与 domain 实现等价"这个结论失去意义。
package com.photomgm.desktop.core.logic

import algorithm.ClassifyResult
import algorithm.DedupKeepMode
import algorithm.PhotoIngest
import algorithm.expandForKeepMode
import algorithm.model.Photo

internal object PcViewDerivation {

    /**
     * 合并人工移动 + 复制后的事件照片表。
     *
     * 顺序是有意义的：先按 overrideMap 把照片从旧事件里摘掉、放进新事件，
     * 再叠加 copyMap —— 这样"移动"永远优先于"复制"，复制只往表里加、从不摘。
     */
    fun effectiveMap(
        c: ClassifyResult,
        overrideMap: Map<Long, Int>,
        copyMap: Map<Long, Set<Int>>,
    ): Map<Int, List<Long>> {
        val map = c.eventPhotoMap.mapValues { it.value.toMutableList() }.toMutableMap()
        val all = (c.eventPhotoMap.values.flatten() + c.unmatched).toSet()
        for ((pid, target) in overrideMap) {
            if (pid !in all) continue
            map.values.forEach { it.remove(pid) }
            if (target >= 0) map.getOrPut(target) { mutableListOf() }.add(pid)
        }
        for ((pid, targets) in copyMap) {
            if (pid !in all) continue
            // 被移进未匹配池的照片不再保留复制份（否则"移出"后它还挂在别的事件里）。
            if ((overrideMap[pid] ?: 0) < 0) continue
            for (target in targets) {
                if (target < 0) continue
                val list = map.getOrPut(target) { mutableListOf() }
                if (pid !in list) list.add(pid)
            }
        }
        return map
    }

    /** 三态展开（仅作用于已分类事件照片；未匹配不参与）。 */
    fun expandedEventMap(
        c: ClassifyResult,
        overrideMap: Map<Long, Int>,
        copyMap: Map<Long, Set<Int>>,
        mode: DedupKeepMode,
        dupGroups: Map<Long, List<Photo>>,
        marked: Map<Int, Pair<Long?, Long?>>,
    ): Map<Int, List<Long>> {
        val effective = effectiveMap(c, overrideMap, copyMap)
        if (mode == DedupKeepMode.KEEP_BOTH) return effective
        val keepAlways = marked.values.flatMap { listOfNotNull(it.first, it.second) }.toSet()
        val originEventOf = HashMap<Long, Int>()
        for ((eventId, ids) in c.eventPhotoMap) for (pid in ids) originEventOf[pid] = eventId
        val passthrough = overrideMap
            .filter { (pid, target) -> target < 0 || originEventOf[pid] != target }
            .keys
        return effective.mapValues { (_, ids) ->
            PhotoIngest.expandForKeepMode(ids, dupGroups, mode, keepAlways, passthrough)
        }
    }

    fun effectiveUnmatched(c: ClassifyResult, overrideMap: Map<Long, Int>): List<Long> {
        val all = (c.eventPhotoMap.values.flatten() + c.unmatched).toSet()
        val moved = overrideMap.filterValues { it >= 0 }.keys
        val removed = overrideMap.filterValues { it < 0 }.keys.filter { it in all }
        return (c.unmatched.filter { it !in moved } + removed).distinct()
    }

    /** 台账照片列标签（`文件夹名/文件名`）；反查不到时退化为 ref 最后一段。 */
    fun labelOf(folderName: String, ref: String?, photosById: Map<Long, Photo>): String? {
        if (ref.isNullOrBlank()) return null
        val name = photosById.values.firstOrNull { it.sourceRef == ref }?.displayName
            ?: ref.substringAfterLast('/')
        return if (folderName.isBlank()) name else "$folderName/$name"
    }
}
