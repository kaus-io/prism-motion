package com.zxhhyj.vap.decode

import android.view.Surface

/**
 * Android-specific extension of [VapFrameDecoder] for Surface output mode.
 * Android 平台专属的 [VapFrameDecoder] 扩展接口，用于 Surface 输出模式。
 *
 * Instances are created directly by [createAndroidVapFrameDecoder] when the Android
 * source set is selected; no global registry or startup initialization is required.
 * Android source set 通过 [createAndroidVapFrameDecoder] 直接创建实例，
 * 不需要全局注册表或启动初始化。
 *
 * Implementations must be safe to call [attachOutputSurface] / [detachOutputSurface]
 * repeatedly across the lifetime of a single decoder (Compose remount / surface loss).
 * 实现必须保证在单个解码器生命周期内，可重复调用 [attachOutputSurface] 与
 * [detachOutputSurface]（Compose 重组 / Surface 丢失场景）。
 */
public interface VapSurfaceFrameDecoder : VapFrameDecoder {
    /**
     * Bind an output [Surface]; the last attach wins. [owner] is an opaque token identifying
     * the attaching host: a later [detachOutputSurface] only clears the attachment when its
     * owner matches the current one, so a disposed host can never tear down a newer host's
     * surface (two hosts may transiently attach the same decoder during Compose transitions).
     *
     * 绑定输出 [Surface]；后 attach 者生效。[owner] 是标识挂载方的不透明令牌：
     * 之后的 [detachOutputSurface] 仅在 owner 与当前挂载方一致时才清除挂载，
     * 因此已销毁的宿主永远不会拆掉更新宿主的 surface（Compose 转场期间两个宿主可能
     * 短暂同时 attach 同一解码器）。
     */
    public fun attachOutputSurface(surface: Surface, width: Int, height: Int, owner: Any? = null)
    public fun resizeOutput(width: Int, height: Int)

    /**
     * Detach the current output. With a non-null [owner] this is a no-op when the current
     * attachment belongs to a different owner; `null` always force-clears (legacy path).
     *
     * 解绑当前输出。[owner] 非空时，若当前挂载属于其他 owner 则本调用为空操作；
     * `null` 总是强制清除（旧路径）。
     */
    public fun detachOutputSurface(owner: Any? = null)
    public suspend fun awaitFramePresented(): Boolean

    /**
     * Packed frame 0 as ARGB pixels in retriever orientation. Vulkan uploads them as an RGBA
     * texture and presents it onto the output Surface before MediaCodec's first frame.
     * Pass `null` to clear.
     *
     * 打包第 0 帧 ARGB 像素，方向与 retriever 第 0 帧一致。Vulkan 会把它上传为 RGBA 纹理，
     * 并在 MediaCodec 首帧之前 present 到输出 Surface。传 `null` 清除。
     */
    public fun setOpeningFrame(pixels: IntArray?, width: Int, height: Int) {}
}
