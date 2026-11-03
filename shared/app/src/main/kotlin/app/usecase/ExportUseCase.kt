// shared/app/usecase/ExportUseCase.kt —— 导出的编排（闸门 → 冻结快照 → 执行 → 回填）
//
// 四步各自对应一个曾经踩过的坑：
//
//   1. **闸门**：导出中再次点导出必须直接拒绝。安卓端曾把 check-then-act 放在协程体里，
//      两次快速点击先后通过检查，两个导出共用同一个临时文件，先结束者把对方正在写的文件删掉
//      （产物损坏）。共享层这里是同步的锁内判定，天然没有这个窗口。
//   2. **冻结快照**：计划在锁内一次取全（事件表 / 未匹配 / 台账行 / 命名模板）。
//      执行期间用户若继续操作（UI 已被禁用，但核心不能假设），产物仍然是"点导出那一刻"的那份。
//   3. **执行**：交给平台 [app.port.ExportExecutor]，本层不碰文件系统。
//   4. **回填**：结果写进 `lastExport`，台账用**快照里那份**（不是重算），
//      否则导出期间若设置变了，界面显示的台账会与写出去的 xlsx 不一致。
//
// ★ 本类阻塞执行：桌面端在自己的执行器上跑，安卓端在协程里跑。
//   取消用 `isCancelled` 传给执行器，**不抛取消异常**——取消是可预期结果，要带着
//   "已导出 N 个"的计数回来，UI 才能说"可稍后续传"。
package app.usecase

import app.api.CoreStatus
import app.api.ExportBlockedException
import app.api.ExportListener
import app.api.ExportPlan
import app.api.ExportProgress
import app.api.ExportRequest
import app.api.ExportResult
import app.api.userMessage
import app.export.ExportPlanner
import app.state.AppStateStore
import app.state.PhotoSession

/** 导出用例。 */
class ExportUseCase(
    private val store: AppStateStore,
    private val session: PhotoSession,
    private val executor: app.port.ExportExecutor,
) {

    /**
     * 执行一次导出（阻塞）。
     *
     * @param listener 结果回调。成功走 [ExportListener.onFinished]，前置失败与执行失败都走
     *   [ExportListener.onFailed]（区别体现在 `state.error` 与状态上：前置失败停在 READY）。
     */
    fun export(request: ExportRequest, listener: ExportListener, isCancelled: () -> Boolean) {
        val plan = store.withLock { prepare(request, listener) } ?: return

        try {
            val result = executor.execute(
                plan = plan,
                target = request.target,
                onProgress = { progress ->
                    publishProgress(progress)
                    // listener 是 UI 传进来的，抛异常不该把已写出的产物变成"失败"
                    runCatching { listener.onProgress(progress) }
                },
                isCancelled = isCancelled,
            )
            store.withLock {
                store.publish {
                    it.copy(
                        status = CoreStatus.READY,
                        exportProgress = null,
                        lastExport = result,
                        ledger = plan.ledgerPreview,
                        message = exportMessage(result),
                    )
                }
            }
            runCatching { listener.onFinished(result) }
        } catch (e: ExportBlockedException) {
            // 前置阻断：用户改个设置再点一次即可，不要留失败标记
            store.withLock {
                store.publish { it.copy(status = CoreStatus.READY, exportProgress = null, error = e.message) }
            }
            runCatching { listener.onFailed(e) }
        } catch (e: Throwable) {
            store.withLock {
                store.publish {
                    it.copy(
                        status = CoreStatus.FAILED,
                        exportProgress = null,
                        error = "导出失败：${e.message ?: e.javaClass.simpleName}",
                    )
                }
            }
            runCatching { listener.onFailed(e) }
        }
    }

    /**
     * 锁内准备：闸门检查 + 生成计划 + 发布 EXPORTING。
     *
     * @return null = 本次导出不该开始（已把原因交给 listener 与 state.error）。
     */
    private fun prepare(request: ExportRequest, listener: ExportListener): ExportPlan? {
        if (store.current().status == CoreStatus.EXPORTING) {
            runCatching { listener.onFailed(IllegalStateException("导出正在进行，请等待完成")) }
            return null
        }
        val parsed = session.parsed
        val classify = session.classify
        if (parsed == null || classify == null) {
            // ★ T11 提交②：这里与"未设目录 / 无可导出照片"归为**同一类前置阻断**，
            //   但**不改 status**（未分析时状态本来就是 IDLE，改成 READY 等于谎称"可导出了"）。
            //   以前这一支只走 listener、不写 `state.error`，症状是：用户点导出，界面什么都不显示
            //   （Android 侧尤其明显 —— 底栏没有任何变化，用户会连点几次）。
            val message = "请先完成分析再导出：请先解析日志并生成分类"
            store.publish { it.copy(error = message) }
            runCatching { listener.onFailed(ExportBlockedException(message)) }
            return null
        }
        if (request.target.isBlank()) {
            return blocked(
                listener,
                "未设置输出目录，请先在「设置」页选择或输入输出目录后再导出",
            )
        }

        val state = store.current()
        val view = session.deriveView(state.dedupMode)
        val plan = ExportPlanner.build(
            events = parsed.events,
            eventPhotoMap = view.eventPhotoMap,
            unmatched = view.unmatched,
            photos = session.photos,
            marked = session.manual.marked,
            namingTemplate = state.settings.namingTemplate,
            includeLedger = request.includeLedger,
            zone = session.zone,
            ledgerName = request.ledgerName,
        )
        if (!plan.hasTargets) {
            return blocked(
                listener,
                "没有可导出的照片：请先选择源目录与巡查日期并完成分析",
            )
        }

        store.publish {
            it.copy(
                status = CoreStatus.EXPORTING,
                exportProgress = ExportProgress("准备导出", 0, 0),
                error = null,
                message = null,
            )
        }
        return plan
    }

    /** 前置阻断：状态停在 READY，错误详情直接给用户看（它本身就是一句可执行的处置建议）。 */
    private fun blocked(listener: ExportListener, message: String): ExportPlan? {
        val error = ExportBlockedException(message)
        store.publish { it.copy(status = CoreStatus.READY, exportProgress = null, error = message) }
        runCatching { listener.onFailed(error) }
        return null
    }

    private fun publishProgress(progress: ExportProgress) {
        store.withLock {
            store.publish { it.copy(exportProgress = progress) }
        }
    }

    /**
     * 完成提示语（四种结局，文案两端必须一致 —— 用户靠它判断"能不能直接用"）。
     *
     * ★ 实现已挪到 [app.api.userMessage]（T11 提交②：安卓侧也要用同一套文案，
     *   而它手里只有 `ExportResult`，没有本用例实例）。这里保留同名方法是为了不惊动
     *   PC 侧既有调用点，**行为一字未改**。
     */
    internal fun exportMessage(result: ExportResult): String = result.userMessage()
}
