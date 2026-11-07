// ui/model/PhoneImportUi.kt —— 手机导入弹窗的纯状态与判定（不认识 Compose，也不认识 core）
//
// ★ 为什么这些规则要抽成纯函数：弹窗的「能不能点」「同步到哪儿」是业务判断，
//   不是界面分支。与 Feedback.kt 的 exportBlockReason 同一个理由 —— 界面只负责把结果念出来，
//   判断本身必须能被单测盯住，否则「按钮点了没反应」永远查不出是哪儿拦的。
//
// ★ 两条判定都通过**函数参数**注入，本文件因此零依赖：
//   validate      —— 区间规则，本体在 domain.sync.SyncRangeFolder，经 DesktopCore 门面转发（F6）；
//   folderNameFor —— 区间 → 子文件夹名，同一个纯函数。
//   UI 层不许 import domain.*（P5 门禁），把规则当参数传进来是最干净的隔离：
//   生产环境注入门面，单测注入同一个门面（不写伪造实现，测的就是真规则）。
package com.photomgm.desktop.ui.model

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * 分钟列的步进：与 PmTimePicker 的列项**同一个来源**（界面读 [MINUTE_OPTIONS]，
 * 单测断言 55 在里面），不给界面留一份自己的常量。
 *
 * ★ 为什么是 5 而不是 1：区间只用于文件夹命名与筛选边界，5 分钟粒度已够；
 *   1 分钟要 60 项，选 23:55 得滚半屏。
 */
const val TIME_STEP_MINUTES: Int = 5

/** 时列：0..23 共 24 项。 */
val HOUR_OPTIONS: List<Int> = (0..23).toList()

/**
 * 分列的末档特例：59 分。
 *
 * ★ 为什么单独占一档：默认结束时刻是巡查日 23:59（V1，整日语义）。5 分钟步进只到 55，
 *   59 就没有对应项 —— 列里一格都不高亮，用户想「保持整日」也无处可点，点 55 又会把
 *   区间悄悄减掉最后 4 分钟（执行器按「整分钟包含」处理，23:59 表示 23:59:59）。
 *   所以 59 不参与步进，只承接「整日」这一个值。
 */
const val LAST_MINUTE_OPTION: Int = 59

/** 分列：0,5,…,55 共 12 项 + 末档特例 59，合计 13 项。 */
val MINUTE_OPTIONS: List<Int> = (0..55 step TIME_STEP_MINUTES).toList() + LAST_MINUTE_OPTION

/**
 * 区间「先序」失败的文案。
 *
 * ★ 这条字符串的**权威来源是 shared/domain 的 SyncRangeFolder.validateRange**（UI 不许
 *   import domain.*，见 P5 门禁），这里是为了「结束字段标红」这个纯 UI 判断复制的一份。
 *   复制就会漂移，所以 PhoneImportUiTest 里有一条用例把本常量与真规则返回值**对齐断言**：
 *   领域改文案而这里没跟，那条会红 —— 不让副本静默过期。
 */
const val RANGE_ORDER_MESSAGE: String = "开始时间必须早于结束时间"

/** 区间超过上限的文案；同上，权威在 SyncRangeFolder，用例里对齐。 */
const val RANGE_SPAN_MESSAGE: String = "时间区间过长"

/** 两个日历最多开一个：单个可空字段天然表达互斥，不用两个 Boolean 去维护「另一个关掉」。 */
enum class CalendarTarget { START, END }

// 弹窗里用户可编辑的全部字段。
// 日期与时刻一律存**字符串**：输入过程中必然出现「2026-11-0」这种半成品，
// 用 LocalDateTime 存就得允许中间态为 null，反而把「没填完」和「填错了」混成一种。
// 提交前由 [phoneImportRange] 一次性解析成可空结果。
data class PhoneImportForm(
    val startDate: String = "",
    val startTime: String = "",
    val endDate: String = "",
    val endTime: String = "",
    val phoneDir: String = "",
    val sourceDir: String = "",
    // 对应 PhoneLinkMode.values() 的下标；model 层不引用 core 的枚举，保持纯 JVM。
    val modeIndex: Int = 0,
    // 用户已确认「另一目录 / 跨年同名」冲突（R5）。
    val confirmConflict: Boolean = false,
    // 当前打开的是哪个日历；null = 都关着。只影响界面展开，不参与任何业务判断。
    val calendarTarget: CalendarTarget? = null,
    // 当前打开的是哪个时/分浮层；null = 都关着。与 [calendarTarget] 互斥（见 openCalendar / toggleTimePicker）。
    val timePickerTarget: CalendarTarget? = null,
)

fun parsePhoneDate(text: String): LocalDate? =
    runCatching { LocalDate.parse(text.trim(), DATE_FMT) }.getOrNull()

