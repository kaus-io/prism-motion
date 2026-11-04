package com.zxhhyj.prism.motion.probe

/** KTX2 / KAP 容器里抽出来的事实。 */
internal data class Ktx2Facts(
    val width: Int?,
    val height: Int?,
    val durationMs: Long?,
    val frameCount: Int?,
    val fps: Int?,
    val metadata: Map<String, String>,
    val issues: List<ProbeIssue>,
)

internal object Ktx2Probe {

    private val MAGIC = byteArrayOf(
        0xAB.toByte(), 0x4B, 0x54, 0x58, 0x20, 0x32, 0x30,
        0xBB.toByte(), 0x0D, 0x0A, 0x1A, 0x0A,
    )

    fun isKtx2(data: ByteArray): Boolean =
        data.size >= MAGIC.size && MAGIC.indices.all { data[it] == MAGIC[it] }

    fun probe(data: ByteArray): Ktx2Facts {
        val issues = ArrayList<ProbeIssue>()
        val metadata = LinkedHashMap<String, String>()

        if (!isKtx2(data)) {
            return Ktx2Facts(
                width = null,
                height = null,
                durationMs = null,
                frameCount = null,
                fps = null,
                metadata = metadata,
                issues = listOf(ProbeIssue(ProbeSeverity.Error, "not-ktx2", "缺少 KTX2 魔数")),
            )
        }
        if (data.size < 80) {
            return Ktx2Facts(
                width = null,
                height = null,
                durationMs = null,
                frameCount = null,
                fps = null,
                metadata = metadata,
                issues = listOf(ProbeIssue(ProbeSeverity.Error, "truncated", "KTX2 头不足 80 字节")),
            )
        }

        val width = u32(data, 20).toInt()
        val height = u32(data, 24).toInt()
        val layerCount = u32(data, 32).toInt()
        val kvdOffset = u32(data, 56)
        val kvdLength = u32(data, 60)
        val supercompression = u32(data, 44)
        metadata["ktx2.vkFormat"] = u32(data, 12).toString()
        metadata["ktx2.supercompression"] = supercompression.toString()

        if (layerCount <= 0) {
            issues += ProbeIssue(ProbeSeverity.Warning, "no-layers", "layerCount 为 0，没有动画帧")
        }

        var fps: Int? = null
        if (kvdLength > 0) {
            val kvdStart = kvdOffset.coerceIn(0L, data.size.toLong()).toInt()
            val kvdEnd = (kvdOffset + kvdLength).coerceIn(0L, data.size.toLong()).toInt()
            var p = kvdStart
            while (p + 4 <= kvdEnd) {
                val length = u32(data, p).toInt()
                p += 4
                if (length <= 0 || p + length > kvdEnd) break
                val entry = String(data, p, length, Charsets.UTF_8)
                p += length
                p += (4 - (length % 4)) % 4
                val sep = entry.indexOf('\u0000')
                if (sep > 0) {
                    // 有些打包器把 4 字节对齐的填充也算进 length，键值末尾会带 NUL。
                    val key = entry.substring(0, sep).trimEnd('\u0000')
                    val value = entry.substring(sep + 1).trimEnd('\u0000')
                    if (key.isNotEmpty()) metadata[key] = value
                }
            }
        }

        // 老打包器写 ktxanim.*，改名后写 anim.*，两种都认。
        val fpsValue = metadata["anim.fps"] ?: metadata["ktxanim.fps"]
        fps = fpsValue?.toIntOrNull()
        if (fpsValue == null) {
            issues += ProbeIssue(ProbeSeverity.Info, "missing-fps", "没有 anim.fps，运行时按 30 处理")
        }

        val frameCount = layerCount
        val durationMs = if (fps != null && fps > 0) frameCount * 1000L / fps else null

        return Ktx2Facts(
            width = width,
            height = height,
            durationMs = durationMs,
            frameCount = frameCount,
            fps = fps,
            metadata = metadata,
            issues = issues,
        )
    }

    /** KTX2 头与 key/value 块都是小端序。 */
    private fun u32(data: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 3 downTo 0) {
            value = (value shl 8) or (data[offset + i].toLong() and 0xFF)
        }
        return value
    }
}
