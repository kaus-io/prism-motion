package com.zxhhyj.vap.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.zxhhyj.vap.decode.VapFrameAdvance
import com.zxhhyj.vap.decode.VapFrameDecoder
import com.zxhhyj.vap.decode.VapGpu
import com.zxhhyj.vap.decode.VapGpuBackend
import com.zxhhyj.vap.decode.VapPlatformFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Fire-and-forget scope for decoder teardown on Compose dispose. Codec stop/release and
 * Vulkan destroy block on the `vap-vk-shared` thread, so they must not run on Main.
 *
 * Compose dispose 时解码器拆除用的 fire-and-forget scope。codec stop/release 与 Vulkan
 * destroy 会阻塞等待 `vap-vk-shared` 线程，因此不得在 Main 上执行。
 */
internal val vapDecoderTeardownScope = CoroutineScope(Dispatchers.IO)

/**
 * Single source of truth for one VAP playback: holds the most recent decoded frame and
 * exposes Compose-readable progress/playing flags. Read-only for callers — playback is
 * controlled by the [VapRequest] flowing into `animateVapCompositionAsState`.
 *
 * 一次 VAP 播放的唯一状态源：持有最近解码的帧，对外暴露 Compose 可读的 progress / playing
 * 标志。对调用方只读 —— 播放控制由流入 `animateVapCompositionAsState` 的 [VapRequest] 承担。
 */
