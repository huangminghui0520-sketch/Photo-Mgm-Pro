// desktop/core/platform/PhotoIdAllocator.kt —— 桌面端照片 id 分配（方案 §4.6）
//
// 为什么需要它：原算法 `PhotoIngest.dedupById` 只对 `id > 0` 的照片去重，`id <= 0` 全部保留。
// Android 用 MediaStore `_ID`（必然 > 0 且唯一）；桌面端若一律传 0 则完全不去重，
// 若随手用"扫描序号"则**跨扫描不稳定**（照片增删后同一张照片换 id，人工调整记录全部错位）。
//
// 本实现：
//   id = SHA-256(规范化 sourceRef) 的前 8 字节，清符号位（保证 > 0），为 0 时置 1
//        → 同一路径永远得到同一 id（跨进程、跨扫描稳定），无需持久化映射表
//   + 会话内 HashMap<Long, String> 守卫：id 已存在但路径不同 → **线性探测 +1** 直至空闲，并计数
//     → 因为 `dedupById` 对 id 碰撞是「静默丢照片」，必须先于它消解碰撞
package com.photomgm.desktop.core.platform

import java.security.MessageDigest

internal class PhotoIdAllocator(
    /**
     * 哈希函数（可注入）。默认 [hashToPositiveId]。
     * ★ 注入点存在的理由：碰撞是"概率极低但后果严重（静默丢照片）"的路径，
     *   不能只靠"1 万条路径没撞"来证明它安全 —— 必须能人为造出碰撞把探测逻辑跑一遍。
     */
    private val hash: (String) -> Long = ::hashToPositiveId,
) {

    /** 已分配：id → 规范化 sourceRef（用于判定"同 id 是否同一文件"）。 */
    private val assigned = HashMap<Long, String>()

    /** 发生碰撞并探测的次数（供日志/排障；正常应为 0）。 */
    var collisions: Int = 0
        private set

    /** 最近一次碰撞的说明（供核心写运行日志）。 */
    val collisionNotes = mutableListOf<String>()

    /** 分配（幂等：同一路径多次调用返回同一 id）。 */
    fun allocate(normalizedRef: String): Long {
        val base = hash(normalizedRef)
        assigned[base]?.let { existing ->
            if (existing == normalizedRef) return base
        }
        if (base !in assigned) {
            assigned[base] = normalizedRef
            return base
        }
        // 碰撞：线性探测。id 上界保持 > 0（越过 Long.MAX_VALUE 时回绕到 1）。
        var probe = base
        var guard = 0
        while (true) {
            probe = if (probe == Long.MAX_VALUE) 1L else probe + 1
            if (probe <= 0) probe = 1L
            val holder = assigned[probe]
            if (holder == null) {
                assigned[probe] = normalizedRef
                collisions++
                collisionNotes += "id 碰撞：$normalizedRef 与 ${assigned[base]} 撞在同一 id，改用探测值 $probe"
                return probe
            }
            guard++
            // 理论上不可能（2^63 个 id），但绝不写"无限循环"的代码
            check(guard < 100_000) { "照片 id 空间异常：连续 $guard 次探测均被占用" }
        }
    }

    fun reset() {
        assigned.clear()
        collisions = 0
        collisionNotes.clear()
    }

    /** 已分配数量（测试用）。 */
    val size: Int get() = assigned.size

    companion object {
        /**
         * 纯函数：规范化路径 → 正数 id。
         * 单测直接验证"同一路径稳定"、"不同路径不撞"、"碰撞探测生效"。
         */
        fun hashToPositiveId(normalizedRef: String): Long {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(normalizedRef.toByteArray(Charsets.UTF_8))
            var v = 0L
            for (i in 0 until 8) {
                v = (v shl 8) or (digest[i].toLong() and 0xFF)
            }
            v = v and Long.MAX_VALUE       // 清符号位 → 恒 > 0（0 除外）
            return if (v == 0L) 1L else v   // 0 会让 dedupById 走"全保留"分支，置 1 兜底
        }
    }
}
