// shared/app/infra/DebouncedTextStore.kt —— 文本落盘防抖的**策略**（T05-C2）
//
// 背景（两端各踩过一次同一块石头）
// --------------------------------
// 安卓 W3 / issue #4：日志输入框每个字符都调 setLogsText，早期实现直接写 SharedPreferences。
// 每次 apply() 都全量重写整个 XML，而那个 XML 里还有 MB 级照片缓存 —— 等于每敲一个字符
// 写一次 MB 级磁盘，输入明显卡顿并持续磨损闪存。桌面端随后复刻了同一套"防抖 + 兜底 flush"。
//
// 于是同一个策略被写了两遍（桌面用 ScheduledExecutorService，安卓用协程 delay），
// 两份实现的取消/清空/兜底顺序稍有出入就是 bug。本文件把它收成一份。
//
// 边界（为什么这里没有线程、协程、文件、SharedPreferences）
// ------------------------------------------------------
// 「多久之后执行一次」和「怎么把文本写到磁盘」都是平台的事，所以都走端口：
//   · [persist]  —— 真正落盘的动作（桌面写临时文件再原子改名；安卓写独立 prefs）
//   · [DelayScheduler] —— 延迟调度（桌面 ScheduledExecutorService；安卓 scope.launch + delay）
// 共享层只保留**策略**：防抖窗口、连打合并、flush / cancelPending 的语义与顺序。
// 一旦把 ScheduledExecutorService 或 SharedPreferences 写进这里，C2（零平台 SDK）就破了，
// 而且安卓侧会为了一个 400ms 的常量背上一整个线程池。
//
// 三条不变量（测试逐条锁住）
// ------------------------
// 1. 窗口内多次 onChanged 只落盘 **一次**，且内容必须是**最后一次**（不能是中间值）。
// 2. [flush] 立即落盘，不等窗口；之后窗口到期**不得补写第二次**。
// 3. [cancelPending] 之后窗口到期**不得落盘**——否则"清空日志"会表现为删了又回来。
package app.infra

/**
 * 延迟执行端口：把"delayMs 毫秒后跑一次 action"这件事交给平台。
 *
 * ★ 实现方**不必**保证 action 在调用线程执行；但必须保证 [DelayHandle.cancel] 之后
 *   至多还能有一次迟到调用（取消与到期是竞态，策略层已经用锁兜住，不会因此写坏数据）。
 */
fun interface DelayScheduler {
    fun schedule(delayMs: Long, action: () -> Unit): DelayHandle
}

/** 待执行动作的撤销句柄。 */
fun interface DelayHandle {
    fun cancel()
}

/**
 * 文本落盘防抖器：内存态由调用方立即更新，这里只负责"稍后把最后一次值落盘"。
 *
 * @param persist 真正落盘的动作。**在锁外调用**——写盘可能很慢，不能占着锁。
 * @param scheduler 延迟调度端口（见类头"边界"）。
 * @param debounceMs 防抖窗口，默认 [DEFAULT_DEBOUNCE_MS]。
 */
class DebouncedTextStore(
    private val persist: (String) -> Unit,
    private val scheduler: DelayScheduler,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
) {
    companion object {
        /**
         * 300~500ms 是输入停顿的自然区间：既能把连打合并，又不会让"停下后还等很久"。
         * 两端曾各自定义过一次（桌面注释写着"与 Android 端同值"），现在只有这一处。
         */
        const val DEFAULT_DEBOUNCE_MS = 400L
    }

    /** ★ 待落盘文本与调度句柄用**同一把锁**保护：两处状态必须一起读写，否则会出现
     *   「新文本已就位、但旧任务先醒并把新文本写掉」或「句柄被读空后新值丢失」。 */
    private val lock = Any()
    private var latest: String? = null
    private var handle: DelayHandle? = null

    /** 输入变化：内存态由调用方立即更新，这里只重排"稍后落盘最后一次值"。 */
    fun onChanged(text: String) {
        synchronized(lock) {
            latest = text
            handle?.cancel()
            handle = scheduler.schedule(debounceMs) { flush() }
        }
    }

    /**
     * 立即落盘（离开页面 / 核心 close / 开始分析前调用）。没有待落盘内容时是空操作。
     * 同步语义：返回即已写入，调用方可以放心退出。
     */
    fun flush() {
        val text: String
        synchronized(lock) {
            text = latest ?: return
            latest = null
            handle?.cancel()
            handle = null
        }
        persist(text)
    }

    /**
     * 取消待落盘（"清空日志"用）：若不取消，用户点了清空后防抖窗口到期会把旧文本写回去，
     * 表现为"删了又回来"。
     */
    fun cancelPending() {
        synchronized(lock) {
            latest = null
            handle?.cancel()
            handle = null
        }
    }
}