@Stable
public class VapAnimationState internal constructor(
    internal val decoder: VapFrameDecoder,
    internal val presentMode: VapPresentMode,
) {
    private var heldFrame: VapPlatformFrame? = null

    /**
     * Monotonic counter incremented every time a new frame is published or the surface
     * is invalidated. Read inside `drawBehind` to subscribe to redraws.
     *
     * 每次发布新帧或 surface 失效时递增的计数器。在 `drawBehind` 中读取以订阅重绘。
     */
    internal var drawGeneration by mutableIntStateOf(0)
        private set

    /**
     * Normalized playback progress in `[0f, 1f]`. Updated at most every
     * [PROGRESS_MIN_INTERVAL] unless forced (first frame / last frame).
     *
     * 归一化播放进度，范围 `[0f, 1f]`。默认节流到 [PROGRESS_MIN_INTERVAL]，首末帧强制刷新。
     */
    public var progress: Float by mutableFloatStateOf(0f)
        private set

    /**
     * Whether the underlying decoder is currently clocked (i.e. producing frames).
     *
     * 解码器当前是否在跑时钟（即持续产出帧）。
     */
    public var isPlaying: Boolean by mutableStateOf(true)
        private set

    /**
     * Coil-style painter state: idle / preparing / ready / failed.
     *
     * 对齐 Coil painter 的阶段：空闲 / 准备 / 就绪 / 失败。
     */
    public var status: VapPlayerStatus by mutableStateOf(VapPlayerStatus.Idle)
        private set

    private var lastError: Throwable? = null
    private var compositionActive: Boolean = false

    /**
     * Cumulative presents since last [resetPresentedCount]. Not a Compose snapshot field —
     * benchmarks / demos should poll it.
     *
     * 自上次 [resetPresentedCount] 起的累计 present 次数。**不是** Compose snapshot 字段，
     * 基准测试 / 演示应主动轮询。
     */
    public var presentedCount: Long = 0L
        private set

    /**
     * Logical RGB content size from `VapConfig` — used to letterbox Surface host (PAG LetterBox).
     *
     * `VapConfig` 中的逻辑 RGB 内容尺寸 —— 用于 Surface host 的 letterbox（类似 PAG LetterBox）。
     */
    internal var contentWidth: Int by mutableIntStateOf(0)
        private set
    internal var contentHeight: Int by mutableIntStateOf(0)
        private set

    private var lastProgressMark = TimeSource.Monotonic.markNow()


    /**
     * True when this state is wired to the Surface render host (Android).
     *
     * 当前 state 是否走 Surface 渲染宿主（Android）。
     */
    internal val usesSurfaceHost: Boolean
        get() = presentMode == VapPresentMode.Surface

    /**
     * Maps the public Compose present mode to the decoder-side present mode.
     *
     * 将 Compose 侧的 present 模式映射到底层解码器侧的 present 模式。
     */
    internal fun decodePresentMode(): com.zxhhyj.vap.decode.VapPresentMode = when (presentMode) {
        VapPresentMode.Canvas -> com.zxhhyj.vap.decode.VapPresentMode.Bitmap
        VapPresentMode.Surface -> com.zxhhyj.vap.decode.VapPresentMode.Surface
    }

    /**
     * Composition whose [VapComposition.poster] is used at Surface bind.
     * The poster lives on the composition, not a process-wide cache.
     *
     * Surface 绑定时使用的 composition；海报挂在 composition 上，不进进程级缓存。
     */
    internal var posterComposition: VapComposition? by mutableStateOf(null)
        private set

    internal fun setPosterLookup(composition: VapComposition?) {
        posterComposition = composition
    }

    internal fun setContentSize(width: Int, height: Int) {
        contentWidth = width.coerceAtLeast(0)
        contentHeight = height.coerceAtLeast(0)
    }

    internal fun markPresented() {
        presentedCount++
        // The Vulkan opening present is intentionally not counted here; the first counted
        // present is still the first MediaCodec frame on the same swapchain.
        // Vulkan opening present 刻意不计入这里；第一个计数 present 仍是同一交换链上的
        // 首个 MediaCodec 帧。
        //
        // 首帧之后 status 不会再变；逐帧 publishStatus 只是一次无谓的快照读取/比较。
        // After the first present the status cannot change here; skip the per-frame
        // snapshot read/compare on the present hot path.
        if (hasHostPresented) return
        val revealHost = !usesSurfaceHost || presentedCount >= 1L
        if (revealHost) hasHostPresented = true
        publishStatus()
    }

    /**
     * True after the first decoded Surface present arrived. Surface hosts no longer draw a
     * Compose still; this flag remains for status/callback bookkeeping.
     *
     * 第一次解码 Surface present 到达后为 true。Surface 宿主不再绘制 Compose 静图；
     * 该标记仅保留给状态/回调记账。
     */
    internal var hasHostPresented: Boolean by mutableStateOf(false)
        private set

    internal fun resetHostPresented() {
        if (!hasHostPresented) return
        hasHostPresented = false
        publishStatus()
        drawGeneration++
    }

    internal fun resetPresentedCount() {
        presentedCount = 0L
    }

    /**
     * Returns the most recently published frame, or `null` if none yet / cleared.
     *
     * 返回最近一次发布的帧；若尚无帧或已被清理则返回 `null`。
     */
    internal fun currentFrame(): VapPlatformFrame? = heldFrame

    /**
     * Optional first frame shown on the same Canvas until [heldFrame] arrives. Surface hosts
     * keep this null and present the packed frame-0 RGBA texture through Vulkan instead.
     *
     * 可选第 0 帧：在 [heldFrame] 到达前画在同一 Canvas 上。Surface 宿主保持它为 null，
     * 改为由 Vulkan present 打包第 0 帧 RGBA 纹理。
     */
    internal var openingFrame: VapPlatformFrame? = null
        private set

    internal fun publishOpeningFrame(frame: VapPlatformFrame?) {
        openingFrame = frame
        publishStatus()
        drawGeneration++
    }

    internal fun setCompositionActive(active: Boolean) {
        compositionActive = active
        if (!active) lastError = null
        publishStatus()
    }

    internal fun setFailure(error: Throwable) {
        lastError = error
        publishStatus()
    }

    internal fun clearFailure() {
        if (lastError == null) return
        lastError = null
        publishStatus()
    }

    private fun publishStatus() {
        val next = when {
            lastError != null -> VapPlayerStatus.Failed(lastError!!)
            !compositionActive -> VapPlayerStatus.Idle
            hasHostPresented || openingFrame != null || heldFrame != null -> VapPlayerStatus.Ready
            else -> VapPlayerStatus.Preparing
        }
        if (status != next) status = next
    }

    internal fun setDisplaySize(widthPx: Int, heightPx: Int) {
        decoder.setDisplaySize(widthPx, heightPx)
    }

    /**
     * Push play/pause state to both the Compose-readable flag and the decoder clock.
     * Internal: callers drive playback via [VapRequest.isPlaying].
     *
     * 将播放/暂停状态同步到 Compose 侧的标志位与底层解码器时钟。
     * 内部方法：调用方通过 [VapRequest.isPlaying] 控制播放。
     */
    internal fun syncPlaying(playing: Boolean) {
        isPlaying = playing
        decoder.setPlaying(playing)
    }

    /**
     * Whether the decoder currently holds an open codec/GL session. Managed by the
     * animate* composable so effect restarts correctly re-open after a release.
     *
     * 解码器当前是否持有打开的 codec/GL 会话。由 animate* 组合函数管理，以便 effect 重启后
     * 能在释放后正确重新打开。
     */
    internal var isSessionOpened: Boolean = false

    /**
     * Drop codec/GL session; composition + this state remain for a later `open` via animate*.
     *
     * 销毁 codec / GL 会话；composition 与本 state 保留，可稍后通过 animate* 重新 `open`。
     */
    internal suspend fun releaseDecodeSession() {
        clearFrame()
        resetHostPresented()
        decoder.releaseDecodeSession()
    }

    /**
     * Publish a new frame: swap in [platformFrame], bump the draw generation so any
     * `drawBehind` reading it redraws, and release the previously held frame.
     *
     * 发布新帧：换入 [platformFrame]，递增 drawGeneration 以触发正在订阅的 `drawBehind` 重绘，
     * 并释放此前持有的帧。
     */
    internal fun present(platformFrame: VapPlatformFrame) {
        val previous = heldFrame
        heldFrame = platformFrame
        publishStatus()
        drawGeneration++
        previous?.release()
    }

    /**
     * Release the held frame and bump generation so consumers redraw a blank.
     *
     * 释放当前持有的帧并递增 generation，使消费者重绘为空白。
     */
    internal fun clearFrame() {
        heldFrame?.release()
        heldFrame = null
        drawGeneration++
    }

    /**
     * Throttled progress publisher — first/last frame always force an update.
     *
     * 节流的进度发布器；首末帧总是强制更新一次。
     */
    internal fun publishProgress(value: Float, force: Boolean = false) {
        val clamped = value.coerceIn(0f, 1f)
        if (!force && lastProgressMark.elapsedNow() < PROGRESS_MIN_INTERVAL) return
        if (clamped == progress) return
        progress = clamped
        lastProgressMark = TimeSource.Monotonic.markNow()
    }

    /**
     * Final teardown: drop the held frame and reset progress now; the decoder itself is
     * closed asynchronously on [vapDecoderTeardownScope] because Compose `onDispose` runs
     * on Main and must not block on codec/Vulkan teardown.
     *
     * 最终拆除：立即释放持有的帧并将进度归零；解码器本身在 [vapDecoderTeardownScope]
     * 上异步关闭，因为 Compose `onDispose` 跑在 Main，不得阻塞在 codec/Vulkan 拆除上。
     */
    internal fun close() {
        clearFrame()
        vapDecoderTeardownScope.launch { decoder.close() }
        publishProgress(0f, force = true)
    }

    private companion object {
        val PROGRESS_MIN_INTERVAL = 100.milliseconds
    }
}

