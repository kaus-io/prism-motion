package com.zxhhyj.prism.motion.probe

import com.zxhhyj.prism.motion.PrismMotionFormat
import com.zxhhyj.prism.motion.format.PrismAlphaMode
import com.zxhhyj.prism.motion.format.PrismPayloadKind

/** 检查问题的严重程度。 / Severity of a validation issue. */
public enum class ProbeSeverity { Info, Warning, Error }

/** 一条可机器识别的校验问题。 / One machine-readable validation issue. */
public data class ProbeIssue(
    public val severity: ProbeSeverity,
    public val code: String,
    public val message: String,
)

/**
 * 容器元数据为准的探测结果；原生载荷差异通过 issues 报错。 / Probe result uses outer metadata and reports native payload disagreements through issues.
 * fps 仅在有理帧率为整数时有值。 / fps is present only when the rational frame rate is an integer.
 */
public data class ProbeResult(
    public val fileName: String,
    public val format: PrismMotionFormat?,
    public val bytes: Long,
    public val sha256: String,
    public val width: Int? = null,
    public val height: Int? = null,
    public val durationMs: Long? = null,
    public val frameCount: Int? = null,
    public val fps: Int? = null,
    public val hasAlpha: Boolean? = null,
    public val metadata: Map<String, String> = emptyMap(),
    public val issues: List<ProbeIssue> = emptyList(),
    public val payloadKind: PrismPayloadKind? = null,
    public val fpsNumerator: Int? = null,
    public val fpsDenominator: Int? = null,
    public val alphaMode: PrismAlphaMode? = null,
) {
    /** 没有错误时为 true，警告不影响通过。 / True when no errors exist; warnings do not fail validation. */
    public val ok: Boolean get() = issues.none { it.severity == ProbeSeverity.Error }
}
