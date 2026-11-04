package com.zxhhyj.prism.motion

import com.zxhhyj.prism.motion.format.PrismAssetDescriptor
import com.zxhhyj.prism.motion.format.PrismMetadata
import com.zxhhyj.prism.motion.format.PrismPayloadKind

/** 已验证边界的载荷字节；JVM 软件解码允许物化载荷，不提取临时文件。 / Bounded payload bytes for JVM software decoding, without temporary extraction. */
public class PrismPayloadData(public val descriptor: PrismAssetDescriptor, public val bytes: ByteArray)

/** 根据容器声明选择实现，而不是把统一后缀绑定到某个引擎。 / Selects the implementation from the container declaration, not the shared suffix. */
public val PrismPayloadKind.engineId: PrismMotionEngineId
    get() = when (this) {
        PrismPayloadKind.Ktx2 -> PrismMotionEngineId.Kap
        PrismPayloadKind.Vap -> PrismMotionEngineId.Vap
    }

/** 当前播放后端只接受整数恒定帧率；不把有理帧率静默取整。 / Current playback backends require integer CFR and never silently round rational FPS. */
public fun PrismMetadata.integerPlaybackFps(): Int {
    require(fpsDenominator == 1) { "This playback backend does not support rational FPS $fpsNumerator/$fpsDenominator" }
    return fpsNumerator
}

/** 原生载荷必须与外层逻辑尺寸和时序一致。 / Native payload dimensions and timing must agree with the outer metadata. */
public fun PrismMetadata.requireNativeMetadata(width: Int, height: Int, frames: Int, fps: Int): Unit {
    require(this.width == width && this.height == height && frameCount == frames && integerPlaybackFps() == fps) {
        "Prism metadata does not match the native payload"
    }
}
