// desktop/core/adapter/PcFileBytesReader.kt —— 「按引用读一段字节」的桌面实现
//
// 用途：用户拖入/选择一份 .txt 日志时把它读进来。解码（UTF-8 优先、BOM 剥离、回落 GBK）
// 由共享层的 `app.infra.decodeText` 做 —— 这是业务约定，两端必须一致：
// 内业同事用记事本另存的日志常是 GBK，若本类直接返回字符串，解码规则就落到平台层，
// 迟早只有一端支持 GBK（表现为"同一份日志 PC 能解析、安卓全变问号"）。
package com.photomgm.desktop.core.adapter

import app.port.FileBytesReader
import com.photomgm.desktop.core.platform.PcPaths
import java.nio.file.Files

internal class PcFileBytesReader : FileBytesReader {

    /**
     * 读全部字节；读不到返回 null，**不抛异常**（读不到的原因由调用方组织成用户提示）。
     *
     * ★ 用 [PcPaths.toPath] 而不是裸 `Paths.get`：超长/含非法字符的路径在 Windows 上会抛
     *   `InvalidPathException`，这里连同"文件不存在"一起收成 null，
     *   调用方只需一句"无法读取日志文件"而不必分别处理两种异常。
     */
    override fun readBytes(ref: String): ByteArray? =
        runCatching { Files.readAllBytes(PcPaths.toPath(ref)) }.getOrNull()
}
