// shared/app/port/FileBytesReader.kt —— 「按引用读一段字节」端口
//
// 用途很窄但两端都有：用户拖入/选择一份日志文本文件时，要把它读进来。
// 桌面端是 `Files.readAllBytes(Path)`，安卓端是 `ContentResolver.openInputStream(uri)`。
//
// ★ 端口返回**字节**而不是字符串：解码策略（UTF-8 优先、BOM 剥离、失败回落 GBK）
//   是业务约定 —— 内业同事用记事本另存的日志常是 GBK，这条规则两端必须一致。
//   若端口直接给字符串，两端就会各写一份解码，GBK 回落迟早只在一端生效
//   （表现为"同一份日志，PC 能解析、安卓全变问号"）。
//   解码实现在 [app.infra.decodeText]。
package app.port

/** 读原始字节（平台实现由装配根注入）。 */
fun interface FileBytesReader {
    /**
     * 读取 [ref] 的全部字节。
     * @return 读不到（文件不存在 / 无权限）返回 null，**不抛异常** —— 由调用方决定报什么错。
     */
    fun readBytes(ref: String): ByteArray?
}
