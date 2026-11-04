package com.zxhhyj.prism.motion.probe

import com.zxhhyj.prism.motion.AnimationFormat

/** 单条检查结果。 */
public enum class ProbeSeverity { Info, Warning, Error }

public data class ProbeIssue(
    public val severity: ProbeSeverity,
    public val code: String,
    public val message: String,
)

/** 一个动画文件的探测结果；无法识别的格式也能拿到字节数与哈希。 */
public data class ProbeResult(
    public val fileName: String,
    public val format: AnimationFormat?,
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
) {
    /** 没有 [ProbeSeverity.Error] 级问题时为 true；Warning 不影响通过。 */
    public val ok: Boolean get() = issues.none { it.severity == ProbeSeverity.Error }
}
