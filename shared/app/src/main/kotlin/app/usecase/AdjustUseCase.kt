// shared/app/usecase/AdjustUseCase.kt —— 人工调整的编排（移动 / 复制 / 台账标记 / 重置）
//
// 纯状态变换在 [domain.adjust.ManualAdjustment]（已由 T07 上移并双跑验证）。
// 本类只负责编排层的三件事：
//   1. **准入判据**：没分析过、正在导出 → 什么都不做（返回 0，不改状态）；
//   2. **调用顺序**：先算移动前后的 owner 差集，再写状态，最后重算视图；
//   3. **提示语**：只在实际发生变化时才说话（"已移动 1 张照片"必须真移动了 1 张）。
//
// ★ 为什么"导出中一律拒绝"：导出用的是**冻结快照**（见 ExportUseCase）。
//   导出期间改归属，用户会看到"我正在导出的那份"和"界面上这份"不一致，
//   而产物已经写出去了 —— 与其给出一个说不清的中间态，不如在导出期间明确忽略写操作。
//   这是两端既有行为，不是本步新加的约束。
package app.usecase

import app.api.CoreStatus
import app.state.AppStateStore
import app.state.PhotoSession
import domain.adjust.ManualAdjustment
import domain.view.ViewDerivation

/** 人工调整用例。 */
class AdjustUseCase(
    private val store: AppStateStore,
    private val session: PhotoSession,
) {

    /**
     * 批量移动照片到目标事件（`null` = 移入未匹配池）。
     *
     * @return **真正改变了归属**的张数（目标本来就是它的家时返回 0）。
     *   ★ 不用 `photoIds.size`：导出一半、目标就是当前归属、id 不存在，这几种情况下
     *   移动其实什么都没做；界面说"已移动 1 张"就是一句假话，比报错更糟。
     */
    fun movePhotos(photoIds: List<Long>, targetEventId: Int?): Int {
        store.withLock {
            val classify = session.classify ?: return 0
            if (store.current().status == CoreStatus.EXPORTING) return 0
            val ids = photoIds.distinct().filter { it in session.knownPhotoIds }
            if (ids.isEmpty()) return 0

            val before = session.manual.toLayer()
            val outcome = ManualAdjustment.move(session.manual, ids, targetEventId, session.knownPhotoIds)
            val after = outcome.state.toLayer()
            session.manual = outcome.state

            val changed = ManualAdjustment.changedCount(classify, before, after, ids)
            if (changed > 0) {
                ViewPublisher.publish(store, session, CoreStatus.READY, message = "已移动 $changed 张照片")
            }
            return changed
        }
    }

    /**
     * 把照片复制到另一个事件（多归属，原事件保留一份）。
     *
     * @return 实际新增的份数（已在目标、不在任何事件、id 不存在的都不计）。
     */
    fun copyPhotos(photoIds: List<Long>, targetEventId: Int): Int {
        store.withLock {
            val classify = session.classify ?: return 0
            if (store.current().status == CoreStatus.EXPORTING) return 0
            if (photoIds.isEmpty()) return 0

            // 判"已在目标 / 已在某事件"必须用**已合并人工移动**的表，
            // 否则刚被移进事件的照片会被判成"不在任何事件"，复制出第二份空气。
            val effective = ViewDerivation.effectiveMap(classify, session.manual.toLayer())
            val outcome = ManualAdjustment.copy(
                state = session.manual,
                photoIds = photoIds,
                targetEventId = targetEventId,
                effectiveMap = effective,
                knownPhotoIds = session.knownPhotoIds,
            )
            session.manual = outcome.state
            if (outcome.added > 0) {
                ViewPublisher.publish(
                    store, session, CoreStatus.READY,
                    message = "已复制 ${outcome.added} 张照片到该事件",
                )
            }
            return outcome.added
        }
    }

    /**
     * 设置台账照片槽位（1=照片1，2=照片2）；`photoId = null` 表示恢复该侧默认。
     *
     * ★ 非法 slot 静默忽略（返回不改状态）—— 这是既有语义：slot 由 UI 固化生成，
     *   传错说明调用方有 bug，但没必要为此把用户的调整清空。
     */
    fun markLedgerPhoto(eventId: Int, slot: Int, photoId: Long?) {
        store.withLock {
            session.manual = ManualAdjustment.mark(session.manual, eventId, slot, photoId)
            if (session.classify == null) return
            ViewPublisher.publish(store, session, CoreStatus.READY)
        }
    }

    /** 清空全部人工调整（恢复自动分类结果）。 */
    fun resetManualAdjustments() {
        store.withLock {
            val count = ManualAdjustment.adjustmentCount(session.manual)
            session.manual = ManualAdjustment.reset()
            if (session.classify == null) return
            ViewPublisher.publish(
                store, session, CoreStatus.READY,
                message = if (count > 0) "已恢复最初分类（清除 $count 处人工调整）" else "已是最初分类状态",
            )
        }
    }
}
