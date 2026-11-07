// shared/domain/sync/SyncRangeFolder.kt —— 手机导入的时间区间：命名、默认、校验（纯函数）
//
// 放在 :shared:domain 的依据（准入 C1~C5）：
//   C1 纯逻辑：只接收 LocalDateTime / LocalDate，不读系统时间、时区、环境变量、文件系统；
//   C2 零平台 SDK：只用 java.time；
//   C3 两端一致：同一区间在 Windows / Linux / 安卓算出逐字相同的名字；
//   C4 单一事实来源：子文件夹命名规则只在这里定义一次，桌面门面只做转发；
//   C5 可独立测试：纯 JVM 单测覆盖跨年、跨日、补零、秒截断与 31 天边界。
//
// 命名格式（已定）：<开始日>_<开始时分>__<结束日>_<结束时分>，例如 04_2300__05_2359。
//   · 每段的日 / 时 / 分都两位补零；段内单下划线，跨段双下划线；
//   · 不含年份：跨年区间 12-31 → 01-01 靠 sync-state.json 里记录的完整年份消歧；
//   · 秒不参与命名：把 LocalDateTime 的秒与纳秒丢掉，同分钟的不同区间视为同一文件夹；
//   · 时分的四位写法是 HHmm 连写（2300），不是小时与分钟各一段。
//
// 语义边界：folderName 只做格式化，不校验先后；跨年时「31 在 01 之前」看起来是倒序，
// 但那是合法的跨年区间，校验交给 validateRange 用完整 LocalDateTime 判断。
package domain.sync

import java.time.LocalDate
import java.time.LocalDateTime

object SyncRangeFolder {

    // 区间跨度上限（天）；超过即拒绝提交，可由调用方覆盖。
    const val DEFAULT_MAX_SPAN_DAYS: Int = 31

    // 区间 → 子文件夹名。确定性：同月日时分无论年份、机器、调用次数，结果逐字相同。
    // 例：2026-11-04 23:00 ~ 2026-11-05 23:59 → 04_2300__05_2359
    // 2026-12-31 22:00 ~ 2027-01-01 02:00 → 31_2200__01_0200
    fun folderName(start: LocalDateTime, end: LocalDateTime): String = buildString {
        append(pad(start.dayOfMonth))
        append('_')
        append(pad(start.hour))
        append(pad(start.minute))
        append("__")
        append(pad(end.dayOfMonth))
        append('_')
        append(pad(end.hour))
        append(pad(end.minute))
    }

    // 默认区间：巡查日期 00:00 ~ 23:59（只在弹窗首次打开时生成，用户改后不重置）。
    fun defaultRange(patrolDate: LocalDate): Pair<LocalDateTime, LocalDateTime> =
        patrolDate.atStartOfDay() to patrolDate.atTime(23, 59)

    // 校验区间，返回提示文案；null = 可以提交。
    // 顺序由硬到软：先空值、再先后关系、最后跨度上限。空值用可空入参表达，
    // 让「起止为空」与「格式非法」在 UI 解析失败时走同一条分支，不重复两套文案。
    fun validateRange(
        start: LocalDateTime?,
        end: LocalDateTime?,
        maxSpanDays: Int = DEFAULT_MAX_SPAN_DAYS,
    ): String? = when {
        start == null || end == null -> "请填写完整的开始与结束时间"
        !end.isAfter(start) -> "开始时间必须早于结束时间"
        end.isAfter(start.plusDays(maxSpanDays.toLong())) -> "时间区间过长"
        else -> null
    }

    private fun pad(value: Int): String = value.toString().padStart(2, '0')
}
