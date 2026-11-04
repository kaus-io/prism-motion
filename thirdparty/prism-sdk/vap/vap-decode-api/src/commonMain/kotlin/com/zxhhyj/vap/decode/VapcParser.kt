package com.zxhhyj.vap.decode

import com.zxhhyj.vap.mp4.Mp4BoxParser
import com.zxhhyj.vap.mp4.Mp4Source
import com.zxhhyj.vap.mp4.Mp4Sources
import com.zxhhyj.vap.player.VapConfig
import com.zxhhyj.vap.vapc.VapcRoot
import com.zxhhyj.vap.vapc.toVapConfig
import kotlinx.serialization.json.Json

/**
 * Parses the `vapc` JSON manifest embedded in VAP MP4 files into a `VapConfig`.
 *
 * The JSON parser tolerates unknown keys so future schema additions do not break
 * older readers; structural validation (rect arity, schema version) is deferred
 * to the `toVapConfig` mapping.
 *
 * 将嵌入在 VAP MP4 文件中的 `vapc` JSON 清单解析为 `VapConfig`。
 *
 * JSON 解析器容忍未知键，后续 schema 增补不会弄坏旧读取方；结构校验
 * （矩形元数、schema 版本）交给 `toVapConfig` 映射。
 */
public object VapcParser {
    private val json = Json { ignoreUnknownKeys = true }

    public fun parseMp4(bytes: ByteArray): VapConfig = parseMp4(Mp4Sources.bytes(bytes))

    public fun parseMp4(source: Mp4Source): VapConfig {
        val vapc = findVapcPayload(source) ?: error("vapc box not found in mp4")
        return parseVapcJson(vapc.decodeToString())
    }

    /**
     * Parses VAP metadata from a forward-only MP4 stream (Coil peek / assets).
     * Returns `null` when no `vapc` box is found before `mdat`.
     *
     * 从只进 MP4 流解析 VAP 元数据（Coil peek / assets）。在 `mdat` 之前找不到 `vapc` 时返回 `null`。
     */
    public fun parseMp4Stream(input: java.io.InputStream): VapConfig? {
        val vapc = Mp4BoxParser.findPayloadFromStream(input, "vapc") ?: return null
        return parseVapcJson(vapc.decodeToString())
    }

    public fun parseVapcJson(jsonText: String): VapConfig =
        json.decodeFromString<VapcRoot>(jsonText.trim()).toVapConfig()

    // Locate the top-level `vapc` box; returns the raw payload bytes (UTF-8 JSON).
    // 定位顶层 `vapc` 盒，返回原始 payload 字节（UTF-8 编码的 JSON）。
    public fun findVapcPayload(bytes: ByteArray): ByteArray? =
        Mp4BoxParser.findPayload(Mp4Sources.bytes(bytes), "vapc")

    public fun findVapcPayload(source: Mp4Source): ByteArray? =
        Mp4BoxParser.findPayload(source, "vapc")
}
