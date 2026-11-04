package com.zxhhyj.vap.decode

import com.zxhhyj.vap.player.VapConfig
import com.zxhhyj.vap.player.VapSource

/**
 * Platform-agnostic VAP decoder session.
 *
 * 与平台无关的 VAP 解码会话。
 *
 * Implementations are provided by platform-specific modules such as
 * `:vap-decode-android` and `:vap-decode-jvm`.
 *
 * 由平台特定模块（如 `:vap-decode-android` 与 `:vap-decode-jvm`）提供实现。
 */
public interface VapFrameDecoder {
    public suspend fun open(
        source: VapSource,
        loop: Boolean = false,
        /**
         * Target present FPS. `null` / `<= 0` follows media PTS.
         * Android: PAG-style wall-clock frame grid when `> 0`; JVM: [VapSpeedControl] pacing.
         *
         * 目标呈现 FPS。`null` 或 `<= 0` 时跟随媒体 PTS。
         * Android：`> 0` 时使用 PAG 风格挂钟帧网格；JVM：使用 [VapSpeedControl] 节奏控制。
         */
        fpsOverride: Int? = null,
    ): VapConfig

    /**
     * Opens a decode session using an already parsed [config].
     * This avoids re-parsing the MP4 container when the caller already has a [VapComposition].
     *
     * 使用已解析的 [config] 打开解码会话，避免调用方已有 [VapComposition] 时重复解析 MP4。
     */
    public suspend fun open(
        source: VapSource,
        config: VapConfig,
        loop: Boolean = false,
        fpsOverride: Int? = null,
    ): VapConfig = open(source, loop, fpsOverride)

    /**
     * Opens the present path (Vulkan session / opening AHB) without starting MediaCodec.
     * Default implementation is [open]. Pair with [startDecode] after a cached frame 0 is on screen.
     *
     * 只打开呈现路径（Vulkan 会话 / opening AHB），不开 MediaCodec。
     * 默认实现等于 [open]。缓存第 0 帧上屏后再调用 [startDecode]。
     */
    public suspend fun openPresent(
        source: VapSource,
        config: VapConfig,
        loop: Boolean = false,
        fpsOverride: Int? = null,
    ): VapConfig = open(source, config, loop, fpsOverride)

    /**
     * Starts MediaCodec after [openPresent]. No-op when [open] already started decode.
     *
     * 在 [openPresent] 之后启动 MediaCodec。[open] 已完整开工时为空操作。
     */
    public suspend fun startDecode(): Unit = Unit

    /**
     * Releases MediaCodec while keeping the present path and last frame.
     * The next [startDecode] seeks to the previous sync of the freeze-frame PTS
     * and drops earlier outputs so playback continues from that frame.
     * No-op when no codec is running.
     *
     * 关掉 MediaCodec，保留呈现路径和最后一帧。下次 [startDecode] 会 seek 到冻帧
     * PTS 的前一个关键帧，并丢掉更早的输出，从这一帧继续播。
     * 没有在跑的 codec 时为空操作。
     */
    public suspend fun stopDecode(): Unit = Unit

    public suspend fun nextFrame(): VapPlatformFrame?
    public fun setDisplaySize(widthPx: Int, heightPx: Int)
    public fun setPlaying(playing: Boolean)

    /**
     * Target present FPS. `0` = follow media PTS.
     * Android `> 0`: PAG-style wall-clock grid (`floor(t * fps)`); can change while open.
     *
     * 目标呈现 FPS。`0` 表示跟随媒体 PTS。
     * Android 上 `> 0`：PAG 风格的挂钟网格（`floor(t * fps)`），会话期间可变更。
     */
    public fun setTargetFrameRate(fps: Int)

    /**
     * Tear down codec/GL session while keeping this instance reusable.
     * Call [open] again to resume. Does not delete temp files from `VapSource.Bytes`.
     *
     * Prefer calling from a coroutine (e.g. Compose `LaunchedEffect`); use [close] from
     * non-coroutine dispose paths.
     *
     * 销毁编解码/GL 会话但保留本实例以便复用，再次调用 [open] 即可恢复。不会删除
     * `VapSource.Bytes` 产生的临时文件。建议在协程中调用（例如 Compose 的 `LaunchedEffect`）；
     * 非协程销毁路径请使用 [close]。
     */
    public suspend fun releaseDecodeSession()

    /** Blocking teardown for non-coroutine dispose (e.g. Compose `DisposableEffect`). 非协程销毁路径的阻塞拆除（例如 Compose `DisposableEffect`）。 */
    public fun close()

    /**
     * Android-only: choose whether decoder frames are emitted as bitmaps or
     * presented directly to a window surface. Default is no-op for platforms
     * without this concept.
     *
     * 仅 Android：选择解码帧以位图形式输出，或直接呈现到窗口 Surface。对没有此概念的平台
     * 默认实现为空操作。
     */
    public fun configurePresentMode(mode: VapPresentMode): Unit = Unit

    /**
     * Android-only: hint the GPU backend used for surface composition.
     * Default is no-op for platforms without this concept.
     *
     * 仅 Android：提示 Surface 合成所用的 GPU 后端。对没有此概念的平台默认实现为空操作。
     */
    public fun configureGpuBackend(backend: VapGpuBackend): Unit = Unit

    /**
     * Advance one frame in the presentation loop.
     *
     * In bitmap mode this returns the next decoded/composited frame.
     * In surface mode the implementation waits for the frame to be presented
     * by the platform compositor.
     *
     * 在呈现循环中推进一帧。位图模式下返回下一帧解码/合成结果；Surface 模式下挂起
     * 直至平台合成器呈现该帧。
     */
    public suspend fun advancePresentedFrame(surfaceMode: Boolean): VapFrameAdvance
}

/**
 * Result of [VapFrameDecoder.advancePresentedFrame].
 *
 * [VapFrameDecoder.advancePresentedFrame] 的返回结果。
 */
public sealed class VapFrameAdvance {
    public data class Bitmap(val frame: VapPlatformFrame) : VapFrameAdvance()
    public data object SurfacePresented : VapFrameAdvance()
    /** Source frame consumed without GPU presentation; progress/iterations still advance. */
    public data object SurfaceSkipped : VapFrameAdvance()
    public data object Ended : VapFrameAdvance()
}

/**
 * Presentation mode for the Android decoder.
 *
 * Android 解码器的呈现模式。
 */
public enum class VapPresentMode {
    /** Decode to offscreen bitmaps; used for Canvas-based Compose rendering. 解码到离屏位图，供基于 Canvas 的 Compose 渲染。 */
    Bitmap,

    /** Decode to a SurfaceTexture-backed window surface; used for SurfaceView rendering. 解码到 SurfaceTexture 支撑的窗口 Surface，供 SurfaceView 渲染。 */
    Surface,
}
