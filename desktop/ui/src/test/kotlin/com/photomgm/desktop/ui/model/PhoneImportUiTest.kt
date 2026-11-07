// ui/test/model/PhoneImportUiTest.kt —— 手机导入弹窗的纯判定
//
// ★ validate / folderNameFor 注入的是 DesktopCore 门面转发到 shared/domain 的**真规则**，
//   不是测试里另写一份复刻：门面与生产链路共用同一个纯函数，改规则时这里会跟着红。
package com.photomgm.desktop.ui.model

import com.photomgm.desktop.core.PhoneSyncFormulas
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

class PhoneImportUiTest {

    private val patrol = LocalDate.of(2026, 11, 5)
    private val dirs = listOf("D:/巡查照片")
    private val validate = PhoneSyncFormulas::validateRange
    private val folderName = PhoneSyncFormulas::folderNameFor

    private fun form(
        startDate: String = "2026-11-05",
        startTime: String = "00:00",
        endDate: String = "2026-11-05",
        endTime: String = "23:59",
        phoneDir: String = "adb:/sdcard/DCIM/Camera",
        sourceDir: String = "D:/巡查照片",
        confirmConflict: Boolean = false,
    ) = PhoneImportForm(
        startDate = startDate,
        startTime = startTime,
        endDate = endDate,
        endTime = endTime,
        phoneDir = phoneDir,
        sourceDir = sourceDir,
        confirmConflict = confirmConflict,
    )

    // ── 未设源目录（§7） ────────────────────────────────────────────────

    @Test
    fun `未设置电脑源目录时拦截并说明原因`() {
        assertEquals(
            "请先设置电脑源目录",
            syncBlockReason(form(), emptyList(), false, validate),
        )
        assertEquals(
            "请先设置电脑源目录",
            syncBlockReason(form(sourceDir = ""), dirs, false, validate),
        )
    }

    @Test
    fun `未选手机目录时拦截`() {
        assertEquals(
            "请先选择手机目录",
            syncBlockReason(form(phoneDir = ""), dirs, false, validate),
        )
    }

    @Test
    fun `正在同步时优先拦截且不让第二次开始`() {
        assertEquals(
            "正在同步，等这一次结束再开始",
            syncBlockReason(form(), dirs, true, validate),
        )
    }

    @Test
    fun `条件齐全时可以执行`() {
        assertNull(syncBlockReason(form(), dirs, false, validate))
    }

    // ── 区间校验 V1 / V5 / 上限 / 空值 ──────────────────────────────────

    @Test
    fun `V1 默认区间是巡查日期0000到2359`() {
        val f = defaultPhoneImportForm(patrol, dirs, PhoneSyncFormulas.defaultRange(patrol))
        assertEquals("2026-11-05", f.startDate)
        assertEquals("00:00", f.startTime)
        assertEquals("2026-11-05", f.endDate)
        assertEquals("23:59", f.endTime)
        assertEquals("D:/巡查照片", f.sourceDir)
        assertNull(syncBlockReason(f.copy(phoneDir = "adb:/sdcard/DCIM/Camera"), dirs, false, validate))
    }

    @Test
    fun `V5 开始不早于结束被拒绝`() {
        val reason = syncBlockReason(
            form(startTime = "12:00", endTime = "10:00"), dirs, false, validate,
        )
        assertEquals("开始时间必须早于结束时间", reason)
    }

    @Test
    fun `区间超过31天上限被拒绝`() {
        val reason = syncBlockReason(
            form(endDate = "2026-12-20"), dirs, false, validate,
        )
        assertEquals("时间区间过长", reason)
    }

    @Test
    fun `区间恰好31天可通过`() {
        assertNull(syncBlockReason(form(endDate = "2026-12-06", endTime = "00:00"), dirs, false, validate))
    }

    @Test
    fun `起止为空或格式非法都禁止提交`() {
        assertEquals(
            "请填写完整的开始与结束时间",
            syncBlockReason(form(startDate = "", phoneDir = ""), dirs, false, validate),
        )
        assertEquals(
            "请填写完整的开始与结束时间",
            syncBlockReason(form(startTime = ""), dirs, false, validate),
        )
        assertEquals(
            "请填写完整的开始与结束时间",
            syncBlockReason(form(endTime = "9:xx"), dirs, false, validate),
        )
    }

