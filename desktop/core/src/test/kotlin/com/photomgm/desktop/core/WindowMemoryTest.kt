// WindowMemoryTest.kt —— 窗口几何的存取（T10 把它从共享 Settings 上摘下来之后的第一道防线）
//
// ★ 为什么这个测试必须存在：`Settings.window` 从跨端契约里拿掉之后，窗口几何由
//   `PcSettingsStorage` 自己保管，而 settings.json 是**一次性整体重写**的。
//   于是有一个不会报错、只会"下次启动窗口回到默认位置"的坑：
//   `SettingsUseCase.updateSettings` 每次改设置都会调 `save(settings)`，
//   交进来的 `Settings` 里**没有** window 这一项 —— 若 `save` 只按传入对象重写整个文件，
//   用户改一次巡查日期就把窗口记忆清成 null。三条用例分别锁住：能存回来、改设置不丢、老配置照读。
package com.photomgm.desktop.core

import app.api.CoreStatus
import app.port.ShellLauncher
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowMemoryTest {

    @TempDir
    lateinit var tmp: Path

    private val dataDir: Path get() = tmp.resolve("data")

    private fun newCore(): DesktopCore = DesktopCore.create(
        DesktopCoreConfig(
            dataDir = dataDir,
            // 同步执行器：这里不测并发，只测落盘口径
            executor = Executor { it.run() },
            shell = ShellLauncher.NOOP,
        ),
    )

    @Test
    fun `记住窗口几何后新实例能读回`() {
        val saved = WindowState(x = 10, y = 20, width = 900, height = 600)
        newCore().use { it.saveWindow(saved) }

        newCore().use { core ->
            assertEquals(saved, core.windowState, "窗口几何必须跨进程存活（它就是给下次启动用的）")
        }
    }

    /**
     * ★ 这条是核心：改设置会走 `SettingsUseCase.updateSettings` → `storage.save(settings)`，
     * 而那份 settings 里没有 window。写盘时若漏补，窗口记忆就被悄悄清掉。
     */
    @Test
    fun `改设置不会抹掉窗口记忆`() {
        val saved = WindowState(x = 1, y = 2, width = 800, height = 500)
        val date = Fixtures.PATROL_DATE

        newCore().use { core ->
            core.saveWindow(saved)
            core.updateSettings { it.copy(patrolDate = date, namingTemplate = 0) }
        }

        newCore().use { core ->
            assertEquals(saved, core.windowState, "改设置把窗口记忆写没了 —— save() 忘补 window 键")
            assertEquals(date, core.state.value.settings.patrolDate, "设置本身也要落盘")
            assertEquals(0, core.state.value.settings.namingTemplate)
        }
    }

    /** 老配置照读：升级前写下的 settings.json 里 `window` 是个对象，键名与结构都没变。 */
    @Test
    fun `老配置里的 window 键照读`() {
        Files.createDirectories(dataDir)
        Files.writeString(
            dataDir.resolve("settings.json"),
            """
            {
              "sourceDirs": ["D:/巡查照片"],
              "outputDir": "D:/导出",
              "patrolDate": "2026-08-14",
              "namingTemplate": 1,
              "dedupMode": "KEEP_BOTH",
              "window": { "x": 3, "y": 4, "width": 1024, "height": 700 }
            }
            """.trimIndent(),
            Charsets.UTF_8,
        )

        newCore().use { core ->
            assertEquals(WindowState(3, 4, 1024, 700), core.windowState)
            assertEquals(listOf("D:/巡查照片"), core.state.value.settings.sourceDirs)
            assertEquals("D:/导出", core.state.value.settings.outputDir)
        }
    }

    /** `window` 键缺失（或字段不全）时按"没有记忆"处理，不能编出一份 0×0 的窗口。 */
    @Test
    fun `window 键缺失或字段不全时按无记忆处理`() {
        Files.createDirectories(dataDir)
        Files.writeString(
            dataDir.resolve("settings.json"),
            """{ "patrolDate": "2026-08-14", "window": { "x": 5, "y": 6 } }""",
            Charsets.UTF_8,
        )

        newCore().use { core ->
            assertNull(core.windowState, "缺 width/height 的半截 window 必须当作没有")
            assertEquals(CoreStatus.IDLE, core.state.value.status)
        }
    }

    /** 传 null = 忘掉（`--demo` 靠它保证每次演示的窗口尺寸一致）。 */
    @Test
    fun `saveWindow 传 null 表示忘掉`() {
        newCore().use { core ->
            core.saveWindow(WindowState(1, 1, 500, 400))
            assertTrue(core.windowState != null)
            core.saveWindow(null)
            assertNull(core.windowState)
        }
        newCore().use { core ->
            assertNull(core.windowState, "忘掉之后重启也不该再冒出来")
        }
    }
}
