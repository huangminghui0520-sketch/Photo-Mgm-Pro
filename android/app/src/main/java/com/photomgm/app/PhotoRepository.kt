// app/PhotoRepository.kt —— 照片数据接入编排（§4.1：MediaStore 查候选 → 粗筛 → EXIF 精读）
// 复用 :data 平台实现（MediaStorePhotoSourceProvider / ExifPhotoReader / FileHasher）
// 与 :algorithm 纯逻辑（PhotoIngest.coarseFilter / dedupDerived / readExif / dedupById）
// ★ 2026-10-24 P3-4：删除 TTL 缓存（旧 coarseCandidates 30 分钟版 + PhotoCache）——
//   生产路径全部调用方（防线一校验/流水线）本就要求强制实时，TTL 版仅测试可达、属死代码；
//   删除后缓存语义唯一 = 强制实时，消除"双实现漂移 / 陈旧缓存被误用"风险；clearCache 随之移除。
package com.photomgm.app

import algorithm.CandidatePhoto
import algorithm.PhotoIngest
import algorithm.model.Photo
import android.content.Context
import com.photomgm.data.ExifPhotoReader
import com.photomgm.data.FileHasher
import com.photomgm.data.MediaStorePhotoSourceProvider
import java.time.LocalDate

class PhotoRepository(context: Context) {
    private val appContext = context.applicationContext
    private val provider = MediaStorePhotoSourceProvider(appContext.contentResolver)
    private val exif: ExifPhotoReader = ExifPhotoReader()
    /** 方案B'：派生去重内容哈希（区分"复制副本"与"水印相机独立 original"）。 */
    private val hasher = FileHasher()

    /**
     * 粗筛候选（唯一入口，**强制实时、无 TTL 缓存**）：
     * 多源目录候选合并 → 目录白名单 + 文件名日期粗筛 → 派生文件基名去重 → 按 sourceRef 去重。
     * 派生去重（§4.2.1）：同一照片的主图/_original/_compressed 只保留主图，避免无 EXIF 的压缩版
     * 全部落「未分类」且与主图重复计数。
     *
     * ★ 2026-10-24 P3-4：原 TTL 版（30 分钟缓存）与 coarseCandidatesFresh 合并为这一个实时实现。
     *   每次调用直接查 MediaStore（照片删除/新增立即感知），满足"防线一强制实时"的既有语义。
     */
    fun coarseCandidates(date: LocalDate, sourceDirs: List<String>): List<CandidatePhoto> {
        val all = mutableListOf<CandidatePhoto>()
        for (dir in sourceDirs) all += provider.listCandidates(dir)   // 每源独立查询（§4.4）
        val (deduped, _) = PhotoIngest.dedupDerived(
            PhotoIngest.coarseFilter(all, sourceDirs, date..date),
            hasher::sha256,   // 方案B'：内容哈希双层判定，保留水印相机同序号 original
        )
        return deduped
    }

    /** EXIF 精读（仅对粗筛出的 50-200 张；失败置 null 不中断）+ 按 _ID 去重（§4.3/§4.4）。 */
    fun readExif(candidates: List<CandidatePhoto>): List<Photo> =
        PhotoIngest.dedupById(PhotoIngest.readExif(candidates, exif))
}
