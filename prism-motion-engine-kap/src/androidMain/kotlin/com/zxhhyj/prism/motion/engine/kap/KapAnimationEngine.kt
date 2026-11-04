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
import com.zxhhyj.prism.motion.PrismMotionSource
import com.zxhhyj.prism.motion.compose.PrismMotionRegistry
import com.zxhhyj.prism.motion.compose.PrismMotionRenderer
import com.zxhhyj.prism.motion.compose.PrismMotionState
import com.zxhhyj.kap.android.KtxAnimationSource
import com.zxhhyj.kap.android.KtxPlaybackMode
import com.zxhhyj.kap.android.loadKtxAnimationAsync
import com.zxhhyj.kap.compose.KtxAnimation
import com.zxhhyj.kap.compose.KtxAnimationState

/** KAP（KTX2 纹理序列 / Vulkan）作为统一门面的引擎实现。 */
public class PrismMotionKapEngine(private val context: Context) : PrismMotionEngine {
    override val id: PrismMotionEngineId = PrismMotionEngineId.Kap

    override fun supports(source: PrismMotionSource): Boolean = when (source) {
        is PrismMotionSource.Asset -> source.path.endsWith(KTX_SUFFIX, ignoreCase = true) ||
                source.path.endsWith(KTX_LEGACY_SUFFIX, ignoreCase = true)
        is PrismMotionSource.File -> source.path.endsWith(KTX_SUFFIX, ignoreCase = true) ||
                source.path.endsWith(KTX_LEGACY_SUFFIX, ignoreCase = true)
        is PrismMotionSource.Bytes -> false
    }

    override suspend fun open(source: PrismMotionSource): PrismMotionResource {
        val ktxSource = when (source) {
            is PrismMotionSource.Asset -> KtxAnimationSource.Asset(source.path)
            is PrismMotionSource.File -> KtxAnimationSource.File(source.path)
            is PrismMotionSource.Bytes -> error("KAP embedded engine does not accept in-memory bytes")
        }
        val composition = loadKtxAnimationAsync(context.applicationContext, ktxSource)
        val state = KtxAnimationState(composition, KtxPlaybackMode.Streaming(ringSize = 32))
        return KapAnimationResource(state)
    }

    private companion object {
        const val KTX_SUFFIX = ".ktx"
        const val KTX_LEGACY_SUFFIX = ".ktx2"
    }
}

private class KapAnimationResource(internal val player: KtxAnimationState) : PrismMotionResource {
    override val width: Int get() = player.composition.width
    override val height: Int get() = player.composition.height
    override val durationMs: Long get() = player.durationMs
    override val hasOutput: Boolean get() = player.hasDisplayContent
    override val progress: Float get() = player.progress
    override val isPlaying: Boolean get() = player.isPlaying

    // 目前只支持 TextureView 宿主：Surface 由 [KtxAnimation] 自己持有。
    override fun attach(surface: PrismMotionSurface, width: Int, height: Int) = Unit
    override fun resize(width: Int, height: Int) = Unit
    override fun detach() = Unit

    override fun play() = player.play()
    override fun pause() = player.pause()
    override fun seekTo(progress: Float) = player.seekTo(progress)
    override fun release() = player.release()
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
