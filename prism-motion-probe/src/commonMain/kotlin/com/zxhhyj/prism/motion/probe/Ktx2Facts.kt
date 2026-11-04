package com.zxhhyj.prism.motion.probe

import com.zxhhyj.prism.motion.format.Bytes
import com.zxhhyj.prism.motion.format.PrismAlphaMode
import com.zxhhyj.prism.motion.ktx.KtxFormat
import com.zxhhyj.prism.motion.ktx.KtxReader

/** KTX2 事实；不创建按帧大小的副本。 / KTX2 facts without allocating per-frame copies. */
internal class KtxFacts(
    val width: Int?, val height: Int?, val frameCount: Int?, val fps: Int?,
    val hasAlpha: Boolean?, val alphaMode: PrismAlphaMode?,
    val metadata: Map<String, String>, val issues: List<ProbeIssue>,
)

/** 对受支持的单层级 KTX2 子集执行有界解析。 / Bounded parser for the supported single-level KTX2 subset. */
internal object Ktx2Probe {
    fun probe(bytes: ByteArray): KtxFacts = try {
        read(bytes)
    } catch (failure: IllegalArgumentException) {
        KtxFacts(null, null, null, null, null, null, emptyMap(),
            listOf(ProbeIssue(ProbeSeverity.Error, "bad-ktx", failure.message ?: "Invalid KTX2 payload")))
    }

    private fun read(bytes: ByteArray): KtxFacts {
        require(bytes.size >= 104 && KtxReader.isKtx(bytes)) { "Truncated KTX2 header" }
        fun int(offset: Int): Int {
            val value = Bytes.u32le(bytes, offset)
            require(value <= Int.MAX_VALUE) { "KTX2 integer exceeds Int range" }
            return value.toInt()
        }
        fun range(offset: Long, length: Long) {
            require(offset >= 0 && length >= 0 && offset <= bytes.size && length <= bytes.size - offset) {
                "KTX2 byte range exceeds payload"
            }
        }
        val format = KtxFormat.entries.firstOrNull { it.vkFormat.toLong() == Bytes.u32le(bytes, 12) }
            ?: throw IllegalArgumentException("Unsupported KTX2 pixel format")
        val width = int(20)
        val height = int(24)
        val frames = int(32)
        require(width > 0 && height > 0 && frames > 0) { "KTX2 dimensions and layer count must be positive" }
        require(int(16) == 1 && int(28) == 0 && int(36) == 1 && int(40) == 1 && int(44) == 0) {
            "Unsupported KTX2 dimensionality, mip levels or supercompression"
        }
        range(Bytes.u32le(bytes, 48), Bytes.u32le(bytes, 52))
        range(Bytes.u64le(bytes, 64), Bytes.u64le(bytes, 72))
        val kvOffset = Bytes.u32le(bytes, 56)
        val kvLength = Bytes.u32le(bytes, 60)
        range(kvOffset, kvLength)
        val levelOffset = Bytes.u64le(bytes, 80)
        val levelLength = Bytes.u64le(bytes, 88)
        range(levelOffset, levelLength)
        require(levelOffset >= 104 && levelLength > 0 && Bytes.u64le(bytes, 96) == levelLength) {
            "Invalid KTX2 level range or uncompressed length"
        }
        var expectedLength = 1L
        for (factor in listOf(
            (width.toLong() + format.blockW - 1) / format.blockW,
            (height.toLong() + format.blockH - 1) / format.blockH,
            format.blockBytes.toLong(), frames.toLong(),
        )) {
            require(factor > 0 && expectedLength <= bytes.size.toLong() / factor) { "KTX2 layer size exceeds payload" }
            expectedLength *= factor
        }
        require(levelLength == expectedLength) { "KTX2 level length does not match dimensions and frames" }
        val keyValues = LinkedHashMap<String, String>()
        var position = kvOffset.toInt()
        val end = (kvOffset + kvLength).toInt()
        while (position < end) {
            require(end - position >= 4) { "Truncated KTX2 key/value length" }
            val length = Bytes.u32le(bytes, position)
            position += 4
            require(length > 0 && length <= end - position) { "Invalid KTX2 key/value length" }
            val entryEnd = position + length.toInt()
            var separator = position
            while (separator < entryEnd && bytes[separator] != 0.toByte()) separator++
            require(separator > position && separator < entryEnd) { "Invalid KTX2 key/value separator" }
            val key = bytes.decodeToString(position, separator)
            val value = bytes.decodeToString(separator + 1, entryEnd).trimEnd('\u0000')
            require(key !in keyValues) { "Duplicate KTX2 metadata key" }
            keyValues[key] = value
            val padding = ((4L - length % 4L) % 4L).toInt()
            require(padding <= end - entryEnd) { "Truncated KTX2 key/value padding" }
            position = entryEnd + padding
        }
        val fps = keyValues["anim.fps"]?.let {
            it.toIntOrNull()?.takeIf { value -> value > 0 } ?: throw IllegalArgumentException("Invalid native anim.fps")
        }
        val premultiplied = keyValues["anim.premultiplied"]?.let {
            it.toBooleanStrictOrNull() ?: throw IllegalArgumentException("Invalid native anim.premultiplied")
        }
        val hasAlpha = if (format == KtxFormat.RGBA8) {
            var alpha = false
            var offset = levelOffset.toInt() + 3
            val limit = (levelOffset + levelLength).toInt()
            while (offset < limit) {
                if (Bytes.u8(bytes, offset) != 255) alpha = true
                offset += 4
            }
            alpha
        } else null
        val alphaMode = when {
            hasAlpha == false -> PrismAlphaMode.Opaque
            premultiplied == true -> PrismAlphaMode.Premultiplied
            premultiplied == false -> PrismAlphaMode.Straight
            else -> null
        }
        return KtxFacts(width, height, frames, fps, hasAlpha, alphaMode, keyValues,
            if (fps == null) listOf(ProbeIssue(ProbeSeverity.Info, "missing-native-fps", "原生载荷没有帧率，使用外容器 / Native FPS absent; outer metadata is authoritative")) else emptyList())
    }
}