    // ── 「同步到」只读行 ────────────────────────────────────────────────

    @Test
    fun `同步到文本V1默认05_0000__05_2359`() {
        assertEquals("同步到：D:/巡查照片/05_0000__05_2359", syncTargetText(form(), folderName))
    }

    @Test
    fun `V2 改开始时间后同步到实时变为04_2300__05_2359`() {
        val f = form(startDate = "2026-11-04", startTime = "23:00")
        assertEquals("同步到：D:/巡查照片/04_2300__05_2359", syncTargetText(f, folderName))
    }

    @Test
    fun `V4 结束跨两日同步到04_2300__06_0200`() {
        val f = form(startDate = "2026-11-04", startTime = "23:00", endDate = "2026-11-06", endTime = "02:00")
        assertEquals("同步到：D:/巡查照片/04_2300__06_0200", syncTargetText(f, folderName))
    }

    @Test
    fun `V6 跨年区间同步到31_2200__01_0200不含年份`() {
        val f = form(
            startDate = "2026-12-31", startTime = "22:00",
            endDate = "2027-01-01", endTime = "02:00",
        )
        assertEquals("同步到：D:/巡查照片/31_2200__01_0200", syncTargetText(f, folderName))
        assertFalse(syncTargetText(f, folderName).contains("2027"))
    }

    @Test
    fun `区间填不完整时同步到不显示半截路径`() {
        assertEquals(
            "同步到：—（请填写完整的开始与结束时间）",
            syncTargetText(form(startTime = ""), folderName),
        )
        assertEquals(
            "同步到：<电脑源目录>/05_0000__05_2359",
            syncTargetText(form(sourceDir = ""), folderName),
        )
    }

    // ── R5 冲突：不静默合并 ────────────────────────────────────────────

    @Test
    fun `R5 换手机目录的冲突提示保留原文并要求显式继续`() {
        val raw = "该区间上次从另一目录同步（adb:/sdcard/DCIM），本次是 adb:/sdcard/Pictures。"
        val prompt = conflictPrompt(raw)
        assertTrue(prompt!!.contains("另一目录"))
        assertTrue(prompt.contains("仍要继续"))
        assertNull(conflictPrompt(null))
        assertNull(conflictPrompt("   "))
    }

    @Test
    fun `R5 确认标记只在用户显式动作后带上`() {
        assertFalse(form().confirmConflict)
        val confirmed = form().copy(confirmConflict = true)
        assertTrue(confirmed.confirmConflict)
        assertNull(syncBlockReason(confirmed, dirs, false, validate))
    }

    // ── U1~U8：日历选日期 + 时分列（替代手敲文本框） ──────────────────────
    //
    // ★ 这 8 条都打在 model 上，不碰 Compose：列项、档位、日历互斥、实时校验
    //   全是纯函数，"点了没反应"这类问题必须能在单测里定位，而不是靠手点。

    @Test
    fun `U1 默认值 巡查日期0000到2359 且没有日历打开`() {
        val f = defaultPhoneImportForm(patrol, dirs, PhoneSyncFormulas.defaultRange(patrol))
        assertEquals("2026-11-05", f.startDate)
        assertEquals("00:00", f.startTime)
        assertEquals("2026-11-05", f.endDate)
        assertEquals("23:59", f.endTime)
        assertNull(f.calendarTarget)
        // 时列 24 项；分列 13 项 = 0,5,…,55 + 末档特例 59（承接默认 23:59 的整日语义）
        assertEquals(24, HOUR_OPTIONS.size)
        assertEquals(13, MINUTE_OPTIONS.size)
        assertEquals(0, MINUTE_OPTIONS.first())
        assertEquals(LAST_MINUTE_OPTION, MINUTE_OPTIONS.last())
        assertTrue(MINUTE_OPTIONS.contains(59))
        assertEquals(0, snapToStep(4))
        assertEquals(55, snapToStep(55))
    }

