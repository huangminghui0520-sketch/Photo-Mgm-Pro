// app/Cancellable.kt —— 协程取消语义的统一兜底（2026-10-31 系统性防护）
// 起因：本轮审查发现 exportZip / prepareLedger 用 catch (Exception) 把取消信号吞掉了（S1/S2）。
//      逐处修靠人肉 review 不可靠，故固化成本文件的 helper + scripts/check-cancellation.py 的 CI 规则。
package com.photomgm.app

import kotlinx.coroutines.CancellationException

/**
 * 协程体内专用的 [runCatching]：**CancellationException 一律重抛**，其余按 Result.failure 处理。
 *
 * 为什么不能直接写 runCatching：
 * JVM 上 `kotlinx.coroutines.CancellationException` 是 `java.util.concurrent.CancellationException`
 * 的 typealias，**继承 IllegalStateException**（已用 javap 实证）。而 runCatching 捕获的是
 * Throwable、catch (e: Exception) 捕获的是 Exception——两者都会把它吞掉。后果是：
 * 协程已经被取消，代码却继续往下跑完，把状态写成"导出失败"，外层也感知不到取消。
 *
 * 使用范围（重要，避免滥用）：
 * - **要用**：suspend 函数体内、launch/async 块内——这些位置存在挂起点，取消信号能传进来。
 * - **不必用**：XlsxWriter / SettingsStore / FileHasher / ExifPhotoReader 等非 suspend 的纯函数。
 *   它们内部没有挂起点，取消不可能传入，用普通 runCatching 是安全的（CI 规则也据此判定）。
 *
 * 配套的 catch 块写法：若必须手写 try/catch，请在 catch (Exception) 之前先写
 * `catch (e: CancellationException) { throw e }`，或在 catch 开头调用
 * `currentCoroutineContext().ensureActive()`。CI 脚本会检查这一模式。
 */
inline fun <R> runCatchingCancellable(block: () -> R): Result<R> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Result.failure(t)
    }