fun parsePhoneTime(text: String): LocalTime? =
    runCatching { LocalTime.parse(text.trim(), TIME_FMT) }.getOrNull()

// 日期 + 时分 → 挂钟时间（F7：不带 offset，offset 只在写 sync-state.json 时补）。
fun parsePhoneDateTime(date: String, time: String): LocalDateTime? {
    val d = parsePhoneDate(date) ?: return null
    val t = parsePhoneTime(time) ?: return null
    return LocalDateTime.of(d, t)
}

fun formatPhoneDate(v: LocalDateTime): String = v.toLocalDate().format(DATE_FMT)
fun formatPhoneTime(v: LocalDateTime): String = v.toLocalTime().format(TIME_FMT)

// 弹窗首次打开时的默认表单；区间由门面给，model 层不重写「00:00 ~ 23:59」这条规则。
fun defaultPhoneImportForm(
    patrolDate: LocalDate,
    sourceDirs: List<String>,
    range: Pair<LocalDateTime, LocalDateTime>,
): PhoneImportForm = PhoneImportForm(
    startDate = formatPhoneDate(range.first),
    startTime = formatPhoneTime(range.first),
    endDate = formatPhoneDate(range.second),
    endTime = formatPhoneTime(range.second),
    sourceDir = sourceDirs.firstOrNull().orEmpty(),
)

// 提交用的区间；任一字段没填好 / 格式非法都返回 null（不猜测、不补默认值）。
fun phoneImportRange(form: PhoneImportForm): Pair<LocalDateTime, LocalDateTime>? {
    val start = parsePhoneDateTime(form.startDate, form.startTime) ?: return null
    val end = parsePhoneDateTime(form.endDate, form.endTime) ?: return null
    return start to end
}

// ── 日历 / 时分列的编辑（界面只调这些，不自己拼字符串） ──────────────────────
//
// ★ 为什么编辑器放 model 而不是界面里 `form.copy(startTime = "$h:$m")`：
//   时与分是**两个控件改同一个字段**，谁都不能把另一半覆盖成默认值；
//   拼接格式（补零）也只有一处。放这里才能被单测盯住（U2~U4/U8）。

fun PhoneImportForm.startDateTime(): LocalDateTime? = parsePhoneDateTime(startDate, startTime)
fun PhoneImportForm.endDateTime(): LocalDateTime? = parsePhoneDateTime(endDate, endTime)

/**
 * 打开某个日历。单字段赋值 = 另一个必然关闭（U2/U8 的互斥由类型保证，不靠调用方自觉）。
 *
 * ★ 同时把 [PhoneImportForm.timePickerTarget] 清空：日历与时分浮层是两块不同的面板，
 *   同一个位置叠两层不仅看不清，还会让「我刚点的是哪一颗」失去答案。
 *   互斥写在这里而不是界面里 —— 界面只调 open/toggle，不维护两个字段的一致性。
 */
fun PhoneImportForm.openCalendar(target: CalendarTarget): PhoneImportForm =
    copy(calendarTarget = target, timePickerTarget = null)

/** 点同一颗日期按钮两次 = 收起；点另一颗 = 换到那颗（不会两个都开）。 */
fun PhoneImportForm.toggleCalendar(target: CalendarTarget): PhoneImportForm =
    copy(
        calendarTarget = if (calendarTarget == target) null else target,
        timePickerTarget = null,
    )

fun PhoneImportForm.closeCalendar(): PhoneImportForm = copy(calendarTarget = null)

// ── 时/分浮层的开关（与日历同构、且互斥）────────────────────────────────────

/** 打开某一侧的时/分浮层；单字段赋值 = 另一侧、以及日历都关闭。 */
fun PhoneImportForm.openTimePicker(target: CalendarTarget): PhoneImportForm =
    copy(timePickerTarget = target, calendarTarget = null)

/** 点同一个时刻按钮两次 = 收起；点另一侧 = 换过去。 */
fun PhoneImportForm.toggleTimePicker(target: CalendarTarget): PhoneImportForm =
    copy(
        timePickerTarget = if (timePickerTarget == target) null else target,
        calendarTarget = null,
    )

fun PhoneImportForm.closeTimePicker(): PhoneImportForm = copy(timePickerTarget = null)

fun PhoneImportForm.withStartDate(value: LocalDate): PhoneImportForm =
    copy(startDate = value.format(DATE_FMT), confirmConflict = false)

fun PhoneImportForm.withEndDate(value: LocalDate): PhoneImportForm =
    copy(endDate = value.format(DATE_FMT), confirmConflict = false)

// 时 / 分分开写：改一项必须保留另一项（23:59 点「22 时」应得 22:59，不是 22:00）。
fun PhoneImportForm.withStartHour(hour: Int): PhoneImportForm =
    copy(startTime = timeText(hour, startDateTime()?.minute ?: 0), confirmConflict = false)

