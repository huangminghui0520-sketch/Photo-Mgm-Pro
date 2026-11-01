// ui/model/Feedback.kt —— 操作结果：能不能做、做完怎么说
//
// ★ 为什么把这些判断抽成纯函数（而不是写在弹窗的 onClick 里）：
//   "这张照片能不能复制到那个事件"是**业务判断**，不是界面分支。它要被单测盯住，
//   否则一旦有人改了 core 的复制语义（比如允许复制未匹配照片），界面就会开始撒谎：
//   点了"复制"、没有任何变化、也没有一句话解释发生了什么。
//   放在这里之后，界面只负责"把结果念出来"。
//
// ★ 反馈分三层，不要混用：
//     core 的 message    —— 流水线层面的事实（"恢复最初分类""已从那里导出"）；
//     LocalNotice        —— 用户刚按下的那个动作的结果（成功 / 为什么没生效）；
//     静态说明文字       —— 还没动手时的预期（"改动立即生效"）。
//   同一个动作的结果**只能有一条**：成功后界面会立刻清掉 core 那条简短 message，
//   否则"已移动 1 张照片"和"已把 1 张照片移到 事件 03"会并排出现，重复且互相稀释。
package com.photomgm.desktop.ui.model

import com.photomgm.desktop.core.api.AppState
import com.photomgm.desktop.core.api.CoreStatus

/** 提示条的语气。与 component 的 Tone 一一对应（model 层不认识 Compose）。 */
enum class NoticeTone {
    INFO,
    OK,
    WARN,
    DANGER,
}

/**
 * 界面侧的一次性提示。
 *
 * ★ 为什么带语气：全部用同一张灰色条的话，用户只能靠读字判断"这次成了没"，
 *   而"成了 / 没生效 / 出错了"是三种要立刻区分的结果 —— 颜色比文字先到达。
 */
data class LocalNotice(
    val text: String,
    val tone: NoticeTone = NoticeTone.WARN,
)

/** 已经真正挂到某个事件上的照片（未匹配池里的不算：它没有"家"，复制等于复制空气）。 */
fun ownedPhotoIds(state: AppState): Set<Long> =
    state.events.flatMapTo(LinkedHashSet()) { it.photoIds }

/**
 * 动手之前：这个目标能不能接这几张照片。返回 null = 可以执行。
 *
 * ★ 校验的顺序是"由硬到软"：先确认对象还在（数据失效是硬错），再看业务约束
 *   （导出中禁止调整、目标已持有），最后才是"我当时选的目标现在还在不在"。
 *   顺序反了会给出"可以点，但点了没反应"这种最坏的体验。
 */
fun rejectReason(
    state: AppState,
    photoIds: List<Long>,
    targetEventId: Int?,
    mode: AssignMode,
): String? {
    val ids = photoIds.distinct()
    if (ids.isEmpty()) return "没有选中照片"
    if (state.photos.none { it.id in ids }) {
        return "这些照片已经不在分析结果里了，请重新分析后再调整"
    }
    // 导出中 core 会静默拒绝所有归属调整，界面必须先说出来 —— 否则又是一次"点了没反应"。
    if (state.status == CoreStatus.EXPORTING) return "导出还没结束，结束后才能调整归属"

    // 移入未匹配池永远是合法的（那里是一个容器，不是某个事件的副本槽）。
    if (targetEventId == null) return null

    val target = state.events.firstOrNull { it.id == targetEventId }
        ?: return "目标事件已经不在结果里了，请重新分析后再调整"

    if (mode == AssignMode.COPY) {
        val owned = ownedPhotoIds(state)
        val homeless = ids.filter { it !in owned }
        if (homeless.isNotEmpty()) {
            return "选中的 ${homeless.size} 张还没有归属事件：未匹配照片不能复制，先移到某个事件里再复制"
        }
        val already = ids.count { it in target.photoIds }
        if (already == ids.size) {
            return "目标事件已经有这 $already 张了，不重复添加"
        }
    }
    return null
}

/**
 * 执行成功后的文案。
 *
 * ★ 目标名由调用方拼好传进来：model 层不认识 Compose，也不该重复"地点 · 描述"那段格式化规则。
 */
fun assignSuccessText(mode: AssignMode, applied: Int, targetLabel: String): String = when (mode) {
    AssignMode.MOVE -> "已把 $applied 张照片移到 $targetLabel"
    AssignMode.COPY -> "已复制 $applied 张照片到 $targetLabel（原事件仍保留）"
}

/**
 * 复制且"其中一部分目标事件已经有了"时的文案。
 *
 * ★ 必须说出来：只报新增数的话，用户会核对总数（"我复制了 3 张怎么只有 2 张"），
 *   而沉默褪成了"这个功能少粘一张"。把没生效的原因说清楚，它就不再是缺陷。
 */
fun copyPartialText(applied: Int, skipped: Int, targetLabel: String): String =
    "已复制 $applied 张照片到 $targetLabel；另 $skipped 张该事件已有，没有重复添加"

/** 统一的成功提示。 */
fun assignSuccessNotice(mode: AssignMode, applied: Int, targetLabel: String): LocalNotice =
    LocalNotice(assignSuccessText(mode, applied, targetLabel), NoticeTone.OK)

/** 统一的失败/未生效提示（[reason] 来自 [rejectReason]）。 */
fun assignFailureNotice(reason: String): LocalNotice = LocalNotice(reason, NoticeTone.WARN)
