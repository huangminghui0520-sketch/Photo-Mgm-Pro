// shared/app/api/ResumeVerifyMode.kt —— 断点续传的物理校验强度（★ 桌面端专用，单独成文件）
//
// 为什么从 ExportModels 里拆出来（计划 §T09「拆出 ResumeVerifyMode」）：
//   这是**桌面端导出执行器**的实现细节——它问的是「同一目录里上次的产物还在不在、完好不完好」。
//   安卓端的导出目标是 SAF 树 / ZIP，没有这套 manifest+续传机制，这个枚举对它毫无意义。
//   留在 `ExportRequest` 里，就等于逼安卓端携带一个永远是默认值的字段。
//
// ★ 拆出之后它**不在** [app.api.ExportRequest] 上：共享层的导出用例不关心校验强度，
//   桌面端的 `PcExportExecutor` 在构造时收下它即可（T10 装配时传入）。
package app.api

/**
 * 断点续传的「产物是否还在」校验方式。
 *
 * ★ 之所以默认 [SIZE_AND_MTIME] 而不是 SHA-256：
 *   导出 1000 张、每张 4MB 时，全量重算哈希约 4GB 顺序读，在机械盘上可能比「重新导出」还慢，
 *   而「续传比导出慢」是荒谬的。产物由本程序自己写入，size+mtime 同时变化而内容保持一致的
 *   概率极低（照片是只读素材，不会被我们改写）。
 *   需要法证级校验（如涉密留证场景）时切到 [SIZE_AND_SHA256]。
 */
enum class ResumeVerifyMode {
    /** 文件存在 + 大小一致 + 修改时间一致 → 认为完好（默认，快）。 */
    SIZE_AND_MTIME,

    /** 文件存在 + 大小一致 + SHA-256 一致 → 认为完好（慢，仅强校验场景）。 */
    SIZE_AND_SHA256,
}
