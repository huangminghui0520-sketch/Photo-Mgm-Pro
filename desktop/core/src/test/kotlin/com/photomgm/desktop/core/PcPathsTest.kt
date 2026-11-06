// 路径规范化单测：这是"派生去重不失效"和"来源去重不误删"的前提
//
// ★ 2026-11-06（方向 1 第二步）：normalize 改为字符串级平台无关实现。
//   下面这份矩阵必须在 Windows 与 Linux 上给出**完全相同**的结果：
//   sourceRef 既是 shared/algorithm/.../PhotoSource.kt:142 的去重分组键输入，
//   又经 PcPaths.toPath() 用于 Files.readAllBytes / newInputStream 的真实文件读取。
//   规范化写错会让两张不同照片被合并、或同一张被拆开（静默错误，比 CI 红更严重）。
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
    fun `盘符反斜杠与正斜杠写法等价`() {
        assertEquals("D:/a/c/IMG.jpg", PcPaths.normalize("""D:\a\c\IMG.jpg"""))
        assertEquals(
            PcPaths.normalize("""D:\a\c\IMG.jpg"""),
            PcPaths.normalize("D:/a/c/IMG.jpg")
        )
    }

    @Test
    fun `幂等`() {
        val once = PcPaths.normalize("""D:\a\b\..\c\IMG.jpg""")
        assertEquals(once, PcPaths.normalize(once))
        assertEquals("D:/a/c/IMG.jpg", once)
    }

    @Test
    fun `UNC 路径保留双斜杠且消解点点`() {
        assertEquals("//server/share/x.jpg", PcPaths.normalize("""\\server\share\x.jpg"""))
        assertEquals("//server/share/x.jpg", PcPaths.normalize("""\\server\share\a\..\x.jpg"""))
        assertEquals("//server/share/x.jpg", PcPaths.normalize("//server/share/x.jpg"))
    }

    @Test
    fun `混合与重复分隔符折叠`() {
        assertEquals("D:/a/b/d/IMG.jpg", PcPaths.normalize("""D:\a/b\\c/../d/IMG.jpg"""))
        assertEquals("D:/a/b/IMG.jpg", PcPaths.normalize("D:///a//b///IMG.jpg"))
        assertEquals("D:/a/b/IMG.jpg", PcPaths.normalize("""D:\a\.\b\IMG.jpg"""))
    }

    @Test
    fun `POSIX 绝对路径平台无关`() {
        assertEquals("/home/u/b/x.jpg", PcPaths.normalize("/home/u/a/../b/x.jpg"))
        assertEquals("/home/u/b/x.jpg", PcPaths.normalize("/home//u/./a/../b/x.jpg"))
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
        assertEquals("d:/photos/img.jpg", PcPaths.key("""D:\Photos\IMG.JPG"""))
    }

    @Test
    fun `空串安全`() {
        assertEquals("", PcPaths.normalize("   "))
        assertEquals("", PcPaths.normalize(""))
        assertTrue(PcPaths.normalize("相对路径.jpg").isNotBlank())
    }

    @Test
    fun `相对路径解析为绝对路径且幂等`() {
        val n = PcPaths.normalize("相对路径.jpg")
        assertTrue(
            n.startsWith("/") || (n.length >= 2 && n[1] == ':'),
            "相对路径应解析为绝对路径，实际：$n"
        )
        assertEquals(n, PcPaths.normalize(n))
    }

    @Test
    fun `全形式幂等矩阵`() {
        val refs = listOf(
            """D:\a\c\IMG.jpg""",
            "D:/a/c/IMG.jpg",
            """\\server\share\x.jpg""",
            "/home/u/a/x.jpg",
            "D:/a/./b/../c.jpg",
            "相对路径.jpg",
        )
        refs.forEach { r ->
            val once = PcPaths.normalize(r)
            assertEquals(once, PcPaths.normalize(once), "幂等失败：$r -> $once")
        }
    }

    @Test
    fun `盘符根与 POSIX 根不被点点越界`() {
        assertEquals("D:/x.jpg", PcPaths.normalize("""D:\..\x.jpg"""))
        assertEquals("/x.jpg", PcPaths.normalize("/../x.jpg"))
    }

    @Test
    fun `盘符相对写法补齐斜杠`() {
        assertEquals("D:/a/x.jpg", PcPaths.normalize("""D:a\x.jpg"""))
    }
}
