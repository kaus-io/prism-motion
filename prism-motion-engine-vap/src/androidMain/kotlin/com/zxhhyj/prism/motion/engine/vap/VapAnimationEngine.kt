package com.zxhhyj.prism.motion.engine.vap

import com.zxhhyj.prism.motion.AnimationSurface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import com.zxhhyj.prism.motion.compose.AnimationRegistry
import com.zxhhyj.prism.motion.compose.AnimationRenderer
import com.zxhhyj.prism.motion.compose.AnimationState
import com.zxhhyj.prism.motion.AnimationEngine
import com.zxhhyj.prism.motion.AnimationEngineId
import com.zxhhyj.prism.motion.AnimationResource
import com.zxhhyj.prism.motion.AnimationSource
import com.zxhhyj.vap.player.VapAnimation
import com.zxhhyj.vap.player.VapAnimationState
import com.zxhhyj.vap.player.VapComposition
import com.zxhhyj.vap.player.VapCompositionSpec
import com.zxhhyj.vap.player.VapPlayerStatus
import com.zxhhyj.vap.player.animateVapCompositionAsState
import com.zxhhyj.vap.player.loadVapCompositionAsync

/** VAP（MP4 + alpha，MediaCodec）作为统一门面的引擎实现。 */
public class VapAnimationEngine : AnimationEngine {
    override val id: AnimationEngineId = AnimationEngineId.Vap

    override fun supports(source: AnimationSource): Boolean = when (source) {
        is AnimationSource.Asset -> source.path.endsWith(MP4_SUFFIX, ignoreCase = true)
        is AnimationSource.File -> source.path.endsWith(MP4_SUFFIX, ignoreCase = true)
        is AnimationSource.Bytes -> true
    }

    override suspend fun open(source: AnimationSource): AnimationResource {
        val spec = when (source) {
            is AnimationSource.Asset -> VapCompositionSpec.Asset(source.path)
            is AnimationSource.File -> VapCompositionSpec.File(source.path)
            is AnimationSource.Bytes -> VapCompositionSpec.Bytes(source.bytes)
        }
        return VapAnimationResource(loadVapCompositionAsync(spec))
    }

    private companion object {
        const val MP4_SUFFIX = ".mp4"
    }
}

private class VapAnimationResource(val composition: VapComposition) : AnimationResource {
    /** 播放开关提升为 Compose 状态，交给 VAP 自己的暖待机切换。 */
    var playing by mutableStateOf(true)

    /** 由渲染器在组合期绑定；绑定前所有播放指令都是空操作。 */
    var player: VapAnimationState? = null

    override val width: Int get() = composition.config.width
    override val height: Int get() = composition.config.height
    override val durationMs: Long
        get() {
            val config = composition.config
            return config.totalFrames.toLong() * 1000L / config.fps.coerceAtLeast(1)
        }

    override val hasOutput: Boolean get() = player?.status is VapPlayerStatus.Ready
    override val progress: Float get() = player?.progress ?: 0f
    override val isPlaying: Boolean get() = player?.isPlaying == true

    // 目前只支持 TextureView 宿主：Surface 由 [VapAnimation] 自己持有。
    override fun attach(surface: AnimationSurface, width: Int, height: Int) = Unit
    override fun resize(width: Int, height: Int) = Unit
    override fun detach() = Unit

    override fun play() {
        playing = true
    }

    override fun pause() {
        playing = false
    }

    /** VAP 的定位由 request/解码器决定，统一门面暂不暴露任意 seek。 */
    override fun seekTo(progress: Float) = Unit

    override fun release() = Unit
}

/** 用 VAP 的统一渲染器；宿主仍是 [VapAnimation] 自己的 Surface / Canvas 路径。 */
public object VapAnimationRenderer : AnimationRenderer {
    @Composable
    override fun Render(state: AnimationState, modifier: Modifier, contentScale: ContentScale) {
        val resource = state.resource as? VapAnimationResource ?: return
        val player = animateVapCompositionAsState(
            composition = resource.composition,
            isPlaying = resource.playing,
        )
        SideEffect { resource.player = player }
        VapAnimation(
            animationState = player,
            modifier = modifier,
            contentScale = contentScale,
        )
    }
}

/** 便捷入口：只注册 VAP 一个引擎。 */
@Composable
public fun rememberVapAnimationRegistry(): AnimationRegistry = remember {
    AnimationRegistry(
        engines = listOf(VapAnimationEngine()),
        renderers = mapOf(AnimationEngineId.Vap to VapAnimationRenderer),
    )
}
