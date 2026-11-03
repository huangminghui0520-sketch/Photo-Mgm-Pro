// shared/domain/exif/ExifTime.kt —— EXIF 拍摄时间文本解析（T03-C1：纯新增，暂不切调用）
//
// 这一份是**并集实现**，不是从某一端搬过来。两端原本各有一套，能力不同：
//
//   | 维度        | PC `PcExifReader.parseExifDateTime`        | 安卓 `ExifTimeParser.parse`          |
//   | ----------- | ------------------------------------------ | ------------------------------------ |
//   | 日期分隔符  | **只认** `yyyy:MM:dd`（冒号）              | 冒号/短横 **都认**（按 s[4] 判定）   |
//   | 尾部冗余    | `substring(0,19)` **截断**，容忍 `.123`/时区尾巴 | **不截断**，`…00.123` 直接解析失败 |
//   | 长度守卫    | `length < 19` → null                       | 无显式守卫（靠异常兜底）             |
//   | 异常        | `runCatching` → null                       | 同                                   |
//
// 取并集的理由（两条都是真实缺陷，不是"顺手做得更全"）：
//   · 只搬 PC 的冒号分支 → 安卓侧 D9 不会消失：短横格式的 8 张素材照片在 PC 上读不出时间，
//     全部掉进 `99_未分类`（这正是 T03 要修的可观测差异）。
//   · 只搬安卓的不截断 → PC 原来能读的"带毫秒尾巴"EXIF 会变成"无时间"。
//     现有素材没有这种照片，所以这个回归**测不出来**，必须靠这里的判断保住。
//
// ★ 语义约定（与两端旧实现一致，不可改）：
//   EXIF 时间戳**不带时区信息**，只能把"相机写下的本地挂钟时间"按业务时区 [AppZone.ID] 解释。
//   用设备默认时区会让同一张照片在不同机器上算出不同 epoch，事件归属随设备时区漂移（issue #2）。
//   尾部即使带了 `+08:00`，也**不看它、不做偏移换算** —— 见 ExifTimeTest 的对应断言：
//   那里锁的是"忽略尾巴"，不是"支持时区"。将来要支持真时区偏移，必须改签名并同步改两端调用，
//   而不是在这里悄悄多解析一种格式。
package domain.exif

import algorithm.AppZone
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object ExifTime {
    /** `yyyy:MM:dd HH:mm:ss`（EXIF 标准形态）。 */
    private val COLON: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

    /** `yyyy-MM-dd HH:mm:ss`（部分安卓相机/修图软件写入的非法但真实存在的形态，D9）。 */
    private val DASH: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** 这两种形态的有效长度都是 19（`2026:09:03 08:30:00`）。 */
    private const val CORE_LEN = 19

    /**
     * 解析 EXIF 时间文本 → epoch 毫秒（绝对时刻）。
     *
     * @param text 原始 EXIF 文本，例如 `2026:09:03 08:30:00`、`2026-09-03 08:30:00`、
     *             带毫秒/时区尾巴的 `2026:09:03 08:30:00.123`、`2026:09:03 08:30:00+08:00`
     * @param zone 把"本地挂钟时间"解释成绝对时刻所用时区，默认业务时区
     * @return 解析失败（空串、过短、非法日期）返回 null，**不抛异常**
     */
    fun parse(text: String, zone: ZoneId = AppZone.ID): Long? {
        val s = text.trim()
        if (s.length < CORE_LEN) return null
        // 截断到 19 位再解析：容忍毫秒（.123）与时区尾巴（+08:00）—— 能力来自 PC 端实现。
        // 注意别"顺手"把尾巴解析出来：EXIF 的时区语义是产品决策，见文件头。
        val core = s.substring(0, CORE_LEN)
        return runCatching {
            // 按**日期**分隔符判断格式：位置 4 是 ':'（yyyy:MM:dd）或 '-'（yyyy-MM-dd）；
            // 时间部分的冒号（位置 13/16）不可作判据。长度已 ≥19，s[4] 必然存在。
            val fmt = if (core[4] == ':') COLON else DASH
            LocalDateTime.parse(core, fmt).atZone(zone).toInstant().toEpochMilli()
        }.getOrNull()
    }
}
