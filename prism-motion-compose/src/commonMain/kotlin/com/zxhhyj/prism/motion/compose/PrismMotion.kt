package com.zxhhyj.prism.motion.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import com.zxhhyj.prism.motion.PrismMotionRequest
import com.zxhhyj.prism.motion.PrismMotionResource
import com.zxhhyj.prism.motion.PrismMotionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * 按请求加载并持有资源；只在请求更换或离开组合时释放，不在 Preparing → Ready 时释放。
 * Owns a loaded resource until the request changes or composition ends, not on Preparing → Ready.
 */
@Composable
public fun rememberPrismMotionState(
    request: PrismMotionRequest,
    registry: PrismMotionRegistry,
): PrismMotionState {
    var state by remember(request, registry) {
        mutableStateOf(PrismMotionState(request, null, null, null, PrismMotionStatus.Preparing))
    }
    LaunchedEffect(request, registry) {
        val engine = registry.engines.engineFor(request)
        if (engine == null) {
            state = PrismMotionState(request, null, null, null, PrismMotionStatus.Failed(
                IllegalStateException("no engine for ${request.source}"),
            ))
            return@LaunchedEffect
        }
        try {
            ownPrismMotionResource(open = { engine.open(request.source) }) { resource ->
                resource.setRepeatCount(request.repeatCount)
                if (request.autoPlay) resource.play() else resource.pause()
                state = PrismMotionState(request, engine, resource, registry.rendererFor(engine.id), PrismMotionStatus.Ready)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state = PrismMotionState(request, engine, null, null, PrismMotionStatus.Failed(e))
        }
    }
    return state
}

/**
 * 资源所有权绑定加载协程，覆盖取消和发布失败；回收的是本次打开的实例。
 * Binds ownership to the loading coroutine, releasing the exact instance on cancellation or publication failure.
 */
internal suspend fun ownPrismMotionResource(
    open: suspend () -> PrismMotionResource,
    publish: (PrismMotionResource) -> Unit,
) {
    var owned: PrismMotionResource? = null
    try {
        val resource = open()
        owned = resource
        currentCoroutineContext().ensureActive()
        publish(resource)
        awaitCancellation()
    } finally {
        owned?.release()
    }
}

/** 按引擎渲染；未实现的输出宿主明确报错。 / Renders through the engine; unsupported hosts fail explicitly. */
@Composable
public fun PrismMotion(
    state: PrismMotionState,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    output: PrismMotionOutput = PrismMotionOutput.TextureView,
) {
    require(output is PrismMotionOutput.TextureView) {
        "PrismMotionOutput $output is not implemented yet; use TextureView"
    }
    val renderer = state.renderer ?: return
    if (state.resource == null) return
    renderer.Render(state, modifier, contentScale)
}
