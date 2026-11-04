package com.zxhhyj.prism.motion.engine.vap

/** vapc 里描述的一帧布局与时长。 / Layout and timing described by the vapc box. */
internal class VapConfig(
    val width: Int,
    val height: Int,
    val videoWidth: Int,
    val videoHeight: Int,
    val fps: Int,
    val frameCount: Int,
    val hasAlpha: Boolean,
    val rgbX: Int,
    val rgbY: Int,
    val alphaX: Int,
    val alphaY: Int,
    val alphaWidth: Int,
    val alphaHeight: Int,
)

/** 解封装后的 VAP 数据：配置 + Annex-B 形式的 H.264 采样。 / Demuxed VAP: config plus Annex-B samples. */
internal class VapDemuxed(val config: VapConfig, val samples: List<ByteArray>)

/**
 * 最小 MP4 解封装：只处理 `moov/trak/mdia/minf/stbl` 与 `vapc`，输出可解码的样本。
 * Minimal MP4 demuxer: handles stbl tables and the `vapc` box, emits decodable samples.
 */
internal object VapMp4 {

    fun demux(bytes: ByteArray): VapDemuxed {
        val top = boxes(bytes, 0, bytes.size)
        val vapc = top.firstOrNull { it.type == "vapc" } ?: error("不是 VAP：缺少 vapc box")
        val config = parseVapc(bytes.decodeToString(vapc.payload, vapc.end).trim())

        val moov = top.firstOrNull { it.type == "moov" } ?: error("缺少 moov")
        val trak = boxes(bytes, moov.payload, moov.end).firstOrNull { it.type == "trak" }
            ?: error("缺少 trak")
        val mdia = boxes(bytes, trak.payload, trak.end).first { it.type == "mdia" }
        val minf = boxes(bytes, mdia.payload, mdia.end).first { it.type == "minf" }
        val stbl = boxes(bytes, minf.payload, minf.end).first { it.type == "stbl" }
        val tables = boxes(bytes, stbl.payload, stbl.end)

        val sizes = tables.first { it.type == "stsz" }.let { stsz ->
            val count = u32(bytes, stsz.payload + 8)
            val constant = u32(bytes, stsz.payload + 4)
            if (constant != 0) List(count) { constant } else List(count) {
                u32(bytes, stsz.payload + 12 + it * 4)
            }
        }
        val chunkOffsets = tables.first { it.type == "stco" }.let { stco ->
            val count = u32(bytes, stco.payload + 4)
            List(count) { u32(bytes, stco.payload + 8 + it * 4) }
        }
        val stscEntries = tables.first { it.type == "stsc" }.let { stsc ->
            val count = u32(bytes, stsc.payload + 4)
            List(count) {
                val base = stsc.payload + 8 + it * 12
                Triple(u32(bytes, base), u32(bytes, base + 4), u32(bytes, base + 8))
            }
        }
        val nalLengthSize = tables.first { it.type == "stsd" }.let { stsd ->
            val avc1 = boxes(bytes, stsd.payload + 8, stsd.end).firstOrNull { it.type == "avc1" }
                ?: error("缺少 avc1")
            val avcC = boxes(bytes, avc1.payload + 78, avc1.end).firstOrNull { it.type == "avcC" }
                ?: error("缺少 avcC")
            (bytes[avcC.payload + 4].toInt() and 0x03) + 1
        }
        val avcC = tables.first { it.type == "stsd" }.let { stsd ->
            val avc1 = boxes(bytes, stsd.payload + 8, stsd.end).first { it.type == "avc1" }
            boxes(bytes, avc1.payload + 78, avc1.end).first { it.type == "avcC" }.let { box ->
                val payload = avcCParameterSets(bytes, box.payload, box.end)
                payload
            }
        }

        val sampleRanges = ArrayList<Pair<Int, Int>>()
        var sampleIndex = 0
        for ((chunkIndex, chunkOffset) in chunkOffsets.withIndex()) {
            val perChunk = samplesPerChunk(stscEntries, chunkIndex + 1)
            var offset = chunkOffset
            repeat(perChunk) {
                if (sampleIndex >= sizes.size) return@repeat
                val size = sizes[sampleIndex]
                sampleRanges += offset to size
                offset += size
                sampleIndex++
            }
        }

        val samples = sampleRanges.map { (offset, size) ->
            val sample = bytes.copyOfRange(offset, offset + size)
            annexB(avcC, sample, nalLengthSize)
        }
        return VapDemuxed(config, samples)
    }

