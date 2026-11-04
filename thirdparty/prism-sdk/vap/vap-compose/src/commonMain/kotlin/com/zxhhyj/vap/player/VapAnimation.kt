package com.zxhhyj.vap.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

/**
 * Top-level Composable that drives a VAP animation from a pre-built [VapAnimationState].
 * Dispatches to the Surface or Canvas draw path based on the state's present mode.
 *
 * 使用已构建的 [VapAnimationState] 驱动 VAP 动画的顶层 Composable。
 * 根据 state 的 present 模式分发到 Surface 或 Canvas 绘制路径。
 *
 * @param animationState Shared state returned by [animateVapCompositionAsState].
 * [animateVapCompositionAsState] 返回的共享状态。
 * @param modifier Standard Compose modifier applied to the host. 应用到宿主的标准 Compose modifier。
 * @param contentScale How the VAP content is scaled within the host bounds.
 * VAP 内容在宿主边界内的缩放方式。
 */
@Composable
public fun VapAnimation(
    animationState: VapAnimationState,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    rememberVapOpeningFrame(animationState)
    if (animationState.usesSurfaceHost) {
        VapSurfaceHostScaled(
            animationState = animationState,
            contentScale = contentScale,
            modifier = modifier,
        )
    } else {
        VapCanvasAnimation(
            animationState = animationState,
            modifier = modifier,
            contentScale = contentScale,
        )
    }
}

/**
 * Surface path previously stretched to fill the Compose box (unlike Canvas `ContentScale.Fit`
 * and PAG LetterBox). Letterbox / fill here so WindowSurface matches.
 *
 * Surface 路径此前会被拉伸填满 Compose 盒子（与 Canvas 的 `ContentScale.Fit` 和 PAG LetterBox 不同）。
 * 这里统一做 letterbox / fill，使 WindowSurface 与其他路径行为一致。
 */
@Composable
internal fun VapSurfaceHostScaled(
    animationState: VapAnimationState,
    contentScale: ContentScale,
    modifier: Modifier,
) {
    val contentW = animationState.contentWidth
    val contentH = animationState.contentHeight
    // Wait for logical content size so we don't create a fill-parent Surface and then
    // tear it down for letterbox (Vulkan swapchain sees SURFACE_LOST on the stale window).
    // 等待逻辑内容尺寸就绪，避免先创建填满父容器的 Surface、再为 letterbox 拆除它
    // （旧窗口上的 Vulkan swapchain 会因此报 SURFACE_LOST）。
    if (contentW <= 0 || contentH <= 0) {
        Box(modifier)
        return
    }
    val letterbox = contentScale == ContentScale.Fit ||
        contentScale == ContentScale.Inside
    val cover = contentScale == ContentScale.Crop
    if (!letterbox && !cover) {
        VapSurfaceRenderHost(
            animationState = animationState,
            modifier = modifier,
        )
        return
    }

    BoxWithConstraints(
        modifier = if (cover) modifier.clipToBounds() else modifier,
        contentAlignment = Alignment.Center,
    ) {
        val density = LocalDensity.current
        val boxW = constraints.maxWidth.coerceAtLeast(1)
        val boxH = constraints.maxHeight.coerceAtLeast(1)
        val scale = if (cover) {
            maxOf(
                boxW.toFloat() / contentW.toFloat(),
                boxH.toFloat() / contentH.toFloat(),
            )
        } else {
            minOf(
                boxW.toFloat() / contentW.toFloat(),
                boxH.toFloat() / contentH.toFloat(),
            )
        }
        val hostW = (contentW * scale).roundToInt().coerceAtLeast(1)
        val hostH = (contentH * scale).roundToInt().coerceAtLeast(1)
        VapSurfaceRenderHost(
            animationState = animationState,
            modifier = Modifier.size(
                width = with(density) { hostW.toDp() },
                height = with(density) { hostH.toDp() },
            ),
        )
    }
}

