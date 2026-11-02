// algorithm/FileNameDateFilter.kt —— §4.2 文件名日期粗筛（第一道粗筛，万级规模关键）
// 纯 Kotlin / 纯函数，可拷到 PC。只依赖文件名，不读文件内容。
package algorithm

import java.time.LocalDate

object FileNameDateFilter {
    // 单一正则承载全部格式：IMG_20260814_101530 / IMG20260814101530 / PANO_ / VID_ / BURST
    private val DATE_RE = Regex("""(\d{4})(\d{2})(\d{2})""")

    /** 从文件名提取拍摄日期；Screenshot_/mmexport 等非照片文件直接排除；非法日期返回 null。 */
    fun extractDate(fileName: String): LocalDate? {
        if (fileName.startsWith("Screenshot_") || fileName.startsWith("mmexport")) return null
        val m = DATE_RE.find(fileName) ?: return null
        val y = m.groupValues[1].toIntOrNull() ?: return null
        val mo = m.groupValues[2].toIntOrNull() ?: return null
        val d = m.groupValues[3].toIntOrNull() ?: return null
        return runCatching { LocalDate.of(y, mo, d) }.getOrNull()
    }

    /** 文件名是否落在 [start, end] 日期区间内（闭区间）。 */
    fun isInRange(fileName: String, start: LocalDate, end: LocalDate): Boolean {
        val d = extractDate(fileName) ?: return false
        return !d.isBefore(start) && !d.isAfter(end)
    }
}
