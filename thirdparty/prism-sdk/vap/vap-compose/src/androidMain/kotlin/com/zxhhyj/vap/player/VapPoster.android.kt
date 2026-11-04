package com.zxhhyj.vap.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.zxhhyj.vap.decode.AndroidVapPlatformFrame
import com.zxhhyj.vap.decode.VapGpuPrewarm
import com.zxhhyj.vap.decode.VapPosterEntry
import com.zxhhyj.vap.decode.VapSurfaceFrameDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal actual fun rememberVapOpeningFrame(animationState: VapAnimationState) {
    val composition = animationState.posterComposition
    val surfaceDecoder = animationState.decoder as? VapSurfaceFrameDecoder
    if (animationState.usesSurfaceHost && surfaceDecoder != null) {
        // Surface path: load the packed frame once, hand it to the Vulkan session, then
        // drop the composition's copy. The decoder owns the IntArray until first codec
        // frame; the composited Bitmap is no longer needed in animation mode, so
        // releasing it here keeps the steady-state PSS close to a Bitmap-only player.
        // Surface 路径：按需加载一次打包帧交给 Vulkan 会话，然后丢掉 composition 的副本。
        // 解码器在首个 codec 帧前持有 IntArray；动画模式下不再需要合成位图，
        // 释放后稳态 PSS 更接近纯 Bitmap 播放器。
        LaunchedEffect(surfaceDecoder, composition) {
            val c = composition ?: return@LaunchedEffect
            val pixels = withContext(Dispatchers.IO) {
                c.poster.loadPackedPixels(c.source, c.config)
            }
            surfaceDecoder.setOpeningFrame(
                pixels = pixels,
                width = c.config.videoWidth,
                height = c.config.videoHeight,
            )
            c.poster.releaseAll()
            if (animationState.openingFrame != null) {
                animationState.publishOpeningFrame(null)
            }
        }
        return
    }
    val cached by produceState(
        initialValue = composition?.poster?.snapshot(),
        composition,
    ) {
        val c = composition ?: return@produceState
        c.poster.snapshot()?.let {
            value = it
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            c.poster.warm(c.source, c.config)
            c.poster.snapshot()
        }
    }
    val existing = animationState.openingFrame as? AndroidVapPlatformFrame
    val next = cached?.toPlatformFrame()
    if (existing?.bitmap !== next?.bitmap) {
        animationState.publishOpeningFrame(next)
    }
}

internal actual fun hasCachedVapOpeningFrame(composition: VapComposition?): Boolean {
    return composition?.poster?.isReady == true
}

internal actual fun warmVapOpeningFrame(composition: VapComposition) {
    composition.poster.warm(composition.source, composition.config)
}

internal actual fun prewarmVapGpu() {
    VapGpuPrewarm.prewarm()
}

private fun VapPosterEntry.toPlatformFrame(): AndroidVapPlatformFrame =
    AndroidVapPlatformFrame.wrap(cropped)
