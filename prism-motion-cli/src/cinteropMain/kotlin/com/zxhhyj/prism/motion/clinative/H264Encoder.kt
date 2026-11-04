package com.zxhhyj.prism.motion.clinative

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import prismcodec.prism_enc_create
import prismcodec.prism_enc_destroy
import prismcodec.prism_enc_encode

/**
 * OpenH264 编码：C 封装返回 Annex-B 字节流，这里拆成 NAL 并做 MP4 需要的长度前缀。
 * SPS/PPS 单独抽出来写进 avcC，不进样本数据。
 */
@OptIn(ExperimentalForeignApi::class)
internal actual object H264Encoder {

    actual fun encode(width: Int, height: Int, fps: Int, bitrateKbps: Int, frames: List<Frame>): H264Stream {
        require(frames.isNotEmpty()) { "没有可编码的帧" }
        val encoder = prism_enc_create(width, height, fps, bitrateKbps)
            ?: error("OpenH264 初始化失败（${width}x$height @${fps}fps）")
        try {
            val capacity = maxOf(width * height * 2, 1 shl 16)
            val buffer = ByteArray(capacity)
            val samples = ArrayList<ByteArray>(frames.size)
            val keyFrames = LinkedHashSet<Int>()
            var sps: ByteArray? = null
            var pps: ByteArray? = null

            memScoped {
                val keyFlag = alloc<IntVar>()
                for ((index, frame) in frames.withIndex()) {
                    val size = frame.rgba.usePinned { source ->
                        buffer.usePinned { target ->
                            prism_enc_encode(
                                encoder,
                                source.addressOf(0).reinterpret(),
                                target.addressOf(0).reinterpret(),
                                capacity,
                                keyFlag.ptr,
                            )
                        }
                    }
                    if (size < 0) error("第 $index 帧编码失败")
                    if (size == 0) continue

                    val nals = splitAnnexB(buffer.copyOf(size))
                    val sampleBytes = ArrayList<ByteArray>()
                    for (nal in nals) {
                        val type = nal[0].toInt() and 0x1F
                        if (type == 7) {
                            if (sps == null) sps = nal
                            continue
                        }
                        if (type == 8) {
                            if (pps == null) pps = nal
                            continue
                        }
                        sampleBytes += nal
                    }
                    if (sampleBytes.isEmpty()) continue
                    var total = 0
                    for (nal in sampleBytes) total += 4 + nal.size
                    val out = ByteArray(total)
                    var offset = 0
                    for (nal in sampleBytes) {
                        out[offset] = (nal.size ushr 24).toByte()
                        out[offset + 1] = (nal.size ushr 16).toByte()
                        out[offset + 2] = (nal.size ushr 8).toByte()
                        out[offset + 3] = nal.size.toByte()
                        nal.copyInto(out, offset + 4)
                        offset += 4 + nal.size
                    }
                    if (keyFlag.ptr[0] != 0) keyFrames += samples.size
                    samples += out
                }
            }

            val spsBytes = sps ?: error("没有收到 SPS")
            val ppsBytes = pps ?: error("没有收到 PPS")
            return H264Stream(spsBytes, ppsBytes, samples, keyFrames)
        } finally {
            prism_enc_destroy(encoder)
        }
    }

    /** 按 00 00 01 / 00 00 00 01 切出裸 NAL（不含起始码）。 */
    private fun splitAnnexB(bytes: ByteArray): List<ByteArray> {
        val starts = ArrayList<IntArray>()
        var i = 0
        while (i + 3 <= bytes.size) {
            if (bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte()) {
                if (bytes[i + 2] == 1.toByte()) {
                    starts += intArrayOf(i, i + 3)
                    i += 3
                    continue
                }
                if (i + 3 < bytes.size && bytes[i + 2] == 0.toByte() && bytes[i + 3] == 1.toByte()) {
                    starts += intArrayOf(i, i + 4)
                    i += 4
                    continue
                }
            }
            i++
        }
        val result = ArrayList<ByteArray>()
        for ((index, start) in starts.withIndex()) {
            val end = if (index + 1 < starts.size) starts[index + 1][0] else bytes.size
            if (end > start[1]) result += bytes.copyOfRange(start[1], end)
        }
        return result
    }
}
