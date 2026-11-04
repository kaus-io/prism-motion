package com.zxhhyj.vap.player

import androidx.compose.foundation.AndroidEmbeddedExternalSurface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.zxhhyj.vap.decode.VapSurfaceFrameDecoder
import kotlinx.coroutines.awaitCancellation

/**
 * Android `actual` for [VapSurfaceRenderHost]: mount an embedded external surface immediately.
 * The Vulkan session presents the packed frame-0 RGBA texture on this same swapchain
 * before the first MediaCodec frame, so Compose draws no separate still and there is
 * no UI-frame/hardware-layer handoff to flicker.
 *
 * The surface lifecycle is driven by the `onSurface` coroutine block: attach on creation
 * and size change, detach on destruction and again from `finally` as a safety net.
 *
 * [VapSurfaceRenderHost] 的 Android `actual`：立即挂载内嵌 external surface。Vulkan 会话会在
 * 同一个交换链上先 present 打包第 0 帧 RGBA 纹理，再交给 MediaCodec 首帧；Compose 不再
 * 单独画静图，也没有 UI 帧/硬件层交接导致的闪烁。
 *
 * surface 生命周期由 `onSurface` 协程块驱动：创建与尺寸变化时 attach，销毁时 detach，
 * 并由 `finally` 兜底再 detach 一次。
 */
@Composable
internal actual fun VapSurfaceRenderHost(
    animationState: VapAnimationState,
    modifier: Modifier,
) {
    val decoder = animationState.decoder as VapSurfaceFrameDecoder
    // Owner token for this host instance: when two hosts transiently attach the same
    // decoder (e.g. a shared VapAnimationState rendered by two viewports during a
    // navigation fade), the loser's detach must not clear the winner's surface.
    // 本宿主实例的 owner 令牌：两个宿主短暂 attach 同一解码器时（例如导航淡入淡出期间
    // 两个 viewport 同时渲染共享的 VapAnimationState），失败方的 detach 不得清除
    // 胜利方的 surface。
    val attachOwner = remember { Any() }
    AndroidEmbeddedExternalSurface(
        modifier = modifier,
        isOpaque = false,
    ) {
        onSurface { surface, width, height ->
            if (width > 0 && height > 0) {
                decoder.attachOutputSurface(surface, width, height, attachOwner)
            }
            surface.onChanged { w, h ->
                if (w > 0 && h > 0) {
                    decoder.attachOutputSurface(surface, w, h, attachOwner)
                }
            }
            surface.onDestroyed {
                decoder.detachOutputSurface(attachOwner)
            }
            try {
                awaitCancellation()
            } finally {
                decoder.detachOutputSurface(attachOwner)
            }
        }
    }
}
