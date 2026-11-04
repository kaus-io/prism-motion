package com.zxhhyj.kap.compose

import androidx.compose.foundation.AndroidExternalSurface
import androidx.compose.foundation.AndroidExternalSurfaceScope
import androidx.compose.foundation.AndroidExternalSurfaceZOrder
import com.zxhhyj.kap.android.KtxDebugOptions
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import com.zxhhyj.kap.android.KtxAnimationComposition
import com.zxhhyj.kap.android.KtxAnimationSource
import com.zxhhyj.kap.android.KtxPlaybackMode
import com.zxhhyj.kap.android.loadKtxAnimationAsync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Load a KTX2 composition (`PAGFile.Load` analogue) on `Dispatchers.IO`.
 *
 * 在 IO 线程加载 KTX2 composition（对应 PAGFile.Load）。
 */
@Composable
public fun rememberKtxComposition(
    source: KtxAnimationSource,
    onError: ((Throwable) -> Unit)? = null,
): State<KtxAnimationComposition?> {
    val context = LocalContext.current
    val state = remember(source) { mutableStateOf<KtxAnimationComposition?>(null) }
    val currentOnError by rememberUpdatedState(onError)
    LaunchedEffect(source) {
        try {
            state.value = loadKtxAnimationAsync(context, source)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Exception) {
            state.value = null
            currentOnError?.invoke(t) ?: throw t
        }
    }
    return state
}

/** Compatibility alias of [rememberKtxComposition]. / [rememberKtxComposition] 的兼容别名。 */
@Deprecated(
    message = "Use rememberKtxComposition.",
    replaceWith = ReplaceWith("rememberKtxComposition(source, onError)"),
)
@Composable
public fun rememberKtxAnimation(
    source: KtxAnimationSource,
    onError: ((Throwable) -> Unit)? = null,
): State<KtxAnimationComposition?> = rememberKtxComposition(source, onError)

/**
 * Creates a composition-owned [KtxAnimationState]; it is released when the caller
 * leaves composition. Hoist [KtxAnimationState] yourself when it must outlive the
 * screen (that is what keeps the engine and textures warm).
 *
 * 由当前组合持有的 [KtxAnimationState]；离开组合即释放。需要跨页面保活时请自行持有
 * state（这正是引擎/纹理保暖的方式）。
 */
@Composable
public fun rememberKtxAnimationState(
    composition: KtxAnimationComposition,
    playbackMode: KtxPlaybackMode = KtxPlaybackMode.Static,
    repeatCount: Int = 0,
    fps: Int? = null,
): KtxAnimationState {
    val state = remember(composition, playbackMode, fps) {
        KtxAnimationState(composition, playbackMode, fps)
    }
    SideEffect(state) {
        state.repeatCount = repeatCount
    }
    DisposableEffect(state) {
        onDispose { state.release() }
    }
    return state
}

/**
 * Convenience that loads [source] and owns the resulting state. Null while the
 * composition is loading or failed; the layout stays empty in that interval.
 *
 * 便捷重载：加载 [source] 并持有 state；加载中/失败返回 null，布局保持空白。
 */
@Composable
public fun rememberKtxAnimationState(
    source: KtxAnimationSource,
    playbackMode: KtxPlaybackMode = KtxPlaybackMode.Static,
    repeatCount: Int = 0,
    onError: (Throwable) -> Unit = {},
): State<KtxAnimationState?> {
    val composition by rememberKtxComposition(source, onError)
    val loaded = composition
    val player = if (loaded != null) {
        rememberKtxAnimationState(loaded, playbackMode, repeatCount)
    } else {
        null
    }
    return rememberUpdatedState(player)
}

/**
 * Renders a hoisted state. The Surface is sized for [contentScale] so letterbox and
 * crop match Compose's other image widgets.
 *
 * 渲染提升状态。Surface 按 [contentScale] 调整尺寸，使 letterbox / crop 行为与
 * Compose 其他图片组件一致。
 */
