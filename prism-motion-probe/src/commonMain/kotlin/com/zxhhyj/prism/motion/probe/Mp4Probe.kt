package com.zxhhyj.prism.motion.probe

import com.zxhhyj.prism.motion.format.Bytes
import com.zxhhyj.prism.motion.format.Mp4Box
import com.zxhhyj.prism.motion.vap.Vapc

/** MP4/VAP 的原生事实；缺失字段不从外容器回填。 / Native MP4/VAP facts without filling missing fields from the outer container. */
internal data class Mp4Facts(
    val width: Int?, val height: Int?, val durationMs: Long?, val frameCount: Int?, val fps: Int?,
    val hasAlpha: Boolean?, val metadata: Map<String, String>, val issues: List<ProbeIssue>,
)

/** 有界解析原生 MP4 和必需的 vapc 配置。 / Bounded parsing of native MP4 and its mandatory vapc configuration. */
internal object Mp4Probe {
    fun probe(data: ByteArray): Mp4Facts = try {
        read(data)
    } catch (failure: IllegalArgumentException) {
        Mp4Facts(null, null, null, null, null, null, emptyMap(),
            listOf(ProbeIssue(ProbeSeverity.Error, "bad-mp4", failure.message ?: "Invalid native MP4")))
    }

    private fun read(data: ByteArray): Mp4Facts {
        val top = boxes(data, 0, data.size)
        val issues = ArrayList<ProbeIssue>()
        val metadata = LinkedHashMap<String, String>()
        require(top.any { it.type == "ftyp" }) { "Native MP4 lacks ftyp" }
        val moov = top.singleOrNull { it.type == "moov" } ?: throw IllegalArgumentException("Native MP4 requires one moov")
        val vapc = top.singleOrNull { it.type == "vapc" } ?: throw IllegalArgumentException("Vap payload requires one vapc box")
        val json = data.decodeToString(vapc.payload, vapc.end).trim()
        metadata["vapc"] = json
        val config = Vapc.parse(json) ?: throw IllegalArgumentException("Invalid vapc JSON")
        fun number(key: String): Int? = Regex("\"$key\"\\s*:\\s*(-?\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull()
        require(config.width > 0 && config.height > 0 && config.frameCount > 0 &&
            number("fps")?.let { it > 0 } == true && number("f") == config.frameCount &&
            Regex("\"hasAlpha\"\\s*:\\s*(true|false)").containsMatchIn(json)) { "Invalid or missing native vapc dimensions, frames, FPS or alpha" }
        val children = boxes(data, moov.payload, moov.end)
        for (trak in children.filter { it.type == "trak" }) {
            val track = boxes(data, trak.payload, trak.end)
            val tkhd = track.firstOrNull { it.type == "tkhd" }
            if (tkhd != null) {
                require(tkhd.end - tkhd.payload >= 4) { "Truncated native tkhd" }
                val version = Bytes.u8(data, tkhd.payload)
                require(version == 0 || version == 1) { "Unsupported native tkhd version" }
                val offset = if (version == 1) 88 else 76
                require(tkhd.end - tkhd.payload >= offset + 8) { "Truncated native track dimensions" }
                val width = Bytes.u32be(data, tkhd.payload + offset) / 65536L
                val height = Bytes.u32be(data, tkhd.payload + offset + 4) / 65536L
                if (width != config.videoWidth.toLong() || height != config.videoHeight.toLong()) {
                    issues += ProbeIssue(ProbeSeverity.Error, "native-metadata-mismatch", "vapc video dimensions disagree with tkhd")
                }
            }
        }
        return Mp4Facts(config.width, config.height, config.frameCount * 1000L / config.fps,
            config.frameCount, config.fps, config.hasAlpha, metadata, issues)
    }

    private fun boxes(bytes: ByteArray, start: Int, end: Int): List<Mp4Box> {
        val result = ArrayList<Mp4Box>()
        var position = start
        while (position < end) {
            require(end - position >= 8) { "Truncated native MP4 box" }
            var size = Bytes.u32be(bytes, position)
            var header = 8
            if (size == 1L) {
                require(end - position >= 16) { "Truncated extended native MP4 box" }
                require(Bytes.u8(bytes, position + 8) < 128) { "Native MP4 size exceeds Long range" }
                size = 0L
                for (index in 0 until 8) size = (size shl 8) or Bytes.u8(bytes, position + 8 + index).toLong()
                header = 16
            } else if (size == 0L) size = (end - position).toLong()
            require(size >= header && size <= end - position) { "Invalid native MP4 box size" }
            result += Mp4Box(Bytes.typeName(bytes, position + 4), position, position + header, position + size.toInt())
            position += size.toInt()
        }
        return result
    }

    fun isVapLike(data: ByteArray): Boolean {
        if (data.size < 8) return false
        if (Bytes.typeName(data, 4) == "ftyp" || Bytes.typeName(data, 4) == "vapc") return true
        return try {
            boxes(data, 0, data.size).any { it.type == "ftyp" || it.type == "vapc" }
        } catch (_: IllegalArgumentException) {
            false
        }
    }
}