    @Test
    fun `U2 点开始日期只打开开始日历且不改业务值`() {
        val f = form().openCalendar(CalendarTarget.START)
        assertEquals(CalendarTarget.START, f.calendarTarget)
        assertTrue(f.calendarTarget != CalendarTarget.END)
        assertEquals("2026-11-05", f.startDate)
        assertEquals("00:00", f.startTime)
        assertEquals("23:59", f.endTime)
    }

    @Test
    fun `U3 点结束时列保留另一项且不动日历状态`() {
        // 改时不偷偷把分钟归零：23:59 点「9 时」得 09:59
        val f = form().openCalendar(CalendarTarget.END).withEndHour(9)
        assertEquals(CalendarTarget.END, f.calendarTarget)
        assertEquals("09:59", f.endTime)
        // 再改开始的分，结束与日历状态都保持不变
        val g = f.withStartMinute(30)
        assertEquals(CalendarTarget.END, g.calendarTarget)
        assertEquals("00:30", g.startTime)
        assertEquals("09:59", g.endTime)
    }

    @Test
    fun `U4 开始改到2355后同步到实时更新`() {
        assertTrue(MINUTE_OPTIONS.contains(55))
        val f = form().withStartHour(23).withStartMinute(55)
        assertEquals("23:55", f.startTime)
        assertEquals("同步到：D:/巡查照片/05_2355__05_2359", syncTargetText(f, folderName))
    }

    @Test
    fun `U5 开始不早于结束标红且按钮禁用 超上限只提示不标红`() {
        val f = form(startTime = "12:00", endTime = "10:00")
        // 结束字段标红
        assertEquals(RANGE_ORDER_MESSAGE, endFieldError(f, validate))
        // 按钮禁用的判据与标红同源
        assertEquals(RANGE_ORDER_MESSAGE, syncBlockReason(f, dirs, false, validate))
        // 漂移守卫：model 里那份文案副本必须与 shared/domain 的真规则逐字一致
        assertEquals(RANGE_ORDER_MESSAGE, validate(f.startDateTime(), f.endDateTime()))

        // 超过 31 天：提示行给出原因，但结束字段不标红（要改的是区间意图，不是结束值）
        val tooLong = form(endDate = "2026-12-20")
        assertEquals(RANGE_SPAN_MESSAGE, rangeError(tooLong, validate))
        assertNull(endFieldError(tooLong, validate))

        // 日期为空：不允许提交，但也不标红结束字段
        val blank = form(startTime = "")
        assertNull(endFieldError(blank, validate))
        assertTrue(syncBlockReason(blank, dirs, false, validate) != null)
    }

    @Test
    fun `U6 跨年区间文件夹名31_2200__01_0200`() {
        val f = defaultPhoneImportForm(patrol, dirs, PhoneSyncFormulas.defaultRange(patrol))
            .withStartDate(LocalDate.of(2026, 12, 31))
            .withStartHour(22)
            .withStartMinute(0)
            .withEndDate(LocalDate.of(2027, 1, 1))
            .withEndHour(2)
            .withEndMinute(0)
        assertEquals("同步到：D:/巡查照片/31_2200__01_0200", syncTargetText(f, folderName))
        assertNull(syncBlockReason(f.copy(phoneDir = "adb:/sdcard/DCIM/Camera"), dirs, false, validate))
    }

    @Test
    fun `U7 取消后重开恢复默认区间`() {
        val edited = defaultPhoneImportForm(patrol, dirs, PhoneSyncFormulas.defaultRange(patrol))
            .openCalendar(CalendarTarget.END)
            .withStartDate(LocalDate.of(2026, 11, 1))
            .withStartHour(9)
            .withStartMinute(45)
            .copy(confirmConflict = true)
        assertEquals(CalendarTarget.END, edited.calendarTarget)
        assertEquals("2026-11-01", edited.startDate)
        assertEquals("09:45", edited.startTime)

        // 关掉弹窗再打开 = 重新按巡查日期生成默认区间（弹窗里是 remember{} 首次进入时求值）
        val reopened = defaultPhoneImportForm(patrol, dirs, PhoneSyncFormulas.defaultRange(patrol))
        assertEquals("2026-11-05", reopened.startDate)
        assertEquals("00:00", reopened.startTime)
        assertEquals("23:59", reopened.endTime)
        assertNull(reopened.calendarTarget)
        assertFalse(reopened.confirmConflict)
    }

