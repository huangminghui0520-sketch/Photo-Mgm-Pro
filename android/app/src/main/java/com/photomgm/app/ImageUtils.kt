// app/ImageUtils.kt —— 导出Excel 2.0 方案 §3.4 图片处理
// content:// Uri 或本地路径 → 解码（采样降内存）→ 4:3 中心裁剪 → 缩放 400×300 → JPEG 质量 50%。
// 裁剪几何计算复用 algorithm.ImageCrop（可移植、可单测）。
package com.photomgm.app

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import algorithm.ImageCrop
import java.io.ByteArrayOutputStream
import java.io.File

object ImageUtils {
    /** 目标缩略图宽度（px，导出Excel 2.0 方案） */
    const val TARGET_WIDTH = 400
    /** JPEG 压缩质量（导出Excel 2.0 方案） */
    const val JPEG_QUALITY = 50

    /** 目标高度 = 400 × 3/4 = 300（4:3 比例，导出 Excel 宽 3.8cm） */
    val TARGET_HEIGHT: Int = (TARGET_WIDTH * ImageCrop.RATIO_H / ImageCrop.RATIO_W).toInt()

    /**
     * 从引用（content:// 或本地物理路径）解码 Bitmap。
     * 先读边界、按目标尺寸 2 倍超采样（inSampleSize），控制峰值内存（大图不全尺寸解码）。
     * ★ 分区存储兜底：Android 10+ 上 MediaStore 返回的 DATA 物理路径无法直接 decodeFile，
     *   需通过 MediaStore content URI 读取；decodeFile 失败时自动回退到 MediaStore 查询。
     *
     * ★ Bitmap 所有权契约（2026-10-31 复核，防范 use-after-recycle）：
     *   返回值是**本函数新建**的 Bitmap，**所有权归调用方**，调用方用毕必须自行 `recycle()`。
     *   本函数不缓存、不复用、不回收返回值，因此调用方持有期间不可能被内部回收。
     *   ⚠ 不得把 [loadBitmap] 的返回值再交给 [process] —— [process] 内部自行解码并**接管**
     *   它自己那一份 Bitmap；两者是不同对象，混用会造成重复回收或误回收调用方的对象。
     */
    fun loadBitmap(context: Context, ref: String): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        decode(context, ref, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            Log.w("PM_Image", "bounds invalid, return null")
            return null
        }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= TARGET_WIDTH * 2 &&
            bounds.outHeight / (sample * 2) >= TARGET_HEIGHT * 2
        ) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        decode(context, ref, opts)
    }.getOrElse { e ->
        Log.e("PM_Image", "loadBitmap exception: ${e.message}", e)
        null
    }

    private fun decode(context: Context, ref: String, opts: BitmapFactory.Options): Bitmap? {
        if (ref.startsWith("content://")) {
            return context.contentResolver.openInputStream(Uri.parse(ref))
                ?.use { BitmapFactory.decodeStream(it, null, opts) }
        }
        // 物理路径：先尝试直接 decodeFile（旧版 / App 自有文件）
        BitmapFactory.decodeFile(ref, opts)?.let { return it }
        // ★ 分区存储兜底：通过 MediaStore 查 DATA 匹配的记录，拿 content URI 读取
        return decodeFromMediaStore(context, ref, opts)
    }

    /** 通过 MediaStore content URI 读取物理路径对应的照片（Android 10+ 分区存储必需）。 */
    private fun decodeFromMediaStore(context: Context, path: String, opts: BitmapFactory.Options): Bitmap? =
        runCatching {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID),
                MediaStore.Images.Media.DATA + "=?",
                arrayOf(path), null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    val id = c.getLong(0)
                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                    )
                    context.contentResolver.openInputStream(contentUri)
                        ?.use { BitmapFactory.decodeStream(it, null, opts) }
                } else null
            }
        }.getOrNull()

    /**
     * ★ 分层解码（2026-10-31 W1：WebP / HEIC 转码层）。
     *   1) API 28+ 走 [ImageDecoder]：统一支持 JPEG/PNG/WebP/HEIC（系统 HEIF 解码器），
     *      且可直接按目标尺寸解码（setTargetSize），不必先读边界再算采样率，内存更可控。
     *   2) 低版本、或 ImageDecoder 抛 DecodeException（含 Robolectric 未实现该 API）
     *      → 回退 [loadBitmap] 的 BitmapFactory 采样路径。
     *   3) 两条都失败 → 返回 null（调用方按"该照片单元格仅保留文件名"处理，绝不产出损坏字节）。
     *      ★ HEIC 在 API<28 无系统解码器，属平台限制，只能退化为文件名——这是"最后一层兜底"。
     */
    private fun decodeForThumbnail(context: Context, ref: String): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val src = when {
                ref.startsWith("content://") -> runCatching {
                    ImageDecoder.createSource(context.contentResolver, Uri.parse(ref))
                }.getOrNull()
                ref.startsWith("file:") -> runCatching {
                    ImageDecoder.createSource(context.contentResolver, Uri.parse(ref))
                }.getOrNull()
                else -> runCatching { ImageDecoder.createSource(File(ref)) }.getOrNull()
            }
            if (src != null) {
                val decoded = runCatching {
                    // setTargetSize：解码结果"装入"该矩形（等比），避免全尺寸解码的峰值内存
                    ImageDecoder.decodeBitmap(src) { decoder, _, _ ->
                        decoder.setTargetSize(TARGET_WIDTH * 2, TARGET_HEIGHT * 2)
                        // ★ 必须显式指定软件位图：默认分配器在部分机型/HEIC 上会给出
                        //   HARDWARE 位图，而 Bitmap.createBitmap(source…)/compress 只接受软件位图，
                        //   会在 centerCrop4to3 之后的 createBitmap 处抛 IllegalArgumentException。
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                }.getOrElse { e ->
                    Log.w("PM_Image", "ImageDecoder 失败，回退 BitmapFactory: ${e.message}")
                    null
                }
                if (decoded != null) return decoded
            }
        }
        return loadBitmap(context, ref)
    }

    /**
     * 处理单张照片：解码 → 4:3 中心裁剪 → 400×300 → JPEG50 → 字节；任一步失败返回 null（该照片单元格留空）。
     *
     * ★ Bitmap 所有权契约（2026-10-31 复核，本轮修复引入新 bug 风险最高的一处）：
     *   本函数**接管**内部解码出的 Bitmap（[decodeForThumbnail] 的返回值），并在**所有出口**
     *   （成功 / 裁剪矩形为空 / createBitmap 失败 / OOM）通过 try-finally 回收，**只回收自己那一份**。
     *   调用方拿到的只是独立的 [ByteArray]，与任何 Bitmap 无引用关系 → **不存在 use-after-recycle**。
     *   ⚠ 本函数不接受 Bitmap 入参正是出于此：所有权唯一，无需调用方判断该不该回收。
     */
    fun process(context: Context, ref: String): ByteArray? {
        val bmp = decodeForThumbnail(context, ref)
        if (bmp == null) { Log.w("PM_Image", "process: bmp null, ref=$ref"); return null }
        return processBitmap(bmp)
    }

    /**
     * ★ 裁剪 → 缩放 → 压缩，并**接管入参 Bitmap 的所有权**（在所有出口回收）。
     *
     * 抽出为独立函数的唯一理由：让 [process] 的所有权契约可以被单测直接验证
     * （Robolectric 下注入一个可控 Bitmap，断言它在"裁剪失败"等分支也被 recycle）。
     * 判据若只能靠"读代码确认 finally 写了"，就会在下一次改结构时被悄悄破坏。
     *
     * ⚠ 调用方契约：传入后**不得再使用**该 Bitmap（本函数结束时它已被回收）；
     *   返回值是独立的 [ByteArray]，与 Bitmap 无任何引用关系。
     */
    internal fun processBitmap(bmp: Bitmap): ByteArray? {
        // ★ 2026-10-31 审查修复：下面任一步提前返回都会漏掉 bmp.recycle()（原实现只在正常路径回收）。
        //   导出是逐张循环调用本函数，累积未回收的原图 Bitmap 可达数百 MB → OOM。
        //   用 try/finally 保证任何出口（含裁剪失败、createBitmap 返回 null）都回收。
        return try {
            val rect = ImageCrop.centerCrop4to3(bmp.width, bmp.height)
            if (rect == null) {
                Log.w("PM_Image", "process: crop rect null")
                null
            } else {
                val cropped = runCatching {
                    Bitmap.createBitmap(bmp, rect.left, rect.top, rect.width, rect.height)
                }.getOrNull()
                if (cropped == null) {
                    Log.w("PM_Image", "process: crop failed")
                    null
                } else {
                    try {
                        val scaled = Bitmap.createScaledBitmap(cropped, TARGET_WIDTH, TARGET_HEIGHT, true)
                        val out = ByteArrayOutputStream()
                        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                        // createScaledBitmap 在尺寸恰好相同时会返回同一对象，回收前先判断
                        if (scaled !== cropped) scaled.recycle()
                        out.toByteArray()
                    } finally {
                        cropped.recycle()
                    }
                }
            }
        } finally {
            bmp.recycle()
        }
    }
}
