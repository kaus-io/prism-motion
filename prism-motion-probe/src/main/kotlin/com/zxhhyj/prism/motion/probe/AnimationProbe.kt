package com.zxhhyj.prism.motion.probe

import com.zxhhyj.prism.motion.AnimationFormat
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** 按容器魔数识别格式，再抽元数据；不做解码，纯 JVM 可跑。 */
public object AnimationProbe {

    /** 超过这个大小的文件不整体读入内存，直接报错。 */
    public const val MAX_BYTES: Long = 512L * 1024 * 1024

    public fun probe(path: Path): ProbeResult {
        val size = Files.size(path)
        require(size <= MAX_BYTES) {
            "$path 有 $size 字节，超过 ${MAX_BYTES} 上限；请先用 ffmpeg/astcenc 压缩"
        }
        return probe(Files.readAllBytes(path), path.fileName.toString())
    }

    public fun probe(bytes: ByteArray, fileName: String): ProbeResult {
        val sha256 = sha256(bytes)
        val byMagic = sniff(bytes)
        val bySuffix = AnimationFormat.ofFileName(fileName)
        val issues = ArrayList<ProbeIssue>()

        if (byMagic == null) {
            issues += ProbeIssue(
                ProbeSeverity.Error,
                "unknown-container",
                "既不是 ISO BMFF(MP4)，也不是 KTX2；后缀为 ${bySuffix?.suffix ?: "未知"}",
            )
        } else if (bySuffix != null && bySuffix != byMagic) {
            issues += ProbeIssue(
                ProbeSeverity.Warning,
                "format-mismatch",
                "后缀是 ${bySuffix.suffix}，内容实际是 ${byMagic.name.lowercase()}",
            )
        }

        val format = byMagic ?: bySuffix
        return when (byMagic) {
            AnimationFormat.Vap -> {
                val facts = Mp4Probe.probe(bytes)
                issues += facts.issues
                ProbeResult(
                    fileName = fileName,
                    format = format,
                    bytes = bytes.size.toLong(),
                    sha256 = sha256,
                    width = facts.width,
                    height = facts.height,
                    durationMs = facts.durationMs,
                    frameCount = facts.frameCount,
                    fps = facts.fps,
                    hasAlpha = facts.hasAlpha,
                    metadata = facts.metadata,
                    issues = issues,
                )
            }

            AnimationFormat.Kap -> {
                val facts = Ktx2Probe.probe(bytes)
                issues += facts.issues
                ProbeResult(
                    fileName = fileName,
                    format = format,
                    bytes = bytes.size.toLong(),
                    sha256 = sha256,
                    width = facts.width,
                    height = facts.height,
                    durationMs = facts.durationMs,
                    frameCount = facts.frameCount,
                    fps = facts.fps,
                    metadata = facts.metadata,
                    issues = issues,
                )
            }

            null -> ProbeResult(
                fileName = fileName,
                format = format,
                bytes = bytes.size.toLong(),
                sha256 = sha256,
                issues = issues,
            )
        }
    }

    private fun sniff(bytes: ByteArray): AnimationFormat? = when {
        Ktx2Probe.isKtx2(bytes) -> AnimationFormat.Kap
        Mp4Probe.isVapLike(bytes) -> AnimationFormat.Vap
        else -> null
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
