// ui/model/Stage.kt —— 把 CoreStatus 投影成界面阶段
//
// ★ 这是整套 UI 结构的地基，所以单独成文件、单独写测试。
//
// 逻辑核心只有 6 个状态（IDLE / ANALYZING / READY / EXPORTING / FAILED / CLOSED），
// 界面上有 3 个步骤（输入 / 复核 / 导出）+ 1 个结果页 + 2 种忙碌覆盖层。
// 两者的关系是**投影**，不是并列：
//
//   CoreStatus          →  Stage / Busy
//   ─────────────────────────────────────────────
//   IDLE                →  INPUT
//   ANALYZING           →  INPUT   + Busy(ANALYZE)
//   READY（有事件）      →  REVIEW（分析完成后自动进入，不再让用户点第二次）
//   READY（无事件）      →  INPUT  + 提示"日志没解析出事件"
//   EXPORTING           →  EXPORT  + Busy(EXPORT)
//   READY（导出完成）    →  RESULT（由 lastExport 变化触发，不做"猜"）
//   FAILED              →  停在原阶段 + 错误横幅
//
// ★ 为什么"步骤"只有 3 个而不是 Android 端的 2 步（日志/导出）：
//   Android 是 parseLogs() → runClassify() 两次点击；桌面核心合成了一次 analyze()。
//   界面上若还画两步，就会出现"点了解析没反应"（因为分类已一起做完），
//   这是一类典型的"UI 结构与真实逻辑不符"的缺陷。
package com.photomgm.desktop.ui.model

/** 顶栏步骤条与左侧步骤栏的三步（对应一次巡查作业的三个阶段）。 */
enum class Step(val index: Int, val label: String, val hint: String) {
    INPUT(0, "输入", "巡查日期 / 源目录 / 巡查日志"),
    REVIEW(1, "复核", "事件与照片归属、台账照片"),
    EXPORT(2, "导出", "台账确认、落地与结果"),
}

/** 主内容区的四种构型。 */
enum class Stage {
    INPUT,
    REVIEW,
    EXPORT,
    RESULT,
}

/** 长时间操作（覆盖主内容区，期间不可操作）。 */
enum class Busy(val step: Step, val label: String) {
    ANALYZE(Step.INPUT, "正在分析"),
    EXPORT(Step.EXPORT, "正在导出"),
}

/** 阶段 → 所属步骤（步骤条高亮用）。 */
val Stage.step: Step
    get() = when (this) {
        Stage.INPUT -> Step.INPUT
        Stage.REVIEW -> Step.REVIEW
        Stage.EXPORT, Stage.RESULT -> Step.EXPORT
    }

/** 主内容区当前该画哪个步骤（忙碌时归属到它所属的步骤）。 */
val Busy.stepOf: Step get() = step
