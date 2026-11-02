// data/MediaStorePhotoSourceProvider.kt —— §4.4 MediaStore 候选照片查询（Android 实现）
// 每个源目录独立查询；目录白名单（前缀+分隔符）复用 :algorithm 的 PhotoIngest.inSourceDir（纯逻辑）
package com.photomgm.data

import algorithm.CandidatePhoto
import algorithm.PhotoIngest
import algorithm.PhotoSourceProvider
import android.content.ContentResolver
import android.provider.MediaStore

/**
 * MediaStore 数据接入：列出授权源目录（如 DCIM/Camera）下的候选照片。
 * - sourceRef 优先取 DATA（物理路径，供 ExifInterface 直接读），无则用 RELATIVE_PATH
 * - 查询失败（权限未授予等）返回空列表，不抛异常
 */
class MediaStorePhotoSourceProvider(private val resolver: ContentResolver) : PhotoSourceProvider {

    override fun listCandidates(sourceDir: String): List<CandidatePhoto> {
        val out = mutableListOf<CandidatePhoto>()
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.DATA,
        )
        // 查询失败（权限/异常）→ 返回已收集结果；不抛异常
        runCatching {
            resolver.query(uri, projection, null, null, null)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val relCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
                val dataCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                while (c.moveToNext()) {
                    val rel = c.getString(relCol) ?: continue
                    if (!PhotoIngest.inSourceDir(rel, sourceDir)) continue   // 目录白名单（纯逻辑）
                    val id = c.getLong(idCol)
                    val name = c.getString(nameCol) ?: continue
                    val data = c.getString(dataCol)
                    val sourceRef = data ?: rel
                    out.add(CandidatePhoto(id, sourceRef, name))
                }
            }
        }
        return out
    }
}
