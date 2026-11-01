// 照片 id 分配单测：必须证明「稳定」+「1 万条不撞」+「真撞了能探测且不丢照片」
package com.photomgm.desktop.core

import com.photomgm.desktop.core.platform.PhotoIdAllocator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhotoIdAllocatorTest {

    @Test
    fun `同一路径多次分配得到同一 id`() {
        val allocator = PhotoIdAllocator()
        val ref = "D:/photos/IMG_001.jpg"
        val first = allocator.allocate(ref)
        val second = allocator.allocate(ref)
        assertEquals(first, second)
        assertEquals(1, allocator.size)
        assertTrue(first > 0)
    }

    @Test
    fun `一万条真实路径无碰撞`() {
        val allocator = PhotoIdAllocator()
        val ids = HashSet<Long>()
        for (i in 0 until 10_000) {
            // 混合中文/空格/长路径，贴近真实场景
            val ref = "D:/巡查照片/2026-08-14 第${i % 37}组/IMG_20260814_1015${i.toString().padStart(2, '0')}.jpg"
            ids += allocator.allocate(ref)
        }
        assertEquals(10_000, ids.size, "1 万条路径必须得到 1 万个不同 id")
        assertEquals(0, allocator.collisions, "真实 SHA-256 前 8 字节不应出现碰撞")
    }

    @Test
    fun `人为构造碰撞时线性探测生效且两张照片都保留`() {
        // 注入常量哈希：所有路径都落在同一个 id → 必触发探测
        val allocator = PhotoIdAllocator(hash = { 42L })
        val a = allocator.allocate("D:/photos/IMG_1.jpg")
        val b = allocator.allocate("D:/photos/IMG_2.jpg")
        val c = allocator.allocate("D:/photos/IMG_3.jpg")

        assertEquals(42L, a)
        assertEquals(43L, b)
        assertEquals(44L, c)
        assertEquals(2, allocator.collisions)
        assertEquals(3, allocator.size)
        assertEquals(2, allocator.collisionNotes.size)
        // 关键：三张照片 id 互不相同 → 核心 dedupById 不会把任何一张当重复丢掉
        assertEquals(3, setOf(a, b, c).size)
    }

    @Test
    fun `id 恒为正数`() {
        // dedupById 只对 id>0 去重，id=0 会被当作"无 id"全保留 → 必须保证 >0
        val allocator = PhotoIdAllocator()
        val samples = listOf("", "D:/a.jpg", "中文/照片.jpg", "\\\\?\\D:\\x\\y.jpg")
        samples.forEach { assertTrue(allocator.allocate(it) > 0, "路径 $it 的 id 必须 > 0") }
    }

    @Test
    fun `纯函数哈希对同一输入稳定`() {
        val ref = "D:/photos/IMG_20260814_101530.jpg"
        assertEquals(
            PhotoIdAllocator.hashToPositiveId(ref),
            PhotoIdAllocator.hashToPositiveId(ref),
        )
        assertTrue(PhotoIdAllocator.hashToPositiveId(ref) > 0)
    }
}
