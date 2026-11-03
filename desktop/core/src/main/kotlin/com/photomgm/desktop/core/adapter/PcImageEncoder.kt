// desktop/core/adapter/PcImageEncoder.kt —— 台账嵌图端口的桌面实现
//
// 薄到只剩一行，但值得单独存在：它把"共享层问要什么"和"桌面怎么变出字节"隔开。
// 规格（4:3 中心裁剪 → 400×300 → JPEG 50%）在 `algorithm.MediaSpec`（T04），
// 解码/缩放/编码的手段在 [PcImageEmbedder]（AWT ImageIO）。
// 端口这一层既不写规格、也不写手段，只负责调用。
package com.photomgm.desktop.core.adapter

import app.port.ImageEncoder
import com.photomgm.desktop.core.platform.PcImageEmbedder

internal class PcImageEncoder(
    private val embedder: PcImageEmbedder = PcImageEmbedder(),
) : ImageEncoder {

    /** 编码失败返回 null（台账该格只写文件名），**不抛异常** —— 抛了会整张台账丢失。 */
    override fun encode(sourceRef: String): ByteArray? = embedder.embed(sourceRef)
}
