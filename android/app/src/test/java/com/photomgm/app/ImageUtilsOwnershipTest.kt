// app/ImageUtilsOwnershipTest.kt —— S9「Bitmap 所有权」回归测试
//
// 由来：ImageUtils.process 会在**内部**解码一张 Bitmap 并在所有出口回收它。这个契约此前
// 只能靠读代码确认——2026-10-31 审查时发现原实现的两条提前返回路径（裁剪矩形为空 /
// createBitmap 返回 null）漏了 recycle()，导出时逐张累积可达数百 MB。修完若没有测试，
// 下一次重构（比如把裁剪抽成函数）会原样复现。
//
// 因此这里把"所有权"变成可断言的：Robolectric LEGACY 图形模式下位图由 ShadowBitmap 托管，
// 可以①读取 isRecycled()；②改写宽高以注入"裁剪失败"这类平时难以触发的分支。
package com.photomgm.app

import android.graphics.Bitmap
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
// ★ 必须是 LEGACY 图形模式：NATIVE 模式下 Bitmap 是真实的 native 对象，
//   既不提供可观测的 isRecycled 影子状态，也无法注入"宽高非法"这类裁剪失败前提。
//   （Robolectric 4.12 的 @Config 没有 graphicsMode 属性，图形模式由本注解单独指定。）
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class ImageUtilsOwnershipTest {

    /**
     * 造一张指定宽高的影子位图。
     *
     * LEGACY 模式下 [Bitmap.getWidth] 由 [ShadowLegacyBitmap] 提供，因此**任意宽高**
     * （含非法的 0）都可以注入——这正是构造"裁剪失败"分支所需的输入。
     * setWidth/setHeight 是 protected（Robolectric 只打算给同包的 shadow 用），
     * 测试侧通过反射调用；若哪天 Robolectric 改了签名，这里会以 NoSuchMethodException 显式失败，
     * 不会退化成"用例悄悄失去区分度"。
     */
    private fun bitmap(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val shadow = shadowOf(bmp)
        fun set(name: String, v: Int) {
            val m = shadow.javaClass.getDeclaredMethod(name, Int::class.javaPrimitiveType)
            m.isAccessible = true
            m.invoke(shadow, v)
        }
        set("setWidth", w)
        set("setHeight", h)
        return bmp
    }

    /**
     * 正常路径：400×300 恰好 4:3 → 应产出字节。
     *
     * ★ 这里**故意不断言** `bmp.isRecycled`（2026-10-31 复核后移除）。
     *   理由：4:3 全幅裁剪时 `Bitmap.createBitmap(src, 0, 0, w, h)` 在 Robolectric LEGACY 下
     *   返回的就是入参本身（cropped === bmp），而正常出口**在修复前就已经回收**了 bmp
     *   —— 失败证明（去掉 finally 里的 recycle）显示本用例**照样通过**，对 S9 零区分度。
     *   留着它只会让人误以为"回收已被覆盖"，实际兜住回收的是下面两条失败分支用例。
     *   本条的价值在另一处：钉住 4:3 输入确实能产出字节（缩放/压缩链路没被改坏）。
     */
    @Test
    fun `正常路径_产出JPEG字节`() {
        val bmp = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
        val out = ImageUtils.processBitmap(bmp)
        assertNotNull("400×300 恰好 4:3，应产出 JPEG 字节", out)
    }

    /**
     * ★ 裁剪失败路径（本轮修复真正要兜住的那一条）：宽高非法 → ImageCrop.centerCrop4to3
     *   返回 null → process 返回 null（该照片单元格只留文件名），但**原图仍必须被回收**。
     *   修复前的实现在这个分支直接 return null，漏掉 recycle()。
     */
    @Test
    fun `裁剪失败_返回null且原图仍被回收`() {
        val bmp = bitmap(0, 0)
        val out = ImageUtils.processBitmap(bmp)
        assertTrue("裁剪矩形为空时应返回 null（该照片单元格只保留文件名）", out == null)
        assertTrue(
            "裁剪失败分支也必须回收原图——逐张导出时漏一张就是几十 MB",
            bmp.isRecycled,
        )
    }

    /**
     * ★ 裁剪阶段抛异常的分支（原实现漏掉 recycle() 的第二条路径）。
     *
     *   注入方式：位图**声明** 1000×800（裁剪矩形算出 (0,25,1000,750)），但底层光栅只有 1×1，
     *   `Bitmap.createBitmap(source, …)` 因此抛 RasterFormatException。
     *   真实世界的同构场景：HEIC 解出 HARDWARE 位图、或位图在低内存下已被回收
     *   —— 都是"前半程好好的、到裁剪这一步炸"。
     *
     *   要求：异常**不得外泄**（导出是逐张循环，抛一次整批失败），按"该照片留空"返回 null，
     *   并且原图必须照常回收。
     */
    @Test
    fun `裁剪阶段抛异常_异常不外泄_返回null且原图仍被回收`() {
        val bmp = bitmap(1000, 800)
        val out = ImageUtils.processBitmap(bmp)   // 若此处抛异常，用例即失败
        assertTrue("裁剪抛异常时应按『该照片留空』处理（不产出损坏字节）", out == null)
        assertTrue(
            "异常出口也必须回收原图——这是 try/finally 存在的全部意义",
            bmp.isRecycled,
        )
    }

    /** 补充：非 4:3 的图也要正常走完（真实光栅，验证裁剪→缩放→回收整条链路）。 */
    @Test
    fun `非4比3图_同样产出字节且原图被回收`() {
        val bmp = Bitmap.createBitmap(1000, 800, Bitmap.Config.ARGB_8888)
        val out = ImageUtils.processBitmap(bmp)
        assertNotNull("1000×800（比例 1.25 < 4:3）应裁成 1000×750 再缩放，产出字节", out)
        assertTrue("原图必须已回收", bmp.isRecycled)
    }
}