    @Test
    fun `U8 两个日历互斥`() {
        // 打开一个 → 只有一个；换一个 → 前一个自动关闭（单字段赋值保证）
        assertEquals(CalendarTarget.START, form().openCalendar(CalendarTarget.START).calendarTarget)
        assertEquals(
            CalendarTarget.END,
            form().openCalendar(CalendarTarget.START).openCalendar(CalendarTarget.END).calendarTarget,
        )
        assertEquals(
            CalendarTarget.START,
            form().openCalendar(CalendarTarget.END).openCalendar(CalendarTarget.START).calendarTarget,
        )
        // 同一颗再点一次 = 收起
        assertNull(form().openCalendar(CalendarTarget.START).toggleCalendar(CalendarTarget.START).calendarTarget)
        // 收起后不残留
        assertNull(form().openCalendar(CalendarTarget.END).closeCalendar().calendarTarget)
    }

    // ── U9 / U10：默认值与分列档位必须对得上 ──────────────────────────────
    // ★ U9 防的是「默认值没有对应档位」这个复发点：默认 23:59 是整日语义，
    //   一旦分列里没有 59，列里就没有一格高亮，用户想保持整日也无处可点。
    //   U10 固定落位规则：只有 59 走特例，相邻的非档位值仍落到 5 分钟下沿。

    @Test
    fun `U9 默认结束分钟的分钟值落在分列档位内`() {
        val f = defaultPhoneImportForm(patrol, dirs, PhoneSyncFormulas.defaultRange(patrol))
        val end = requireNotNull(f.endDateTime()) { "默认结束时间应可解析：${f.endDate} ${f.endTime}" }
        // 默认值仍是 23:59（V1，不改共享层），且它现在是分列的末档 → 能高亮、能点回
        assertEquals(59, end.minute)
        assertTrue(MINUTE_OPTIONS.contains(end.minute))
    }

    @Test
    fun `U10 档位落位 59 取末档特例 其余仍落 5 分钟下沿`() {
        assertEquals(LAST_MINUTE_OPTION, snapToStep(59))
        // 特例只对 59 生效：相邻非档位值仍按 5 分钟下沿落位，没有被一起抬到 59
        assertEquals(55, snapToStep(58))
        assertEquals(55, snapToStep(57))
        assertEquals(55, snapToStep(55))
        assertEquals(0, snapToStep(4))
    }

    // ── U11 在 dialog/CalendarPanelOffsetTest（浮层定位纯函数）────────────

    // ── U12：时/分改成浮层后的开关状态 ────────────────────────────────────
    // ★ 时/分原来是内嵌两列（一直可见，没有开关状态）；改成浮层后「开哪一个、关哪个」
    //   变成需要维护的状态，且必须与日历互斥 —— 否则两块面板会叠在同一处。
    @Test
    fun `U12 时分浮层开关与日历互斥`() {
        // 打开 / 收起（点同一颗两次）
        assertEquals(CalendarTarget.START, form().toggleTimePicker(CalendarTarget.START).timePickerTarget)
        assertNull(form().toggleTimePicker(CalendarTarget.START).toggleTimePicker(CalendarTarget.START).timePickerTarget)

        // 两侧互斥：换一颗 = 前一颗关掉
        assertEquals(
            CalendarTarget.END,
            form().toggleTimePicker(CalendarTarget.START).toggleTimePicker(CalendarTarget.END).timePickerTarget,
        )

        // 与日历互斥：开日历 → 时分关掉；开时分 → 日历关掉
        val afterCalendar = form().toggleTimePicker(CalendarTarget.START).openCalendar(CalendarTarget.END)
        assertEquals(CalendarTarget.END, afterCalendar.calendarTarget)
        assertNull(afterCalendar.timePickerTarget)
        val afterTime = form().openCalendar(CalendarTarget.START).toggleTimePicker(CalendarTarget.END)
        assertEquals(CalendarTarget.END, afterTime.timePickerTarget)
        assertNull(afterTime.calendarTarget)

        // 显式关闭
        assertNull(form().openTimePicker(CalendarTarget.END).closeTimePicker().timePickerTarget)
    }
}
