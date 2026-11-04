package com.zxhhyj.prism.motion.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import com.zxhhyj.prism.motion.PrismMotionRequest
import com.zxhhyj.prism.motion.PrismMotionStatus
import kotlinx.coroutines.CancellationException

/**
 * 按 [registry] 打开 [request]。加载失败是状态的一部分，不抛给组合；
 * 取消异常照常上抛。离开组合时释放资源。
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
            state = PrismMotionState(request, null, null, null, PrismMotionStatus.Failed(IllegalStateException("no engine for ${request.source}")))
            return@LaunchedEffect
        }
        try {
            val resource = engine.open(request.source)
            state = PrismMotionState(
                request = request,
                engine = engine,
                resource = resource,
                renderer = registry.rendererFor(engine.id),
                status = PrismMotionStatus.Ready,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state = PrismMotionState(request, engine, null, null, PrismMotionStatus.Failed(e))
        }
    }
    // 离开组合或换请求时释放底层资源；文档里承诺过，之前漏了实现。
    // Releases the resource when leaving composition (or switching requests).
    DisposableEffect(state) {
        onDispose { state.resource?.release() }
    }
    return state
}

/**
 * 渲染统一状态。当前只支持 [PrismMotionOutput.TextureView]；
 * 其它宿主在接入前会明确失败，而不是退回另一种合成方式。
 */
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