/**
 * Composable that drives a [VapComposition] and exposes a [VapAnimationState] for use
 * with [VapAnimation].
 *
 * 驱动 [VapComposition] 并返回可与 [VapAnimation] 配合使用的 [VapAnimationState] 的 Composable。
 *
 * The present session stays warm while paused (last frame remains on screen). MediaCodec
 * is started only while playing and released on pause; resume seeks to the pause position.
 * The session is torn down when the composition changes or the state is disposed.
 *
 * 暂停时呈现会话保持热状态（最后一帧留在画面上）。MediaCodec 只在播放时运行，暂停即释放；
 * 恢复时 seek 回暂停位置。composition 变更或 state 销毁时才拆除整个会话。
 *
 * @param composition Loaded VAP, or `null` to idle. 已加载的 VAP；`null` 表示空闲。
 * @param request Immutable playback options. Prefer this over the parameter overload.
 * 不可变播放选项。优先使用本重载，而不是散落参数的重载。
 */
@Composable
public fun animateVapCompositionAsState(
    composition: VapComposition?,
    request: VapRequest = VapRequest.Default,
): VapAnimationState = animateVapCompositionAsState(
    composition = composition,
    request = request,
    presentModeOverride = null,
)

/**
 * Convenience overload that builds a [VapRequest] internally.
 *
 * 便捷重载：内部构建 [VapRequest]。
 *
 * @param composition Loaded VAP, or `null` to idle. 已加载的 VAP；`null` 表示空闲。
 * @param isPlaying Whether playback runs immediately. 是否立即播放。
 * @param iterations Play count; [VapConstants.IterateForever] loops indefinitely.
 * 播放次数；[VapConstants.IterateForever] 表示无限循环。
 * @param fps Target present frame rate. `null` follows media PTS; e.g. `30` matches PAG maxFrameRate.
 * 目标呈现帧率。`null` 跟随媒体时间戳；如 `30` 与 PAG maxFrameRate 一致。
 * @param onCompleted Invoked on natural end of all iterations (not on every loop).
 * 全部迭代自然结束时调用（不是每一圈）。
 * @param onError Invoked when decoding/presenting fails; non-fatal so the loop can continue.
 * 解码或呈现失败时调用；非致命，循环可继续。
 * @param onPresented High-frequency per-present hook (benchmarks/diagnostics); keep it
 * allocation-free. 高频逐帧 present 钩子（基准/诊断）；实现应保持无分配。
 * @param gpuBackend Optional override for the GPU backend the decoder should target.
 * 可选覆盖解码器目标 GPU 后端。
 */
