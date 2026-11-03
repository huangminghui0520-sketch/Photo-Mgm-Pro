// SettingsUseCaseTest —— 设置与日志文本的编排语义（T09-C2）
//
// 三条分界必须钉住，否则用户会丢掉人工调整或看到错误的台账：
//   · 改巡查日期/源目录 → 既有结果失效，只提示"请重新分析"，**不重算**；
//   · 改命名模板 → 不失效，但必须**立即**重算（台账前缀与导出文件夹名同源）；
//   · 切去重模式 → 同步重算输出层（不重跑扫描），并且口径提示要说清合并了几组。
package app.usecase

import algorithm.DedupKeepMode
import app.api.CoreStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsUseCaseTest {

    private fun harness(): Harness {
        val h = Harness(photos = Harness.twoPhotos(), exif = Harness.twoPhotoExif())
        h.analyze()
        return h
    }

    @Test
    fun `改巡查日期只提示重新分析而不动既有结果`() {
        val h = harness()
        val eventsBefore = h.store.current().events

        h.settingsUseCase.updateSettings { it.copy(patrolDate = java.time.LocalDate.of(2026, 8, 15)) }

        val s = h.store.current()
        assertTrue(s.message!!.contains("请重新分析"), "必须告诉用户结果已过期：${s.message}")
        assertEquals(eventsBefore, s.events, "失效不等于清空：用户还能看到上一轮结果")
        assertEquals(1, h.settingsStorage.saves, "设置必须落盘")
        assertEquals(java.time.LocalDate.of(2026, 8, 15), h.settingsStorage.current.patrolDate)
    }

    @Test
    fun `改命名模板立即重算台账前缀`() {
        val h = harness()
        val before = h.store.current().ledger.first().folderName

        h.settingsUseCase.updateSettings { it.copy(namingTemplate = 0) }

        val after = h.store.current().ledger.first().folderName
        assertNotEquals(before, after, "模板影响文件夹名 → 台账照片列前缀必须立刻跟着变")
        assertTrue(after.contains("交接班") || after.contains("护栏"), "模板 0 用事件类型命名：$after")
        assertNull(h.store.current().message, "改模板不是失效，不该弹'请重新分析'")
    }

    @Test
    fun `切到相同去重模式是空操作`() {
        val h = harness()
        val messageBefore = h.store.current().message

        h.settingsUseCase.setDedupMode(h.store.current().dedupMode)

        assertEquals(messageBefore, h.store.current().message, "同一模式再点一次不该有任何反馈")
        assertEquals(0, h.settingsStorage.saves, "没有变化就不该写盘")
    }

    @Test
    fun `切去重模式同步重算并给出口径提示`() {
        val h = harness()

        h.settingsUseCase.setDedupMode(DedupKeepMode.KEEP_BOTH)

        val s = h.store.current()
        assertEquals(DedupKeepMode.KEEP_BOTH, s.dedupMode)
        assertEquals(DedupKeepMode.KEEP_BOTH, s.settings.dedupMode, "设置与状态必须同步，否则重开又变回去")
        assertEquals(CoreStatus.READY, s.status, "同步重算，返回时已是新口径")
        assertTrue(s.message!!.contains("重复照片全部保留"), "提示要随模式变化：${s.message}")
        assertEquals(1, h.settingsStorage.saves)
    }

    @Test
    fun `日志文本立即生效_落盘走防抖`() {
        val h = Harness()

        h.settingsUseCase.setLogText("08时00分 只改一个字符")

        assertEquals("08时00分 只改一个字符", h.store.current().logText, "内存态必须立即生效")
        assertEquals(0, h.logStorage.saves, "防抖窗口内不写盘")

        h.scheduler.runAll()
        assertEquals(1, h.logStorage.saves, "到期后写一次")
        assertEquals("08时00分 只改一个字符", h.logStorage.persisted)
    }

    @Test
    fun `连续输入只落盘最后一次`() {
        val h = Harness()

        h.settingsUseCase.setLogText("a")
        h.settingsUseCase.setLogText("ab")
        h.settingsUseCase.setLogText("abc")
        h.scheduler.runAll()

        assertEquals(1, h.logStorage.saves, "连打合并成一次")
        assertEquals("abc", h.logStorage.persisted, "落盘的是最后一次，不是中间值")
    }

    @Test
    fun `分析前会把待落盘的日志刷掉`() {
        val h = Harness()
        h.settingsUseCase.setLogText(Fixtures.LOG_TEXT)

        h.pipeline.analyze(listener = null, isCancelled = { false })

        assertEquals(1, h.logStorage.saves, "开始分析前必须 flush：否则强杀进程会丢最后一次输入")
        assertEquals(3, h.store.current().parsed!!.eventCount)
    }

    @Test
    fun `改源目录与改输出目录的失效判据不同`() {
        val h = harness()

        h.settingsUseCase.updateSettings { it.copy(outputDir = "/out") }
        assertNull(h.store.current().message, "只改输出目录不影响分类结果")

        h.settingsUseCase.updateSettings { it.copy(sourceDirs = listOf("/other")) }
        assertTrue(h.store.current().message!!.contains("请重新分析"), "改源目录必须失效")
    }
}
