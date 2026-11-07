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
