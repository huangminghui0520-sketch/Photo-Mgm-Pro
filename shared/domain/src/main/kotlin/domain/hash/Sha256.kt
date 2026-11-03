// shared/domain/hash/Sha256.kt —— SHA-256（T03-C1 新增；T10 补增量入口）
//
// 为什么只有 `ofBytes` / `ofStream` / `digester` 三个入口，**没有** `ofFile(path)`：
//   本模块准入 C2 要求"零平台 SDK"、C1 要求"不读文件系统"。一旦这里出现 `java.io.File`，
//   下一个人就会顺手把 `File.exists()`、`Files.copy` 甚至 `Files.walk` 也搬进来，
//   共享层边界当场糊掉。所以：**读写文件留在平台层**，这里只接收字节或流。
//   安卓侧 `data/FileHasher`、PC 侧 `core/PcFileHasher` 各留一行适配器即可。
//
// 缓冲区沿用两端的 64 KB：既有实现（安卓 `FileHasher`、PC `PcFileHasher`）都是这个值，
// 改它会改变单张照片的读取次数，进而影响"万级照片导出"的耗时曲线 —— 别无声改。
//
// ★ T10 补 [Sha256.digester] 的起因：仓库里曾有 **4 处** `MessageDigest.getInstance("SHA-256")`
//   （安卓 FileHasher / PcFileHasher / PcFolderExporter 复制循环 / PhotoIdAllocator）。
//   前两处 T03 已收口，后两处当初各有"正当理由"没搬：
//     · 复制循环要**边读边写边算**，而这里只有整段/整流的入口；
//     · id 分配只要**前 8 字节**，而这里只返回十六进制串。
//   两个理由都成立，但正确的修法是补入口，不是让算法留 4 份 —— 否则哪天有人调缓冲区、
//   改大小写、动符号位处理，四条路径会分别漂移，而症状是"清单里的哈希对不上"。
package domain.hash

import java.io.InputStream
import java.security.MessageDigest

object Sha256 {
    /** 分块读取缓冲区（沿用两端既有实现）。 */
    const val BUFFER_SIZE: Int = 64 * 1024

    /** 计算字节数组的 SHA-256，返回**小写十六进制**（64 字符）。 */
    fun ofBytes(bytes: ByteArray): String =
        sha256Hex(MessageDigest.getInstance("SHA-256").apply { update(bytes) })

    /**
     * 计算输入流的 SHA-256，返回小写十六进制。
     *
     * ★ **不关闭传入的流**：所有权属于调用方（平台层用 `use { }` 关）。
     *   在这里 `close()` 会让"同一个流算完哈希还能继续读"的调用方莫名失败。
     *   读失败按异常向上抛 —— 是否吞掉异常、要不要退化成 null，是调用方的策略
     *   （例如"哈希读不到就保守保留照片"），不属于哈希本身。
     */
    fun ofStream(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(BUFFER_SIZE)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            // n == 0 时不能 break：非阻塞/网络流可能暂时读不到数据但流未结束
            if (n > 0) digest.update(buf, 0, n)
        }
        return sha256Hex(digest)
    }

    /**
     * 增量摘要：**边读边算**，供「复制并留证」这类边写边要哈希的场景用。
     *
     * ★ 为什么不直接用 [ofStream]：导出时字节是「从源读一块、往目标写一块」流过去的，
     *   事后再算一遍哈希等于把源文件**再读一次**（1000 张 × 4MB = 多 4GB 顺序读）；
     *   用 [ofBytes] 则要把整张照片读进内存。两者都比"顺手 update 一下"差得多。
     *
     * ★ 只开 `update` + `finish` 两个口子，不暴露底层 `MessageDigest`：
     *   一旦暴露，`digest()` 的"调一次就清空状态"就会漏到调用方，
     *   「复制完算一次哈希、日志里再算一次」会静默得到**空内容**的哈希。
     */
    class Digester internal constructor() {
        private val digest: MessageDigest = MessageDigest.getInstance("SHA-256")
        private var hex: String? = null

        /** 喂入 `buffer` 的 `[offset, offset+length)` 区间（`length == 0` 是空操作）。 */
        fun update(buffer: ByteArray, offset: Int, length: Int) {
            check(hex == null) { "Digester 已结束，不能再 update —— 每份产物请新建一个" }
            require(offset >= 0 && length >= 0 && offset + length <= buffer.size) {
                "区间越界：offset=$offset length=$length size=${buffer.size}"
            }
            if (length > 0) digest.update(buffer, offset, length)
        }

        /** 喂入整段字节。 */
        fun update(bytes: ByteArray) = update(bytes, 0, bytes.size)

        /**
         * 结束并返回小写十六进制。
         *
         * ★ 幂等：`MessageDigest.digest()` 会重置内部状态，第二次调用返回的是**空内容**的哈希。
         *   这里把结果缓存住 —— 否则"日志里想再记一次哈希"就成了一条静默的错数据。
         */
        fun finish(): String = hex ?: sha256Hex(digest).also { hex = it }
    }

    /** 新建一个增量摘要器（同一份字节无论怎么切块，结果必须相同）。 */
    fun digester(): Digester = Digester()
}

/**
 * 摘要 → 小写十六进制（查表）。
 *
 * ★ 放在文件级而不是 object 内：`Digester` 这个嵌套类也要用，
 *   而它访问外层 object 的 private 成员要靠合成访问器 —— 文件级 private 更直白，
 *   也避免以后有人手滑把 `Digester` 提成 inner class 时又出一堆隐式外部引用。
 */
private fun sha256Hex(digest: MessageDigest): String {
    val raw = digest.digest()
    val hex = "0123456789abcdef".toCharArray()
    val out = StringBuilder(raw.size * 2)
    for (b in raw) {
        val v = b.toInt() and 0xFF
        out.append(hex[v ushr 4]).append(hex[v and 0x0F])
    }
    return out.toString()
}
