// data/FileHasher.kt —— 文件内容 SHA-256（方案B' 双层判定：区分"同一照片复制副本"与"水印相机独立 original"）
// 使用场景：dedupDerived 同基名分组后，读主图与派生文件内容哈希——
//   哈希一致 = 同一照片的复制副本（合并）；哈希不同 + _original = 独立照片（保留）。
// - sourceRef 为 MediaStore DATA 物理路径（/storage/emulated/0/...）时直接读文件；
//   content:// URI / RELATIVE_PATH（无物理路径）读不到 → 返回 null。
// - 读失败返回 null（不抛异常）：dedupDerived 对 _original 走"保守保留"，对加工副本走合并。
package com.photomgm.data

import java.io.File
import java.security.MessageDigest

class FileHasher {

    /** 计算文件 SHA-256 十六进制小写；文件不可读/路径非物理文件 → null。 */
    fun sha256(sourceRef: String): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        File(sourceRef).inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            var n = input.read(buf)
            while (n >= 0) {
                digest.update(buf, 0, n)
                n = input.read(buf)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()
}
