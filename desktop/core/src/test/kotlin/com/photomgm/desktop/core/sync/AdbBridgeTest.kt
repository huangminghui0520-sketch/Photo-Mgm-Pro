// desktop/core/test/sync/AdbBridgeTest.kt —— adb 定位顺序 T1~T8（全离线，不碰真实 SDK）
//
// 判据面向「顺序」而不是「本机恰好装了 SDK」：所有环境能力都经 [AdbEnv] 注入，
// 因此 Windows 分支可以在 Linux runner 上验证，盘符扫描也不会真的去读 26 个盘。
package com.photomgm.desktop.core.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AdbBridgeTest {

    // 真环境的替身：只认显式登记过的环境变量与文件，其余一律「不存在」。
    private class FakeEnv(
        private val vars: Map<String, String> = emptyMap(),
        private val files: Set<String> = emptySet(),
        private val os: String = "Windows 11",
    ) : AdbEnv {
        override fun getenv(name: String): String? = vars[name]
        override fun isRegularFile(path: String): Boolean = path in files
        override fun osName(): String = os
    }

    private fun bridge(env: AdbEnv, explicit: String? = null) =
        AdbBridge(explicitPath = explicit, adbEnv = env)

    // ── T1 显式路径优先 ──────────────────────────────────────────────────

    @Test
    fun `T1 显式路径优先于环境变量`() {
        val env = FakeEnv(
            vars = mapOf("ANDROID_HOME" to "D:\\Android\\sdk"),
            files = setOf("C:\\tools\\adb.exe", "D:\\Android\\sdk\\platform-tools\\adb.exe"),
        )
        assertEquals("C:\\tools\\adb.exe", bridge(env, explicit = "C:\\tools\\adb.exe").executable())
    }

    // ── T2 ANDROID_HOME ─────────────────────────────────────────────────

    @Test
    fun `T2 ANDROID_HOME 命中`() {
        val env = FakeEnv(
            vars = mapOf("ANDROID_HOME" to "E:\\Sdk"),
            files = setOf("E:\\Sdk\\platform-tools\\adb.exe"),
        )
        assertEquals("E:\\Sdk\\platform-tools\\adb.exe", bridge(env).executable())
    }

    // ── T3 ANDROID_SDK_ROOT（ANDROID_HOME 为空）──────────────────────────

    @Test
    fun `T3 ANDROID_HOME 为空时回落 ANDROID_SDK_ROOT`() {
        val env = FakeEnv(
            vars = mapOf("ANDROID_HOME" to "  ", "ANDROID_SDK_ROOT" to "F:\\sdk-root"),
            files = setOf("F:\\sdk-root\\platform-tools\\adb.exe"),
        )
        assertEquals("F:\\sdk-root\\platform-tools\\adb.exe", bridge(env).executable())
    }

    // ── T4 PATH ─────────────────────────────────────────────────────────

    @Test
    fun `T4 环境变量全空时从 PATH 命中`() {
        val env = FakeEnv(
            vars = mapOf("PATH" to "C:\\Windows\\system32;C:\\tools\\platform-tools"),
            files = setOf("C:\\tools\\platform-tools\\adb.exe"),
        )
        assertEquals("C:\\tools\\platform-tools\\adb.exe", bridge(env).executable())
    }

    // ── T5 盘符扫描（Q4-B 核心）──────────────────────────────────────────

    @Test
    fun `T5 环境变量与 PATH 都空时扫到 D 盘 SDK`() {
        val env = FakeEnv(files = setOf("D:\\Android\\sdk\\platform-tools\\adb.exe"))
        assertEquals("D:\\Android\\sdk\\platform-tools\\adb.exe", bridge(env).executable())
    }

    // ── T6 非 Windows 跳过盘符扫描 ───────────────────────────────────────

    @Test
    fun `T6 非 Windows 不扫盘符`() {
        val env = FakeEnv(
            files = setOf(
                "C:\\Android\\sdk\\platform-tools\\adb.exe",
                "D:\\Android\\sdk\\platform-tools\\adb.exe",
            ),
            os = "Linux",
        )
        val found = bridge(env).executable()
        assertNotEquals("D:\\Android\\sdk\\platform-tools\\adb.exe", found)
        // Linux 上兜底名是裸 adb（不带 .exe），这里同时钉住跳过盘符后的落点。
        assertEquals("adb", found)
    }

    // ── T7 全无 → 明确异常 ───────────────────────────────────────────────

    @Test
    fun `T7 全无时 requireExecutable 抛含 Android SDK 的异常`() {
        val env = FakeEnv()
        val error = assertThrows(IllegalStateException::class.java) {
            bridge(env).requireExecutable()
        }
        assertTrue(error.message.orEmpty().contains("Android SDK"), "异常消息应指明 SDK 缺失")
    }

    // ── T8 提示文案形态 ─────────────────────────────────────────────────

    @Test
    fun `T8 提示文案含三个排查步骤`() {
        val hint = AdbBridge.ADB_NOT_FOUND_HINT
        for (mark in listOf("①", "②", "③")) {
            assertTrue(hint.contains(mark), "提示应包含排查步骤 $mark")
        }
    }
}
