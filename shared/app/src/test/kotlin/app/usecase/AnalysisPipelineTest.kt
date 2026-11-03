// AnalysisPipelineTest —— 共享分析流水线的行为锁（T09-C2）
//
// 逐条钉住「搬上来之后不能变」的东西（每一条都能对应到既有实现的一句注释或一次踩坑）：
//   1. 源目录为空：只解析日志、不报错，且**不弹"失败"**；
//   2. 日期不对：事件条仍在（解析与扫描是两条独立链路），照片为 0 并给出可执行的提示；
//   3. 正常一路：事件照片表、台账行数、摘要计数三者同源（预览 == 台账）；
//   4. 重新分析清空人工调整：否则改完日期重跑，用户会看到"上一批照片的归属"挂在新的照片上；
//   5. 取消 → READY + "分析已取消"（**不是** FAILED）；
//   6. 异常 → FAILED + "分析失败：…"，且消息里带原始原因（否则现场无法排障）；
//   7. 重复分析不沿用上一轮统计（零照片时最容易露出"沿用旧摘要"的 bug）。
package app.usecase

import app.api.CoreStatus
import app.api.Settings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnalysisPipelineTest {

    @Test
    fun `源目录为空时只解析日志并提示暂不扫描`() {
        val h = Harness(photos = Harness.twoPhotos(), sourceDirs = emptyList())
        h.analyze()

        val s = h.store.current()
        assertEquals(CoreStatus.READY, s.status, "未选源目录不是失败：${s.error}")
        assertNull(s.error)
        assertNotNull(s.parsed, "日志已解析，摘要必须可见")
        assertEquals(3, s.parsed!!.eventCount)
        assertTrue(s.events.isEmpty(), "没有分类结果时不发事件卡（与既有实现一致）")
        assertEquals("已解析 3 条事件；未选择源目录，暂不扫描照片", s.message)
    }

    @Test
    fun `日期不匹配时事件仍在但零照片并给出可执行提示`() {
        // 文件名日期是 08-13，巡查日期是 08-14 → 粗筛全部滤掉
        val h = Harness(photos = listOf(Fixtures.candidate("IMG_20260813_101530.jpg")), exif = Harness.twoPhotoExif())
        h.analyze()

        val s = h.store.current()
        assertEquals(CoreStatus.READY, s.status)
        assertEquals(3, s.events.size, "解析与扫描互不阻断：事件条不能因为没照片就消失")
        assertEquals(0, s.photos.size)
        assertTrue(s.message!!.contains("未找到照片"), "提示要说清是日期还是源目录的问题：${s.message}")
        assertEquals(0, s.analysis!!.clusterCount)
    }

    @Test
    fun `正常分析得到事件照片表与台账行`() {
        val h = Harness(photos = Harness.twoPhotos(), exif = Harness.twoPhotoExif())
        h.analyze()

        val s = h.store.current()
        assertEquals(CoreStatus.READY, s.status, s.error ?: "")
        assertEquals(3, s.events.size)
        assertEquals(2, s.photos.size)
        assertEquals(3, s.ledger.size, "台账行数按事件数，不按照片数")
        assertEquals(2, s.eventPhotoIds.values.flatten().size)
        assertEquals(2, s.analysis!!.photoCount)
        assertEquals(0, s.analysis!!.heicCount)
        assertEquals(2, s.analysis!!.matchedCount)
        assertTrue(s.ledger.all { it.folderName.startsWith("0") }, "模板 1 的文件夹名带序号前缀")
    }

    @Test
    fun `同一张照片不会同时出现在事件与未匹配池`() {
        val h = Harness(photos = Harness.twoPhotos(), exif = Harness.twoPhotoExif())
        h.analyze()

        val s = h.store.current()
        val inEvents = s.eventPhotoIds.values.flatten().toSet()
        assertTrue(s.unmatchedPhotoIds.none { it in inEvents }, "未匹配池与事件照片必须互斥")
    }

    @Test
    fun `HEIC 计入摘要而不是静默丢掉`() {
        // HEIC 在台账里嵌不进图片，摘要必须如实计数，否则用户无法解释"为什么有的格子没有图"
        val photos = Harness.twoPhotos() + Fixtures.candidate("IMG_20260814_101600.heic")
        val h = Harness(photos = photos, exif = Harness.twoPhotoExif())
        h.analyze()

        val s = h.store.current()
        assertEquals(3, s.analysis!!.photoCount)
        assertEquals(1, s.analysis!!.heicCount)
    }

    @Test
    fun `重新分析清空人工调整`() {
        val h = Harness(photos = Harness.twoPhotos(), exif = Harness.twoPhotoExif())
        h.analyze()

        // 把事件 2 的照片移到事件 1（事件 1 原本没有照片）
        val photo = h.store.current().eventPhotoIds.getValue(2).first()
        assertEquals(1, h.adjust.movePhotos(listOf(photo), 1))
        assertTrue(h.store.current().eventPhotoIds[1]!!.contains(photo))

        h.analyze()
        val s = h.store.current()
        assertTrue(s.eventPhotoIds[1].isNullOrEmpty(), "重新分析 = 全新分配，人工移动必须被清掉")
        assertTrue(s.eventPhotoIds.getValue(2).contains(photo))
    }

    @Test
    fun `取消时回到就绪并提示已取消而不是失败`() {
        val h = Harness(photos = Harness.twoPhotos(), exif = Harness.twoPhotoExif())
        h.analyze(isCancelled = { true })

        val s = h.store.current()
        assertEquals(CoreStatus.READY, s.status, "取消不是失败")
        assertNull(s.error, "取消不得留下错误详情")
        assertEquals("分析已取消", s.message)
        assertEquals(0, s.photos.size, "取消发生在 EXIF 精读之前，照片不应落地")
    }

    @Test
    fun `扫描抛异常时记失败并保留原因`() {
        val h = Harness(
            sourceProvider = FakeSourceProvider(failWith = IllegalStateException("读目录被拒绝")),
        )
        h.analyze()

        val s = h.store.current()
        assertEquals(CoreStatus.FAILED, s.status)
        assertTrue(s.error!!.startsWith("分析失败："), "错误详情要有统一前缀：${s.error}")
        assertTrue(s.error!!.contains("读目录被拒绝"), "必须带原始原因，否则现场无从排障")
        assertNull(s.progress, "失败后不能留着半截进度条")
    }

    @Test
    fun `重复分析不沿用上一轮的统计`() {
        val h = Harness(photos = Harness.twoPhotos(), exif = Harness.twoPhotoExif())
        h.analyze()
        assertNotNull(h.store.current().analysis)
        assertEquals(2, h.store.current().analysis!!.photoCount)

        // 第二轮：把源目录清空 → 只解析日志，分析摘要必须归零/消失，而不是继续显示 2 张
        h.settingsUseCase.updateSettings { it.copy(sourceDirs = emptyList()) }
        h.pipeline.analyze(listener = null, isCancelled = { false })

        val s = h.store.current()
        assertNull(s.analysis, "重新分析开始时就把上一轮的摘要清掉")
        assertEquals(0, s.photos.size)
        assertTrue(s.events.isEmpty())
    }

    @Test
    fun `设置的巡查日期决定粗筛区间`() {
        // 日志按 08-13 解析，照片文件名也是 08-13 → 同一个日期下必须能匹配上
        val other = java.time.LocalDate.of(2026, 8, 13)
        val h = Harness(
            photos = listOf(Fixtures.candidate("IMG_20260813_101530.jpg")),
            settings = Settings(patrolDate = other, sourceDirs = listOf("/photos")),
            exif = FakeExif(
                captureTimes = mapOf("/photos/IMG_20260813_101530.jpg" to Fixtures.at(10, 15, 30, other)),
                gps = mapOf("/photos/IMG_20260813_101530.jpg" to (30.25 to 120.50)),
            ),
        )
        h.analyze()

        val s = h.store.current()
        assertEquals(1, s.photos.size, "巡查日期与文件名日期一致时必须扫到：${s.message}")
        assertEquals(1, s.analysis!!.photoCount)
    }
}
