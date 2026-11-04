package com.zxhhyj.prism.motion.engine.vap

import com.zxhhyj.prism.motion.format.Bytes
import com.zxhhyj.prism.motion.format.Mp4
import com.zxhhyj.prism.motion.vap.Vapc

/** 解封装后的 VAP：vapc 配置 + Annex-B 采样。 / Demuxed VAP: vapc config plus Annex-B samples. */
internal class VapDemuxed(val config: Vapc, val samples: List<ByteArray>)

/**
 * 最小 MP4 解封装：只处理 stbl 与 vapc，样本统一转成 Annex-B 交给解码器。
 * Minimal MP4 demuxer: reads stbl and vapc, converts samples to Annex-B.
 */
internal object VapMp4 {

    fun demux(bytes: ByteArray): VapDemuxed {
        val top = Mp4.boxes(bytes)
        val vapcBox = top.firstOrNull { it.type == "vapc" } ?: error("不是 VAP：缺少 vapc box")
        val config = Vapc.parse(bytes.decodeToString(vapcBox.payload, vapcBox.end))
            ?: error("vapc 解析失败")

        val moov = top.firstOrNull { it.type == "moov" } ?: error("缺少 moov")
        val trak = Mp4.children(bytes, moov).firstOrNull { it.type == "trak" } ?: error("缺少 trak")
        val mdia = Mp4.children(bytes, trak).first { it.type == "mdia" }
        val minf = Mp4.children(bytes, mdia).first { it.type == "minf" }
        val stbl = Mp4.children(bytes, minf).first { it.type == "stbl" }
        val tables = Mp4.children(bytes, stbl)

        val sizes = tables.first { it.type == "stsz" }.let { stsz ->
            val count = Bytes.u32be(bytes, stsz.payload + 8).toInt()
            val constant = Bytes.u32be(bytes, stsz.payload + 4).toInt()
            if (constant != 0) List(count) { constant }
            else List(count) { Bytes.u32be(bytes, stsz.payload + 12 + it * 4).toInt() }
        }
        val chunkOffsets = tables.first { it.type == "stco" }.let { stco ->
            val count = Bytes.u32be(bytes, stco.payload + 4).toInt()
            List(count) { Bytes.u32be(bytes, stco.payload + 8 + it * 4).toInt() }
        }
        val stscEntries = tables.first { it.type == "stsc" }.let { stsc ->
            val count = Bytes.u32be(bytes, stsc.payload + 4).toInt()
            List(count) {
                val base = stsc.payload + 8 + it * 12
                Triple(
                    Bytes.u32be(bytes, base).toInt(),
                    Bytes.u32be(bytes, base + 4).toInt(),
                    Bytes.u32be(bytes, base + 8).toInt(),
                )
            }
        }
        val stsd = tables.first { it.type == "stsd" }
        val avc1 = Mp4.boxes(bytes, stsd.payload + 8, stsd.end).firstOrNull { it.type == "avc1" }
            ?: error("缺少 avc1")
        val avcC = Mp4.boxes(bytes, avc1.payload + 78, avc1.end).firstOrNull { it.type == "avcC" }
            ?: error("缺少 avcC")
        val nalLengthSize = (Bytes.u8(bytes, avcC.payload + 4) and 0x03) + 1
        val parameterSets = parameterSets(bytes, avcC.payload, avcC.end)

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
            annexB(parameterSets, bytes.copyOfRange(offset, offset + size), nalLengthSize)
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
    private fun parameterSets(bytes: ByteArray, payload: Int, end: Int): ByteArray {
        var position = payload + 5
        val spsCount = Bytes.u8(bytes, payload + 5) and 0x1F
        val out = ArrayList<Byte>()
        position++
        repeat(spsCount) {
            val length = Bytes.u16be(bytes, position)
            position += 2
            out += listOf<Byte>(0, 0, 0, 1)
            for (i in 0 until length) out += bytes[position + i]
            position += length
        }
        val ppsCount = Bytes.u8(bytes, position)
        position++
        repeat(ppsCount) {
            val length = Bytes.u16be(bytes, position)
            position += 2
            out += listOf<Byte>(0, 0, 0, 1)
            for (i in 0 until length) out += bytes[position + i]
            position += length
        }
        check(position <= end) { "avcC 解析越界" }
        return out.toByteArray()
    }

    private fun annexB(parameterSets: ByteArray, sample: ByteArray, lengthSize: Int): ByteArray {
        val out = ArrayList<Byte>()
        for (byte in parameterSets) out += byte
        var position = 0
        while (position + lengthSize <= sample.size) {
            var length = 0
            for (i in 0 until lengthSize) length = (length shl 8) or Bytes.u8(sample, position + i)
            position += lengthSize
            if (length <= 0 || position + length > sample.size) break
            out += listOf<Byte>(0, 0, 0, 1)
            for (i in 0 until length) out += sample[position + i]
            position += length
        }
        return out.toByteArray()
    }
}
