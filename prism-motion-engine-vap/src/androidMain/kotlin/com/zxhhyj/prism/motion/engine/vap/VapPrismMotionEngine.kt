package com.zxhhyj.prism.motion.engine.vap

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.zxhhyj.prism.motion.PrismAndroidSources
import com.zxhhyj.prism.motion.PrismMotionEngine
import com.zxhhyj.prism.motion.PrismMotionEngineId
import com.zxhhyj.prism.motion.PrismMotionFrameObservable
import com.zxhhyj.prism.motion.PrismMotionFrameObserver
import com.zxhhyj.prism.motion.PrismMotionResource
import com.zxhhyj.prism.motion.PrismMotionSource
import com.zxhhyj.prism.motion.PrismMotionSurface
import com.zxhhyj.prism.motion.compose.PrismMotionRegistry
import com.zxhhyj.prism.motion.compose.PrismMotionRenderer
import com.zxhhyj.prism.motion.compose.PrismMotionState
import com.zxhhyj.prism.motion.format.PrismAlphaMode
import com.zxhhyj.prism.motion.format.PrismContainer
import com.zxhhyj.prism.motion.format.PrismPayloadKind
import com.zxhhyj.prism.motion.integerPlaybackFps
import com.zxhhyj.prism.motion.requireNativeMetadata
import com.zxhhyj.vap.player.VapAnimation
import com.zxhhyj.vap.player.VapAnimationState
import com.zxhhyj.vap.player.VapComposition
import com.zxhhyj.vap.player.VapPlayerStatus
import com.zxhhyj.vap.player.VapSource
import com.zxhhyj.vap.player.animateVapCompositionAsState
import com.zxhhyj.vap.player.loadVapCompositionAsync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 只打开 Prism 中的 VAP 载荷，复用原版 MediaCodec 播放器与暖待机调度。
 * Opens only Prism VAP payloads using the original MediaCodec player and warm-stage scheduler.
 */
public class PrismMotionVapEngine(context: Context) : PrismMotionEngine {
    private val context = context.applicationContext
    private val loadMutex = Mutex()
    private var cachedAsset: VapSource? = null
    private var cachedComposition: VapComposition? = null

    override val id: PrismMotionEngineId = PrismMotionEngineId.Vap

    /** 在 IO 上按容器载荷判断，不接受裸 MP4。 / Checks the container payload on IO, rejecting bare MP4. */
    override suspend fun supports(source: PrismMotionSource): Boolean = withContext(Dispatchers.IO) {
        PrismAndroidSources.supports(context, source, PrismPayloadKind.Vap)
    }

    /** 校验外层声明后打开有界载荷，文件与资产不提取到临时文件。 / Validates and opens bounded payloads without extracting files or assets. */
    override suspend fun open(source: PrismMotionSource): PrismMotionResource {
        val descriptor = withContext(Dispatchers.IO) { PrismAndroidSources.inspect(context, source) }
        require(descriptor.kind == PrismPayloadKind.Vap) { "VAP requires a Prism video payload" }
        val metadata = descriptor.metadata
        metadata.integerPlaybackFps()
        val vapSource = when (source) {
            is PrismMotionSource.Asset -> VapSource.Region(
                VapSource.Asset(source.path), descriptor.payloadOffset, descriptor.payloadLength,
            )
            is PrismMotionSource.File -> VapSource.Region(
                VapSource.AbsolutePath(source.path), descriptor.payloadOffset, descriptor.payloadLength,
            )
            is PrismMotionSource.Bytes -> withContext(Dispatchers.IO) {
                // 仅显式内存来源复制并校验载荷；SDK 接管副本，不再复制或落盘。
                // Only explicit RAM sources copy and verify the payload; the SDK owns that copy without disk extraction.
                VapSource.Bytes.wrap(PrismContainer.extractPayload(source.bytes, verifyPayload = true))
            }
        }
        val composition = if (source is PrismMotionSource.Asset) {
            // 按原始资产判定缓存资格，键包含完整区间；解码器与播放状态仍按实例独立。
            // Cache only original immutable assets by the full region; decoder sessions and playback state stay independent.
            loadMutex.withLock {
                if (cachedAsset == vapSource) cachedComposition!! else {
                    loadVapCompositionAsync(vapSource).also {
                        cachedAsset = vapSource
                        cachedComposition = it
                    }
                }
            }
        } else {
            // 可变文件每次重读，调用方内存也不跨打开共享解析结果。
            // Reread mutable files and never share parsed caller RAM across opens.
            loadVapCompositionAsync(vapSource)
        }
        val config = composition.config
        metadata.requireNativeMetadata(config.width, config.height, config.totalFrames, config.fps)
        // VAP 公开头部只声明 alpha 是否存在，不提供预乘标记。
        // The public VAP header declares alpha presence, not premultiplication semantics.
        require(config.hasAlpha == (metadata.alphaMode != PrismAlphaMode.Opaque)) {
            "Prism alpha mode does not match video payload"
        }
        return VapAnimationResource(composition)
    }
}

private class VapAnimationResource(val composition: VapComposition) : PrismMotionResource, PrismMotionFrameObservable {
    @Volatile private var frameObserver: PrismMotionFrameObserver? = null

    override fun setFrameObserver(observer: PrismMotionFrameObserver?) {
        frameObserver = observer
    }

    fun onPresented() {
        frameObserver?.onFramePresented(System.nanoTime())
    }
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
        frameObserver = null
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
            onPresented = resource::onPresented,
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

/** 从当前 Android 上下文创建仅含 VAP 的注册表。 / Creates a VAP-only registry from the current Android context. */
@Composable
public fun rememberPrismMotionVapRegistry(): PrismMotionRegistry {
    val context = LocalContext.current.applicationContext
    return remember(context) {
        PrismMotionRegistry(
            engines = listOf(PrismMotionVapEngine(context)),
            renderers = mapOf(PrismMotionEngineId.Vap to PrismMotionVapRenderer),
        )
    }
}
