package com.zxhhyj.vap.player

import androidx.compose.runtime.Composable

@Composable
internal actual fun rememberVapOpeningFrame(animationState: VapAnimationState) {
    // JVM desktop uses the Canvas path; the first frame is published by the decode loop, so no poster is needed.
    // JVM 桌面端走 Canvas 路径，首帧由解码循环直接发布，无需海报帧。
}

internal actual fun hasCachedVapOpeningFrame(composition: VapComposition?): Boolean = false

internal actual fun warmVapOpeningFrame(composition: VapComposition) = Unit

internal actual fun prewarmVapGpu() = Unit