@Composable
public fun KtxAnimation(
    animationState: KtxAnimationState,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    val composition = animationState.composition
    val contentWidth = composition.width.coerceAtLeast(1)
    val contentHeight = composition.height.coerceAtLeast(1)
    when (contentScale) {
        ContentScale.Fit, ContentScale.Inside -> {
            BoxWithConstraints(
                modifier = modifier,
                contentAlignment = Alignment.Center,
            ) {
                val density = LocalDensity.current
                val boxWidth = constraints.maxWidth.coerceAtLeast(1)
                val boxHeight = constraints.maxHeight.coerceAtLeast(1)
                val scale = min(
                    boxWidth.toFloat() / contentWidth,
                    boxHeight.toFloat() / contentHeight,
                )
                val width = (contentWidth * scale).roundToInt().coerceAtLeast(1)
                val height = (contentHeight * scale).roundToInt().coerceAtLeast(1)
                key(animationState) {
                    KtxSurfaceHost(
                        animationState = animationState,
                        modifier = Modifier.size(
                            width = with(density) { width.toDp() },
                            height = with(density) { height.toDp() },
                        ),
                    )
                }
            }
        }

        ContentScale.Crop -> {
            BoxWithConstraints(
                modifier = modifier.clipToBounds(),
                contentAlignment = Alignment.Center,
            ) {
                val density = LocalDensity.current
                val boxWidth = constraints.maxWidth.coerceAtLeast(1)
                val boxHeight = constraints.maxHeight.coerceAtLeast(1)
                val scale = maxOf(
                    boxWidth.toFloat() / contentWidth,
                    boxHeight.toFloat() / contentHeight,
                )
                val width = (contentWidth * scale).roundToInt().coerceAtLeast(1)
                val height = (contentHeight * scale).roundToInt().coerceAtLeast(1)
                key(animationState) {
                    KtxSurfaceHost(
                        animationState = animationState,
                        modifier = Modifier.size(
                            width = with(density) { width.toDp() },
                            height = with(density) { height.toDp() },
                        ),
                    )
                }
            }
        }

        else -> {
            // FillBounds / FillWidth / FillHeight: let the Surface fill the host.
            // FillBounds / FillWidth / FillHeight：Surface 填满宿主。
            Box(modifier) {
                key(animationState) {
                    KtxSurfaceHost(
                        animationState = animationState,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
private fun KtxSurfaceHost(
    animationState: KtxAnimationState,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val resizeScope = rememberCoroutineScope()
    val initialize: AndroidExternalSurfaceScope.() -> Unit = {
        onSurface { surface, width, height ->
            val epoch = animationState.onSurfaceAttached(width, height)
            surface.onChanged { newWidth, newHeight ->
                if (newWidth > 0 && newHeight > 0) {
                    // Swapchain recreation can wait on the render thread; keep it off Main.
                    // 交换链重建可能等待渲染线程，放到后台执行，避免阻塞主线程。
                    resizeScope.launch(Dispatchers.Default) {
                        animationState.resize(surface, newWidth, newHeight, epoch)
                    }
                }
            }
            surface.onDestroyed {
                animationState.onSurfaceDestroyed(epoch)
            }
            animationState.runSurface(context, surface, epoch)
        }
    }
    val debug = KtxDebugOptions.load(context)
    val source = animationState.composition.source.let { if (it is KtxAnimationSource.Region) it.source else it }
    val path = when (source) {
        is KtxAnimationSource.Asset -> source.path
        is KtxAnimationSource.File -> source.path
        is KtxAnimationSource.Region -> error("Nested KTX2 regions are not supported")
    }
    val mode = if (debug.surfaceSource.isEmpty() || path == debug.surfaceSource) debug.surfaceMode else "embedded"
    when (mode) {
        "behind" -> AndroidExternalSurface(
            modifier = modifier, isOpaque = false,
            zOrder = AndroidExternalSurfaceZOrder.Behind, onInit = initialize,
        )
        "top" -> AndroidExternalSurface(
            modifier = modifier, isOpaque = false,
            zOrder = AndroidExternalSurfaceZOrder.OnTop, onInit = initialize,
        )
        else -> KtxEmbeddedSurface(animationState = animationState, modifier = modifier)
    }
}
