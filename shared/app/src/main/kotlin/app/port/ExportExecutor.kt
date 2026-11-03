// shared/app/port/ExportExecutor.kt —— 导出**执行**端口（计划上移，执行留平台）
//
// 契约：共享层给出 [ExportPlan]（要写哪些相对路径、台账行是什么），执行器只负责
// 「把源字节搬到目标路径上」，并把过程与结果如实报回来。
//
// ★ 为什么这样切（而不是把整个导出实现上移）：
//   两端产物形态不同 —— 桌面写文件夹树（用户直接看、不受资源管理器 ZIP 中文名乱码影响），
//   安卓写 ZIP（还可能写到 SAF 树）。文件系统 API、长路径约束、续传 manifest、
//   输出目标权限，全是平台知识。硬要统一，只会得到一个「两端都得写 if」的共享实现。
//   反过来，「哪些照片进哪个文件夹、重名加 _2、文件夹怎么编号」是业务规则，必须只有一份。
//
// ★ 执行器**不得**修改计划：重命名、跳过、兜底都按计划走。要加"导出时才发现的前置校验"
//   （例如桌面端的长路径超限），以异常或 [ExportResult.warnings] 报回，不要偷偷改文件名 ——
//   改名会让台账里的「文件夹名/文件名」与实际磁盘不一致。
//
// ★ 取消：每写完一个文件检查一次 [isCancelled]，为真则**停止但保留已写产物**（可续传）。
//   不要抛出取消异常来表达「用户取消」：取消是可预期的结果，要带着已导出的计数回来
//   （`ExportResult.cancelled = true`），否则 UI 无法告诉用户"已经导出 N 个，可以续传"。
package app.port

import app.api.ExportPlan
import app.api.ExportProgress
import app.api.ExportResult

/** 导出执行（平台实现由装配根注入）。 */
interface ExportExecutor {
    /**
     * 执行导出计划。**阻塞**调用；异步由调用方（门面/ViewModel）决定。
     *
     * @param plan 导出计划（共享层产出，执行器只读）。
     * @param target 输出目标（桌面=父目录绝对路径；安卓=树 URI 或私有目录）。语义由实现解释。
     * @param onProgress 进度回调。会在**写文件的同一线程**被调用，实现方不需要自己切线程。
     * @param isCancelled 取消查询（每个文件边界查一次）。
     * @return 导出结果；计数口径见 [ExportResult] 的注释（只数照片，台账看 ledgerFile）。
     */
    fun execute(
        plan: ExportPlan,
        target: String,
        onProgress: (ExportProgress) -> Unit,
        isCancelled: () -> Boolean,
    ): ExportResult
}
