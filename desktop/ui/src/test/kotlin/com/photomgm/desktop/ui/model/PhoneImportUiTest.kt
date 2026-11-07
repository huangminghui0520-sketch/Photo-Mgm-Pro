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
}
