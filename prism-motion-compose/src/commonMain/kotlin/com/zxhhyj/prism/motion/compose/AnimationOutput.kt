package com.zxhhyj.prism.motion.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import com.zxhhyj.prism.motion.AnimationEngine
import com.zxhhyj.prism.motion.AnimationEngineId
import com.zxhhyj.prism.motion.AnimationEngineRegistry
import com.zxhhyj.prism.motion.AnimationRequest
import com.zxhhyj.prism.motion.AnimationResource
import com.zxhhyj.prism.motion.AnimationSurface
import com.zxhhyj.prism.motion.AnimationStatus

/** 输出宿主。目前只实现 TextureView，其余两个保留接口。 */
public sealed interface AnimationOutput {
    public data object TextureView : AnimationOutput

    /** 独立 Surface，省 HWUI 合成；不能裁 Compose 形状。暂未实现。 */
    public data object SurfaceView : AnimationOutput

    /** 调用方自己持有输出面；生命周期归调用方。暂未实现。 / Caller-owned surface; not implemented yet. */
    public data class External(public val surface: AnimationSurface) : AnimationOutput
}

/** 引擎提供的渲染实现。当前契约只要求支持 [AnimationOutput.TextureView]。 */
public interface AnimationRenderer {
    @Composable
    public fun Render(state: AnimationState, modifier: Modifier, contentScale: ContentScale)
}

/**
 * 一次动画的统一状态。加载失败也是状态的一部分（[AnimationStatus.Failed]），
 * 与 Coil 的 painter state 一致；没有资源时所有播放指令都是空操作。
 */
@Stable
public class AnimationState internal constructor(
    public val request: AnimationRequest,
    public val engine: AnimationEngine?,
    public val resource: AnimationResource?,
    internal val renderer: AnimationRenderer?,
    status: AnimationStatus,
) {
    public var status: AnimationStatus by mutableStateOf(status)
        internal set

    public var isPlaying: Boolean by mutableStateOf(false)
        private set

    public val hasOutput: Boolean get() = resource?.hasOutput == true
    public val progress: Float get() = resource?.progress ?: 0f
    public val durationMs: Long get() = resource?.durationMs ?: 0L
    public val width: Int get() = resource?.width ?: 0
    public val height: Int get() = resource?.height ?: 0

    public fun play() {
        resource?.play() ?: return
        isPlaying = true
    }

    public fun pause() {
        resource?.pause() ?: return
        isPlaying = false
    }

    public fun seekTo(progress: Float) {
        resource?.seekTo(progress)
    }

    public fun release() {
        resource?.release()
    }
}

/** 引擎 + 渲染器的注册表；显式引擎优先，否则按支持能力选择。 */
public class AnimationRegistry(
    engines: List<AnimationEngine>,
    private val renderers: Map<AnimationEngineId, AnimationRenderer>,
) {
    internal val engines = AnimationEngineRegistry(engines)

    internal fun rendererFor(id: AnimationEngineId): AnimationRenderer? = renderers[id]
}
