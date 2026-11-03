// shared/domain/hash/Sha256.kt —— SHA-256（T03-C1：纯新增，暂不切调用）
//
// 为什么只有 `ofBytes` / `ofStream` 两个入口，**没有** `ofFile(path)`：
//   本模块准入 C2 要求"零平台 SDK"、C1 要求"不读文件系统"。一旦这里出现 `java.io.File`，
//   下一个人就会顺手把 `File.exists()`、`Files.copy` 甚至 `Files.walk` 也搬进来，
//   共享层边界当场糊掉。所以：**读写文件留在平台层**，这里只接收字节或流。
//   安卓侧 `data/FileHasher`、PC 侧 `core/PcFileHasher` 各留一行适配器即可。
//
// 缓冲区沿用两端的 64 KB：既有实现（安卓 `FileHasher`、PC `PcFileHasher`）都是这个值，
// 改它会改变单张照片的读取次数，进而影响"万级照片导出"的耗时曲线 —— 别无声改。
package domain.hash

import java.io.InputStream
import java.security.MessageDigest

object Sha256 {
    /** 分块读取缓冲区（沿用两端既有实现）。 */
    const val BUFFER_SIZE: Int = 64 * 1024

    private val HEX = "0123456789abcdef".toCharArray()

    /** 计算字节数组的 SHA-256，返回**小写十六进制**（64 字符）。 */
    fun ofBytes(bytes: ByteArray): String = ofDigest(MessageDigest.getInstance("SHA-256").apply { update(bytes) })

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
        return ofDigest(digest)
    }

    private fun ofDigest(digest: MessageDigest): String {
        val raw = digest.digest()
        val out = StringBuilder(raw.size * 2)
        for (b in raw) {
            val v = b.toInt() and 0xFF
            out.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return out.toString()
    }
}
