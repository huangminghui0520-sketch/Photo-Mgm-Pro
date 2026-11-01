// ui/model/LedgerJoin.kt —— 台账行 ↔ 事件的配对
//
// ★ 为什么需要这一层：AppState 里 events 按日志顺序，ledger 行按**结束时间排序**
//   （LedgerEngine.buildLedger 里 `events.sortedBy { endTimeMs }`），两者下标不对应。
//   而复核阶段要显示"这一行的台账照片 1/2 是哪两张"，只能靠内容配对。
//
//   配对键用 (location, description)：这两个字段来自日志原文，LedgerRow 原样带出，
//   比时间更稳（时间在"待定事件"里是空的）。同一对值可能出现多次（同一桩号连续记两条
//   同样的病害），所以按出现顺序逐个消费，不做"第一条命中就永远命中"。
//
//   配对失败不是错误：返回 null，界面就不显示台账列，而不是显示错行。
package com.photomgm.desktop.ui.model

import com.photomgm.desktop.core.api.AppState
import com.photomgm.desktop.core.api.LedgerRowInfo
import com.photomgm.desktop.core.api.PhotoInfo

fun ledgerByEvent(state: AppState): Map<Int, LedgerRowInfo> {
    if (state.ledger.isEmpty() || state.events.isEmpty()) return emptyMap()
    val remaining = state.ledger.toMutableList()
    val out = HashMap<Int, LedgerRowInfo>(state.events.size)
    for (e in state.events) {
        val idx = remaining.indexOfFirst { it.location == e.location && it.description == e.description }
        if (idx >= 0) out[e.id] = remaining.removeAt(idx)
    }
    return out
}

/**
 * 台账照片槽位 → 该事件内的照片 id。
 *
 * 台账里的标签形如 `01_护栏损坏/IMG_001.jpg`（文件夹名前缀 + 文件名），
 * 所以用"路径最后一段"与 displayName 比对。同名不同目录的照片在同一事件里几乎不存在
 * （同一事件内文件名由相机保证唯一），因此这个匹配是安全的。
 */
fun slotPhotoIds(row: LedgerRowInfo?, photos: List<PhotoInfo>): Map<Long, Int> {
    if (row == null) return emptyMap()
    val out = HashMap<Long, Int>(2)
    fun match(label: String?): Long? {
        if (label.isNullOrBlank()) return null
        val name = label.substringAfterLast('/')
        return photos.firstOrNull { it.displayName == name }?.id
    }
    match(row.photo1Label)?.let { out[it] = 1 }
    // 只有一个照片的事件，台账会把同一张写进两列；这种时候标"照1"而不是"照2"，
    // 否则界面上会显示一个其实只有一张照片的事件"用了第 2 格"。
    match(row.photo2Label)?.let { if (!out.containsKey(it)) out[it] = 2 }
    return out
}
