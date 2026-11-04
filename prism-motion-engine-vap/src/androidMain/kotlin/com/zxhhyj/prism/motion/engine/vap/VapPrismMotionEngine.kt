package com.zxhhyj.prism.motion.engine.vap

import com.zxhhyj.prism.motion.PrismMotionSurface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import com.zxhhyj.prism.motion.compose.PrismMotionRegistry
import com.zxhhyj.prism.motion.compose.PrismMotionRenderer
import com.zxhhyj.prism.motion.compose.PrismMotionState
import com.zxhhyj.prism.motion.PrismMotionEngine
import com.zxhhyj.prism.motion.PrismMotionEngineId
import com.zxhhyj.prism.motion.PrismMotionResource
import com.zxhhyj.prism.motion.PrismMotionSource
import com.zxhhyj.vap.player.VapAnimation
import com.zxhhyj.vap.player.VapAnimationState
import com.zxhhyj.vap.player.VapComposition
import com.zxhhyj.vap.player.VapCompositionSpec
import com.zxhhyj.vap.player.VapPlayerStatus
import com.zxhhyj.vap.player.animateVapCompositionAsState
import com.zxhhyj.vap.player.loadVapCompositionAsync

/** VAP（MP4 + alpha，MediaCodec）作为统一门面的引擎实现。 */
public class PrismMotionVapEngine : PrismMotionEngine {
    private val loadMutex = Mutex()
    private var cachedAsset: PrismMotionSource.Asset? = null
    private var cachedComposition: VapComposition? = null

    override val id: PrismMotionEngineId = PrismMotionEngineId.Vap

    override fun supports(source: PrismMotionSource): Boolean = when (source) {
        is PrismMotionSource.Asset -> source.path.endsWith(MP4_SUFFIX, ignoreCase = true)
        is PrismMotionSource.File -> source.path.endsWith(MP4_SUFFIX, ignoreCase = true)
        is PrismMotionSource.Bytes -> true
    }

    override suspend fun open(source: PrismMotionSource): PrismMotionResource {
        val spec = when (source) {
            is PrismMotionSource.Asset -> VapCompositionSpec.Asset(source.path)
            is PrismMotionSource.File -> VapCompositionSpec.File(source.path)
            is PrismMotionSource.Bytes -> VapCompositionSpec.Bytes(source.bytes)
        }
        val composition = if (source is PrismMotionSource.Asset) {
            // 同一个不可变资产共享解析结果，解码器和播放状态仍按实例独立。
            // Share immutable asset metadata, not decoder sessions or playback state.
            loadMutex.withLock {
                if (cachedAsset == source) cachedComposition!! else {
                    loadVapCompositionAsync(spec).also {
                        cachedAsset = source
                        cachedComposition = it
                    }
                }
            }
        } else {
            loadVapCompositionAsync(spec)
        }
        return VapAnimationResource(composition)
    }

    private companion object {
        const val MP4_SUFFIX = ".mp4"
    }
}

private class VapAnimationResource(val composition: VapComposition) : PrismMotionResource {
    /** 播放开关提升为 Compose 状态，交给 VAP 自己的暖待机切换。 */
    var playing by mutableStateOf(false)
    var released by mutableStateOf(false)
        private set
    var iterations by mutableIntStateOf(1)
        private set
    var failure by mutableStateOf<Throwable?>(null)
        private set

    fun onFailure(error: Throwable) {
        failure = error
    }

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
    override val isPlaying: Boolean get() = !released && playing && player?.isPlaying == true
    override val presentedFrameCount: Long get() = player?.presentedCount ?: 0L
    override val playbackError: Throwable? get() = failure ?: (player?.status as? VapPlayerStatus.Failed)?.error

    override fun setRepeatCount(repeatCount: Int) {
        require(repeatCount >= -1)
        iterations = if (repeatCount == -1 || repeatCount == Int.MAX_VALUE) Int.MAX_VALUE else repeatCount + 1
    }

    // 目前只支持 TextureView 宿主：Surface 由 [VapAnimation] 自己持有。
    override fun attach(surface: PrismMotionSurface, width: Int, height: Int) = Unit
    override fun resize(width: Int, height: Int) = Unit
    override fun detach() = Unit

    override fun play() {
        if (!released) playing = true
    }

    override fun pause() {
        playing = false
    }

    /** VAP 的定位由 request/解码器决定，统一门面暂不暴露任意 seek。 */
    override fun seekTo(progress: Float) = Unit

    override fun release() {
        if (released) return
        playing = false
        released = true
        player = null
    }
}

/** 用 VAP 的统一渲染器；宿主仍是 [VapAnimation] 自己的 Surface / Canvas 路径。 */
public object PrismMotionVapRenderer : PrismMotionRenderer {
    @Composable
    override fun Render(state: PrismMotionState, modifier: Modifier, contentScale: ContentScale) {
        val resource = state.resource as? VapAnimationResource ?: return
        if (resource.released) return
        val player = animateVapCompositionAsState(
            composition = resource.composition,
            isPlaying = resource.playing,
            iterations = resource.iterations,
            // 上游未设置错误监听时会重新抛出；门面必须收敛为失败状态。
            // Without an error listener upstream rethrows; the facade must retain a failure state.
            onError = resource::onFailure,
        )
        SideEffect { resource.player = player }
        // 从组合移除后由上游异步关闭 MediaCodec/Vulkan，不在主线程重复销毁。
        // Upstream closes MediaCodec/Vulkan asynchronously on disposal, not twice on Main.
        DisposableEffect(resource, player) {
            onDispose { if (resource.player === player) resource.player = null }
        }
        VapAnimation(
            animationState = player,
            modifier = modifier,
            contentScale = contentScale,
        )
    }
}

/** 便捷入口：只注册 VAP 一个引擎。 */
@Composable
public fun rememberPrismMotionVapRegistry(): PrismMotionRegistry = remember {
    PrismMotionRegistry(
        engines = listOf(PrismMotionVapEngine()),
        renderers = mapOf(PrismMotionEngineId.Vap to PrismMotionVapRenderer),
    )
}
