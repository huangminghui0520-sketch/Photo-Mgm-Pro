// desktop/core/platform/PcFileHasher.kt —— 文件内容哈希（SHA-256）
//
// 与 Android 端 data/FileHasher 同一算法（MessageDigest），桌面端单写一份而不去依赖 data 模块
// （data 模块带 androidx 依赖，桌面端无法编译它）。
// 用途：① 派生去重的内容哈希双层判定（区分"复制副本"与"水印相机原图"）；② 导出清单留证。
package com.photomgm.desktop.core.platform

import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest

class PcFileHasher {

    /** 文件 SHA-256（小写十六进制）；读失败返回 null（不抛异常，调用方按"哈希未知"保守处理）。 */
    fun sha256(path: String): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(Paths.get(path)).use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                if (n > 0) digest.update(buf, 0, n)
            }
        }
        digest.digest().toHex()
    }.getOrNull()

    fun sha256Of(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    fun sha256Of(text: String): String = sha256Of(text.toByteArray(Charsets.UTF_8))

    private fun ByteArray.toHex(): String {
        val out = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            out.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return out.toString()
    }

    private companion object {
        val HEX = "0123456789abcdef".toCharArray()
    }
}
