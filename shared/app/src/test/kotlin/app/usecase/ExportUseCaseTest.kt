// ExportUseCaseTest —— 导出编排的四种结局（T09-C3）
//
// 用假执行器验证的是「闸门 → 冻结计划 → 回填「这一串」，不是「怎么复制文件」：
//   · 未分析 / 没设输出目录 / 没有照片 → 前置阻断（停在 READY，给一句能照做的话）；
//   · 正常 → lastExport 回填、台账用**快照那份**、提示语按四种结局区分；
//   · 执行器抛前置异常 vs 抛普通异常 → 状态不同（READY / FAILED），这是 UI 唯一能依赖的差别；
//   · 导出中再次点导出 → 直接拒绝，不产生第二份计划。
package app.usecase

import app.api.CoreStatus
import app.api.ExportBlockedException
import app.api.ExportListener
import app.api.ExportPlan
import app.api.ExportProgress
import app.api.ExportRequest
import app.api.ExportResult
import app.port.ExportExecutor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 假执行器：记录收到的计划与目标，按预置结局返回/抛出。 */
private class FakeExportExecutor(
    private val outcome: ExportResult? = null,
    private val failWith: Throwable? = null,
) : ExportExecutor {
    var lastPlan: ExportPlan? = null
        private set
    var lastTarget: String? = null
        private set
    var calls: Int = 0
        private set

    override fun execute(
        plan: ExportPlan,
        target: String,
        onProgress: (ExportProgress) -> Unit,
        isCancelled: () -> Boolean,
    ): ExportResult {
        calls++
        lastPlan = plan
        lastTarget = target
        onProgress(ExportProgress("复制照片", 1, plan.totalTargets.coerceAtLeast(1)))
        failWith?.let { throw it }
        return requireNotNull(outcome) { "测试必须预置结局" }
    }
}

/** 记录回调的监听器。 */
private class RecordingListener : ExportListener {
    var finished: ExportResult? = null
        private set
    var failed: Throwable? = null
        private set
    var progressCount: Int = 0
        private set

    override fun onProgress(progress: ExportProgress) {
        progressCount++
    }

    override fun onFinished(result: ExportResult) {
        finished = result
    }

    override fun onFailed(error: Throwable) {
        failed = error
    }
}

private fun result(
    total: Int = 2,
    copied: Int = 2,
    skipped: Int = 0,
    failed: Int = 0,
    cancelled: Boolean = false,
    resumed: Boolean = false,
) = ExportResult(
    exportDir = "/out/1101_203200",
    ledgerFile = null,
    logFile = "/out/1101_203200/export.log",
    manifestFile = "/out/1101_203200/export-manifest.json",
    totalTargets = total,
    copied = copied,
    skipped = skipped,
    failed = failed,
    cancelled = cancelled,
    resumed = resumed,
)

class ExportUseCaseTest {

    private fun harnessWithPhotos(executor: ExportExecutor): Pair<Harness, ExportUseCase> {
        val h = Harness(photos = Harness.twoPhotos(), exif = Harness.twoPhotoExif())
        h.analyze()
        return h to ExportUseCase(h.store, h.session, executor)
    }

    @Test
    fun `未分析时拒绝导出且不碰执行器`() {
        val executor = FakeExportExecutor(outcome = result())
        val h = Harness(photos = Harness.twoPhotos(), exif = Harness.twoPhotoExif())
        val useCase = ExportUseCase(h.store, h.session, executor)
        val listener = RecordingListener()

        useCase.export(ExportRequest("/out"), listener, isCancelled = { false })

        assertTrue(listener.failed!!.message!!.contains("请先完成分析"), "${listener.failed}")
        assertEquals(0, executor.calls)
        assertEquals(CoreStatus.IDLE, h.store.current().status, "拒绝不该改变状态")
    }

    @Test
    fun `没有输出目录时给出可执行的提示且停在就绪`() {
        val executor = FakeExportExecutor(outcome = result())
        val (h, useCase) = harnessWithPhotos(executor)
        val listener = RecordingListener()

        useCase.export(ExportRequest("   "), listener, isCancelled = { false })

        val s = h.store.current()
        assertEquals(CoreStatus.READY, s.status, "前置阻断不是失败")
        assertTrue(s.error!!.contains("未设置输出目录"), "${s.error}")
        assertEquals(0, executor.calls)
    }

    @Test
    fun `没有可导出的照片时阻断而不是导出空目录`() {
        val executor = FakeExportExecutor(outcome = result(total = 0, copied = 0))
        // 照片文件名日期与巡查日期不符 → 分类成功但 0 张照片
        val h = Harness(photos = listOf(Fixtures.candidate("IMG_20260813_101530.jpg")), exif = Harness.twoPhotoExif())
        h.analyze()
        val useCase = ExportUseCase(h.store, h.session, executor)
        val listener = RecordingListener()

        useCase.export(ExportRequest("/out"), listener, isCancelled = { false })

        assertTrue(h.store.current().error!!.contains("没有可导出的照片"), "${h.store.current().error}")
        assertEquals(0, executor.calls)
    }