    private fun samplesPerChunk(entries: List<Triple<Int, Int, Int>>, chunk: Int): Int {
        var result = entries.firstOrNull()?.second ?: 1
        for ((first, perChunk, _) in entries) {
            if (first <= chunk) result = perChunk else break
        }
        return result
    }

    /** avcC 里的 SPS/PPS 前置到每个样本，得到 Annex-B。 / Prepend SPS/PPS from avcC to make Annex-B. */
    private fun avcCParameterSets(bytes: ByteArray, payload: Int, end: Int): ByteArray {
        var position = payload + 5
        val spsCount = bytes[payload + 5].toInt() and 0x1F
        val out = java.io.ByteArrayOutputStream()
        position++
        repeat(spsCount) {
            val length = u16(bytes, position)
            position += 2
            out.write(byteArrayOf(0, 0, 0, 1))
            out.write(bytes, position, length)
            position += length
        }
        val ppsCount = bytes[position].toInt() and 0xFF
        position++
        repeat(ppsCount) {
            val length = u16(bytes, position)
            position += 2
            out.write(byteArrayOf(0, 0, 0, 1))
            out.write(bytes, position, length)
            position += length
        }
        check(position <= end) { "avcC 解析越界" }
        return out.toByteArray()
    }

    private fun annexB(parameterSets: ByteArray, sample: ByteArray, lengthSize: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(parameterSets)
        var position = 0
        while (position + lengthSize <= sample.size) {
            var length = 0
            for (i in 0 until lengthSize) length = (length shl 8) or (sample[position + i].toInt() and 0xFF)
            position += lengthSize
            if (length <= 0 || position + length > sample.size) break
            out.write(byteArrayOf(0, 0, 0, 1))
            out.write(sample, position, length)
            position += length
        }
        return out.toByteArray()
    }

    private fun parseVapc(json: String): VapConfig {
        fun number(key: String): Int = Regex("\"$key\"\\s*:\\s*(-?\\d+)").find(json)
            ?.groupValues?.get(1)?.toIntOrNull() ?: 0

        val alpha = Regex("\"aFrame\"\\s*:\\s*\\[([^]]+)]").find(json)?.groupValues?.get(1)
            ?.split(',')?.map { it.trim().toInt() } ?: listOf(0, 0, 0, 0)
        val rgb = Regex("\"rgbFrame\"\\s*:\\s*\\[([^]]+)]").find(json)?.groupValues?.get(1)
            ?.split(',')?.map { it.trim().toInt() } ?: listOf(0, 0, 0, 0)
        val hasAlpha = Regex("\"hasAlpha\"\\s*:\\s*(true|false)").find(json)?.groupValues?.get(1) == "true"

        return VapConfig(
            width = number("w"),
            height = number("h"),
            videoWidth = number("videoW"),
            videoHeight = number("videoH"),
            fps = number("fps").coerceAtLeast(1),
            frameCount = number("f"),
            hasAlpha = hasAlpha,
            rgbX = rgb[0],
            rgbY = rgb[1],
            alphaX = alpha[0],
            alphaY = alpha[1],
            alphaWidth = alpha[2],
            alphaHeight = alpha[3],
        )
    }

    private class Box(val type: String, val payload: Int, val end: Int)

    private fun boxes(bytes: ByteArray, start: Int, limit: Int): List<Box> {
        val result = ArrayList<Box>()
        var position = start
        while (position + 8 <= limit) {
            var size = u32(bytes, position)
            val type = bytes.decodeToString(position + 4, position + 8)
            if (size == 1) break // 不支持 64 位长度
            if (size == 0) size = limit - position
            if (size < 8 || position + size > limit) break
            result += Box(type, position + 8, position + size)
            position += size
        }
        return result
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    private fun u32(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
}
