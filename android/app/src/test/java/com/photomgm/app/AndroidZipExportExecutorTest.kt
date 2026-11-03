// app/AndroidZipExportExecutorTest —— 安卓 ZIP 导出执行器的**产物级**用例（T11 提交②）
//
// 为什么需要这一组用例：`ExportGateTest` 验的是门禁（重入 / 取消后释放 / Job 级兜底），
// 它一路走的是"未设置输出目录 → 提前返回"的分支，**从来没有真正写出过一个 ZIP**。
// 而第二提交把「计划生成 + ZIP 写入 + SAF 落地」整段换掉了，这是全项目风险最高的一次改动：
// 旧实现由 `ZipExporter` 自己算文件夹与重名，新实现完全按 `ExportPlan` 写 ——
// 计划里有几条就要写出几条，一条不多一条不少。
//
// 所以这里断言的不是"没抛异常"，而是**产物本身**：
//   · ZIP 条目名集合必须与 `plan.files[].relativePath` 完全一致（含顺序）；
//   · 每个条目的字节必须与源文件字节一致（复制语义，不是重编码）；
//   · 源缺失只影响那一条（进 failures），不影响其余；
//   · 台账进 ZIP 但**不计入 copied**（`ExportResult` 的计数口径是"只数照片"）；
//   · 取消后不交付半截 ZIP（安卓没有续传，半截包等于坏包）。
package com.photomgm.app

