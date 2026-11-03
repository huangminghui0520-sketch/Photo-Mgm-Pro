// shared/app/usecase/PhotoScan.kt —— 粗筛那一段的**唯一实现**（T11）
//
// 为什么单独抽出来：
//   `AnalysisPipeline.scanAndRead` 与安卓的「防线一」照片增删校验（`AndroidCore.currentPhotoIds`）
//   需要**同一套**粗筛判定。此前两边各写一份（流水线一份、`PhotoRepository` 一份，连
//   `MediaStorePhotoSourceProvider`/`FileHasher` 都是各自新建的实例）——那正是
//   `PhotoRepository` 文件头警告过的分叉：一旦两边判定漂移，就会出现
//   「校验说照片没变、重扫却扫出另一批」这种最难排查的故障（表现为人工调整错位到别的照片上）。
//
// 只搬了「候选合并 → 目录白名单 + 文件名日期粗筛 → 派生去重」这一段：
//   id 分配、EXIF 精读、按 id 去重仍在流水线里 —— 它们有严格的前后顺序（见 `AnalysisPipeline` 文件头），
//   而防线一校验**不需要**它们（只要候选集合）。
package app.usecase

import algorithm.CandidatePhoto
import algorithm.PhotoIngest
import algorithm.PhotoSourceProvider
import java.time.LocalDate

/** 粗筛结果：候选照片 + 被派生去重合并掉的组数（后者只用于统计日志）。 */
class CoarseCandidates(
    val photos: List<CandidatePhoto>,
    val mergedGroups: Int,
)

object PhotoScan {

    /**
     * 粗筛候选：多源目录候选合并 → 目录白名单 + 文件名日期粗筛 → 派生文件基名/内容哈希去重。
     *
     * **强制实时**：每次调用直接查 MediaStore，没有 TTL 缓存 ——
     * 防线一要求"照片删除/新增立即感知"，缓存会让它看到上一批照片。
     *
     * @param hasher 内容哈希（区分"复制副本"与"水印相机独立 original"）；读不到返回 null。
     */
    fun coarseCandidates(
        sourceProvider: PhotoSourceProvider,
        sourceDirs: List<String>,
        date: LocalDate,
        hasher: (String) -> String?,
    ): CoarseCandidates {
        val all = ArrayList<CandidatePhoto>()
        // 每源独立查询（§4.4）：MediaStore 的 RELATIVE_PATH 过滤按源目录展开，合并查询会漏
        for (dir in sourceDirs) all += sourceProvider.listCandidates(dir)

        // 粗筛：目录白名单 + 文件名日期区间（闭区间，只看文件名）+ 按路径去重
        val filtered = PhotoIngest.coarseFilter(all, sourceDirs, date..date)
        // 派生去重：内容哈希双层判定（保留主图，合并真正的复制副本）
        val (deduped, mergedGroups) = PhotoIngest.dedupDerived(filtered) { hasher(it) }
        return CoarseCandidates(deduped, mergedGroups)
    }
}
