// data/FileHasher.kt —— 文件内容 SHA-256 的平台适配器（方案B' 双层判定：区分"同一照片复制副本"与"水印相机独立 original"）
// 使用场景：dedupDerived 同基名分组后，读主图与派生文件内容哈希——
//   哈希一致 = 同一照片的复制副本（合并）；哈希不同 + _original = 独立照片（保留）。
// - sourceRef 为 MediaStore DATA 物理路径（/storage/emulated/0/...）时直接读文件；
//   content:// URI / RELATIVE_PATH（无物理路径）读不到 → 返回 null。
// - 读失败返回 null（不抛异常）：dedupDerived 对 _original 走"保守保留"，对加工副本走合并。
//
// T03-C3：哈希算法已收敛到 :shared:domain 的 [domain.hash.Sha256]，这里**只剩"怎么打开文件"**。
// 刻意不把 `File(...).inputStream()` 搬进 shared/domain：一旦 `java.io.File` 进去，
// 下一个人就会顺势把 `exists()`、`length()` 也搬进去，C2 的"零平台 SDK"边界就废了。
package com.photomgm.data

import domain.hash.Sha256
import java.io.File

class FileHasher {

    /** 计算文件 SHA-256 十六进制小写；文件不可读/路径非物理文件 → null。 */
    fun sha256(sourceRef: String): String? =
        runCatching { File(sourceRef).inputStream().use { Sha256.ofStream(it) } }.getOrNull()
}