@Composable
public fun animateVapCompositionAsState(
    composition: VapComposition?,
    isPlaying: Boolean = true,
    iterations: Int = 1,
    fps: Int? = null,
    onCompleted: (() -> Unit)? = null,
    onError: ((Throwable) -> Unit)? = null,
    onPresented: (() -> Unit)? = null,
    gpuBackend: VapGpuBackend? = null,
): VapAnimationState = animateVapCompositionAsState(
    composition = composition,
    request = VapRequest.Builder()
        .playing(isPlaying)
        .iterations(iterations)
        .fps(fps)
        .gpuBackend(gpuBackend)
        .onCompleted(onCompleted)
        .onError(onError)
        .onPresented(onPresented)
        .build(),
)

/**
 * Internal worker overload that adds a [presentModeOverride] for callers that need to force
 * Surface or Canvas mode (e.g. the Android-only Surface path).
 *
 * 内部工作重载，提供 [presentModeOverride] 用于需要强制 Surface / Canvas 模式的调用方
 * （例如仅 Android 的 Surface 路径）。
 *
 * [VapRequest.presentFirstFrameEagerly] opens the present path (Vulkan / poster)
 * without waiting for [VapRequest.isPlaying]. MediaCodec starts only while playing
 * and is released on pause so a parked session keeps the last frame.
 *
 * [VapRequest.presentFirstFrameEagerly] 不等 [VapRequest.isPlaying] 就打开呈现路径
 * （Vulkan / 海报）。MediaCodec 只在播放时启动，暂停时释放，停住的会话仍留着最后一帧。
 */
