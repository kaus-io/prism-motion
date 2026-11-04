package com.zxhhyj.prism.motion.clinative

import com.zxhhyj.prism.motion.format.Bytes
/** 一次 H.264 编码的产物：参数集 + 长度前缀化的 NAL 样本。 / Encoded H.264: parameter sets plus length-prefixed samples. */
internal class AvcStream(
    public val sps: ByteArray,
    public val pps: ByteArray,
    public val samples: List<ByteArray>,
    public val keyFrameIndices: Set<Int>,
)
