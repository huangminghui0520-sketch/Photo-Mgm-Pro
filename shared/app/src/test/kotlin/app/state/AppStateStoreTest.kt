// AppStateStoreTest —— 发布纪律的运行期保证（T09-C2）
//
// 这个类只做一件事，但它是整套状态流的地基：**锁内组装、一次性发布**。
// 如果这条纪律只写在注释里，错误写法会表现为"UI 偶发闪一帧半成品状态"——
// 本地不复现、CI 看不见、用户偶尔报"台账闪了一下又出现"。所以用 `Thread.holdsLock` 把它
// 变成运行期异常，并用下面两条用例把这条断言本身钉住（否则断言可能悄悄失效）。
package app.state

import app.api.AppState
import app.api.CoreStatus
import app.api.Settings
import algorithm.DedupKeepMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AppStateStoreTest {

    @Test
    fun `锁外发布必须抛异常`() {
        val store = AppStateStore(AppState(settings = Settings()))
        val e = assertFailsWith<IllegalStateException> {
            store.publish { it.copy(status = CoreStatus.READY) }
        }
        assertTrue(e.message!!.contains("withLock"), "异常信息要直接指出正确写法：${e.message}")
        assertEquals(CoreStatus.IDLE, store.current().status, "被拦下的那次发布不得生效")
    }

    @Test
    fun `锁内发布生效且支持早退`() {
        val store = AppStateStore(AppState(settings = Settings()))

        val returned: Int = store.withLock {
            store.publish { it.copy(status = CoreStatus.READY, message = "ok") }
            if (store.current().status == CoreStatus.READY) return@withLock 7
            0
        }

        assertEquals(7, returned)
        assertEquals(CoreStatus.READY, store.current().status)
        assertEquals("ok", store.current().message)
    }

    @Test
    fun `可重入`() {
        val store = AppStateStore(AppState(settings = Settings()))
        // 流水线持锁调用用例、用例再持锁发布 —— 同一把锁必须可重入
        store.withLock {
            store.withLock {
                store.publish { it.copy(message = "内层") }
            }
            store.publish { it.copy(status = CoreStatus.ANALYZING) }
        }
        assertEquals("内层", store.current().message)
        assertEquals(CoreStatus.ANALYZING, store.current().status)
    }

    @Test
    fun `初始快照的去重模式与设置对齐`() {
        val settings = Settings(dedupMode = DedupKeepMode.KEEP_PLAIN)
        val initial = AppStateStore.initial(settings, logText = "x")

        assertEquals(DedupKeepMode.KEEP_PLAIN, initial.dedupMode, "两处各自初始化会导致预览与台账口径不同")
        assertEquals(settings, initial.settings)
        assertEquals("x", initial.logText)
        assertEquals(CoreStatus.IDLE, initial.status)
    }

    @Test
    fun `每次发布都是一份完整快照`() {
        val store = AppStateStore(AppState(settings = Settings()))
        store.withLock {
            store.publish { it.copy(status = CoreStatus.READY, message = "第一次") }
            store.publish { it.copy(message = "第二次") }
        }
        // 订阅方拿到的永远是完整对象（不是"只含本次改动字段"的增量）
        val s = store.current()
        assertEquals(CoreStatus.READY, s.status)
        assertEquals("第二次", s.message)
    }
}
