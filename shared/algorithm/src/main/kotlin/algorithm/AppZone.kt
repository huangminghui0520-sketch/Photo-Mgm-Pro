// algorithm/AppZone.kt —— 业务时区唯一来源（issue #2 产品决策的代码落点）
//
// 决策（2026-10-31，issue #2 已关闭「产品语义待拍板」）：
//   1. 存储：绝对时刻（epoch 毫秒 / Instant / UTC）。事件时间、照片拍摄时间在内存与
//      持久化里一律是 epoch 毫秒，不带任何本地时间字符串。
//   2. 展示与日期分组：固定北京时间（Asia/Shanghai），与设备时区无关。
//      跨时区出差/改系统语言，台账的日期列、事件边界、"当日零点"都必须不变。
//   3. 旧数据：历史上是用当时设备的默认时区把「本地时间字符串」解释成 epoch 的。
//      若当时设备不在 +08:00，这些值是错的，且**无法从 epoch 反推原始挂钟**。
//      处置方式是丢弃可重算的派生缓存（解析结果/分类/照片元数据），用日志原文
//      在 AppZone 下重算 —— 见 app/SettingsStore 的 CACHE_ZONE 版本位。
//
// ★ 生产代码禁止再直接写 ZoneId.systemDefault()：
//   由 scripts/check-timezone.py 在 CI 拦截（详见该脚本头部）。
package algorithm

import java.time.ZoneId

object AppZone {
    /**
     * 业务时区：北京时间。
     *
     * 用 [ZoneId.of] 而不是 `ZoneId.of("Asia/Shanghai", aliasMap)`：固定 ID 而非偏移量，
     * 保证历史上出现过的夏令时（1986–1991 年中国实施过）也能正确解释。
     */
    val ID: ZoneId = ZoneId.of("Asia/Shanghai")
}
