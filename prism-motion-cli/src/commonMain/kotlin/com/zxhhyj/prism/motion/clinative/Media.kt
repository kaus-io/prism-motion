package com.zxhhyj.prism.motion.clinative

/** 一帧 RGBA 像素；行优先，每像素 4 字节。 */
internal class Frame(val width: Int, val height: Int, val rgba: ByteArray) {
    init {
        require(rgba.size == width * height * 4) { "像素数量与尺寸不符：${rgba.size} != ${width}x$height" }
    }
}

/** 一次 H.264 编码的产物：参数集 + 长度前缀化的 NAL 样本。 */
internal class H264Stream(
    val sps: ByteArray,
    val pps: ByteArray,
    /** 每个样本是若干个 4 字节长度 + NAL 的拼接。 */
    val samples: List<ByteArray>,
    val keyFrameIndices: Set<Int>,
)

/** PNG 解码（stb_image）。 */
internal expect object ImageDecoder {
    fun decodePng(bytes: ByteArray): Frame
}

/** H.264 编码（OpenH264）。 */
internal expect object H264Encoder {
    fun encode(width: Int, height: Int, fps: Int, bitrateKbps: Int, frames: List<Frame>): H264Stream
}
