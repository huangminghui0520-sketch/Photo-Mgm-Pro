// shared/domain/hash/Sha256Test.kt —— SHA-256（纯 JVM 单测，T03-C1）
//
// 三条主线：
//   ① 已知向量：证明"算的是对的"，而不只是"几次算得一样"；
//   ② 分块边界：64 KB 前后各差 1 字节 —— 缓冲区循环写错（漏掉尾部/重复喂一段）只会在这里暴露；
//   ③ 与 JDK `MessageDigest` + `%02x` 逐字节一致：安卓旧实现用 `"%02x".format(byte)` 拼十六进制，
//      PC 旧实现用查表。两套都要被新实现同时替代，所以**必须证明两种写法结果相同**，
//      尤其负数 byte 的补零/符号位（`%02x` 对 Byte 会先加 2^8，直接 `Integer.toHexString` 会出错）。
package domain.hash

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest
import kotlin.random.Random

class Sha256Test {

    // ───────────────────────── ① 已知向量 ─────────────────────────

    @Test
    fun `空内容的标准向量`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Sha256.ofBytes(ByteArray(0)),
        )
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Sha256.ofStream(ByteArrayInputStream(ByteArray(0))),
        )
    }

    @Test
    fun `abc 的标准向量`() {
        val expected = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        assertEquals(expected, Sha256.ofBytes("abc".toByteArray()))
        assertEquals(expected, Sha256.ofStream(ByteArrayInputStream("abc".toByteArray())))
    }

    @Test
    fun `输出恒为64位小写十六进制`() {
        val hex = Sha256.ofBytes("x".toByteArray())
        assertEquals(64, hex.length)
        assertTrue(hex.all { it in '0'..'9' || it in 'a'..'f' }, "出现了大写或非十六进制字符：$hex")
    }

    // ───────────────────────── ② 分块边界 ─────────────────────────

    @Test
    fun `64KB缓冲区前后各差一字节时结果与整体一致`() {
        val rnd = Random(20261102)   // 固定种子：失败可复现
        for (size in listOf(Sha256.BUFFER_SIZE - 1, Sha256.BUFFER_SIZE, Sha256.BUFFER_SIZE + 1, Sha256.BUFFER_SIZE * 2 + 7)) {
            val bytes = rnd.nextBytes(size)
            assertEquals(
                Sha256.ofBytes(bytes),
                Sha256.ofStream(ByteArrayInputStream(bytes)),
                "长度 $size 时流式与整体结果不一致（缓冲区循环写错了）",
            )
        }
    }

    // ───────────────── ③ 与两端旧实现的十六进制写法一致 ─────────────────

    /**
     * 安卓旧实现：`digest.digest().joinToString("") { "%02x".format(it) }`。
     * 覆盖全 0x00–0xFF 每个字节值：负数 byte 的补零最容易在这里出错。
     */
    @Test
    fun `与安卓旧写法百分号02x逐字节一致`() {
        val all = ByteArray(256) { it.toByte() }
        val legacy = MessageDigest.getInstance("SHA-256").digest(all)
            .joinToString("") { "%02x".format(it) }
        assertEquals(legacy, Sha256.ofBytes(all))
        // 单独确认"含高位字节"的情形真的走到了：全 0xFF 的哈希里必然有 a-f
        assertTrue(Sha256.ofBytes(ByteArray(64) { 0xFF.toByte() }).any { it in 'a'..'f' })
    }

    /** PC 旧实现：`digest.digest().toHex()`（查表）。同算法不同写法，结果必须一致。 */
    @Test
    fun `与PC旧查表写法逐字节一致`() {
        val rnd = Random(42)
        repeat(20) {
            val bytes = rnd.nextBytes(1 + rnd.nextInt(4096))
            val legacyTable = buildString {
                val HEX = "0123456789abcdef".toCharArray()
                for (b in MessageDigest.getInstance("SHA-256").digest(bytes)) {
                    val v = b.toInt() and 0xFF
                    append(HEX[v ushr 4]).append(HEX[v and 0x0F])
                }
            }
            assertEquals(legacyTable, Sha256.ofBytes(bytes))
        }
    }

    // ───────────────────────── 所有权约定 ─────────────────────────

    /**
     * `ofStream` **不得关闭**传入的流：平台层适配器写的是
     * `File(src).inputStream().use { Sha256.ofStream(it) }`，若这里先 close，
     * 调用方的 `use` 会再关一次（通常无害），但"同一个流算完还能继续读"的场景会直接失败。
     * 这条用例把约定钉死，避免以后有人"顺手加个 use"。
     */
    @Test
    fun `ofStream不关闭调用方的流`() {
        var closed = false
        val stream: InputStream = object : ByteArrayInputStream("abc".toByteArray()) {
            override fun close() {
                closed = true
                super.close()
            }
        }
        val hex = Sha256.ofStream(stream)
        assertNotNull(hex)
        assertTrue(!closed, "ofStream 关闭了调用方传入的流，违反所有权约定")
        // 流仍可继续读（未关闭）
        assertEquals(-1, stream.read())
    }

    // ───────────────────────── ④ 增量摘要（T10） ─────────────────────────

    /**
     * 分块方式不得影响结果：这是"边复制边算"能与"整体算"等价的唯一前提。
     * 覆盖 1 字节小块（最坏的分块）与 64 KB ± 1（缓冲区边界）。
     */
    @Test
    fun `增量摘要在任意分块下与整体一致`() {
        val rnd = Random(20261103)
        for (size in listOf(0, 1, 2, Sha256.BUFFER_SIZE - 1, Sha256.BUFFER_SIZE + 3)) {
            val bytes = rnd.nextBytes(size)
            val expected = Sha256.ofBytes(bytes)
            for (chunk in listOf(1, 7, 1024, Sha256.BUFFER_SIZE)) {
                val d = Sha256.digester()
                var off = 0
                while (off < bytes.size) {
                    val n = minOf(chunk, bytes.size - off)
                    d.update(bytes, off, n)
                    off += n
                }
                assertEquals(expected, d.finish(), "size=$size chunk=$chunk 时分块结果与整体不一致")
            }
        }
    }

    /** `finish()` 必须幂等：`MessageDigest.digest()` 会把状态清空，第二次调用会得到空内容的哈希。 */
    @Test
    fun `增量摘要finish幂等且结束后拒绝update`() {
        val d = Sha256.digester()
        d.update("abc".toByteArray())
        val first = d.finish()
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", first)
        assertEquals(first, d.finish(), "第二次 finish 返回了不同值（状态被 digest() 清空了）")
        assertThrows(IllegalStateException::class.java, { d.update("x".toByteArray()) }, "结束后再 update 必须报错，而不是静默算错")
    }

    /** 空摘要器 == 空内容的哈希（"零字节文件"要能算出标准向量，而不是抛异常）。 */
    @Test
    fun `空摘要器得到空内容向量`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Sha256.digester().finish(),
        )
    }
}
