// shared/app/api/ExportBlockedException.kt —— 「根本不该开始」的导出前置失败
//
// 与「导出失败」区分开，因为给用户的处置完全不同：
//   · 前置失败（没有照片 / 目标路径过长 / 输出目录不可写）→ 用户改一下设置再点一次就行，
//     状态回到 READY，**不留失败标记**；
//   · 导出失败（复制时报错、磁盘满）→ 状态 FAILED，错误详情要能复制给工程师。
// 若两者都用 IllegalArgumentException 表达，UI 只能一律显示"导出失败"，
// 用户会以为产物坏了一半，实际一张都还没开始写。
//
// ★ 由执行器抛出、由导出用例分类处理：执行器最清楚"我拒绝了"还是"我干活时出错了"。
package app.api

/**
 * 导出被前置校验阻断（不是"复制失败"，而是"根本不该开始"）。
 *
 * @param message 给用户看的一句话（要包含"怎么办"，例如"请缩短输出目录"）。
 * @param offendingPaths 触发阻断的具体路径（桌面端长路径超限时有值，便于排障）。
 */
class ExportBlockedException(
    message: String,
    val offendingPaths: List<String> = emptyList(),
) : Exception(message)