    @Test
    fun `正常导出回填结果与快照台账`() {
        val executor = FakeExportExecutor(outcome = result())
        val (h, useCase) = harnessWithPhotos(executor)
        val listener = RecordingListener()

        useCase.export(ExportRequest("/out"), listener, isCancelled = { false })

        val s = h.store.current()
        assertEquals(CoreStatus.READY, s.status)
        assertNull(s.error)
        assertNotNull(s.lastExport)
        assertEquals("/out", executor.lastTarget)
        assertEquals(2, executor.lastPlan!!.totalTargets, "两张照片各一份")
        assertEquals(executor.lastPlan!!.ledgerPreview, s.ledger, "台账用快照那份，不重算")
        assertEquals("导出完成：2 个文件 → /out/1101_203200", s.message)
        assertEquals(listener.finished, s.lastExport)
    }

    @Test
    fun `导出期间状态为导出中且进度回调可见`() {
        val h = Harness(photos = Harness.twoPhotos(), exif = Harness.twoPhotoExif())
        h.analyze()
        var statusDuringExecute: CoreStatus? = null
        val executor = object : ExportExecutor {
            override fun execute(
                plan: ExportPlan,
                target: String,
                onProgress: (ExportProgress) -> Unit,
                isCancelled: () -> Boolean,
            ): ExportResult {
                statusDuringExecute = h.store.current().status
                onProgress(ExportProgress("复制照片", 2, 2, "IMG_B.jpg"))
                return result()
            }
        }
        val useCase = ExportUseCase(h.store, h.session, executor)
        val listener = RecordingListener()

        useCase.export(ExportRequest("/out"), listener, isCancelled = { false })

        assertEquals(CoreStatus.EXPORTING, statusDuringExecute, "执行期间必须处于导出中，UI 据此禁用写操作")
        assertEquals(1, listener.progressCount, "进度必须转给监听器")
        assertNull(h.store.current().exportProgress, "结束后清掉进度")
    }

    @Test
    fun `执行器抛普通异常记为失败`() {
        val executor = FakeExportExecutor(failWith = java.io.IOException("磁盘已满"))
        val (h, useCase) = harnessWithPhotos(executor)
        val listener = RecordingListener()

        useCase.export(ExportRequest("/out"), listener, isCancelled = { false })

        val s = h.store.current()
        assertEquals(CoreStatus.FAILED, s.status)
        assertTrue(s.error!!.startsWith("导出失败：") && s.error!!.contains("磁盘已满"), "${s.error}")
        assertNotNull(listener.failed, "失败必须回调给监听器")
    }

    @Test
    fun `执行器抛前置异常停在就绪并保留原文`() {
        val blocked = ExportBlockedException("有 3 个目标路径超过 240 字符，请缩短输出目录")
        val executor = FakeExportExecutor(failWith = blocked)
        val (h, useCase) = harnessWithPhotos(executor)
        val listener = RecordingListener()

        useCase.export(ExportRequest("/out"), listener, isCancelled = { false })

        val s = h.store.current()
        assertEquals(CoreStatus.READY, s.status, "前置阻断不该留失败标记")
        assertEquals(blocked.message, s.error, "错误原文要原样交给用户（它就是处置建议）")
        assertEquals(blocked, listener.failed)
    }

    @Test
    fun `取消的导出提示可续传而不是完成`() {
        val executor = FakeExportExecutor(outcome = result(copied = 1, skipped = 1, cancelled = true))
        val (h, useCase) = harnessWithPhotos(executor)

        useCase.export(ExportRequest("/out"), RecordingListener(), isCancelled = { false })

        assertEquals("导出已取消：已导出 1 个文件，可稍后续传", h.store.current().message)
    }

    @Test
    fun `续传命中的提示与完成区分开`() {
        val executor = FakeExportExecutor(outcome = result(copied = 0, skipped = 2, resumed = true))
        val (h, useCase) = harnessWithPhotos(executor)

        useCase.export(ExportRequest("/out"), RecordingListener(), isCancelled = { false })

        assertEquals("上次导出的产物校验一致（2 个文件），本次未重复复制", h.store.current().message)
    }

    @Test
    fun `部分失败时提示去看 export_log`() {
        val executor = FakeExportExecutor(outcome = result(copied = 1, failed = 1))
        val (h, useCase) = harnessWithPhotos(executor)

        useCase.export(ExportRequest("/out"), RecordingListener(), isCancelled = { false })

        assertEquals("导出完成但有 1 个失败，详见 export.log", h.store.current().message)
    }

    @Test
    fun `导出中再次点导出被拒绝`() {
        val executor = FakeExportExecutor(outcome = result())
        val (h, useCase) = harnessWithPhotos(executor)
        h.store.withLock { h.store.publish { it.copy(status = CoreStatus.EXPORTING) } }
        val listener = RecordingListener()

        useCase.export(ExportRequest("/out"), listener, isCancelled = { false })

        assertTrue(listener.failed!!.message!!.contains("导出正在进行"), "${listener.failed}")
        assertEquals(0, executor.calls)
    }
}
