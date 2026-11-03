// shared/app/infra/Cancellation.kt —— 跨端唯一的「取消信号」约定（T05-C1）
//
// 为什么要有这个文件
// ------------------
// 重构前两端各有一套取消语义，而且**不兼容**：
//   · 安卓：协程取消抛 kotlinx.coroutines.CancellationException（JVM 上就是
//     java.util.concurrent.CancellationException 的 typealias，**继承 IllegalStateException**）；
//     曾因 `runCatching` / `catch (Exception)` 把它吞掉，导致"协程已取消、代码却继续跑完，
//     最后把状态写成导出失败"（2026-10-31 S1/S2 踩坑）。
//   · 桌面：自造的私有 `CancellationExceptionMarker : RuntimeException`，只有分析流程认它；
//     别人的 `runCatching` 一样会把它吞掉，只是恰好没踩到。
//
// 本文件把两端收敛到**同一个信号类型**：JDK 自带的 [CancellationException]。
// 选它而不是新造一个类，理由有三：
//   1. 它正是协程取消在 JVM 上的实际类型，安卓侧代码**零改动**即可被同一套判定覆盖；
//   2. 桌面侧从此不再需要私有 marker，"取消"这件事在两个进程里有同一个类型；
//   3. 不引入 kotlinx-coroutines 依赖 —— 共享层不必为了一个异常类型背一整套协程库。
//
// 用法（两端同一条纪律）
// ---------------------
//   · 抛出：`checkCancelled { op.isCancelled }`
//   · 捕获：`catch (e: CancellationException) { … 按"已取消"收尾 … }`
//   · 兜底：协程体 / 后台任务里一律用 [runCatchingCancellable]，不用裸 `runCatching`。
//   · 不要用 `catch (e: Exception)` 去收取消 —— 它一定会把取消一起吞掉（CI 脚本会拦）。
package app.infra

import java.util.concurrent.CancellationException

/**
 * 协作式取消检查：标志位为真时抛出 [CancellationException]。
 *
 * 桌面端的分析循环在两个批次之间调用它（原来写的是 `if (op.isCancelled) throw CancellationExceptionMarker()`）；
 * 两端共用后，抛出的异常类型与协程取消完全一致，[runCatchingCancellable] 一视同仁地重抛。
 */
fun checkCancelled(isCancelled: () -> Boolean) {
    if (isCancelled()) throw CancellationException("cancelled")
}

/**
 * 协程体 / 后台任务专用的 [runCatching]：**CancellationException 一律重抛**，其余按 `Result.failure` 处理。
 *
 * 为什么不能直接写 `runCatching`：
 * JVM 上 `kotlinx.coroutines.CancellationException` 就是 `java.util.concurrent.CancellationException`，
 * **继承 IllegalStateException**（已用 javap 实证）。而 `runCatching` 捕获 Throwable、
 * `catch (e: Exception)` 捕获 Exception —— 两者都会把它吞掉。后果是协程已被取消，
 * 代码却继续往下跑完，把状态写成"导出失败"，外层也感知不到取消。
 *
 * 使用范围（重要，避免滥用）：
 * - **要用**：suspend 函数体内、launch/async 块内、任何后台任务循环里 —— 这些位置收得到取消信号。
 * - **不必用**：XlsxWriter / SettingsStore / FileHasher / ExifPhotoReader 等非 suspend 的纯函数。
 *   它们内部没有挂起点，取消不可能传入，用普通 runCatching 是安全的（CI 规则也据此判定）。
 *
 * 配套的 catch 块写法：若必须手写 try/catch，请在 `catch (e: Exception)` 之前先写
 * `catch (e: CancellationException) { throw e }`，或在 catch 开头调用
 * `currentCoroutineContext().ensureActive()`。CI 脚本（scripts/check-cancellation.py）会检查这一模式。
 */
inline fun <R> runCatchingCancellable(block: () -> R): Result<R> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Result.failure(t)
    }