/**
 * Canvas draw path: a `Spacer` that draws the latest decoded frame via [drawVapPlatformFrame]
 * inside `drawBehind`. Forwards size changes to the state.
 *
 * Canvas 绘制路径：在 `Spacer` 的 `drawBehind` 中通过 [drawVapPlatformFrame] 绘制最新解码帧，
 * 并把尺寸变化转发给 state。
 */
@Composable
internal fun VapCanvasAnimation(
    animationState: VapAnimationState,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    Spacer(
        modifier = modifier
            .onSizeChanged { size ->
                animationState.setDisplaySize(size.width, size.height)
            }
            .drawBehind {
                // Reading drawGeneration snapshots the state so recomposition/repaint
                // is triggered each time a new frame is published.
                // 读取 drawGeneration 使 state 被快照订阅，从而在新帧发布时触发重绘。
                animationState.drawGeneration
                val frame = animationState.currentFrame()
                    ?: animationState.openingFrame
                    ?: return@drawBehind
                val dst: IntSize
                val origin: IntOffset
                when (contentScale) {
                    ContentScale.Fit, ContentScale.Inside -> {
                        val scale = minOf(
                            size.width / frame.width.coerceAtLeast(1),
                            size.height / frame.height.coerceAtLeast(1),
                        )
                        val w = (frame.width * scale).roundToInt().coerceAtLeast(1)
                        val h = (frame.height * scale).roundToInt().coerceAtLeast(1)
                        dst = IntSize(w, h)
                        origin = IntOffset(
                            ((size.width - w) / 2f).roundToInt(),
                            ((size.height - h) / 2f).roundToInt(),
                        )
                    }

                    else -> {
                        dst = IntSize(
                            size.width.toInt().coerceAtLeast(1),
                            size.height.toInt().coerceAtLeast(1),
                        )
                        origin = IntOffset.Zero
                    }
                }
                drawVapPlatformFrame(
                    frame = frame,
                    dstOffset = origin,
                    dstSize = dst,
                )
            },
    )
}

/**
 * Encapsulated player: builds the [VapAnimationState] internally and binds it to this
 * composable subtree's lifetime. For page keep-alive, hoist [animateVapCompositionAsState]
 * and use the state-based [VapAnimation] instead. Overlay UIs (loading / error) can be
 * built on [VapAnimationState.status] with the state-based overload.
 *
 * 封装播放器：内部构建 [VapAnimationState]，生命周期绑定当前 Composable 子树。
 * 若要切页保活，请提升 [animateVapCompositionAsState] 并使用 state 版 [VapAnimation]；
 * loading / error 等叠加层可基于 state 版的 [VapAnimationState.status] 自行叠加。
 *
 * @param composition Loaded VAP, or `null` to idle. 已加载的 VAP；`null` 表示空闲。
 * @param request Immutable playback options. 不可变播放选项。
 * @param modifier Standard Compose modifier applied to the host. 应用到宿主的标准 Compose modifier。
 * @param contentScale Content scaling strategy within the host. 宿主内的内容缩放策略。
 */
@Composable
public fun VapAnimation(
    composition: VapComposition?,
    request: VapRequest,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    val animationState = animateVapCompositionAsState(
        composition = composition,
        request = request,
    )
    VapAnimation(
        animationState = animationState,
        modifier = modifier,
        contentScale = contentScale,
    )
}

@Composable
internal expect fun rememberVapOpeningFrame(animationState: VapAnimationState)

internal expect fun hasCachedVapOpeningFrame(composition: VapComposition?): Boolean

/**
 * Extracts packed frame 0 into the shared poster cache. Android blocking; JVM no-op.
 * Default for both [loadVapComposition] and [loadVapCompositionAsync]; the async
 * path just moves this work off the main thread.
 *
 * 把打包第 0 帧抽进共用海报缓存。Android 上阻塞；JVM 为空操作。
 * [loadVapComposition] 与 [loadVapCompositionAsync] 的默认行为；异步只是把
 * 这份工作挪出主线程。
 */
internal expect fun warmVapOpeningFrame(composition: VapComposition)

/** Best-effort Android Vulkan warmup; JVM no-op. / Android Vulkan 预热；JVM 空操作。 */
internal expect fun prewarmVapGpu()