fun PhoneImportForm.withStartMinute(minute: Int): PhoneImportForm =
    copy(startTime = timeText(startDateTime()?.hour ?: 0, minute), confirmConflict = false)

fun PhoneImportForm.withEndHour(hour: Int): PhoneImportForm =
    copy(endTime = timeText(hour, endDateTime()?.minute ?: 0), confirmConflict = false)

fun PhoneImportForm.withEndMinute(minute: Int): PhoneImportForm =
    copy(endTime = timeText(endDateTime()?.hour ?: 0, minute), confirmConflict = false)

private fun timeText(hour: Int, minute: Int): String =
    LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59)).format(TIME_FMT)

/**
 * 分钟落位到列里**该高亮的那一格**：本身是档位就原样返回，否则落到档位下沿
 * （4 → 0，58 → 55）。
 *
 * ★ 为什么 59 要特判：59 是 [MINUTE_OPTIONS] 的末档特例（见 [LAST_MINUTE_OPTION]）。
 *   不特判的话 floorMod 会把 59 压成 55 —— 列里高亮 55、业务值却是 59，两处对不上，
 *   「点 55 才生效」「整日悄悄少 4 分钟」都由此而来。
 *   本函数只决定**高亮哪一格**，不改业务值：改小时仍保留原分钟（见 [withEndHour]）。
 */
fun snapToStep(minute: Int, step: Int = TIME_STEP_MINUTES): Int {
    val m = minute.coerceIn(0, 59)
    if (step <= 0) return m
    // 59 只在默认步进下算档位；自定义步进仍按 step 落位，不把特例值混进别人的档。
    if (step == TIME_STEP_MINUTES && m == LAST_MINUTE_OPTION) return LAST_MINUTE_OPTION
    return (m - Math.floorMod(m, step)).coerceIn(0, 59)
}

/** 区间规则本体在 shared/domain，经门面转发（F6）；这里只转发，不重写。 */
fun rangeError(
    form: PhoneImportForm,
    validate: (LocalDateTime?, LocalDateTime?) -> String?,
): String? = validate(form.startDateTime(), form.endDateTime())

/**
 * 结束字段是否要标红：只认「开始不早于结束」这一条。
 *
 * ★ 上限超了（>31 天）不标红：那不是结束**填错**，是区间太长 ——
 *   标在结束字段上会让人以为要改结束值，实际要改的是区间意图。它只出现在提示行里。
 */
fun endFieldError(
    form: PhoneImportForm,
    validate: (LocalDateTime?, LocalDateTime?) -> String?,
): String? = rangeError(form, validate)?.takeIf { it == RANGE_ORDER_MESSAGE }

// 点顶部按钮前的拦截原因；null = 可以执行。
// 顺序由硬到软（与 rejectReason / exportBlockReason 同口径）：
// 正在跑 → 没有源目录 → 区间不合法（空值 / V5 / 上限）→ 没选手机目录。
// 把「没选手机目录」放最后，是为了让「区间填错」时先说区间，不拿另一个问题盖住它。
fun syncBlockReason(
    form: PhoneImportForm,
    sourceDirs: List<String>,
    running: Boolean,
    validate: (LocalDateTime?, LocalDateTime?) -> String?,
): String? = when {
    running -> "正在同步，等这一次结束再开始"
    form.sourceDir.isBlank() || sourceDirs.isEmpty() -> "请先设置电脑源目录"
    else -> {
        val range = phoneImportRange(form)
        val rangeError = validate(range?.first, range?.second)
        when {
            rangeError != null -> rangeError
            form.phoneDir.isBlank() -> "请先选择手机目录"
            else -> null
        }
    }
}

// 只读的「同步到」一行，随区间实时更新。
// 区间没填完整时不显示半截路径 —— 让用户看到落点，而不是看到一个会变的假目录名。
fun syncTargetText(
    form: PhoneImportForm,
    folderNameFor: (LocalDateTime, LocalDateTime) -> String,
): String {
    val range = phoneImportRange(form) ?: return "同步到：—（请填写完整的开始与结束时间）"
    val root = form.sourceDir.ifBlank { "<电脑源目录>" }
    return "同步到：$root/${folderNameFor(range.first, range.second)}"
}

// R5：把执行器给出的冲突原因转成弹窗里的提示，并留出「仍要继续」这个显式动作。
// 不静默合并 —— 有冲突就必须让用户看见原文并再点一次，[PhoneImportForm.confirmConflict] 才置真。
fun conflictPrompt(conflict: String?): String? =
    conflict?.takeIf { it.isNotBlank() }?.let { "$it 若确认是同一批照片，可点「仍要继续」。" }
