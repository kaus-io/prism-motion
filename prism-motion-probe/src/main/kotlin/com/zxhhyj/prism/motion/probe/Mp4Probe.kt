package com.zxhhyj.prism.motion.probe

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
        val boxes = Mp4Boxes(data)
        val top = boxes.topLevel()
        val issues = ArrayList<ProbeIssue>()
        val metadata = LinkedHashMap<String, String>()

        if (boxes.truncated) {
            issues += ProbeIssue(ProbeSeverity.Warning, "truncated", "文件末尾的 box 长度不完整")
        }
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
            val json = String(boxes.payload(vapc), Charsets.UTF_8).trim()
            metadata["vapc"] = json
            frameCount = number(json, "f")?.toInt()
            width = number(json, "w")?.toInt()
            height = number(json, "h")?.toInt()
            fps = number(json, "fps")?.toInt()
            hasAlpha = bool(json, "hasAlpha")
            metadata["vapc.version"] = number(json, "v")?.toString().orEmpty()
            if (width == null || height == null) {
                issues += ProbeIssue(ProbeSeverity.Error, "bad-vapc", "vapc JSON 缺少 w/h 字段")
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
            durationMs = readDurationMs(boxes, boxes.children(moov))
            if (width == null || height == null) {
                val size = readTrackSize(boxes, boxes.children(moov))
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

    private fun readDurationMs(boxes: Mp4Boxes, moovChildren: List<Mp4Boxes.Box>): Long? {
        val mvhd = moovChildren.firstOrNull { it.type == "mvhd" } ?: return null
        val version = boxes.u8(mvhd.payload)
        val timescale: Long
        val duration: Long
        if (version == 1) {
            if (mvhd.end - mvhd.payload < 32) return null
            timescale = boxes.u32(mvhd.payload + 20)
            duration = boxes.u64(mvhd.payload + 24)
        } else {
            if (mvhd.end - mvhd.payload < 20) return null
            timescale = boxes.u32(mvhd.payload + 12)
            duration = boxes.u32(mvhd.payload + 16)
        }
        if (timescale <= 0) return null
        return duration * 1000L / timescale
    }

    /** tkhd 的 width/height 是 16.16 定点数。 */
    private fun readTrackSize(boxes: Mp4Boxes, moovChildren: List<Mp4Boxes.Box>): Pair<Int, Int>? {
        for (trak in moovChildren.filter { it.type == "trak" }) {
            val tkhd = boxes.children(trak).firstOrNull { it.type == "tkhd" } ?: continue
            val version = boxes.u8(tkhd.payload)
            val offset = if (version == 1) 88 else 76
            if (tkhd.end - tkhd.payload < offset + 8) continue
            val w = (boxes.u32(tkhd.payload + offset) / 65536).toInt()
            val h = (boxes.u32(tkhd.payload + offset + 4) / 65536).toInt()
            if (w > 0 && h > 0) return w to h
        }
        return null
    }

    private fun number(json: String, key: String): Long? =
        Regex("\"${Regex.escape(key)}\"\\s*:\\s*(-?\\d+)").find(json)
            ?.groupValues?.get(1)?.toLongOrNull()

    private fun bool(json: String, key: String): Boolean? =
        Regex("\"${Regex.escape(key)}\"\\s*:\\s*(true|false)").find(json)
            ?.groupValues?.get(1)?.toBooleanStrictOrNull()

    fun isVapLike(data: ByteArray): Boolean {
        val boxes = Mp4Boxes(data)
        val top = boxes.topLevel()
        return top.any { it.type == "vapc" } || top.any { it.type == "ftyp" }
    }
}
