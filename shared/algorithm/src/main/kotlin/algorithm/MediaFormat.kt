// algorithm/MediaFormat.kt —— 媒体格式判定（两端共用一份）
//
// ★ T09 上移理由：`heicCount` 原本只有桌面端统计（`PcPhotoSourceProvider.isHeic`）。
//   它不是一个"桌面端顺手的统计"，而是**台账里有些照片格只有文件名的唯一原因**：
//   Excel 不支持 HEIC，`XlsxWriter` 的格式白名单只放行 JPEG/PNG，嵌不进去就退化为文件名。
//   用户在两端看到同一个现象（"有的格子没有图"），就该在两端看到同一句解释。
//
// 判定只按**扩展名**：HEIC 的容器头是 `ftyp` + brand，和 MP4 系共用，读文件头要开文件；
//   而扫描阶段本来就要按扩展名过滤，用同一份清单不会多一次 IO。
//   ★ 真正的格式识别（魔数）在 `XlsxWriter` 里做，那里才是"字节到底能不能嵌"的判据。
package algorithm

/** HEIC/HEIF 扩展名（v1 不解码，仅参与扫描与文件名日期兜底）。 */
private val HEIC_EXTENSIONS: Set<String> = setOf("heic", "heif")

/**
 * 该文件名是否 HEIC/HEIF（大小写不敏感）。
 *
 * ★ 点号必须在首位之后：`.heic`（无主名的隐藏文件）不算，否则会统计出一个永远嵌不进图的幽灵。
 */
fun isHeicPhotoName(fileName: String): Boolean {
    val dot = fileName.lastIndexOf('.')
    if (dot <= 0 || dot == fileName.length - 1) return false
    return fileName.substring(dot + 1).lowercase() in HEIC_EXTENSIONS
}
