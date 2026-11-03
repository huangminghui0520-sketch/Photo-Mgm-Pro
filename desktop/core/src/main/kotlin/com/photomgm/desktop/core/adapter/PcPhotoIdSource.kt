// desktop/core/adapter/PcPhotoIdSource.kt —— 照片 id 分配端口的桌面实现
//
// 桌面端扫目录拿不到天然 id（安卓那边是 MediaStore `_ID`），必须**自己造一个跨扫描稳定的**
// id：`SHA-256(规范化路径)` 取前 8 字节 + 碰撞线性探测，实现在 [PhotoIdAllocator]。
//
// ★ 端口这一层只做两件事：把 `reset()` / `allocate()` 转过去，并把扫描阶段给的
//   `candidateId`（桌面恒为 0）**忽略掉** —— 桌面没有可沿用的 id，
//   忽略一个恒为 0 的参数比"试着用一下"安全。
// ★ "必须 >0、必须稳定、必须先在 EXIF/分类之前分配"是业务约束，由共享流水线保证；
//   本类不重复实现那套顺序，否则改一处忘一处。
package com.photomgm.desktop.core.adapter

import app.port.PhotoIdSource
import com.photomgm.desktop.core.platform.PhotoIdAllocator

internal class PcPhotoIdSource(
    private val allocator: PhotoIdAllocator = PhotoIdAllocator(),
) : PhotoIdSource {

    override fun reset() = allocator.reset()

    override fun allocate(sourceRef: String, candidateId: Long): Long = allocator.allocate(sourceRef)
}
