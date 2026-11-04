package com.zxhhyj.prism.motion.probe

import com.zxhhyj.prism.motion.format.Bytes
import com.zxhhyj.prism.motion.format.Mp4
import com.zxhhyj.prism.motion.format.Mp4Box
import com.zxhhyj.prism.motion.vap.Vapc
/** MP4 / VAP 容器里抽出来的事实。字段为 null 表示容器里没有。 */
internal data class Mp4Facts(
    val width: Int?,
    val height: Int?,
    val durationMs: Long?,
    val frameCount: Int?,
    val fps: Int?,
    val hasAlpha: Boolean?,
    val metadata: Map<String, String>,
    val issues: List<ProbeIssue>,
)

internal object Mp4Probe {

    fun probe(data: ByteArray): Mp4Facts {
        val top = Mp4.boxes(data)
        val issues = ArrayList<ProbeIssue>()
        val metadata = LinkedHashMap<String, String>()

        if (top.none { it.type == "ftyp" }) {
            issues += ProbeIssue(ProbeSeverity.Error, "not-mp4", "缺少 ftyp box，不是 ISO BMFF 容器")
        }

        var width: Int? = null
        var height: Int? = null
        var frameCount: Int? = null
        var fps: Int? = null
        var hasAlpha: Boolean? = null
        var durationMs: Long? = null

        val vapc = top.firstOrNull { it.type == "vapc" }
        if (vapc != null) {
            val json = data.decodeToString(vapc.payload, vapc.end).trim()
            metadata["vapc"] = json
            val config = Vapc.parse(json)
            if (config == null) {
                issues += ProbeIssue(ProbeSeverity.Error, "bad-vapc", "vapc JSON 缺少 w/h 字段")
            } else {
                width = config.width
                height = config.height
                frameCount = config.frameCount.takeIf { it > 0 }
                fps = config.fps
                hasAlpha = config.hasAlpha
            }
        } else {
            issues += ProbeIssue(
                ProbeSeverity.Warning,
                "missing-vapc",
                "没有 vapc box：这是普通 MP4，不是 VAP 动画",
            )
        }

        val moov = top.firstOrNull { it.type == "moov" }
        if (moov != null) {
            val moovChildren = Mp4.children(data, moov)
            durationMs = readDurationMs(data, moovChildren)
            if (width == null || height == null) {
                val size = readTrackSize(data, moovChildren)
                width = width ?: size?.first
                height = height ?: size?.second
            }
        } else {
            issues += ProbeIssue(ProbeSeverity.Error, "missing-moov", "缺少 moov box，文件无法播放")
        }

        val resolvedFps = fps
        val resolvedFrames = frameCount
        if (resolvedFps != null && resolvedFps > 0 && resolvedFrames != null) {
            durationMs = resolvedFrames * 1000L / resolvedFps
        }

        return Mp4Facts(width, height, durationMs, frameCount, fps, hasAlpha, metadata, issues)
    }

    private fun readDurationMs(bytes: ByteArray, moovChildren: List<Mp4Box>): Long? {
        val mvhd = moovChildren.firstOrNull { it.type == "mvhd" } ?: return null
        val version = Bytes.u8(bytes, mvhd.payload)
        val timescale: Long
        val duration: Long
        if (version == 1) {
            if (mvhd.end - mvhd.payload < 32) return null
            timescale = Bytes.u32be(bytes, mvhd.payload + 20)
            duration = Bytes.u64le(bytes, mvhd.payload + 24)
        } else {
            if (mvhd.end - mvhd.payload < 20) return null
            timescale = Bytes.u32be(bytes, mvhd.payload + 12)
            duration = Bytes.u32be(bytes, mvhd.payload + 16)
        }
        if (timescale <= 0) return null
        return duration * 1000L / timescale
    }

    /** tkhd 的 width/height 是 16.16 定点数。 */
    private fun readTrackSize(bytes: ByteArray, moovChildren: List<Mp4Box>): Pair<Int, Int>? {
        for (trak in moovChildren.filter { it.type == "trak" }) {
            val tkhd = Mp4.children(bytes, trak).firstOrNull { it.type == "tkhd" } ?: continue
            val version = Bytes.u8(bytes, tkhd.payload)
            val offset = if (version == 1) 88 else 76
            if (tkhd.end - tkhd.payload < offset + 8) continue
            val w = (Bytes.u32be(bytes, tkhd.payload + offset) / 65536).toInt()
            val h = (Bytes.u32be(bytes, tkhd.payload + offset + 4) / 65536).toInt()
            if (w > 0 && h > 0) return w to h
        }
        return null
    }

    fun isVapLike(data: ByteArray): Boolean {
        val top = Mp4.boxes(data)
        return top.any { it.type == "vapc" } || top.any { it.type == "ftyp" }
    }
}
