package com.kausio.prism.releaseconsumer

import com.zxhhyj.prism.motion.PrismMotionEngineId
import com.zxhhyj.prism.motion.PrismMotionEngineRegistry
import com.zxhhyj.prism.motion.PrismMotionRequest
import com.zxhhyj.prism.motion.PrismMotionSource
import com.zxhhyj.prism.motion.engine.kap.PrismMotionKapEngine
import com.zxhhyj.prism.motion.engine.vap.PrismMotionVapEngine
import com.zxhhyj.prism.motion.format.PrismAlphaMode
import com.zxhhyj.prism.motion.format.PrismContainer
import com.zxhhyj.prism.motion.format.PrismMetadata
import com.zxhhyj.prism.motion.format.PrismPayloadKind
import com.zxhhyj.prism.motion.ktx.Ktx
import com.zxhhyj.prism.motion.ktx.KtxFormat

/** 从真实 Maven 包封装并打开微型 RGBA8 动画，无需 ASTC/H.264 原生库。 / Wraps and opens a tiny RGBA8 animation from real Maven packages without ASTC/H.264 native libraries. */
public suspend fun main() {
    val metadata = PrismMetadata(
        width = 2,
        height = 1,
        frameCount = 2,
        fpsNumerator = 25,
        fpsDenominator = 1,
        alphaMode = PrismAlphaMode.Straight,
    )
    val payload = Ktx.write(
        format = KtxFormat.RGBA8,
        width = metadata.width,
        height = metadata.height,
        layers = listOf(
            byteArrayOf(-1, 0, 0, -1, 0, -1, 0, -1),
            byteArrayOf(0, 0, -1, -1, -1, -1, 0, -1),
        ),
        keyValues = mapOf("anim.fps" to metadata.fpsNumerator.toString(), "anim.premultiplied" to "false"),
    )
    val container = PrismContainer.write(metadata, PrismPayloadKind.Ktx2, payload)
    val header = PrismContainer.readPrefix(
        container.copyOf(PrismContainer.PREFIX_SIZE),
        container.size.toLong(),
    )
    check(header.kind == PrismPayloadKind.Ktx2)
    check(header.metadata == metadata)
    check(PrismContainer.verify(container) == header)

    val source = PrismMotionSource.Bytes(container)
    val kap = PrismMotionKapEngine()
    val vap = PrismMotionVapEngine()
    check(!vap.supports(source)) { "VAP must reject a Prism KTX2 payload" }
    val registry = PrismMotionEngineRegistry(listOf(vap, kap))
    check(registry.engineIds == setOf(PrismMotionEngineId.Kap, PrismMotionEngineId.Vap))
    val engine = checkNotNull(registry.engineFor(PrismMotionRequest(source, autoPlay = false)))
    check(engine.id == PrismMotionEngineId.Kap) { "The Prism header must select KAP, not registration order" }
    check(registry.engineFor(PrismMotionRequest(source, engine = PrismMotionEngineId.Vap)) == null)

    val resource = engine.open(source)
    try {
        check(resource.width == 2 && resource.height == 1)
        check(resource.durationMs == 80L && resource.durationMs == metadata.durationMs)
        check(resource.playbackError == null)
    } finally {
        resource.release()
    }
    Class.forName("com.zxhhyj.prism.motion.utils.JvmNativeLoader")
    println("Prism Maven consumer passed: KAP RGBA8 2x1, 2 frames, 80 ms; VAP rejected KTX2.")
}
