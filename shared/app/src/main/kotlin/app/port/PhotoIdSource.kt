// shared/app/port/PhotoIdSource.kt —— 照片 id 分配端口
//
// 为什么 id 分配不能直接写进共享层：
//   安卓端的 id 是 MediaStore `_ID`（必然 > 0 且唯一），直接沿用即可；
//   桌面端扫目录拿不到天然 id，必须**自己造一个跨扫描稳定的** id
//   （`SHA-256(规范化路径)` 取前 8 字节，碰撞时线性探测）——否则照片增删后同一张照片换 id，
//   人工调整记录会整体错位到别的照片上。
//
//   两端的"怎么造 id"是平台知识，而"id 必须稳定、必须 >0、必须先于 EXIF/分类分配"是业务约束，
//   所以：端口只留分配动作，顺序与重置时机由共享的编排层负责。
//
// ★ 必须在进入 EXIF 精读 / 分类之前完成分配：`PhotoIngest.dedupById` 只对 `id > 0` 去重，
//   id 稳定才能跨扫描复现；id 为 0 的照片会被视为"每张都不同"而全部保留。
package app.port

/** 照片 id 分配（平台实现由装配根注入）。 */
interface PhotoIdSource {
    /** 一轮分析开始前重置内部状态（桌面端按扫描重建分配表；安卓无状态，空实现即可）。 */
    fun reset()

    /**
     * 为一张候选照片分配 id。
     *
     * @param sourceRef 照片引用（桌面=物理路径；安卓=content URI）。
     * @param candidateId 扫描阶段给出的 id：安卓=MediaStore `_ID`；桌面=0。
     * @return 最终 id，必须 > 0 且在本次扫描内唯一。**同一路径多次调用必须返回同一 id**。
     */
    fun allocate(sourceRef: String, candidateId: Long): Long
}
