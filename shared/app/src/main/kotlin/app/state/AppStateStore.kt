// shared/app/state/AppStateStore.kt —— 状态快照的唯一发布口（锁内一次性发布）
//
// 为什么需要它（两端都踩过同一类问题）：
//   · 「先改字段、再发状态」两步之间，别的线程能读到「字段已改、state 还是旧的」；
//   · 连续两次 publish 会发出一份**半成品**快照（第一次只填了 events、第二次才填 ledger），
//     UI 收到第一份就会渲染出「有事件但台账空」的一帧 —— 表现为画面闪一下或台账短暂消失。
//   所以发布纪律是：**在一次持锁区间内把整个快照算完，再一次性发出去**。
//
// ★ 本类用 `Thread.holdsLock` 强制这条纪律：`publish` 在锁外被调用会立刻抛异常，
//   而不是"看起来能跑、偶发闪帧"。这条断言在单测里被显式验证（见 AppStateStoreTest），
//   否则它只是一句注释。
//
// ★ 锁的边界刻意很窄：只有"读会话字段 + 组装 AppState + 发布"在锁内。
//   落盘、读 EXIF、复制文件等 IO 都在锁内**确实**有（分析流水线整段持锁，与既有实现一致），
//   但它们不会回调进 store，因此不存在锁顺序反转。
package app.state

import app.api.AppState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 状态快照的持有者与发布口。
 *
 * @param initial 初始快照（装配根用 [initial] 构造，保证 `dedupMode` 与 `settings` 一致）。
 */
class AppStateStore(initial: AppState) {

    /**
     * ★ `@PublishedApi internal` 而不是 `private`：下面的 [withLock] 是 inline（为了让用例能写
     * `store.withLock { … return 0 }` 这种早退，与既有实现的 `synchronized(lock) { return }` 同形），
     * 而 public inline 函数访问 private 字段是编译错误。把锁暴露成 internal 只影响本模块，
     * 外部模块看不到它，也就无法绕过 [publish] 的持锁断言。
     */
    @PublishedApi
    internal val lock: Any = Any()

    private val _state = MutableStateFlow(initial)

    /** 对外只读流（首次订阅立即收到当前值）。 */
    val state: StateFlow<AppState> = _state.asStateFlow()

    /** 当前快照（不加锁读取：StateFlow 的 value 本身是 volatile 引用，读到的总是某个完整快照）。 */
    fun current(): AppState = _state.value

    /**
     * 在锁内执行一段读写。
     *
     * ★ 嵌套调用是安全的（`synchronized` 可重入）：流水线持锁调用用例、用例再持锁发布，
     *   这在既有实现里也是同一把锁的行为，不要改成不可重入的锁。
     */
    inline fun <T> withLock(block: () -> T): T = synchronized(lock, block)

    /**
     * 发布一份新快照（必须已在 [withLock] 内）。
     *
     * @throws IllegalStateException 锁外调用。这不是防御性代码：它把"发布纪律"变成
     *   运行期可发现的事实，否则错误写法只会在 UI 上偶发闪现，且极难复现。
     */
    fun publish(transform: (AppState) -> AppState) {
        check(Thread.holdsLock(lock)) {
            "AppStateStore.publish 必须在 withLock 内调用：锁外发布会与并发读交错，产生半成品快照"
        }
        _state.update(transform)
    }

    companion object {
        /**
         * 构造初始快照。
         *
         * ★ `dedupMode` 必须与 `settings.dedupMode` 对齐：两者曾各自初始化，
         *   结果是"设置里是仅保留带水印、状态里是都保留"，预览与台账按不同口径渲染。
         */
        fun initial(settings: app.api.Settings, logText: String): AppState =
            AppState(
                settings = settings,
                logText = logText,
                dedupMode = settings.dedupMode,
            )
    }
}
