// 端到端集成测试：真实文件系统 + 真实 EXIF + 真实算法核心，只把"执行器/外壳/时钟"换成确定性实现。
//
// 覆盖的产品语义（都与 Android 端一致，改这些等于改产品行为）：
//   · 日志解析 → 照片扫描 → 文件名日期粗筛 → 派生去重 → EXIF 精读 → GPS 分组 → 事件分类 → 台账
//   · 三态去重只作用于输出层（分类结果不变），切换后事件照片与台账同步变化
//   · 人工移动 / 移入未匹配池 / 恢复最初分类
//   · 导出产物落地 + 状态回填
package com.photomgm.desktop.core

import algorithm.AppZone
import algorithm.DedupKeepMode
import com.photomgm.desktop.core.api.AppState
import com.photomgm.desktop.core.api.CoreConfig
import com.photomgm.desktop.core.api.CoreStatus
import com.photomgm.desktop.core.api.ExportListener
import com.photomgm.desktop.core.api.ExportProgress
import com.photomgm.desktop.core.api.ExportRequest
import com.photomgm.desktop.core.api.ExportResult
import com.photomgm.desktop.core.api.PhotoMgmCore
import com.photomgm.desktop.core.api.PhotoMgmCoreFactory
import com.photomgm.desktop.core.api.ShellLauncher
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CorePipelineTest {

    @TempDir
    lateinit var tmp: Path

    private lateinit var photosDir: Path
    private lateinit var outDir: Path

    private val logText = """
        08时00分 与早班巡查人员张某某、李某某在A收费管理中心进行交接班，检查巡查装备齐全完好，车况正常。
        10时15分 巡查至上行K138+700M处，检查交安设施，发现护栏损坏，已拍照取证。
        12时00分 巡查人员张某某、李某某在下行K138+800M处发现标志牌倾斜，已拍照取证。
    """.trimIndent()

    @BeforeEach
    fun setUp() {
        photosDir = tmp.resolve("photos")
        outDir = tmp.resolve("out")
        // 主图 + 水印相机同刻原图（内容不同 → 两张都保留，之后由三态决定输出哪张）
        TestImages.writeJpegWithExif(
            photosDir.resolve("IMG_20260814_101530.jpg"),
            "2026:08:14 10:15:30", 30.25, 120.5, color = Color(30, 120, 200),
        )
        TestImages.writeJpegWithExif(
            photosDir.resolve("IMG_20260814_101530_original.jpg"),
            "2026:08:14 10:15:30", 30.25, 120.5, color = Color(200, 60, 60),
        )
        TestImages.writeJpegWithExif(
            photosDir.resolve("IMG_20260814_120000.jpg"),
            "2026:08:14 12:00:00", 30.5, 120.6, color = Color(60, 180, 90),
        )
    }

    private fun newCore(dataDir: Path = tmp.resolve("data")): PhotoMgmCore =
        PhotoMgmCoreFactory.create(
            CoreConfig(
                dataDir = dataDir,
                zone = AppZone.ID,
                executor = Executor { it.run() }, // 同步执行器：测试确定性
                shell = ShellLauncher.NOOP,
                clock = Clock.fixed(Instant.parse("2026-08-14T02:15:00Z"), AppZone.ID),
            ),
        )

    private fun readyCore(): PhotoMgmCore {
        val core = newCore()
        core.updateSettings {
            it.copy(
                sourceDirs = listOf(photosDir.toAbsolutePath().toString().replace('\\', '/')),
                outputDir = outDir.toAbsolutePath().toString(),
                patrolDate = Fixtures.PATROL_DATE,
                namingTemplate = 0,
            )
        }
        core.setLogText(logText)
        core.analyze()
        return core
    }

    @Test
    fun `端到端分析得到事件与分类`() {
        newCore().use { core ->
            core.updateSettings {
                it.copy(
                    sourceDirs = listOf(photosDir.toAbsolutePath().toString().replace('\\', '/')),
                    patrolDate = Fixtures.PATROL_DATE,
                    namingTemplate = 0,
                )
            }
            core.setLogText(logText)
            core.analyze()

            val s = core.state.value
            assertEquals(CoreStatus.READY, s.status, "分析应成功：${s.error}")
            assertNull(s.error)
            assertEquals(3, s.events.size, "日志三行应解析为三条事件")
            assertEquals(3, s.photos.size, "主图 + 同刻原图 + 12点照片 = 3 张")
            assertEquals(3, s.ledger.size)
            assertNotNull(s.parsed)
            assertEquals(0, s.parsed!!.skippedLineCount)
            assertNotNull(s.analysis)
            assertEquals(3, s.analysis!!.photoCount)
            // 08:00 交接班是最早事件
            assertEquals("1", s.events.first().id.toString())
            assertTrue(s.events.none { it.isPending }, "三行都有时间，不应有待定事件")
        }
    }

    @Test
    fun `三态去重只改输出层且同步生效`() {
        readyCore().use { core ->
            val before = core.state.value
            val event2 = before.events.first { it.id == 2 }
            assertEquals(1, event2.photoIds.size, "默认仅保留带水印：同刻两张折叠为 1 张")

            core.setDedupMode(DedupKeepMode.KEEP_BOTH)
            val both = core.state.value.events.first { it.id == 2 }
            assertEquals(2, both.photoIds.size, "两者都保留：同刻两张都在")

            core.setDedupMode(DedupKeepMode.KEEP_PLAIN)
            val plain = core.state.value.events.first { it.id == 2 }
            assertEquals(1, plain.photoIds.size, "仅保留不带水印：仍折叠为 1 张")
            val keptName = core.state.value.photos.first { it.id == plain.photoIds.first() }.displayName
            assertTrue(keptName.contains("_original"), "应保留 _original（不带水印），实际=$keptName")

            // 分类结果本身未被修改：切回默认仍是一张，且台账同步
            core.setDedupMode(DedupKeepMode.KEEP_WATERMARKED)
            assertEquals(1, core.state.value.events.first { it.id == 2 }.photoIds.size)
            assertEquals(3, core.state.value.ledger.size)
        }
    }

    @Test
    fun `人工移动与恢复最初分类`() {
        readyCore().use { core ->
            val mainId = core.state.value.events.first { it.id == 2 }.photoIds.first()

            core.movePhotos(listOf(mainId), null) // 移入未匹配池
            val moved = core.state.value
            assertTrue(moved.unmatchedPhotoIds.contains(mainId))
            assertTrue(moved.events.first { it.id == 2 }.photoIds.isEmpty())

            core.resetManualAdjustments()
            val reset = core.state.value
            assertEquals(1, reset.events.first { it.id == 2 }.photoIds.size)
            assertTrue(!reset.unmatchedPhotoIds.contains(mainId))
        }
    }

    @Test
    fun `移动的返回值是真正改了归属的张数`() {
        readyCore().use { core ->
            val photoId = core.state.value.events.first { it.id == 2 }.photoIds.first()

            assertEquals(1, core.movePhotos(listOf(photoId), 3), "第一次移动：1 张真的换了归属")
            assertEquals(0, core.movePhotos(listOf(photoId), 3), "已经在事件 3：重复移动不该算变化")
            assertEquals(1, core.movePhotos(listOf(photoId), null), "移进未匹配池：算一次变化")
            assertEquals(0, core.movePhotos(listOf(photoId), null), "已经在那儿了：不再算变化")
            assertEquals(0, core.movePhotos(listOf(999_999L), 1), "照片不存在：不算变化")
        }
    }

    @Test
    fun `复制照片到另一个事件`() {
        readyCore().use { core ->
            val photoId = core.state.value.events.first { it.id == 2 }.photoIds.first()

            // 复制：原事件不动，目标事件多一份
            assertEquals(1, core.copyPhotos(listOf(photoId), 1))
            val after = core.state.value
            assertTrue(after.events.first { it.id == 2 }.photoIds.contains(photoId), "原事件必须保留")
            assertTrue(after.events.first { it.id == 1 }.photoIds.contains(photoId), "目标事件必须多一份")
            assertEquals(0, after.unmatchedPhotoIds.count { it == photoId }, "复制不能让照片变成未匹配")

            // 幂等：同一目标再点一次、或目标本来就是它的家，都不应长出第二份
            assertEquals(0, core.copyPhotos(listOf(photoId), 1), "重复复制不再新增")
            assertEquals(0, core.copyPhotos(listOf(photoId), 2), "已经是它自己的事件")

            // 移动会清掉复制关系：移走之后旧的事件里不能还留着一份
            core.movePhotos(listOf(photoId), 3)
            val moved = core.state.value
            assertTrue(moved.events.first { it.id == 1 }.photoIds.none { it == photoId }, "移走后旧复制份必须消失")
            assertTrue(moved.events.first { it.id == 3 }.photoIds.contains(photoId))

            // 未匹配池里的照片不参与复制（它没有家，复制会同时出现在两处）
            core.movePhotos(listOf(photoId), null)
            assertEquals(0, core.copyPhotos(listOf(photoId), 1), "未匹配照片不能复制")

            core.resetManualAdjustments()
            assertEquals(1, core.state.value.events.first { it.id == 2 }.photoIds.size)
        }
    }

    @Test
    fun `未设置输出目录时不可导出`() {
        newCore().use { core ->
            core.setLogText(logText)
            core.analyze()
            assertTrue(!core.state.value.canExport, "输出目录未设置时必须禁止导出")
        }
    }

    @Test
    fun `导出落地并回填状态`() {
        readyCore().use { core ->
            assertTrue(core.state.value.canExport, "分析完成 + 有输出目录 ⇒ 可导出")

            var finished: ExportResult? = null
            var failure: Throwable? = null
            val listener = object : ExportListener {
                override fun onProgress(progress: ExportProgress) = Unit
                override fun onFinished(result: ExportResult) { finished = result }
                override fun onFailed(error: Throwable) { failure = error }
            }

            core.export(ExportRequest(outDir), listener)

            assertNull(failure, "导出不应失败：${failure?.message}")
            val r = assertNotNull(finished, "同步执行器下应已回调 onFinished")
            assertTrue(Files.isRegularFile(r.exportDir.resolve("ledger.xlsx")))
            assertTrue(Files.isRegularFile(r.exportDir.resolve("export.log")))
            assertTrue(Files.isRegularFile(r.exportDir.resolve("export-manifest.json")))

            val s: AppState = core.state.value
            assertEquals(CoreStatus.READY, s.status)
            assertEquals(r.exportDir, s.lastExport?.exportDir)
            assertNotNull(s.message)
            assertTrue(s.message!!.contains("导出完成"), "状态提示应回填，实际=${s.message}")
        }
    }

    @Test
    fun `设置持久化后新实例可恢复`() {
        val dataDir = tmp.resolve("data")
        val dirs = listOf(photosDir.toAbsolutePath().toString().replace('\\', '/'))
        newCore(dataDir).use { core ->
            core.updateSettings {
                it.copy(
                    sourceDirs = dirs,
                    outputDir = outDir.toAbsolutePath().toString(),
                    patrolDate = Fixtures.PATROL_DATE,
                    namingTemplate = 1,
                    dedupMode = DedupKeepMode.KEEP_BOTH,
                )
            }
            core.setLogText("08时00分 出发")
        }

        newCore(dataDir).use { core ->
            val s = core.state.value.settings
            assertEquals(dirs, s.sourceDirs)
            assertEquals(Fixtures.PATROL_DATE, s.patrolDate)
            assertEquals(1, s.namingTemplate)
            assertEquals(DedupKeepMode.KEEP_BOTH, s.dedupMode)
            assertEquals("08时00分 出发", core.state.value.logText, "日志文本应随设置一起恢复")
        }
    }

    @Test
    fun `源目录未找到照片时给出可读提示而不是崩溃`() {
        val emptyDir = tmp.resolve("empty").also { Files.createDirectories(it) }
        newCore().use { core ->
            core.updateSettings {
                it.copy(
                    sourceDirs = listOf(emptyDir.toAbsolutePath().toString()),
                    patrolDate = Fixtures.PATROL_DATE,
                )
            }
            core.setLogText(logText)
            core.analyze()

            val s = core.state.value
            assertEquals(CoreStatus.READY, s.status)
            assertEquals(3, s.events.size, "无照片也应保留事件解析结果")
            assertEquals(0, s.photos.size)
            assertTrue(s.message?.contains("未找到照片") == true, "应提示未找到照片，实际=${s.message}")
            assertTrue(!s.canExport, "无照片不可导出")
        }
    }
}
