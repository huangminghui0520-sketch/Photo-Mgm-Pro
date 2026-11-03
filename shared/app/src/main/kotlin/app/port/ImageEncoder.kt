// shared/app/port/ImageEncoder.kt —— 台账嵌图编码端口
//
// 台账 xlsx 的照片列里嵌的是**缩略图字节**。两端解码与重编码的手段完全不同
// （桌面 ImageIO / 安卓 Bitmap.compress），但规格必须一致 —— 规格已收在
// `algorithm.MediaSpec`（T04），这里只留「把一张照片编码成字节」这一步。
//
// ★ 不要把它命名为 `encodeToPng`：桌面端产出 JPEG，安卓端也是 JPEG
//   （`MediaSpec.JPEG_QUALITY_*`）。返回的字节由 `XlsxWriter` 按**魔数**判定格式，
//   认不出的（WebP/HEIC/GIF）一律退化为「单元格只写文件名」。
//   名字里写死格式会诱导实现方去转码，而转码不是本端口的职责。
//
// ★ 返回值允许为 null（读不到/解码失败/格式不支持）：台账仍然要能生成，
//   缺图的那一格写文件名即可。抛异常会让整张台账丢失 —— 那是更坏的降级。
package app.port

/** 台账嵌图编码（平台实现由装配根注入）。 */
fun interface ImageEncoder {
    /**
     * 把 [sourceRef] 指向的照片编码为可嵌入 xlsx 的字节（JPEG/PNG）。
     *
     * @param sourceRef 源照片引用（桌面=物理路径；安卓=content URI）。实现方自行处理
     *   平台读取差异（例如安卓 10+ 的分区存储要走 MediaStore URI）。
     * @return 编码后的字节；读不到或无法编码时返回 null（不抛异常）。
     */
    fun encode(sourceRef: String): ByteArray?
}
