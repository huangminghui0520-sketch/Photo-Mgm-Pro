// desktop/core/platform/PcPhotoSourceProvider.kt —— 照片扫描（实现核心的 PhotoSourceProvider 接口）
//
// Android 侧是 MediaStore 查询；桌面侧是真实文件系统遍历。语义对齐点：
//   1. 递归扫描（用户选的是"照片总目录"，实际照片常在子目录里）；
//   2. 只返回图片扩展名，且**不跟随符号链接**（防环、防网络盘自引用）；
//   3. sourceRef 一律 [PcPaths.normalize]（正斜杠绝对路径）——见 PcPaths 头部关于派生去重的说明；
//   4. 不可读目录/文件不抛异常，记入 [warnings]，由核心汇总成"跳过 N 个不可读项"提示。
package com.photomgm.desktop.core.platform

import algorithm.CandidatePhoto
import algorithm.PhotoSourceProvider
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

class PcPhotoSourceProvider : PhotoSourceProvider {

    /** 不可读/被跳过的项（每次 listCandidates 前清空）。 */
    val warnings = mutableListOf<String>()

    override fun listCandidates(sourceDir: String): List<CandidatePhoto> {
        val root = runCatching { Paths.get(sourceDir) }.getOrNull() ?: return emptyList()
        if (!Files.isDirectory(root)) {
            warnings += "源目录不存在或不是目录：$sourceDir"
            return emptyList()
        }
        val out = ArrayList<CandidatePhoto>(256)
        runCatching {
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val name = dir.fileName?.toString().orEmpty()
                    // 隐藏目录 / 系统目录 / 回收站：跳过整棵子树（不是"没找到照片"，而是"不该来"）
                    if (dir != root && (name.startsWith(".") || name.startsWith("$") ||
                                name.equals("System Volume Information", ignoreCase = true) ||
                                name.equals("RECYCLER", ignoreCase = true) ||
                                name.equals("\$RECYCLE.BIN", ignoreCase = true))
                    ) return FileVisitResult.SKIP_SUBTREE
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile && isSupportedImage(file.fileName.toString())) {
                        out += CandidatePhoto(
                            id = 0, // 由核心的 PhotoIdAllocator 统一分配（这里只负责"发现"）
                            sourceRef = PcPaths.normalize(file.toString()),
                            displayName = file.fileName.toString(),
                        )
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    warnings += "跳过不可读项：${PcPaths.normalize(file.toString())}（${exc.javaClass.simpleName}）"
                    return FileVisitResult.CONTINUE
                }
            })
        }.onFailure { warnings += "扫描中断：$sourceDir（${it.message ?: it.javaClass.simpleName}）" }

        // 稳定排序：让"同一批照片"在两次扫描中顺序一致（影响列表首现顺序与人工调整的直觉）
        out.sortBy { it.sourceRef }
        return out
    }

    companion object {
        /** 支持的图片扩展名（HEIC 可扫描但 EXIF 读不出，由核心显式降级提示）。 */
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "heic", "heif")

        /** HEIC 系（v1 不解码，仅参与扫描与文件名日期兜底）。 */
        val HEIC_EXTENSIONS = setOf("heic", "heif")

        fun isSupportedImage(fileName: String): Boolean {
            val dot = fileName.lastIndexOf('.')
            if (dot <= 0 || dot == fileName.length - 1) return false
            return fileName.substring(dot + 1).lowercase() in IMAGE_EXTENSIONS
        }

        fun isHeic(fileName: String): Boolean {
            val dot = fileName.lastIndexOf('.')
            if (dot <= 0) return false
            return fileName.substring(dot + 1).lowercase() in HEIC_EXTENSIONS
        }
    }
}
