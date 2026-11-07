// desktop/core/sync/SyncDecider.kt —— 断点续传判据（纯函数，不碰文件系统）
//
// 判据（已定）：文件名 + 大小 + 哈希。
//   · 目标不存在                    → COPY
//   · 大小不同                      → REWRITE（半截/损坏，删除后重写）
//   · 大小相同、哈希相同            → SKIP（已完成，不重写、不产生副本）
//   · 大小相同、哈希不同或读不出    → RENAME（冲突，不覆盖，写 name_1 递增后缀）
//
// ★ 性能规则：哈希是惰性求值的两个 lambda，只有大小相同时才被调用。
//   先比大小能避开大部分全量哈希；这条规则由 SyncDeciderTest 用计数器断言，
//   不是一句口头约定。哈希读不出时按 RENAME 处理（宁可留一份多余副本，也不覆盖用户文件）。
//
// ★ 本文件刻意不 import java.nio.file：判据是纯逻辑，文件访问全部由 PhoneSyncExecutor
//   完成后把「大小 / 哈希函数 / 已占用文件名」传进来。这样 R1~R7 可以无真机、无临时目录地测。
package com.photomgm.desktop.core.sync

internal enum class SyncAction {
    COPY,
    SKIP,
    REWRITE,
    RENAME,
}

internal data class SyncDecision(
    val action: SyncAction,
    // 实际落盘的文件名；RENAME 时是递增后的新名，其余与原名相同。
    val targetName: String,
)

internal object SyncDecider {

    fun decide(
        name: String,
        incomingSize: Long,
        existingSize: Long?,
        incomingHash: () -> String?,
        existingHash: () -> String?,
        occupied: Set<String>,
    ): SyncDecision {
        // ① 目标不存在：正常写入
        if (existingSize == null) return SyncDecision(SyncAction.COPY, name)

        // ② 大小不同：半截或损坏，删掉重写。此处不调用任何哈希函数。
        if (existingSize != incomingSize) return SyncDecision(SyncAction.REWRITE, name)

        // ③ 大小相同才计算哈希（先算暂存文件，再算目标文件）
        val incoming = incomingHash()
        val existing = existingHash()
        if (incoming != null && incoming == existing) {
            return SyncDecision(SyncAction.SKIP, name)
        }
        // ④ 冲突：不覆盖，另取一个不存在的名字
        return SyncDecision(SyncAction.RENAME, nextAvailableName(name, occupied))
    }

    // 递增后缀：IMG_001.jpg → IMG_001_1.jpg → IMG_001_2.jpg …
    // ★ 保留扩展名：目标子文件夹会被 PcPhotoSourceProvider 递归扫描，扩展名丢了照片就不再被识别。
    // 无扩展名 / 点文件（.nomedia）按整体加后缀处理。
    fun nextAvailableName(name: String, occupied: Set<String>): String {
        val dot = name.lastIndexOf('.')
        val (base, ext) = if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
        var index = 1
        var candidate = "$base" + "_" + index + ext
        while (candidate in occupied) {
            index++
            candidate = "$base" + "_" + index + ext
        }
        return candidate
    }
}
