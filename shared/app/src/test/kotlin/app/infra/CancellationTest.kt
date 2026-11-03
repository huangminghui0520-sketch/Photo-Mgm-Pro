// CancellationTest —— 把"取消信号不许被吞"这件事钉成可执行的判据（T05-C1）
//
// 为什么这组测试比文档重要：
// 文档写"记得重抛取消"，下一个改代码的人照样会写 runCatching。这里用**可执行的反例**
// （同一条经验：先用 characterization 证明旧写法确实会吞，再证明新写法不吞），
// 一旦将来有人把 runCatchingCancellable 改回 runCatching，本文件的第 2 条立刻红。
package app.infra

import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CancellationTest {

    // ── 反证：旧写法为什么必须换掉 ────────────────────────────────────────

    @Test
    fun `裸runCatching会吞掉取消_这就是必须换实现的原因`() {
        val marker = CancellationException("cancelled")

        // 旧写法：值是 failure，调用方拿不到异常，取消信号消失
        val caught = runCatching { throw marker }
        assertTrue(caught.isFailure, "runCatching 应把取消包成 failure（正是缺陷本身）")
        assertSame(marker, caught.exceptionOrNull(), "被吞的正是那个取消异常")
    }

    @Test
    fun `catch Exception 也会吞掉取消_安卓S1S2踩过的坑`() {
        // JVM 上 CancellationException 继承 IllegalStateException，所以宽泛 catch 一定接住它
        var swallowed = false
        try {
            throw CancellationException("cancelled")
        } catch (e: Exception) {
            swallowed = true
        }
        assertTrue(swallowed, "catch (Exception) 会吞取消；协程体里必须显式重抛")
    }

    // ── 正证：runCatchingCancellable 的语义 ──────────────────────────────

    @Test
    fun `runCatchingCancellable重抛取消_不变成failure`() {
        val marker = CancellationException("cancelled")

        val thrown = assertFailsWith<CancellationException> {
            runCatchingCancellable { throw marker }
        }
        assertSame(marker, thrown, "必须原样重抛同一个异常实例，不能包一层")
    }

    @Test
    fun `runCatchingCancellable对普通异常仍返回failure`() {
        val boom = IllegalStateException("disk full")

        val r = runCatchingCancellable { throw boom }

        assertTrue(r.isFailure, "非取消异常不该被重抛，否则调用方的失败处理全部失效")
        assertSame(boom, r.exceptionOrNull())
    }

    @Test
    fun `runCatchingCancellable成功时返回success`() {
        assertEquals(42, runCatchingCancellable { 42 }.getOrNull())
    }

    @Test
    fun `嵌套调用时取消穿透所有层`() {
        // 导出流程里是 runCatchingCancellable 套 runCatchingCancellable（台账 / ZIP / 清理各一层），
        // 只要有一层把它接成 failure，外层就再也看不到取消了。
        val marker = CancellationException("cancelled")

        val thrown = assertFailsWith<CancellationException> {
            runCatchingCancellable {
                runCatchingCancellable {
                    runCatchingCancellable { throw marker }
                }
            }
        }
        assertSame(marker, thrown)
    }

    // ── checkCancelled：桌面端分析循环用的协作式检查点 ──────────────────

    @Test
    fun `checkCancelled在标志为真时抛出取消`() {
        assertFailsWith<CancellationException> { checkCancelled { true } }
    }

    @Test
    fun `checkCancelled在标志为假时是空操作`() {
        var probes = 0
        checkCancelled { probes++; false }
        assertEquals(1, probes, "未取消时必须正常返回，且确实读过一次标志位")
    }

    @Test
    fun `checkCancelled抛出的类型与协程取消同源`() {
        // 两端共用一个信号类型的前提：桌面抛的异常能被"按取消收尾"的 catch 接住，
        // 也能被 runCatchingCancellable 重抛。这条断言随 JDK 语义变化会立刻失效。
        // ★ 用运行期判定而不是 `thrown is IllegalStateException`：静态类型已经决定了结果，
        //   那种写法的 `is` 是恒真表达式（编译器也会警告），测不出任何东西。
        val thrown = assertFailsWith<CancellationException> { checkCancelled { true } }
        assertTrue(
            IllegalStateException::class.java.isAssignableFrom(thrown.javaClass),
            "JVM 上 CancellationException 继承 IllegalStateException —— 所以 catch(Exception) 会吞它",
        )
    }
}