import algorithm.model.LedgerRow
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.api.ExportBlockedException
import app.api.ExportFile
import app.api.ExportPlan
import app.api.ExportProgress
import app.api.ExportResult
import com.photomgm.app.core.adapter.AndroidZipExportExecutor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDateTime
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidZipExportExecutorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val app: Application = ApplicationProvider.getApplicationContext()

    /** 固定导出时刻：ZIP 名精确到秒，用真实时钟会让同一秒内的两条用例撞名。 */
    private val fixedClock = { LocalDateTime.of(2026, 8, 14, 13, 13, 49) }

    private fun executor(imageEncoder: (String) -> ByteArray? = { null }) =
        AndroidZipExportExecutor(app, clock = fixedClock, imageEncoder = imageEncoder)

    // ── 素材 ────────────────────────────────────────────────────────────────

    /** 造一个源照片文件，返回它的绝对路径。内容用 [seed] 保证可逐字节比对。 */
    private fun source(seed: String): String {
        // 同一个用例里会造多个源文件：目录复用（`newFolder("src")` 第二次会抛
        // "a folder with the path 'src' already exists"），文件名靠 seed 区分
        val dir = File(tmp.root, "src").apply { mkdirs() }
        val f = File(dir, "IMG_$seed.jpg")
        f.writeBytes("BYTES-$seed".toByteArray())
        return f.absolutePath
    }

    private fun file(relativePath: String, sourceRef: String, photoId: Long) =
        ExportFile(relativePath = relativePath, sourceRef = sourceRef, photoId = photoId)

    private fun plan(
        files: List<ExportFile>,
        ledger: List<LedgerRow> = emptyList(),
        includeLedger: Boolean = false,
    ) = ExportPlan(
        namingTemplate = 1,
        includeLedger = includeLedger,
        folderNames = mapOf(1 to "01_护栏损坏"),
        files = files,
        unmatchedFolder = ExportPlan.UNMATCHED_FOLDER,
        ledger = ledger,
        ledgerPreview = emptyList(),
        renames = emptyList(),
        warnings = emptyList(),
    )

    private fun outDir(): String = tmp.newFolder("out").absolutePath

    private fun run(
        plan: ExportPlan,
        target: String,
        isCancelled: () -> Boolean = { false },
        imageEncoder: (String) -> ByteArray? = { null },
        onProgress: (ExportProgress) -> Unit = {},
    ): ExportResult = executor(imageEncoder).execute(plan, target, onProgress, isCancelled)

    private fun entriesOf(zip: File): List<String> = ZipFile(zip).use { z ->
        z.entries().toList().map { it.name }
    }

    // ── 用例 ────────────────────────────────────────────────────────────────

    /**
     * 主路径：计划里有几条，ZIP 里就有几条，且**字节逐条相同**。
     *
     * ★ 断言顺序而不只是集合：ZIP 条目顺序就是用户的解压顺序，也是"同一文件夹内照片
     *   按事件结束时间升序"这条业务规则的外部表现。用 Set 比对会把这条规则弄丢。
     */
    @Test
    fun `按计划逐条写出_条目名与顺序和计划一致_字节逐条相同`() {
        val a = source("a")
        val b = source("b")
        val c = source("c")
        val plan = plan(
            listOf(
                file("01_护栏损坏/IMG_a.jpg", a, 1),
                file("01_护栏损坏/IMG_b.jpg", b, 2),
                file("99_未分类/IMG_c.jpg", c, 3),
            ),
        )
        val out = outDir()

        val r = run(plan, out)

        assertEquals("三张全部写出", 3, r.copied)
        assertEquals(0, r.failed)
        assertEquals(0, r.skipped)
        assertEquals(3, r.totalTargets)
        assertFalse(r.cancelled)
        assertTrue("落地产物必须是用户选的那个目录下", r.exportDir.startsWith(out))

        val entries = entriesOf(File(r.exportDir))
        assertEquals(
            "条目名与顺序必须与计划完全一致（顺序=解压顺序=事件时间序）",
            listOf("01_护栏损坏/IMG_a.jpg", "01_护栏损坏/IMG_b.jpg", "99_未分类/IMG_c.jpg"),
            entries,
        )
        ZipFile(File(r.exportDir)).use { z ->
            listOf(
                "01_护栏损坏/IMG_a.jpg" to "BYTES-a",
                "01_护栏损坏/IMG_b.jpg" to "BYTES-b",
                "99_未分类/IMG_c.jpg" to "BYTES-c",
            ).forEach { (name, expected) ->
                val actual = z.getInputStream(z.getEntry(name)).use { it.readBytes().decodeToString() }
                assertEquals("条目 $name 的字节必须与源文件一致（复制语义，不许重编码）", expected, actual)
            }
        }
    }

    /**
     * 源文件缺失：只影响那一条，其余照常写出。
     *
     * ★ 这里同时守着一个**曾经真实存在的缺陷**：旧 `ZipExporter` 先 `putNextEntry` 再开流，
     *   源缺失时异常被 catch 掉，但条目已经建好了 —— ZIP 里会留下一个零字节的坏条目
     *   （解压时才报错）。新实现改成"先开源流、再建条目"，所以这条用例额外断言
     *   "失败的那条**不在**条目列表里"。
     */
    @Test
    fun `源缺失只让那一条进失败明细_不留零字节坏条目`() {
        val ok = source("ok")
        val plan = plan(
            listOf(
                file("01_护栏损坏/IMG_ok.jpg", ok, 1),
                file("01_护栏损坏/IMG_gone.jpg", File(tmp.root, "not-there.jpg").absolutePath, 0),
            ),
        )

        val r = run(plan, outDir())

        assertEquals(1, r.copied)
        assertEquals(1, r.failed)
        assertEquals("跳过数恒等于没复制的数量", 0, r.skipped)
        assertEquals(1, r.failures.size)
        assertEquals("01_护栏损坏/IMG_gone.jpg", r.failures[0].relativePath)

        val entries = entriesOf(File(r.exportDir))
        assertEquals(listOf("01_护栏损坏/IMG_ok.jpg"), entries)
    }

    /**
     * 输出目录不可写 → **必须抛**，而不是返回"成功 0 张"。
     *
     * 旧实现这里是 `copyToOutput` 返回 null，然后在 UI 上标红但结果里仍写"成功 N"；
     * 现在统一成 `ExportBlockedException`，由 `ExportUseCase` 落到 `AppState.error`
     * （状态停 READY，用户改个目录就能重试，不留 FAILED 标记）。
     */
    @Test
    fun `输出目标不可写时抛出阻断异常_而不是静默成功`() {
        val notADir = tmp.newFile("target-is-a-file")
        val plan = plan(listOf(file("01_护栏损坏/IMG_a.jpg", source("a"), 1)))

        val thrown = runCatching { run(plan, notADir.absolutePath) }.exceptionOrNull()

        assertTrue("必须是 ExportBlockedException，实际：$thrown", thrown is ExportBlockedException)
        assertTrue(
            "提示要能指导用户下一步（去设置页重选目录），实际：${thrown?.message}",
            thrown?.message.orEmpty().contains("设置"),
        )
    }

    /** 空计划不是"成功导出 0 张"：应该阻断（共享层 prepare 已拦，执行器自守一道）。 */
    @Test
    fun `空计划直接阻断`() {
        val thrown = runCatching { run(plan(emptyList()), outDir()) }.exceptionOrNull()
        assertTrue(thrown is ExportBlockedException)
    }

    /**
     * 取消：不交付半截 ZIP，但要把"已经写了几个"带回来。
     *
     * ★ 安卓没有续传（ZIP 名精确到秒、一次性交付），所以取消的产物直接丢弃 ——
     *   留着半截包比没有更糟：用户会以为拿到了完整导出。`copied` 仍然如实计数，
     *   否则 UI 无法说清"取消前已经打包了几张"。
     */
    @Test
    fun `取消后不交付半截ZIP_但带回已写数量`() {
        val plan = plan(
            listOf(
                file("01_护栏损坏/IMG_a.jpg", source("a"), 1),
                file("01_护栏损坏/IMG_b.jpg", source("b"), 2),
                file("01_护栏损坏/IMG_c.jpg", source("c"), 3),
            ),
        )
        val out = outDir()
        var checks = 0
        val r = run(plan, out, isCancelled = { checks++ > 0 })   // 第 1 张之后取消

        assertTrue("必须如实报告被取消", r.cancelled)
        assertEquals("取消前已写出的数量要带回来", 1, r.copied)
        assertEquals(0, r.failed)
        assertEquals(
            "输出目录里不许留下半截 ZIP",
            emptyList<String>(),
            File(out).list()?.toList().orEmpty(),
        )
    }

    /**
     * 台账：进 ZIP、另存一份私有副本供「打开台账」，但**不计入 copied**。
     *
     * ★ `copied` 口径是"照片"。旧实现把 extraFiles 也加进 copied，于是 60 张照片 + 1 个表格
     *   会显示"成功 61"，与 `ExportResult` 的契约（也只数照片）矛盾。
     */
    @Test
    fun `台账进ZIP并另存私有副本_但不计入照片计数`() {
        val a = source("a")
        val row = LedgerRow(
            dateTime = "2026-08-14 13:00",
            location = "K123+000",
            description = "护栏损坏",
            photo1Ref = null,
            photo2Ref = null,
            folderName = "01_护栏损坏",
        )
        val plan = plan(
            files = listOf(file("01_护栏损坏/IMG_a.jpg", a, 1)),
            ledger = listOf(row),
            includeLedger = true,
        )

        val r = run(plan, outDir())

        assertEquals("台账不算照片", 1, r.copied)
        assertEquals(1, r.totalTargets)
        val ledgerFile = r.ledgerFile
        assertNotNull("台账要另存一份私有副本，否则「打开台账」永远无响应", ledgerFile)
        assertTrue("私有副本必须真的落盘", File(ledgerFile!!).isFile)
        assertTrue(File(ledgerFile).length() > 0)

        val entries = entriesOf(File(r.exportDir))
        assertEquals(
            listOf("01_护栏损坏/IMG_a.jpg", ExportPlan.DEFAULT_LEDGER_NAME),
            entries,
        )
    }

    /** 进度回调必须单调推进到 total，UI 的进度条不会倒退。 */
    @Test
    fun `进度回调覆盖每个文件_且上限等于照片数`() {
        val plan = plan(
            listOf(
                file("01_护栏损坏/IMG_a.jpg", source("a"), 1),
                file("01_护栏损坏/IMG_b.jpg", source("b"), 2),
            ),
        )
        val seen = mutableListOf<ExportProgress>()

        run(plan, outDir(), onProgress = { seen += it })

        val photoSteps = seen.filter { it.stage == "打包照片" }
        assertEquals(2, photoSteps.size)
        assertEquals(2, photoSteps.last().copied)
        assertEquals(2, photoSteps.last().total)
        assertEquals(1f, photoSteps.last().fraction, 0.0001f)
    }

    /** 日志：末行必须是 `export_end`，且 summary 与返回值一致（双端对拍用它当基准）。 */
    @Test
    fun `export_log 末行是 export_end 且计数与结果一致`() {
        val plan = plan(
            listOf(
                file("01_护栏损坏/IMG_a.jpg", source("a"), 1),
                file("01_护栏损坏/IMG_gone.jpg", File(tmp.root, "nope.jpg").absolutePath, 0),
            ),
        )

        val r = run(plan, outDir())

        val log = File(r.logFile)
        assertTrue("日志必须落在返回的 logFile 上", log.isFile)
        val lines = log.readLines().filter { it.isNotBlank() }
        assertEquals("第一条必须是 export_start", true, lines.first().contains("\"export_start\""))
        val last = lines.last()
        assertTrue("末行必须是 export_end，实际：$last", last.contains("\"export_end\""))
        assertTrue("summary.success 要与 copied 一致，实际：$last", last.contains("\"success\":${r.copied}"))
        assertTrue("summary.failed 要与 failed 一致，实际：$last", last.contains("\"failed\":${r.failed}"))
    }

    /** 照片顺序即写入顺序，同一文件夹内重名由计划解决；执行器**不许**改名。 */
    @Test
    fun `执行器不改名_计划里叫什么就写什么`() {
        val a = source("a")
        val plan = plan(
            listOf(
                file("01_护栏损坏/IMG_a.jpg", a, 1),
                file("01_护栏损坏/IMG_a_2.jpg", source("b"), 2),
            ),
        )

        val r = run(plan, outDir())

        assertEquals(
            listOf("01_护栏损坏/IMG_a.jpg", "01_护栏损坏/IMG_a_2.jpg"),
            entriesOf(File(r.exportDir)),
        )
        assertTrue(r.failures.isEmpty())
        assertNull("成功路径不该有台账（includeLedger=false）", r.ledgerFile)
    }
}
