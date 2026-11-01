// ui/model/PhotoSearch.kt —— 照片弹窗里的搜索（纯函数，单测盯住）
//
// ★ 为什么弹窗里需要搜索：一个事件最多十几张，未匹配池可能几十张。找"08:31 那张"时，
//   靠横着滚缩略条是线性扫描；而在文件多的日子里，用户记得的往往是时刻或文件名片段。
// ★ 匹配口径故意做得"宽"：文件名、拍摄时刻（补零的 HH:mm 和原始的小时/分钟都算）、
//   以及"无时间"这个状态词。搜索框是为"找到那一张"服务的，多命中一条的代价远小于漏掉。
package com.photomgm.desktop.ui.model

import com.photomgm.desktop.core.api.PhotoInfo
import com.photomgm.desktop.ui.format.hhmm

/** 无时间的照片用这个词能被搜到（它们是真实的一类，用户会专门去找）。 */
private const val NO_TIME = "无时间"

fun photoMatches(photo: PhotoInfo, query: String): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    if (photo.displayName.contains(q, ignoreCase = true)) return true
    // "无时间"是状态词，只对真的没有拍摄时刻的照片成立 —— 否则它会把整条照片条都命中。
    return photo.captureTimeMs?.let { hhmm(it).contains(q) } ?: (q == NO_TIME)
}

/** 按搜索词过滤；空词返回原列表（保持原顺序 —— 时刻序就是用户脑中的顺序）。 */
fun filterPhotos(photos: List<PhotoInfo>, query: String): List<PhotoInfo> =
    if (query.isBlank()) photos else photos.filter { photoMatches(it, query) }
