package com.zxhhyj.vap.player

import com.zxhhyj.vap.decode.VapGpuBackend

/**
 * Immutable playback options for [VapAnimation] / [animateVapCompositionAsState].
 * Modeled on Coil's `ImageRequest`: the composable stays `composition + modifier + contentScale`,
 * while how to play lives on this value object.
 *
 * [VapAnimation] / [animateVapCompositionAsState] 的不可变播放选项。对齐 Coil 的
 * `ImageRequest`：Composable 只留 composition / modifier / contentScale，怎么播放在本对象上。
 *
 * @see Builder
 */
public class VapRequest private constructor(
    public val isPlaying: Boolean,
    public val iterations: Int,
    public val fps: Int?,
    public val gpuBackend: VapGpuBackend?,
    public val presentFirstFrameEagerly: Boolean,
    public val listener: Listener?,
    public val defaults: Defaults,
) {
    public fun newBuilder(): Builder = Builder(this)

    /**
     * Playback callbacks. Empty defaults so callers override only what they need.
     * Mirrors Coil's `ImageRequest.Listener` (`onStart` / `onSuccess` / `onError` / `onCancel`).
     * [onCompleted] is VAP-only (natural end of iterations).
     *
     * 播放回调。默认空实现，调用方只覆盖需要的方法。对齐 Coil 的
     * `ImageRequest.Listener`（`onStart` / `onSuccess` / `onError` / `onCancel`）。
     * [onCompleted] 是 VAP 独有（全部迭代自然结束）。
     */
    public interface Listener {
        /** Session started for a composition. / 某个 composition 的会话开始。 */
        public fun onStart() {}

        /**
         * Coil `onSuccess`: first opening or decoded frame is on the host.
         * Once per composition session.
         *
         * 对标 Coil 的 `onSuccess`：第 0 帧或首帧解码结果已在宿主上。每个 composition 会话一次。
         */
        public fun onSuccess() {}

        /** Decode/present failure; non-fatal if handled. / 解码或呈现失败；处理后可继续。 */
        public fun onError(error: Throwable) {}

        /**
         * Session cancelled before [onSuccess] (left the tree, composition cleared).
         *
         * 在 [onSuccess] 之前会话被取消（离开组合树、composition 被清空）。
         */
        public fun onCancel() {}

        /** Natural end of all iterations (not every loop). VAP-only. / 全部迭代自然结束（不是每一圈）。VAP 独有。 */
        public fun onCompleted() {}

        /**
         * High-frequency hook: invoked once per present, right after the state's presented
         * count bumps. Runs on the present-loop thread; implementations must be
         * allocation-free. Intended for benchmarks / diagnostics.
         *
         * 高频钩子：每次 present 后触发一次，紧随 state 的 present 计数递增。运行于
         * present 循环线程；实现必须无分配。供基准 / 诊断使用。
         */
        public fun onPresented() {}
    }

    /**
     * Values used when a [Builder] field is left unset.
     *
     * [Builder] 未设置字段时使用的默认值。
     */
    public class Defaults(
        public val isPlaying: Boolean = true,
        public val iterations: Int = 1,
        public val fps: Int? = null,
        public val gpuBackend: VapGpuBackend? = null,
        public val presentFirstFrameEagerly: Boolean = true,
        public val listener: Listener? = null,
    ) {
        public companion object {
            public val Standard: Defaults = Defaults()
        }
    }

    /**
     * Builds a [VapRequest]. Unset fields fall back to [defaults].
     *
     * 构建 [VapRequest]。未设置的字段回退到 [defaults]。
     */
    public class Builder {
        private var defaults: Defaults = processDefaults
        private var isPlaying: Boolean? = null
        private var iterations: Int? = null
        private var fps: Int? = null
        private var fpsDefined: Boolean = false
        private var gpuBackend: VapGpuBackend? = null
        private var gpuBackendDefined: Boolean = false
        private var presentFirstFrameEagerly: Boolean? = null
        private var listener: Listener? = null
        private var listenerDefined: Boolean = false

        public constructor()

        public constructor(request: VapRequest) {
            defaults = request.defaults
            isPlaying = request.isPlaying
            iterations = request.iterations
            fps = request.fps
            fpsDefined = true
            gpuBackend = request.gpuBackend
            gpuBackendDefined = true
            presentFirstFrameEagerly = request.presentFirstFrameEagerly
            listener = request.listener
            listenerDefined = true
        }

        public fun defaults(defaults: Defaults): Builder = apply { this.defaults = defaults }

        /** Play/pause gate. Independent of decoder lifetime. / 播放门，与解码器生命周期独立。 */
        public fun playing(isPlaying: Boolean): Builder = apply { this.isPlaying = isPlaying }

        /**
         * Play count; [VapConstants.IterateForever] loops indefinitely.
         *
         * 播放次数；[VapConstants.IterateForever] 表示无限循环。
         */
        public fun iterations(iterations: Int): Builder = apply { this.iterations = iterations }

        /**
         * Target present FPS. `null` follows media PTS.
         *
         * 目标呈现帧率。`null` 跟随媒体时间戳。
         * Android Vulkan: rates below the source FPS skip presentation frames while
         * preserving the source timeline; they do not slow down the animation.
         * Android Vulkan：低于素材帧率时跳过部分呈现帧，保留原时间轴，不降低动画速度。
         */
        public fun fps(fps: Int?): Builder = apply {
            this.fps = fps
            fpsDefined = true
        }

        public fun gpuBackend(gpuBackend: VapGpuBackend?): Builder = apply {
            this.gpuBackend = gpuBackend
            gpuBackendDefined = true
        }

        /**
         * Open the present path without waiting for [playing], so frame 0 can land as soon as
         * the Surface/Canvas exists. MediaCodec still waits for [playing]. Defaults to `true`;
         * set `false` to defer present-path open until playback starts.
         *
         * 不等 [playing] 就打开呈现路径，让第 0 帧尽快落到已有 Surface / Canvas 上。
         * MediaCodec 仍等 [playing]。默认为 `true`；设为 `false` 则推迟到开始播放时才打开呈现路径。
         */
        public fun presentFirstFrameEagerly(enabled: Boolean): Builder = apply {
            presentFirstFrameEagerly = enabled
        }

        public fun listener(listener: Listener?): Builder = apply {
            this.listener = listener
            listenerDefined = true
        }

        /**
         * Convenience [Listener] that only overrides the supplied callbacks.
         *
         * 便捷 [Listener]，只覆盖传入的回调。
         */
        public inline fun listener(
            crossinline onStart: () -> Unit = {},
            crossinline onSuccess: () -> Unit = {},
            crossinline onError: (Throwable) -> Unit = {},
            crossinline onCancel: () -> Unit = {},
            crossinline onCompleted: () -> Unit = {},
            crossinline onPresented: () -> Unit = {},
        ): Builder = listener(
            object : Listener {
                override fun onStart() = onStart()
                override fun onSuccess() = onSuccess()
                override fun onError(error: Throwable) = onError(error)
                override fun onCancel() = onCancel()
                override fun onCompleted() = onCompleted()
                override fun onPresented() = onPresented()
            },
        )

        public fun onStart(callback: (() -> Unit)?): Builder = apply {
            if (callback == null) return@apply
            listener(overlay(listener, onStart = callback))
        }

        public fun onSuccess(callback: (() -> Unit)?): Builder = apply {
            if (callback == null) return@apply
            listener(overlay(listener, onSuccess = callback))
        }

        public fun onCancel(callback: (() -> Unit)?): Builder = apply {
            if (callback == null) return@apply
            listener(overlay(listener, onCancel = callback))
        }

        public fun onCompleted(callback: (() -> Unit)?): Builder = apply {
            if (callback == null) return@apply
            listener(overlay(listener, onCompleted = callback))
        }

        public fun onError(callback: ((Throwable) -> Unit)?): Builder = apply {
            if (callback == null) return@apply
            listener(overlay(listener, onError = callback))
        }

        /**
         * Per-present hook, see [Listener.onPresented]. High-frequency; keep the
         * implementation allocation-free.
         *
         * 逐帧 present 钩子，见 [Listener.onPresented]。高频触发；实现应保持无分配。
         */
        public fun onPresented(callback: (() -> Unit)?): Builder = apply {
            if (callback == null) return@apply
            listener(overlay(listener, onPresented = callback))
        }

        private fun overlay(
            previous: Listener?,
            onStart: (() -> Unit)? = null,
            onSuccess: (() -> Unit)? = null,
            onError: ((Throwable) -> Unit)? = null,
            onCancel: (() -> Unit)? = null,
            onCompleted: (() -> Unit)? = null,
            onPresented: (() -> Unit)? = null,
        ): Listener = object : Listener {
            override fun onStart() {
                if (onStart != null) onStart() else previous?.onStart()
            }

            override fun onSuccess() {
                if (onSuccess != null) onSuccess() else previous?.onSuccess()
            }

            override fun onError(error: Throwable) {
                if (onError != null) onError(error) else previous?.onError(error)
            }

            override fun onCancel() {
                if (onCancel != null) onCancel() else previous?.onCancel()
            }

            override fun onCompleted() {
                if (onCompleted != null) onCompleted() else previous?.onCompleted()
            }

            override fun onPresented() {
                if (onPresented != null) onPresented() else previous?.onPresented()
            }
        }

        public fun build(): VapRequest {
            val d = defaults
            return VapRequest(
                isPlaying = isPlaying ?: d.isPlaying,
                iterations = iterations ?: d.iterations,
                fps = if (fpsDefined) fps else d.fps,
                gpuBackend = if (gpuBackendDefined) gpuBackend else d.gpuBackend,
                presentFirstFrameEagerly = presentFirstFrameEagerly ?: d.presentFirstFrameEagerly,
                listener = if (listenerDefined) listener else d.listener,
                defaults = d,
            )
        }
    }

    public companion object {
        /**
         * Process-level fallbacks for unset [Builder] fields, analogous to Coil's
         * `ImageLoader.defaults`. Set once in `Application.onCreate`.
         *
         * 未设置的 [Builder] 字段的进程级回退，对标 Coil 的 `ImageLoader.defaults`。
         * 在 `Application.onCreate` 里设一次。
         */
        public var processDefaults: Defaults = Defaults.Standard

        /**
         * Request built from [processDefaults]. Rebuilt on each access so later
         * [processDefaults] changes are visible.
         *
         * 用 [processDefaults] 构建的请求。每次访问都会重建，以便吃到后续的
         * [processDefaults] 变更。
         */
        public val Default: VapRequest
            get() = Builder().build()
    }
}
