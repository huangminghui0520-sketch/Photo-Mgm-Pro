// desktop/core/platform/PcFileHasher.kt —— 文件内容哈希（SHA-256）的平台适配器
//
// T03-C2：哈希算法本身已收敛到 :shared:domain 的 [domain.hash.Sha256]（纯函数，两端共用一套）。
// 这里**只剩"怎么把文件变成字节流"**这一件平台事 —— 用 java.nio 打开、交给纯函数算、
// 读失败返回 null。算法（缓冲区大小、十六进制大小写、负数 byte 补零）不再在桌面端重复实现，
// 否则两端哪天各自"顺手优化"就会悄悄算出不同的哈希，去重结果随之漂移。
//
// 用途：① 派生去重的内容哈希双层判定（区分"复制副本"与"水印相机原图"）；② 导出清单留证。
package com.photomgm.desktop.core.platform

import domain.hash.Sha256
import java.nio.file.Files
import java.nio.file.Paths

internal class PcFileHasher {

    /** 文件 SHA-256（小写十六进制）；读失败返回 null（不抛异常，调用方按"哈希未知"保守处理）。 */
    fun sha256(path: String): String? =
        runCatching { Files.newInputStream(Paths.get(path)).use { Sha256.ofStream(it) } }.getOrNull()

    fun sha256Of(bytes: ByteArray): String = Sha256.ofBytes(bytes)

    fun sha256Of(text: String): String = Sha256.ofBytes(text.toByteArray(Charsets.UTF_8))
}
