package com.zxhhyj.prism.motion.probe

/** KTX2 容器里抽出来的事实。 / Facts extracted from a KTX2 container. */
internal class KtxFacts(
    val width: Int?,
    val height: Int?,
    val durationMs: Long?,
    val frameCount: Int?,
    val fps: Int?,
    val metadata: Map<String, String>,
    val issues: List<ProbeIssue>,
)
