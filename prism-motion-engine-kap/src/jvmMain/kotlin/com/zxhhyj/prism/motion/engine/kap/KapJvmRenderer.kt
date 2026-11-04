package com.zxhhyj.prism.motion.engine.kap

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import com.zxhhyj.prism.motion.AnimationEngineId
import com.zxhhyj.prism.motion.compose.AnimationRegistry
import com.zxhhyj.prism.motion.compose.AnimationRenderer
import com.zxhhyj.prism.motion.compose.AnimationState
import kotlinx.coroutines.delay

/**
 * JVM 渲染器：把当前帧作为 `ImageBitmap` 交给 Compose 绘制，播放进度由协程按 fps 推进。
 * JVM renderer: hands the current frame to Compose as an `ImageBitmap`; a coroutine advances it at `fps`.
 */
public object KapJvmRenderer : AnimationRenderer {

    @Composable
    override fun Render(state: AnimationState, modifier: Modifier, contentScale: ContentScale) {
        val resource = state.resource as? KapJvmResource ?: return
        val bitmap = remember(resource, resource.frameIndex) { resource.currentBitmap() }
        Image(bitmap = bitmap, contentDescription = null, modifier = modifier, contentScale = contentScale)

        val frameDelay = remember(resource) { 1000L / resource.fps.coerceAtLeast(1) }
        val playing by rememberUpdatedState(resource.isPlaying)
        LaunchedEffect(resource) {
            while (true) {
                delay(frameDelay)
                if (playing) resource.advance()
            }
        }
    }
}

/** 便捷入口：只注册 KAP 一个引擎（JVM）。 / Convenience: register only the JVM KAP engine. */
@Composable
public fun rememberKapAnimationRegistry(): AnimationRegistry = remember {
    AnimationRegistry(
        engines = listOf(KapAnimationEngine()),
        renderers = mapOf(AnimationEngineId.Kap to KapJvmRenderer),
    )
}
