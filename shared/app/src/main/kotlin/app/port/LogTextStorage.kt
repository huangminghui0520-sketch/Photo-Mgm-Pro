// shared/app/port/LogTextStorage.kt —— 日志原文的持久化端口（只做「读」「写最后一次」）
//
// ★ 这里刻意**没有** `onChanged(text)`：
//   防抖策略已经收在 [app.infra.DebouncedTextStore]（T05-C2），由它决定「什么时候写」。
//   端口只回答「怎么把一段文本落下去」。若把防抖也做成端口方法，两端就会各自实现一遍
//   防抖窗口与清空顺序 —— 那正是 D11（两端各写一份防抖）的成因。
//
// ★ `persist` 必须**同步写完**再返回：`DebouncedTextStore.flush()` 的同步语义依赖它
//   （离开页面 / 开始分析前刷盘，返回即已写盘）。
package app.port

/** 日志原文落盘（平台实现由装配根注入）。 */
interface LogTextStorage {
    /** 读取上次落盘的日志原文；没有则返回空串，**不抛异常**。 */
    fun load(): String

    /** 立即落盘。放在锁外调用（写盘可能很慢），实现需自行保证原子性。 */
    fun persist(text: String)
}
