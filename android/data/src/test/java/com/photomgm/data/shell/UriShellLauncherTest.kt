// data/shell/UriShellLauncherTest.kt —— T06 新增：三态结果里的两态可断言
//
// 为什么只测两态：
//   NO_VIEWER_APP 依赖"系统里真的没有能打开 xlsx 的应用"，Robolectric 的 ShadowApplication
//   对 startActivity 一律记录成功，构造不出这个前提。硬造（比如 mock Context 让 startActivity 抛）
//   测到的是 mock 的配置，不是本类的逻辑，故不写。这一态留给真机手工清单（见 T08 的 12 项）。
//
// 测什么：
//   1. `content://` 必须原样透传，且带上 GRANT_READ + NEW_TASK 两个 flag
//      —— 少 NEW_TASK 会在真机抛 "Calling startActivity() from outside of an Activity context"；
//      少 GRANT_READ 则接收方拿到 URI 也读不了，表现为"打开后应用报无法读取文件"。
//   2. 物理路径且 FileProvider 不可用时，**返回可识别的状态而不是抛异常**
//      —— 调用方据此提示"无法打开：路径"，而不是让整个 UI 事件崩掉。
package com.photomgm.data.shell

import android.app.Application
import android.content.Intent
import androidx.core.content.IntentCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UriShellLauncherTest {

    private val xlsxMime = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

    private fun app(): Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `content_uri 原样透传_并带读权限与新任务栈标记`() {
        val launcher = UriShellLauncher(app())

        val result = launcher.openDocument(
            "content://media/external/downloads/42", xlsxMime, "打开台账",
        )

        assertEquals("content:// 不需要 FileProvider，必然能启动", UriShellLauncher.Result.STARTED, result)

        // 实际启动的是 chooser，真正的 ACTION_VIEW 在 EXTRA_INTENT 里
        val chooser = shadowOf(app()).nextStartedActivity
        assertNotNull("必须真的调用 startActivity（而不是预检后静默返回）", chooser)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val inner = IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java)
        assertNotNull("chooser 里必须挂着 ACTION_VIEW", inner)
        inner!!

        assertEquals("content://media/external/downloads/42", inner.data.toString())
        assertEquals(xlsxMime, inner.type)
        assertTrue(
            "必须授予读权限，否则接收方拿到 URI 也读不出文件",
            inner.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0,
        )
        assertTrue(
            "本类只持 applicationContext，启动必须带 NEW_TASK，否则真机直接抛异常",
            inner.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0,
        )
    }

    @Test
    fun `物理路径拿不到FileProvider时_返回URI_UNAVAILABLE且不启动`() {
        val launcher = UriShellLauncher(app())

        // :data 的测试环境没有声明 FileProvider（provider 由宿主的 manifest 提供），
        // getUriForFile 会抛 IllegalArgumentException —— 这正是"配错 authority / 没声明 provider"
        // 在真实场景下的表现。
        val result = launcher.openDocument("/sdcard/Download/台账.xlsx", xlsxMime, "打开台账")

        assertEquals(
            "拿不到可用 URI 时必须返回可识别的状态，让调用方提示路径问题",
            UriShellLauncher.Result.URI_UNAVAILABLE,
            result,
        )
        assertNull("连 URI 都没拿到就不该启动任何界面", shadowOf(app()).nextStartedActivity)
    }
}
