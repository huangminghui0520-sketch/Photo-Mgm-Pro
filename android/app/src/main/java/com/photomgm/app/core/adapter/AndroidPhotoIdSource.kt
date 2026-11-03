// app/core/adapter/AndroidPhotoIdSource.kt —— `app.port.PhotoIdSource` 的安卓实现（T11）
//
// 安卓侧的照片 id 就是 MediaStore `_ID`：必然 > 0、天然唯一、跨扫描稳定
// （同一张照片只要没被系统重建，`_ID` 不变）。因此这里几乎没有逻辑 ——
// 与桌面端那套「SHA-256(规范化路径) 取前 8 字节 + 碰撞探测」完全不同，
// 正是端口注释里说的「怎么造 id 是平台知识」。
//
// ★ `reset()` 是空实现：共享流水线每轮开始都会调它，桌面端借它重建分配表；
//   安卓没有分配表可重建，但**不能因此把这个方法当成可省**——流水线的调用约定是两端一致的。
//
// ★ 兜底分支不是防御性代码：`_ID` 理论上恒 > 0，但 `CandidatePhoto.id` 的默认值是 0，
//   一旦上游某个实现忘了填（或将来换成别的扫描源），id=0 会让 `PhotoIngest.dedupById`
//   把每张照片都当成"不同的照片"而全部保留 —— 症状是照片数翻倍且人工调整整体错位。
//   宁可按引用算一个正数，也不要静默放行 0。
package com.photomgm.app.core.adapter

import app.port.PhotoIdSource

/** 照片 id 分配：直接沿用 MediaStore `_ID`。 */
class AndroidPhotoIdSource : PhotoIdSource {

    override fun reset() = Unit

    override fun allocate(sourceRef: String, candidateId: Long): Long =
        if (candidateId > 0) candidateId else fallbackId(sourceRef)

    /** 兜底：按引用算一个稳定的正数（同一路径多次调用结果相同）。 */
    private fun fallbackId(sourceRef: String): Long {
        val h = sourceRef.hashCode().toLong() and Long.MAX_VALUE
        // 0 会让 dedupById 视为"未分配"，必须避开
        return if (h == 0L) 1L else h
    }
}
