package com.zxhhyj.prism.motion.probe

import com.zxhhyj.prism.motion.PrismMotionFormat
import com.zxhhyj.prism.motion.format.PrismAlphaMode
import com.zxhhyj.prism.motion.format.PrismContainer
import com.zxhhyj.prism.motion.format.PrismMetadata
import com.zxhhyj.prism.motion.format.PrismPayloadKind
import com.zxhhyj.prism.motion.format.PrismSha256
import com.zxhhyj.prism.motion.ktx.KtxReader

/** 校验 .prism 外容器及原生载荷元数据，不解码视频。 / Validates .prism containers and native payload metadata without video decoding. */
public object PrismMotionProbe {
    /** 整文件读取上限；字节数组入口也执行相同限制。 / Whole-file limit, also enforced for byte-array input. */
    public const val MAX_BYTES: Long = 512L * 1024 * 1024

    /** 从路径读取并校验一个有界资源。 / Reads and validates a bounded asset from a path. */
    public fun probe(path: String): ProbeResult {
        val size = PlatformIo.size(path)
        require(size in 0L..MAX_BYTES) { "$path exceeds the $MAX_BYTES byte probe limit" }
        return probe(PlatformIo.read(path), fileNameOf(path))
    }

    /** 校验 CRC32、SHA-256、原生类型及可用的内部元数据。 / Checks CRC32, SHA-256, native kind and available inner metadata. */
    public fun probe(bytes: ByteArray, fileName: String): ProbeResult {
        require(bytes.size.toLong() <= MAX_BYTES) { "Input exceeds the $MAX_BYTES byte probe limit" }
        val hash = PrismSha256.hex(PrismSha256.digest(bytes))
        val issues = ArrayList<ProbeIssue>()
        val suffix = fileName.substringAfterLast('.', "").lowercase()
        if (suffix in setOf("mp4", "ktx", "ktx2")) {
            issues += error("obsolete-format", "旧外部格式 .$suffix 已废弃，只接受 .prism / Obsolete external format; only .prism is supported")
        } else if (PrismMotionFormat.ofFileName(fileName) != PrismMotionFormat.Prism) {
            issues += error("unsupported-format", "资源必须使用 .prism 后缀 / Assets must use the .prism suffix")
        }
        if (!PrismContainer.isPrism(bytes)) {
            val legacy = KtxReader.isKtx(bytes) || Mp4Probe.isVapLike(bytes)
            issues += error(
                if (legacy) "obsolete-container" else "unknown-container",
                "缺少 PRISM 魔数；原生 MP4/KTX2 只能作为内嵌载荷 / Missing PRISM magic; native MP4/KTX2 is payload-only",
            )
            return ProbeResult(fileName, null, bytes.size.toLong(), hash, issues = issues)
        }
        val descriptor = try {
            PrismContainer.verify(bytes)
        } catch (failure: IllegalArgumentException) {
            issues += error("bad-prism", failure.message ?: "Invalid .prism container")
            return ProbeResult(fileName, PrismMotionFormat.Prism, bytes.size.toLong(), hash, issues = issues)
        }
        val metadata = descriptor.metadata
        val payload = bytes.copyOfRange(descriptor.payloadOffset.toInt(), bytes.size)
        val nativeMetadata = when (descriptor.kind) {
            PrismPayloadKind.Vap -> {
                if (!Mp4Probe.isVapLike(payload) || KtxReader.isKtx(payload)) {
                    issues += error("payload-kind-mismatch", "Vap 载荷不是原生 MP4 / Vap payload is not native MP4")
                    emptyMap()
                } else {
                    val facts = Mp4Probe.probe(payload)
                    issues += facts.issues
                    compare(metadata, facts.width, facts.height, facts.frameCount, facts.fps, facts.hasAlpha, null, issues)
                    facts.metadata
                }
            }
            PrismPayloadKind.Ktx2 -> {
                if (!KtxReader.isKtx(payload)) {
                    issues += error("payload-kind-mismatch", "Ktx2 载荷缺少原生魔数 / Ktx2 payload lacks its native magic")
                    emptyMap()
                } else {
                    val facts = Ktx2Probe.probe(payload)
                    issues += facts.issues
                    compare(metadata, facts.width, facts.height, facts.frameCount, facts.fps, facts.hasAlpha, facts.alphaMode, issues)
                    facts.metadata
                }
            }
        }
        return ProbeResult(
            fileName = fileName,
            format = PrismMotionFormat.Prism,
            bytes = bytes.size.toLong(),
            sha256 = hash,
            width = metadata.width,
            height = metadata.height,
            durationMs = metadata.durationMs,
            frameCount = metadata.frameCount,
            fps = if (metadata.fpsNumerator % metadata.fpsDenominator == 0) metadata.fpsNumerator / metadata.fpsDenominator else null,
            hasAlpha = metadata.alphaMode != PrismAlphaMode.Opaque,
            metadata = nativeMetadata,
            issues = issues,
            payloadKind = descriptor.kind,
            fpsNumerator = metadata.fpsNumerator,
            fpsDenominator = metadata.fpsDenominator,
            alphaMode = metadata.alphaMode,
        )
    }

    private fun compare(
        metadata: PrismMetadata,
        width: Int?, height: Int?, frames: Int?, fps: Int?, hasAlpha: Boolean?, alphaMode: PrismAlphaMode?,
        issues: MutableList<ProbeIssue>,
    ) {
        fun mismatch(field: String, inner: Any?, outer: Any) {
            issues += error("metadata-mismatch", "$field: outer=$outer, native=$inner")
        }
        if (width != null && width != metadata.width) mismatch("width", width, metadata.width)
        if (height != null && height != metadata.height) mismatch("height", height, metadata.height)
        if (frames != null && frames != metadata.frameCount) mismatch("frameCount", frames, metadata.frameCount)
        if (fps != null && fps.toLong() * metadata.fpsDenominator != metadata.fpsNumerator.toLong()) {
            mismatch("fps", fps, "${metadata.fpsNumerator}/${metadata.fpsDenominator}")
        }
        if (hasAlpha != null && hasAlpha != (metadata.alphaMode != PrismAlphaMode.Opaque)) {
            mismatch("hasAlpha", hasAlpha, metadata.alphaMode)
        }
        if (alphaMode != null && metadata.alphaMode != PrismAlphaMode.Opaque && alphaMode != metadata.alphaMode) {
            mismatch("alphaMode", alphaMode, metadata.alphaMode)
        }
    }

    private fun error(code: String, message: String): ProbeIssue = ProbeIssue(ProbeSeverity.Error, code, message)
    private fun fileNameOf(path: String): String = path.substringAfterLast('/').substringAfterLast('\\')
}
