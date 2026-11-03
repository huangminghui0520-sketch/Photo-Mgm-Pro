// shared/app/infra/TextDecoding.kt —— 日志文本解码（UTF-8 优先，失败回落 GBK）
//
// ★ T09 从桌面端 `PhotoMgmCoreImpl.decodeText` 上移：这段判定是**业务约定**，不是平台细节。
//   内业同事用记事本另存的日志常是 GBK；安卓端若自己写一份「一律 UTF-8」，
//   同一份日志在两端会得到不同结果（安卓满屏问号、解析出 0 条事件），
//   而用户完全无法从现象推断原因。
//
// 三步顺序（不要调整）：
//   1. 空字节 → 空串（不是 null：空日志是合法输入，解析出 0 条事件即可）；
//   2. UTF-8 BOM → 跳过 3 字节按 UTF-8 解码（BOM 会变成一个不可见字符，
//      混进第一行正文，轻则首条事件描述带个怪字符，重则首行识别失败）；
//   3. 严格 UTF-8 解码（malformed/unmappable 一律 REPORT）—— 只有**确实不是** UTF-8 时才回落，
//      不能用「含有非法字节就替换成 �」的宽松模式，那会把 GBK 文本悄悄变成满屏问号。
//
// 兜底再兜底：GBK 也不认时用 ISO-8859-1（任意字节都可解），保证「总能返回一个字符串」。
// 宁可解析出 0 条事件并显示原文，也不要抛异常让用户连日志都看不到。
package app.infra

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** UTF-8 优先、失败回落 GBK 的文本解码（见文件头三步顺序）。 */
fun decodeText(bytes: ByteArray): String {
    if (bytes.isEmpty()) return ""
    if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
        return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
    }
    val utf8 = runCatching {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull()
    if (utf8 != null) return utf8
    return runCatching { String(bytes, charset("GBK")) }.getOrDefault(String(bytes, Charsets.ISO_8859_1))
}
