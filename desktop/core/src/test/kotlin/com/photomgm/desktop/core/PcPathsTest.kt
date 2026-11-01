// 路径规范化单测：这是"派生去重不失效"和"来源去重不误删"的前提
package com.photomgm.desktop.core

import com.photomgm.desktop.core.platform.PcPaths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PcPathsTest {

    @Test
    fun `反斜杠统一为正斜杠`() {
        val n = PcPaths.normalize("""D:\巡查照片\2026-08-14\IMG_001.jpg""")
        assertEquals("D:/巡查照片/2026-08-14/IMG_001.jpg", n)
    }

    @Test
    fun `幂等`() {
        val once = PcPaths.normalize("""D:\a\b\..\c\IMG.jpg""")
        assertEquals(once, PcPaths.normalize(once))
        assertEquals("D:/a/c/IMG.jpg", once)
    }

    @Test
    fun `规范化后同一目录的文件共享目录前缀`() {
        // ★ 核心 dedupDerived 用 substringBeforeLast('/') 取目录，这是它成立的前提
        val a = PcPaths.normalize("""D:\photos\IMG_1.jpg""")
        val b = PcPaths.normalize("D:/photos/IMG_1_original.jpg")
        assertEquals(a.substringBeforeLast('/'), b.substringBeforeLast('/'))
    }

    @Test
    fun `去重键大小写不敏感`() {
        assertEquals(
            PcPaths.key("""D:\Photos\IMG.JPG"""),
            PcPaths.key("d:/photos/img.jpg"),
        )
    }

    @Test
    fun `空串安全`() {
        assertEquals("", PcPaths.normalize("   "))
        assertTrue(PcPaths.normalize("相对路径.jpg").isNotBlank())
    }
}
