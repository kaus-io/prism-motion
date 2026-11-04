package com.zxhhyj.prism.motion.engine.kap

import android.content.Context
import com.zxhhyj.prism.motion.PrismMotionSurface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import com.zxhhyj.prism.motion.PrismMotionEngine
import com.zxhhyj.prism.motion.PrismMotionEngineId
import com.zxhhyj.prism.motion.PrismMotionResource
import com.zxhhyj.prism.motion.PrismMotionFrameObservable
import com.zxhhyj.prism.motion.PrismMotionFrameObserver
import com.zxhhyj.kap.compose.KtxAnimationListener
import com.zxhhyj.prism.motion.PrismMotionSource
import com.zxhhyj.prism.motion.PrismAndroidSources
import com.zxhhyj.prism.motion.integerPlaybackFps
import com.zxhhyj.prism.motion.requireNativeMetadata
import com.zxhhyj.prism.motion.format.PrismAlphaMode
import com.zxhhyj.prism.motion.format.PrismPayloadKind
import com.zxhhyj.prism.motion.compose.PrismMotionRegistry
import com.zxhhyj.prism.motion.compose.PrismMotionRenderer
import com.zxhhyj.prism.motion.compose.PrismMotionState
import com.zxhhyj.kap.android.KtxAnimationSource
import com.zxhhyj.kap.android.KtxPlaybackMode
import com.zxhhyj.kap.android.loadKtxAnimationAsync
import com.zxhhyj.kap.compose.KtxAnimation
import com.zxhhyj.kap.compose.KtxAnimationState
import com.zxhhyj.kap.android.KtxAnimationComposition
import com.zxhhyj.kap.android.KtxPlayerStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 复用原版 KAP 的流式 Vulkan 路径，不在门面层复制帧或替换调度器。
 * Uses the original streaming Vulkan path without copying frames or replacing its scheduler.
 */
public class PrismMotionKapEngine(
    context: Context,
    private val playbackMode: KtxPlaybackMode = KtxPlaybackMode.Streaming(ringSize = 32),
) : PrismMotionEngine {
    private val context = context.applicationContext
    private val loadMutex = Mutex()
    private var cachedAsset: KtxAnimationSource.Region? = null
    private var cachedComposition: KtxAnimationComposition? = null

    override val id: PrismMotionEngineId = PrismMotionEngineId.Kap

    override suspend fun supports(source: PrismMotionSource): Boolean = withContext(Dispatchers.IO) {
        PrismAndroidSources.supports(context, source, PrismPayloadKind.Ktx2, allowBytes = false)
    }

    override suspend fun open(source: PrismMotionSource): PrismMotionResource {
        val baseSource = when (source) {
            is PrismMotionSource.Asset -> KtxAnimationSource.Asset(source.path)
            is PrismMotionSource.File -> KtxAnimationSource.File(source.path)
            is PrismMotionSource.Bytes -> error("KAP embedded engine does not accept in-memory bytes")
        }
        val descriptor = withContext(Dispatchers.IO) { PrismAndroidSources.inspect(context, source) }
        require(descriptor.kind == PrismPayloadKind.Ktx2) { "KAP requires a Prism texture payload" }
        val metadata = descriptor.metadata
        metadata.integerPlaybackFps()
        val ktxSource = KtxAnimationSource.Region(baseSource, descriptor.payloadOffset, descriptor.payloadLength)
        val composition = if (source is PrismMotionSource.Asset) {
            // 同资产并发打开只解析一次头部；不缓存播放器/纹理，文件源仍每次重读。
            // Coalesce immutable asset headers, never players/textures; mutable files are reloaded.
            loadMutex.withLock {
                if (cachedAsset == ktxSource) cachedComposition!! else {
                    loadKtxAnimationAsync(context, ktxSource).also {
                        cachedAsset = ktxSource
                        cachedComposition = it
                    }
                }
            }
        } else {
            loadKtxAnimationAsync(context, ktxSource)
        }
        metadata.requireNativeMetadata(composition.width, composition.height, composition.frameCount, composition.fps)
        if (metadata.alphaMode != PrismAlphaMode.Opaque) {
            require(composition.info.premultiplied == (metadata.alphaMode == PrismAlphaMode.Premultiplied)) {
                "Prism alpha mode does not match texture payload"
            }
        }
        return KapAnimationResource(KtxAnimationState(composition, playbackMode))
    }
}

private class KapAnimationResource(internal val player: KtxAnimationState) : PrismMotionResource, PrismMotionFrameObservable {
    @Volatile private var frameObserver: PrismMotionFrameObserver? = null
    private val frameListener = object : KtxAnimationListener {
        override fun onUpdate(progress: Float) {
            frameObserver?.onFramePresented(System.nanoTime())
        }
    }

    override fun setFrameObserver(observer: PrismMotionFrameObserver?) {
        frameObserver = observer
        player.listener = if (observer == null) null else frameListener
    }
    override val width: Int get() = player.composition.width
    override val height: Int get() = player.composition.height
    override val durationMs: Long get() = player.durationMs
    override val hasOutput: Boolean get() = player.hasDisplayContent
    override val progress: Float get() = player.progress
    override val isPlaying: Boolean get() = player.isPlaying
    override val presentedFrameCount: Long get() = player.presentedCount
    override val playbackError: Throwable? get() = (player.status as? KtxPlayerStatus.Failed)?.error
    override fun setRepeatCount(repeatCount: Int) {
        require(repeatCount >= -1)
        player.repeatCount = repeatCount
    }

    // 目前只支持 TextureView 宿主：Surface 由 [KtxAnimation] 自己持有。
    override fun attach(surface: PrismMotionSurface, width: Int, height: Int) = Unit
    override fun resize(width: Int, height: Int) = Unit
    override fun detach() = Unit

    override fun play() = player.play()
    override fun pause() = player.pause()
    override fun seekTo(progress: Float) = player.seekTo(progress)
    override fun release() {
        setFrameObserver(null)
        player.release()
    }
}

/** 用 KAP 的统一渲染器；宿主仍是 [KtxAnimation] 的 TextureView。 */
public object PrismMotionKapRenderer : PrismMotionRenderer {
    @Composable
    override fun Render(state: PrismMotionState, modifier: Modifier, contentScale: ContentScale) {
        val player = (state.resource as? KapAnimationResource)?.player ?: return
        KtxAnimation(
            animationState = player,
            modifier = modifier,
            contentScale = contentScale,
        )
    }
}

/** 便捷入口：只注册 KAP 一个引擎。 */
@Composable
public fun rememberPrismMotionKapRegistry(context: Context): PrismMotionRegistry = remember(context) {
    PrismMotionRegistry(
        engines = listOf(PrismMotionKapEngine(context)),
        renderers = mapOf(PrismMotionEngineId.Kap to PrismMotionKapRenderer),
    )
}
