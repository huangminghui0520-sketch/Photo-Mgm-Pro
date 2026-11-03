// shared/domain/view/PhotoOrder.kt —— 照片先后口径（T07 引入，T12 启用）
//
// 为什么现在写、又为什么现在不用：
//
//   现状（2026-11-03）两端**都没有显式排序**。事件内照片的先后完全由输入顺序决定：
//   · PC   `Files.walk` / 目录遍历顺序（受文件系统与操作系统影响）
//   · 安卓  MediaStore 查询顺序（受 `_id` 分配与媒体扫描时机影响）
//   两端的 `eventPhotoMap` 又都建立在 ingest 顺序之上，于是「事件照片列表顺序」是一个
//   **未定义行为**。它影响的地方比看起来多：台账照片列取「首张 / 末张」、预览页第一张缩略图、
//   ZIP 内是否重名编号。同一批照片换台机器跑，台账照片列就可能换一张。
//
//   但 T07 阶段**不能直接接上去**：一旦把排序塞进派生流程，「原本靠输入顺序碰巧一致」的
//   产物会立刻变化（黄金样本会红），而那时候我们还没法区分「修好了」和「改坏了」。
//   所以本文件先作为**唯一口径的定义**落地，由测试锁住语义；真正接到
//   `ViewDerivation` / `LedgerProjection` 是 T12 的事，且必须单独提交 + 写进 CHANGELOG，
//   并重新采集两端黄金样本基线。
//
// ★ 口径本身（再改就要重采基线）：
//   1. 有拍摄时间在前，按时间升序；
//   2. 无拍摄时间的排**最后**（不是最前）—— 它们多是 EXIF 读不出/被剥离的照片，
//      把「时间未知」放在第一张的位置会让台账照片列选到一张来路不明的图；
//   3. 同一时刻按 `displayName` 升序（自然序，不做大小写折叠）—— 连拍照片时间可能完全相同，
//      必须有一个确定性的第二关键字，否则又回到「看输入顺序」。
package domain.view

import algorithm.model.Photo

object PhotoOrder {

    /**
     * 事件内照片的推荐排序：拍摄时间 → 文件名。
     *
     * `captureTimeMs` 为 null（EXIF 缺失/剥离）者排在所有有时间的照片之后。
     */
    val BY_CAPTURE_TIME_THEN_NAME: Comparator<Photo> =
        compareBy<Photo, Long?>(nullsLast(naturalOrder())) { it.captureTimeMs }
            .thenBy { it.displayName }

    /** 按 [BY_CAPTURE_TIME_THEN_NAME] 排序，返回新列表（不改入参）。 */
    fun sortedPhotos(photos: List<Photo>): List<Photo> = photos.sortedWith(BY_CAPTURE_TIME_THEN_NAME)

    /**
     * 按 id 顺序重排事件照片表。
     *
     * @param photoById 照片 id → 照片；查不到的 id 排在最后并按 id 升序，保证结果仍然确定。
     */
    fun sortEventMap(
        map: Map<Int, List<Long>>,
        photoById: (Long) -> Photo?,
    ): Map<Int, List<Long>> = map.mapValues { (_, ids) ->
        ids.sortedWith(
            Comparator { a, b ->
                val pa = photoById(a)
                val pb = photoById(b)
                when {
                    pa != null && pb != null -> BY_CAPTURE_TIME_THEN_NAME.compare(pa, pb)
                    pa != null -> -1
                    pb != null -> 1
                    else -> a.compareTo(b)
                }
            },
        )
    }
}
