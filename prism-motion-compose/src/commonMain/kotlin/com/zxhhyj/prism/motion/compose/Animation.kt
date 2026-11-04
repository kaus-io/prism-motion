package com.zxhhyj.prism.motion.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import com.zxhhyj.prism.motion.AnimationRequest
import com.zxhhyj.prism.motion.AnimationStatus
import kotlinx.coroutines.CancellationException

/**
 * 按 [registry] 打开 [request]。加载失败是状态的一部分，不抛给组合；
 * 取消异常照常上抛。离开组合时释放资源。
 */
@Composable
public fun rememberAnimationState(
    request: AnimationRequest,
    registry: AnimationRegistry,
): AnimationState {
    var state by remember(request, registry) {
        mutableStateOf(AnimationState(request, null, null, null, AnimationStatus.Preparing))
    }
    LaunchedEffect(request, registry) {
        val engine = registry.engines.engineFor(request)
        if (engine == null) {
            state = AnimationState(request, null, null, null, AnimationStatus.Failed(IllegalStateException("no engine for ${request.source}")))
            return@LaunchedEffect
        }
        try {
            val resource = engine.open(request.source)
            state = AnimationState(
                request = request,
                engine = engine,
                resource = resource,
                renderer = registry.rendererFor(engine.id),
                status = AnimationStatus.Ready,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state = AnimationState(request, engine, null, null, AnimationStatus.Failed(e))
        }
    }
    return state
}

/**
 * 渲染统一状态。当前只支持 [AnimationOutput.TextureView]；
 * 其它宿主在接入前会明确失败，而不是退回另一种合成方式。
 */
@Composable
public fun Animation(
    state: AnimationState,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    output: AnimationOutput = AnimationOutput.TextureView,
) {
    require(output is AnimationOutput.TextureView) {
        "AnimationOutput $output is not implemented yet; use TextureView"
    }
    val renderer = state.renderer ?: return
    if (state.resource == null) return
    renderer.Render(state, modifier, contentScale)
}
