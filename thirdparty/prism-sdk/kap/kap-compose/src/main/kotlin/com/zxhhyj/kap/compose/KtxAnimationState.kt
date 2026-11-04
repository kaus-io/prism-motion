package com.zxhhyj.kap.compose

import android.content.Context
import android.util.Log
import android.view.Surface
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zxhhyj.kap.android.KtxAnimationComposition
import com.zxhhyj.kap.android.KtxPlaybackMode
import com.zxhhyj.kap.android.KtxPlayerStatus
import com.zxhhyj.kap.android.KtxSurfacePlayer
import com.zxhhyj.kap.android.KtxDebugOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * PAGView-style playback handle: own it, hoist it, and attach a render Surface
 * through [KtxAnimation]. Detaching the Surface keeps the engine and its uploaded
 * textures so the next attachment only rebuilds the swapchain.
 *
 * PAGView 语义的播放句柄：由调用方持有/提升，通过 [KtxAnimation] 挂载渲染 Surface。
 * Surface 摘除后保留引擎与纹理，下次挂载只重建交换链。
 */
@Stable
public class KtxAnimationState(
    public val composition: KtxAnimationComposition,
    public val playbackMode: KtxPlaybackMode = KtxPlaybackMode.Static,
    /** Present-rate override; `null` follows the KTX2 metadata. / 呈现帧率覆盖；`null` 跟随 KTX2 元数据。 */
    public val fps: Int? = null,
) {
    public var status: KtxPlayerStatus by mutableStateOf(KtxPlayerStatus.Idle)
        private set

    /**
     * The current embedded Surface has both submitted a frame and had a new buffer consumed
     * through TextureView and a hardware UI frame commit. This is not physical-display timing.
     * False for non-embedded experimental hosts, and reset on Surface/geometry replacement.
     */
    public var hasDisplayContent: Boolean by mutableStateOf(false)
        private set

    /** Changes whenever the output is replaced or invalidated, even before its next frame. */
    public var displayContentGeneration: Long by mutableLongStateOf(0L)
        private set

    /** 当前 Surface 的抓帧实现；随 Surface 绑定/销毁，不持有 View 生命周期。 */
    @Volatile
    internal var frameSnapshot: (() -> ImageBitmap?)? = null

    /**
     * 当前输出最近一帧的像素副本；没有 Surface 或抓帧失败时为 null。
     * 只在主线程调用，用于内存缓存（对齐 Coil 的已解码像素缓存）。
     */
    public fun captureFrame(): ImageBitmap? = frameSnapshot?.invoke()

    public var isPlaying: Boolean by mutableStateOf(false)
        private set

    private var progressValue by mutableFloatStateOf(0f)

    /**
     * Normalized progress in `[0, 1]`; assigning seeks like PAGView's
     * `setProgress`. Also updated at ~10 Hz while playing.
     *
     * 归一化进度；赋值等同 PAGView 的 `setProgress`，播放中约 10Hz 刷新。
     */
    public var progress: Float
        get() = progressValue
        set(value) {
            seekTo(value)
        }

    /**
     * Extra plays after the first one. `-1` loops forever, `0` plays once,
     * `n > 0` plays `n + 1` times. Changing it takes effect on the next frame.
     *
     * 额外重复次数：`-1` 无限，`0` 播一次，`n > 0` 共播 `n + 1` 次；下一帧生效。
     */
    public var repeatCount: Int = 0

    /** Playback callbacks; invoked on the playback worker thread. / 播放回调（worker 线程）。 */
    @Volatile
    public var listener: KtxAnimationListener? = null

    /** Single-play duration derived from frame count and composition fps. / 单次播放时长。 */
    public val durationMs: Long
        get() = composition.frameCount.toLong() * 1000L / (fps ?: composition.fps).coerceAtLeast(1)

    /** Successful submission count, published at ~10 Hz. */
    public var presentedCount: Long by mutableLongStateOf(0L)
        private set

    internal val playing = MutableStateFlow(false)
    @Volatile
    internal var surfaceDestroyed: Boolean = false
    @Volatile
    private var session: KtxSurfacePlayer? = null
    @Volatile
    private var started = false
    @Volatile
    private var surfaceEpoch: Long = 0L
    @Volatile
    private var disposed: Boolean = false
    @Volatile
    private var pendingSeek: Float? = null
    // Exact count is separate from the throttled Compose snapshot, including across Surfaces.
    private val submittedCount = AtomicLong()
    private val surfaceVersion = MutableStateFlow(0L)
    private val seekVersion = MutableStateFlow(0L)
    private val engineLock = Any()
    private val surfaceLock = Any()
    private val displayContent = KtxDisplayContentGate()
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    @Volatile
    private var outputRevision = 0L

    /** Resume playback without releasing the engine. / 恢复播放，不释放引擎。 */
    public fun play() {
        applyPlaying(true)
    }

    /** Pause playback without releasing the engine. / 暂停播放，不释放引擎。 */
    public fun pause() {
        applyPlaying(false)
    }

    /** Pause and seek back to the first frame. / 暂停并回到第 0 帧（PAGView stop 语义）。 */
    public fun stop() {
        pause()
        seekTo(0f)
    }

    public fun toggle() {
        applyPlaying(!isPlaying)
    }

    /** Seek to a normalized progress; works while playing and while paused. */
    public fun seekTo(progress: Float) {
        val target = progress.coerceIn(0f, 1f)
        pendingSeek = target
        progressValue = target
        seekVersion.value++
    }

    internal fun applyPlaying(playing: Boolean) {
        isPlaying = playing
        this.playing.value = playing
    }

    internal fun onSurfaceAttached(width: Int, height: Int, initialTimestamp: Long = 0L): Long {
        synchronized(this) {
            if (disposed) return surfaceEpoch
            surfaceEpoch += 1
            surfaceDestroyed = false
            status = KtxPlayerStatus.Preparing
            surfaceWidth = width
            surfaceHeight = height
            outputRevision = 0L
            displayContent.reset(KtxSurfaceOutput(surfaceEpoch, outputRevision, initialTimestamp))
            hasDisplayContent = false
            displayContentGeneration++
            surfaceVersion.value++
            return surfaceEpoch
        }
    }

    internal fun awaitingDisplayOutput(epoch: Long): KtxSurfaceOutput? = synchronized(this) {
        if (!ownsSurface(epoch) || !displayContent.needsConsumption) null else displayContent.output
    }

    internal fun onDisplayBufferConsumed(output: KtxSurfaceOutput, timestamp: Long) {
        synchronized(this) {
            if (!ownsSurface(output.epoch)) return
            displayContent.onConsumed(output, timestamp)
            hasDisplayContent = displayContent.hasDisplayContent
        }
    }

    private fun onFrameSubmitted(output: KtxSurfaceOutput) {
        synchronized(this) {
            if (!ownsSurface(output.epoch)) return
            displayContent.onSubmitted(output)
            hasDisplayContent = displayContent.hasDisplayContent
        }
    }

    internal fun onSurfaceDestroyed(epoch: Long) {
        // Late destroy callbacks from a previous Surface must not kill the new
        // session that already attached.
        // 旧 Surface 迟到的 destroy 回调不能把已经接管的新会话停掉。
        synchronized(this) {
            if (epoch != surfaceEpoch) return
            surfaceDestroyed = true
            displayContent.invalidate()
            hasDisplayContent = false
            displayContentGeneration++
            surfaceVersion.value++
            if (status == KtxPlayerStatus.Ready || status == KtxPlayerStatus.Preparing) {
                status = KtxPlayerStatus.Idle
            }
            started = false
        }
    }

    private fun ownsSurface(epoch: Long): Boolean =
        !disposed && !surfaceDestroyed && epoch == surfaceEpoch

    internal suspend fun runSurface(
        context: Context,
        surface: Surface,
        epoch: Long,
    ) {
        val surfaceStartedAt = System.nanoTime()
        // One engine per hoisted state: keep the uploaded texture array/ring
        // across Surface recreation instead of uploading it on every switch.
        // 每个提升状态保留一个引擎：Surface 重建不重新上传已保留的纹理数组/环。
        val engine = synchronized(engineLock) {
            if (!ownsSurface(epoch)) {
                null
            } else {
                session ?: KtxSurfacePlayer(
                    context,
                    composition,
                    playbackMode,
                ).also { session = it }
            }
        } ?: return
        try {
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                if (!publishStatus(KtxPlayerStatus.Preparing, epoch)) return@withContext
                if (!started) {
                    listener?.onStart()
                    started = true
                }
                val attachState = synchronized(surfaceLock) {
                    attachKtxSurfaceIfCurrent(
                        isCurrent = { ownsSurface(epoch) && surface.isValid },
                        attach = { engine.attach(surface, surfaceWidth, surfaceHeight) },
                    )
                }
                when (attachState) {
                    KtxSurfaceAttachResult.Stale -> return@withContext
                    KtxSurfaceAttachResult.Failed ->
                        throw IllegalStateException("nativeSetOutputSurface failed")

                    KtxSurfaceAttachResult.Attached -> Unit
                }
                runInterruptible(Dispatchers.IO) { engine.upload() }
                if (!ownsSurface(epoch)) return@withContext
                runLoop(engine, epoch, surfaceStartedAt, KtxDebugOptions.load(context))
            }
        } catch (e: CancellationException) {
            if (presentedCount == 0L) listener?.onCancel()
            throw e
        } catch (t: Exception) {
            // A Surface may disappear while native attach/upload is in flight.
            // An obsolete run must not fail the new Surface or invoke its listener.
            if (ownsSurface(epoch) && surface.isValid && publishStatus(KtxPlayerStatus.Failed(t), epoch)) {
                Log.e(TAG, "KTX animation failed", t)
                listener?.onError(t)
            }
        } finally {
            withContext(NonCancellable + Dispatchers.Default) {
                synchronized(surfaceLock) {
                    presentedCount = submittedCount.get()
                    // A newer Surface may already own the engine; only detach when
                    // this run is still the current one.
                    // 新 Surface 可能已接管引擎；只有当前 run 才能 detach。
                    if (!disposed && epoch == surfaceEpoch) {
                        engine.detachOutput()
                    }
                }
            }
        }
    }

    internal fun resize(
        surface: Surface,
        width: Int,
        height: Int,
        epoch: Long,
        initialTimestamp: Long = 0L,
        isCurrentSize: () -> Boolean = { true },
    ) {
        synchronized(surfaceLock) {
            if (!isCurrentSize() || !ownsSurface(epoch) || !surface.isValid || width <= 0 || height <= 0) return
            synchronized(this) {
                if (!ownsSurface(epoch) || (surfaceWidth == width && surfaceHeight == height)) return
                surfaceWidth = width
                surfaceHeight = height
                outputRevision++
                displayContent.reset(KtxSurfaceOutput(epoch, outputRevision, initialTimestamp))
                hasDisplayContent = false
                displayContentGeneration++
            }
            try {
                session?.attach(surface, width, height)
            } finally {
                // A paused run owes the recreated swapchain its current frame. Wake only after
                // the resize has finished; presentation uses this same surfaceLock.
                synchronized(this) {
                    if (ownsSurface(epoch)) surfaceVersion.value++
                }
            }
        }
    }

    /** Closes the native engine and releases its textures. / 销毁引擎与纹理。 */
    public fun release() {
        val engine = synchronized(engineLock) {
            if (disposed) {
                null
            } else {
                disposed = true
                session.also { session = null }
            }
        }
        synchronized(this) {
            surfaceDestroyed = true
            surfaceEpoch += 1
            displayContent.invalidate()
            hasDisplayContent = false
            displayContentGeneration++
            surfaceVersion.value++
        }
        // Compose disposal runs on Main. Native teardown may wait for GPU work.
        // Invalidate immediately, but never block Main on Vulkan destruction.
        if (engine != null) {
            releaseScope.launch {
                synchronized(surfaceLock) { engine.close() }
            }
        }
    }

    private suspend fun runLoop(engine: KtxSurfacePlayer, epoch: Long, surfaceStartedAt: Long, debug: KtxDebugOptions) {
        val frameRate = (fps ?: composition.fps).coerceAtLeast(1)
        val totalFrames = composition.frameCount.toLong().coerceAtLeast(1L)
        val timeline = KtxFrameTimeline(frameRate)
        // Resume a hoisted state at its PAG-style progress; a fresh state starts at 0.
        val startFrame = (progress * totalFrames).toLong().coerceIn(0L, totalFrames - 1L)
        timeline.seekTo(startFrame)
        var nextDeadline = 0L
        var lastFrame = -1L
        var lastPresentedOutputRevision = -1L
        var lastProgressAt = 0L
        var lastIteration = -1L
        var ready = false
        var completed = false
        var forcePresent = false
        var firstTick = true
        var lastCountAt = 0L
        val probe = if (debug.measure) KtxCadenceProbe(frameRate) else null

        while (currentCoroutineContext().isActive &&
            !surfaceDestroyed &&
            epoch == surfaceEpoch
        ) {
            val seek = pendingSeek
            if (seek != null) {
                pendingSeek = null
                val target = (seek * totalFrames).toLong().coerceIn(0L, totalFrames - 1L)
                timeline.seekTo(target)
                lastFrame = -1L
                lastIteration = -1L
                completed = false
                forcePresent = true
                progressValue = seek
            }
            val repeat = repeatCount
            val maxFrames = if (repeat < 0) Long.MAX_VALUE else (repeat + 1L) * totalFrames
            // A paused player still owes a new Surface (or a seek) its real frame.
            // Once presented, keep that frame without advancing the timeline.
            if ((!playing.value || debug.holdFrameZero) && ready && !forcePresent &&
                outputRevision == lastPresentedOutputRevision
            ) {
                presentedCount = submittedCount.get()
                timeline.pause()
                nextDeadline = 0L
                combine(playing, surfaceVersion, seekVersion) { play, _, _ ->
                    (play && !debug.holdFrameZero) || surfaceDestroyed || epoch != surfaceEpoch ||
                        outputRevision != lastPresentedOutputRevision || pendingSeek != null
                }.first { it }
                continue
            }
            // A new output owes its current frame immediately after attach/upload.
            // Only this first attempt bypasses vsync; even a failed attempt must
            // await a real refresh before retrying. Normal playback stays vsync-driven.
            val eager = firstTick
            firstTick = false
            val now = when {
                eager -> System.nanoTime()
                debug.sharedVsync -> KtxSharedVsyncClock.awaitFrame(nextDeadline)
                else -> KtxVsyncClock.awaitFrame(nextDeadline)
            }
            if (surfaceDestroyed || epoch != surfaceEpoch) break
            val advancing = playing.value && !debug.holdFrameZero
            if (!advancing && ready && !forcePresent && outputRevision == lastPresentedOutputRevision) continue
            if (completed && advancing) {
                // A finite clip completed and the user pressed Play again.
                // 有限次播放完成后用户再次点 Play：从头开始。
                completed = false
                timeline.restart()
                lastFrame = -1L
                lastIteration = -1L
                progressValue = 0f
            }
            val frame = when {
                debug.holdFrameZero -> debug.heldFrame.toLong().coerceAtMost(totalFrames - 1)
                completed -> lastFrame.coerceAtLeast(0L) // Resize repaints the completed frame; it is not Play.
                // The eager repaint doesn't anchor/advance animation time; the
                // next real vsync starts the timeline at the same current frame.
                else -> timeline.advance(now, advancing && !eager)
            }
            if (repeat >= 0 && frame >= maxFrames) {
                presentedCount = submittedCount.get()
                publishProgress(1f)
                isPlaying = false
                playing.value = false
                forcePresent = false
                if (!completed) {
                    completed = true
                    listener?.onEnd()
                }
                continue
            }
            if (frame != lastFrame || outputRevision != lastPresentedOutputRevision || forcePresent) {
                var attemptedOutputRevision = -1L
                val presented = synchronized(surfaceLock) {
                    if (!ownsSurface(epoch)) {
                        false
                    } else {
                        val output = synchronized(this) { displayContent.output }
                        attemptedOutputRevision = outputRevision
                        engine.present((frame % Int.MAX_VALUE).toInt()).also { submitted ->
                            if (submitted && output != null) onFrameSubmitted(output)
                        }
                    }
                }
                probe?.record(presented, frame, System.nanoTime())?.let {
                    Log.i("KtxCadence", "source=${composition.source} $it")
                }
                if (presented) {
                    lastPresentedOutputRevision = attemptedOutputRevision
                    lastFrame = frame
                    forcePresent = false
                    val actualPresented = submittedCount.incrementAndGet()
                    if (!debug.throttleState || !ready || now - lastCountAt >= 100_000_000L) {
                        presentedCount = actualPresented
                        lastCountAt = now
                    }
                    val normalized = ((frame % totalFrames).toFloat() / totalFrames).coerceIn(0f, 1f)
                    val iteration = frame / totalFrames
                    if (lastIteration >= 0L && iteration != lastIteration) listener?.onRepeat()
                    lastIteration = iteration
                    listener?.onUpdate(normalized)
                    if (!ready) {
                        Log.i(TAG, "first present source=${composition.source} surface_to_present_ms=${(System.nanoTime() - surfaceStartedAt) / 1_000_000}")
                        ready = true
                        publishStatus(KtxPlayerStatus.Ready, epoch)
                    }
                }
            }
            if (!completed && now - lastProgressAt >= 100_000_000L) {
                lastProgressAt = now
                publishProgress(((frame % totalFrames).toFloat() / totalFrames).coerceIn(0f, 1f))
            }
            nextDeadline = if (!eager && lastFrame == frame && lastPresentedOutputRevision == outputRevision) {
                timeline.nextDeadline(now, frame)
            } else {
                // Retry only on the next actual vsync; never spin or enqueue work.
                0L
            }
        }
    }

    private fun publishStatus(next: KtxPlayerStatus, epoch: Long): Boolean = synchronized(this) {
        if (!ownsSurface(epoch)) return@synchronized false
        status = next
        true
    }

    private fun publishProgress(value: Float) {
        progressValue = value
    }

    private companion object {
        const val TAG = "KtxAnim"
        val releaseScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