@Composable
internal fun animateVapCompositionAsState(
    composition: VapComposition?,
    request: VapRequest,
    presentModeOverride: VapPresentMode?,
): VapAnimationState {
    val isPlaying = request.isPlaying
    val iterations = request.iterations
    val fps = request.fps
    val presentFirstFrameEagerly = request.presentFirstFrameEagerly
    val listenerLatest by rememberUpdatedState(request.listener)
    val presentMode = currentVapPresentMode(override = presentModeOverride)
    val resolvedGpu = VapGpu.resolve(request.gpuBackend)
    // Key on `composition` so a fresh decoder/state is always created when the
    // composition object changes.
    // key 用 `composition`：composition 对象变化时一定创建全新的 decoder / state。
    val decoder = remember(presentMode, resolvedGpu, composition) {
        val decodePresentMode = when (presentMode) {
            VapPresentMode.Canvas -> com.zxhhyj.vap.decode.VapPresentMode.Bitmap
            VapPresentMode.Surface -> com.zxhhyj.vap.decode.VapPresentMode.Surface
        }
        createPlatformVapFrameDecoder().also {
            it.configurePresentMode(decodePresentMode)
            it.configureGpuBackend(resolvedGpu)
        }
    }
    val state = remember(decoder, presentMode) {
        VapAnimationState(decoder = decoder, presentMode = presentMode)
    }
    // Invalidates in-flight LaunchedEffect finally blocks so a cancelled effect cannot
    // releaseDecodeSession() after a newer effect has already opened the next session.
    // 会话纪元：用于让旧 effect 的 finally 块失效，避免被取消的 effect 在新 effect 已经
    // 打开新会话之后再次调用 releaseDecodeSession()。
    val sessionEpoch = remember(state) { intArrayOf(0) }
    val loopForever = iterations == VapConstants.IterateForever
    val decoderLoop = loopForever || iterations > 1
    val surfaceMode = state.usesSurfaceHost
    val playingLatest by rememberUpdatedState(isPlaying)

    SideEffect(state, composition) {
        state.setCompositionActive(composition != null)
    }

    // Apply content size during composition so the first Surface host layout is not an
    // empty Box (SideEffect would run too late and flash a hole-punched black Surface).
    // 在 composition 阶段写入内容尺寸，避免首帧 Surface 宿主先画空 Box
    // （SideEffect 太晚，会闪一下挖洞后的黑 Surface）。
    if (composition != null) {
        state.setContentSize(composition.config.width, composition.config.height)
        state.setPosterLookup(composition)
    } else {
        state.setContentSize(0, 0)
        state.setPosterLookup(null)
    }

    DisposableEffect(state) {
        onDispose {
            sessionEpoch[0]++
            state.close()
        }
    }

    // Sync content size before first Surface host layout (letterbox like PAG LetterBox).
    // 在 Surface host 首次布局前同步内容尺寸（letterbox 行为参考 PAG LetterBox）。
    SideEffect(state, composition) {
        if (composition != null) {
            state.setContentSize(composition.config.width, composition.config.height)
            state.setPosterLookup(composition)
        } else {
            state.setContentSize(0, 0)
            state.setPosterLookup(null)
        }
    }

    // Warm standby split into two effects so that toggling `isPlaying` does NOT tear down
    // the decoder session:
    //   - The first effect only forwards the play/pause gate; it restarts on `isPlaying`
    //     changes and is short-lived, so syncPlaying is a cheap, instant call.
    //   - The second effect owns the open/present loop lifetime. Its key excludes
    //     `isPlaying`, so pause/resume doesn't cancel it; the loop stays parked on
    //     `advancePresentedFrame` while the playback gate is closed and resumes when it
    //     re-opens. The effect only re-runs when `composition` / `iterations` / `fps`
    //     change, or when the state is disposed.
    // 热待机拆成两个 effect，避免 `isPlaying` 切换时拆掉解码会话：
    //   - 第一个 effect 只转发播放门；它的 key 包含 `isPlaying`，切换时重启是廉价操作。
    //   - 第二个 effect 拥有 open/present 循环的生命周期，key 不含 `isPlaying`，因此暂停/
    //     恢复不会取消它；播放门关闭时循环挂在 `advancePresentedFrame` 上，播放门重新打开
    //     后继续。该 effect 只在 `composition` / `iterations` / `fps` 变化或 state 被销毁
    //     时重启。
    LaunchedEffect(state, isPlaying) {
        state.syncPlaying(isPlaying)
        if (!state.isSessionOpened) return@LaunchedEffect
        try {
            withContext(Dispatchers.IO) {
                if (isPlaying) state.decoder.startDecode() else state.decoder.stopDecode()
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            // startDecode() 走 MediaCodec.start()，个别机型 / 资源紧张时会抛
            // CodecException。这里只是后台播放门切换，异常绝不能逃出 Compose 协程
            // （之前没有 try/catch，直接崩进程）。与 open / present 路径一致地
            // 收敛成失败状态并回调，交给 UI 处理。
            // startDecode() may throw CodecException from MediaCodec.start(); this is a
            // background gate flip and must not escape the Compose coroutine.
            state.setFailure(t)
            listenerLatest?.onError(t)
        }
    }
    LaunchedEffect(state, composition) {
        if (composition == null) return@LaunchedEffect
        listenerLatest?.onStart()
        var terminal = false
        try {
            snapshotFlow { state.status }.collect { status ->
                when (status) {
                    is VapPlayerStatus.Ready -> {
                        if (!terminal) {
                            terminal = true
                            listenerLatest?.onSuccess()
                        }
                    }
                    is VapPlayerStatus.Failed -> {
                        terminal = true
                    }
                    else -> Unit
                }
            }
        } catch (t: CancellationException) {
            if (!terminal) listenerLatest?.onCancel()
            throw t
        }
    }
    LaunchedEffect(state, composition, iterations, fps) {
        val epoch = ++sessionEpoch[0]
        if (composition == null) {
            if (sessionEpoch[0] == epoch) {
                state.setContentSize(0, 0)
                state.setPosterLookup(null)
                state.releaseDecodeSession()
                state.isSessionOpened = false
                state.publishProgress(0f, force = true)
            }
            return@LaunchedEffect
        }
        state.setContentSize(composition.config.width, composition.config.height)
        state.setPosterLookup(composition)
        state.clearFailure()
        try {
            if (presentFirstFrameEagerly && !state.isSessionOpened) {
                withContext(Dispatchers.IO) {
                    state.decoder.openPresent(
                        composition.source,
                        composition.config,
                        loop = decoderLoop,
                        fpsOverride = fps,
                    )
                    if (playingLatest) state.decoder.startDecode()
                }
                if (sessionEpoch[0] != epoch) return@LaunchedEffect
                state.isSessionOpened = true
                state.resetPresentedCount()
                state.syncPlaying(playingLatest)
            }
            if (sessionEpoch[0] != epoch) return@LaunchedEffect
            if (!state.isSessionOpened) {
                if (!playingLatest) {
                    snapshotFlow { playingLatest }.first { it }
                    if (sessionEpoch[0] != epoch) return@LaunchedEffect
                }
                withContext(Dispatchers.IO) {
                    state.decoder.openPresent(
                        composition.source,
                        composition.config,
                        loop = decoderLoop,
                        fpsOverride = fps,
                    )
                    state.decoder.startDecode()
                }
                state.isSessionOpened = true
                state.resetPresentedCount()
            }
            if (sessionEpoch[0] != epoch) return@LaunchedEffect
            // Start the present loop immediately. Unused / paused sessions park
            // inside awaitFramePresented; waiting for the first `isPlaying` here
            // meant Think/Speech missed their first Surface (AvatarBall / Hanna).
            // 立刻启动 present 循环。未选中 / 暂停的会话在 awaitFramePresented
            // 里挂起；若在这里等第一次 isPlaying，Think/Speech 会错过第一块
            // Surface（AvatarBall / Hanna）。
            state.syncPlaying(playingLatest)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            state.setFailure(t)
            listenerLatest?.onError(t) ?: throw t
            return@LaunchedEffect
        }
        runPresentLoop(
            state = state,
            composition = composition,
            loopForever = loopForever,
            iterations = iterations,
            fps = fps,
            decoderLoop = decoderLoop,
            isPlaying = { playingLatest },
            surfaceMode = surfaceMode,
            onCompleted = { listenerLatest?.onCompleted() },
            onError = listenerLatest?.let { listener ->
                { error: Throwable -> listener.onError(error) }
            },
            onPresented = { listenerLatest?.onPresented() },
            shouldReleaseOnExit = { sessionEpoch[0] == epoch },
        )
    }

    return state
}

/**
 * Pull-based present loop. For Canvas mode the decoder advances and yields a `VapFrameAdvance.Bitmap`
 * frame; for Surface mode it just signals `VapFrameAdvance.SurfacePresented` because the swap happens
 * inside the decoder. Loops according to [loopForever] / [iterations].
 *
 * 拉取式 present 循环。Canvas 模式下解码器推进后产出 `VapFrameAdvance.Bitmap` 帧；
 * Surface 模式下 swap 在解码器内部完成，这里仅收到 `VapFrameAdvance.SurfacePresented` 信号。
 * 按 [loopForever] / [iterations] 控制循环次数。
 *
 * @param shouldReleaseOnExit Returns `true` only when this loop's epoch is still current, so the
 * `finally` does not release a session that has already been replaced.
 * 仅当本循环纪元仍为当前时返回 `true`，避免 `finally` 释放已被替换的会话。
 */
private suspend fun runPresentLoop(
    state: VapAnimationState,
    composition: VapComposition,
    loopForever: Boolean,
    iterations: Int,
    fps: Int?,
    decoderLoop: Boolean,
    isPlaying: () -> Boolean,
    surfaceMode: Boolean,
    onCompleted: (() -> Unit)?,
    onError: ((Throwable) -> Unit)?,
    onPresented: (() -> Unit)?,
    shouldReleaseOnExit: () -> Boolean,
) {
    val total = composition.config.totalFrames.coerceAtLeast(1)
    var index = 0
    var playCount = 0
    var consecutiveEnds = 0
    try {
        while (currentCoroutineContext().isActive) {
            try {
                when (val advance = state.decoder.advancePresentedFrame(surfaceMode)) {
                    VapFrameAdvance.Ended -> {
                        if (loopForever && consecutiveEnds < 5 && shouldReleaseOnExit()) {
                            consecutiveEnds++
                            withContext(Dispatchers.IO) {
                                state.decoder.open(
                                    composition.source,
                                    composition.config,
                                    loop = decoderLoop,
                                    fpsOverride = fps,
                                )
                            }
                            state.isSessionOpened = true
                            state.syncPlaying(isPlaying())
                            index = 0
                            continue
                        }
                        state.publishProgress(1f, force = true)
                        onCompleted?.invoke()
                        break
                    }

                    is VapFrameAdvance.Bitmap -> {
                        consecutiveEnds = 0
                        state.present(advance.frame)
                        state.markPresented()
                        onPresented?.invoke()
                    }

                    VapFrameAdvance.SurfacePresented -> {
                        consecutiveEnds = 0
                        state.markPresented()
                        onPresented?.invoke()
                    }

                    VapFrameAdvance.SurfaceSkipped -> {
                        // Keep progress and iteration boundaries on the source timeline,
                        // without counting a skipped frame as an actual presentation.
                        consecutiveEnds = 0
                    }
                }
                val atBoundary = index + 1 >= total
                state.publishProgress(
                    value = (index + 1).toFloat() / total,
                    force = atBoundary || index == 0,
                )
                if (++index >= total) {
                    onCompleted?.invoke()
                    playCount++
                    index = when {
                        loopForever -> 0
                        playCount >= iterations.coerceAtLeast(1) -> break
                        else -> 0
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                state.setFailure(t)
                if (onError != null) onError.invoke(t) else throw t
                break
            }
        }
    } finally {
        // Reset the open-state flag whenever we actually release, so a subsequent effect
        // restart (e.g. `iterations` / `fps` change) re-opens against the same decoder
        // instead of skipping `open()` on an already-released session.
        // 真正释放时把 `isSessionOpened` 重置为 false，这样后续 effect 重启（例如 `iterations`
        // 或 `fps` 变化）时就能在同一个 decoder 上重新 `open()`，而不是错误地跳过打开。
        if (shouldReleaseOnExit()) {
            state.releaseDecodeSession()
            state.isSessionOpened = false
        }
    }
}
