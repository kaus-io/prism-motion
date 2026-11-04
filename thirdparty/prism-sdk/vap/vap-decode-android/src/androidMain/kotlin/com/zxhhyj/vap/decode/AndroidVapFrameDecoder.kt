@file:OptIn(ExperimentalAtomicApi::class)

package com.zxhhyj.vap.decode

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.view.Surface
import com.zxhhyj.vap.log.VapLog
import com.zxhhyj.vap.player.VapConfig
import com.zxhhyj.vap.player.VapSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

internal data class PendingWindowSurface(
    val surface: Surface,
    val width: Int,
    val height: Int,
    /** Opaque token of the host that owns this attachment (see VapSurfaceFrameDecoder). / 拥有本次挂载的宿主令牌（见 VapSurfaceFrameDecoder）。 */
    val owner: Any? = null,
)

internal class PendingOpeningFrame(
    val pixels: IntArray,
    val width: Int,
    val height: Int,
)

private val nextDecoderSessionId = AtomicInt(0)

internal class AndroidVapFrameDecoder : VapSurfaceFrameDecoder {
    internal var config: VapConfig? = null
    internal var extractorSource: AndroidVapExtractorSource? = null
    internal var loop: Boolean = false
    /** Distinguishes concurrent Idle/Think/Speech sessions in VapLoop. / 并发 Idle/Think/Speech 会话在 VapLoop 里靠这个区分。 */
    internal var sessionSeq: Int = 0

    /**
     * Immutable decode-session configuration, set once before open. Grouped so a
     * caller cannot change one field and forget the others.
     * 不可变的解码会话配置，open 前一次设好。归组避免调用方改了一个字段却漏掉其它。
     */
    internal data class DecoderConfig(
        val outputMode: VapOutputMode = VapOutputMode.WindowSurface,
        val gpuBackend: VapGpuBackend = VapGpu.defaultBackend,
        val targetFrameRate: Int = 0,
        val displayWidth: Int = 0,
        val displayHeight: Int = 0,
    )

    internal var decoderConfig = DecoderConfig()

    internal var extractor: MediaExtractor? = null
    internal var codec: MediaCodec? = null
    internal var codecAsync: VapMediaCodecAsync? = null
    internal var vkSession: VapVulkanSession? = null

    internal var frameChannel =
        Channel<VapPlatformFrame>(capacity = 2)
    internal var presentedTicks =
        Channel<VapFrameAdvance>(capacity = 2)
    internal val inputDone = AtomicBoolean(false)
    internal val stopped = AtomicBoolean(false)
    /**
     * Incremented every time the codec is (re-)started or flushed. The inline input feeder
     * discards callbacks whose captured generation is stale, preventing use of buffer indices
     * invalidated by flush()/start() on the shared callback thread.
     * 在 codec 每次 (重)启动或 flush 时递增。内联 input 喂入器会丢弃捕获到陈旧 generation
     * 的回调，避免在共享回调线程上使用已被 flush()/start() 失效的缓冲索引。
     */
    internal val inputGeneration = AtomicInt(0)

    /** Serializes inline input feeding (codec callback thread) against softRewind seek/flush.
     * 将 codec 回调线程上的内联 input 喂入与 softRewind 的 seek/flush 操作串行化。 */
    internal val inputLock = Any()

    /**
     * Input buffer indices deferred while [playbackGate] is closed. Feeding during pause
     * would advance [extractor] and jump many frames on resume.
     * 播放门关闭期间暂缓的 input 缓冲索引。暂停时继续喂入会推进 [extractor]，
     * 恢复时画面会跳过很多帧。
     */
    internal val deferredInputIndices = java.util.ArrayDeque<Int>()
    internal var deferredInputCodec: MediaCodec? = null
    internal var deferredInputGeneration: Int = 0

    /**
     * Output packets queued to the decode pump (incremented on the callback thread before
     * enqueue, decremented by the pump after handling). The inline fast path only runs
     * when this reads 0, which guarantees it can never overtake a queued earlier frame.
     * 已入队到解码泵的 output 包数量（在回调线程入队前递增，泵处理完后递减）。内联
     * 快速路径仅当读到 0 时才运行，以此保证它永远不可能抢先于已入队的更早帧。
     */
    internal val queuedOutputs = AtomicInt(0)

    internal val pendingWindowSurface = AtomicReference<PendingWindowSurface?>(null)
    internal val pendingOpeningFrame = AtomicReference<PendingOpeningFrame?>(null)
    internal var pendingBindExtractor: ((MediaExtractor) -> Unit)? = null
    internal var codecStarted: Boolean = false
    /**
     * Last presented (or last released) PTS captured in [stopDecode].
     * `-1` = never paused. [startDecode] seeks to the previous sync and drops
     * outputs until this PTS so the freeze frame and the first resume frame match.
     *
     * [stopDecode] 记下的最后呈现（或最后送出）PTS。`-1` 表示从未暂停。
     * [startDecode] seek 到前一个关键帧，并丢掉该 PTS 之前的输出，
     * 让冻帧和恢复后的第一帧对齐。
     */
    internal var resumeSampleTimeUs: Long = -1L
    /** Drop codec output until PTS passes [resumeSampleTimeUs]. `-1` = not catching up. /
     * 丢掉 codec 输出直到 PTS 超过 [resumeSampleTimeUs]。`-1` 表示不在追赶。 */
    internal var resumeCatchUpUntilUs: Long = -1L
    /** Last PTS sent to ImageReader (`releaseOutputBuffer(true)`). / 最近一次送进 ImageReader 的 PTS。 */
    internal var lastReleasedPtsUs: Long = -1L
    /**
     * True while [stopDecode] is tearing down the codec pump. The pump must not
     * close present ticks (that looks like EOS to the Compose loop).
     * [stopDecode] 拆除 codec 泵期间为 true。泵不得关闭 present 通道（Compose 循环会当成播完）。
     */
    internal val releaseCodecOnly = AtomicBoolean(false)

    internal val scope = CoroutineScope(Dispatchers.IO)
    internal var decodeJob: Job? = null
    /** Serializes open / releaseDecodeSession / close against Compose effect races.
     * 将 open / releaseDecodeSession / close 与 Compose effect 的竞争串行化。 */
    internal val sessionMutex = Mutex()

    internal val compositorSlot = VapCompositorSlot()
    internal val playbackGate = VapPlaybackGate()

    internal fun releasePresentSlot() {
        compositorSlot.release()
    }

    internal fun wakePresentSlotWaiters() {
        compositorSlot.requestPark()
    }

    /**
     * Output-surface attach signal for the present loop. While no surface is attached
     * (host transition / window hidden), `awaitFramePresented` parks instead of letting
     * the present loop treat a missing surface as end-of-stream — session lifetime is
     * owned by the state holder, the surface is just a pluggable output.
     *
     * 输出 surface 挂载信号。无 surface（宿主转场 / 窗口隐藏）时 awaitFramePresented
     * 挂起等待重挂，而不是把「暂时没 surface」当成流结束 —— 会话生命周期由 state
     * 持有者决定，surface 只是可插拔的输出端。
     */
    private val surfaceAttached = kotlinx.coroutines.flow.MutableStateFlow(false)

    /**
     * Wake-up for the present loop when the gate or surface changes state.
     * CONFLATED: one pending event is enough; the loop re-reads the real state.
     *
     * 播放门 / Surface 状态变化时唤醒 present 循环。CONFLATED：只需保留一个待处理事件，
     * 循环被唤醒后重新读取真实状态。
     */
    private val presentEvents = Channel<Unit>(Channel.CONFLATED)

    private enum class PresentWait { Ended, Retry }

    /** Update the whole session config at once; applied on the next open. / 一次更新整份会话配置；下次 open 生效。 */
    fun configure(config: DecoderConfig) {
        decoderConfig = config
    }

    override fun attachOutputSurface(surface: Surface, width: Int, height: Int, owner: Any?) {
        val pending = PendingWindowSurface(
            surface = surface,
            width = width.coerceAtLeast(1),
            height = height.coerceAtLeast(1),
            owner = owner,
        )
        pendingWindowSurface.store(pending)
        surfaceAttached.value = true
        presentEvents.trySend(Unit)
        // Fade / first layout can attach after the play-gate is already open.
        // Force the vk clock to match the gate so we do not idle with pending=true.
        // 淡入 / 首次布局可能在播放门已打开之后才挂上 Surface。
        // 强制 vk 时钟跟门一致，避免 pending=true 却 idle。
        if (playbackGate.isActive()) {
            vkSession?.setPlaying(true)
        }
        vkSession?.attachOutputSurface(pending.surface, pending.width, pending.height)
    }

    override fun setOpeningFrame(pixels: IntArray?, width: Int, height: Int) {
        val frame = if (pixels != null && width > 0 && height > 0) {
            PendingOpeningFrame(pixels, width, height)
        } else {
            null
        }
        pendingOpeningFrame.store(frame)
        vkSession?.setOpeningFrame(frame?.pixels, frame?.width ?: 0, frame?.height ?: 0)
    }

    override fun resizeOutput(width: Int, height: Int) {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        pendingWindowSurface.load()?.let { current ->
            pendingWindowSurface.store(current.copy(width = w, height = h))
        }
        vkSession?.resizeOutput(w, h)
    }

    override fun detachOutputSurface(owner: Any?) {
        // Stale-host guard: a disposed host must not tear down an attachment that a newer
        // host already took over (Compose transition overlap). `owner == null` force-clears.
        // 过期宿主防护：已销毁的宿主不得拆除已被新宿主接管的挂载（Compose 转场重叠）。
        // `owner == null` 强制清除。
        if (owner != null && pendingWindowSurface.load()?.owner !== owner) {
            return
        }
        pendingWindowSurface.store(null)
        surfaceAttached.value = false
        presentEvents.trySend(Unit)
        vkSession?.detachOutputSurface()
    }

    override fun configurePresentMode(mode: VapPresentMode) {
        decoderConfig = decoderConfig.copy(
            outputMode = when (mode) {
                VapPresentMode.Bitmap -> VapOutputMode.HardwareBuffer
                VapPresentMode.Surface -> VapOutputMode.WindowSurface
            },
        )
    }

    override fun configureGpuBackend(backend: VapGpuBackend) {
        decoderConfig = decoderConfig.copy(gpuBackend = backend)
    }

    override suspend fun advancePresentedFrame(surfaceMode: Boolean): VapFrameAdvance {
        return if (surfaceMode) {
            val advance = awaitSurfaceAdvance()
            if (advance == VapFrameAdvance.SurfacePresented) {
                // The opening's job ends with the first presented codec frame; drop the
                // decoder-side stash (~1.8MB IntArray). The session layer clears its own
                // references (openingPixels/openingUploadedFor) on the same transition.
                // 首个 codec 帧 present 后 opening 使命结束：清掉 decoder 侧暂存
                //（约 1.8MB IntArray）。session 层的 openingPixels/openingUploadedFor
                // 在同一时机自清。
                pendingOpeningFrame.store(null)
            }
            advance
        } else {
            val frame = nextFrame() ?: return VapFrameAdvance.Ended
            VapFrameAdvance.Bitmap(frame)
        }
    }

    override suspend fun awaitFramePresented(): Boolean {
        while (true) {
            when (awaitSurfaceAdvance()) {
                VapFrameAdvance.SurfacePresented -> return true
                VapFrameAdvance.Ended -> return false
                else -> continue
            }
        }
    }

    private suspend fun awaitSurfaceAdvance(): VapFrameAdvance {
        if (decoderConfig.outputMode != VapOutputMode.WindowSurface) {
            error("awaitFramePresented() requires WindowSurface output mode")
        }
        // Event-driven retry: pause / detach abort the tick wait so a later
        // attach+resume can receive the next present. A one-shot receive()
        // stayed blocked forever after the 3s watchdog was removed.
        // 事件驱动重试：暂停 / 拆 Surface 会中断等 tick，之后 attach+resume
        // 才能收到下一次 present。去掉 3 秒看门狗后，一次 receive() 会永远卡住。
        //
        // 每帧只做一次 select，不再创建 CompletableDeferred + 3 个协程。
        // `presentEvents` 是 CONFLATED 状态唤醒；`presentedTicks` 关闭即 EOS。
        // One select per frame instead of a CompletableDeferred + 3 coroutines.
        while (true) {
            if (config == null || stopped.load()) return VapFrameAdvance.Ended
            if (!playbackGate.isActive()) {
                // awaitActive 内部先查 active.value：门开着时立即返回，不再走
                // StateFlow.first 的 AbortFlowException 路径。
                playbackGate.awaitActive { stopped.load() }
                continue
            }
            if (!surfaceAttached.value) {
                // 无 Surface 时只等“状态变化 / 流结束”，不能把 tick 当成一次 present。
                val wait = select<PresentWait> {
                    presentedTicks.onReceiveCatching {
                        if (it.isClosed) PresentWait.Ended else PresentWait.Retry
                    }
                    presentEvents.onReceive { PresentWait.Retry }
                }
                if (wait == PresentWait.Ended) return VapFrameAdvance.Ended
                continue
            }
            // Fast path: a tick is already buffered (present loop slightly behind), no
            // need to build a select. tryReceive also reports a closed channel.
            val buffered = presentedTicks.tryReceive()
            if (buffered.isSuccess) return buffered.getOrThrow()
            if (buffered.isClosed) return VapFrameAdvance.Ended
            val advance = select<VapFrameAdvance?> {
                    presentedTicks.onReceiveCatching {
                        if (it.isClosed) VapFrameAdvance.Ended else it.getOrThrow()
                    }
                    presentEvents.onReceive { null }
            }
            if (advance != null) return advance
        }
    }

    override suspend fun open(source: VapSource, loop: Boolean, fpsOverride: Int?): VapConfig =
        openInternal(source, config = null, loop = loop, fpsOverride = fpsOverride, decode = true)

    override suspend fun open(
        source: VapSource,
        config: VapConfig,
        loop: Boolean,
        fpsOverride: Int?,
    ): VapConfig = openInternal(source, config = config, loop = loop, fpsOverride = fpsOverride, decode = true)

    override suspend fun openPresent(
        source: VapSource,
        config: VapConfig,
        loop: Boolean,
        fpsOverride: Int?,
    ): VapConfig = openInternal(source, config = config, loop = loop, fpsOverride = fpsOverride, decode = false)

    override suspend fun startDecode() {
        sessionMutex.withLock {
            if (codecStarted || codec != null) return@withLock
            val bind = pendingBindExtractor ?: return@withLock
            val cfg = config ?: return@withLock
            startCodecPipelineLocked(bind, cfg)
        }
    }

    override suspend fun stopDecode() {
        withContext(Dispatchers.IO) {
            sessionMutex.withLock {
                val presented = vkSession?.lastPresentedPtsUs() ?: -1L
                val released = lastReleasedPtsUs
                // Prefer the on-screen PTS when it shares the codec timebase.
                // Some devices stamp Image.timestamp with a monotonic clock; ignore those.
                // 画面上的 PTS 与 codec 同一时基时优先用它。
                // 有的设备给 Image.timestamp 打的是单调时钟，那种不能用。
                val t = when {
                    presented >= 0L && (released < 0L || kotlin.math.abs(presented - released) < 2_000_000L) ->
                        presented
                    released >= 0L -> released
                    else -> extractor?.sampleTime ?: -1L
                }
                if (t >= 0L) resumeSampleTimeUs = t
                if (codec == null && !codecStarted) return@withLock
                VapLog.i("VapLoop") { "sid=$sessionSeq codec-down resumeUs=$resumeSampleTimeUs" }
                stopCodecPipelineLocked()
            }
        }
    }

    private suspend fun openInternal(
        source: VapSource,
        config: VapConfig?,
        loop: Boolean,
        fpsOverride: Int?,
        decode: Boolean,
    ): VapConfig = withContext(Dispatchers.IO) {
        sessionMutex.withLock {
            closeInternalLocked()
            sessionSeq = nextDecoderSessionId.addAndFetch(1)
            this@AndroidVapFrameDecoder.loop = loop
            VapLog.i("VapLoop") { "sid=$sessionSeq open loop=$loop decode=$decode" }
            playbackGate.setPlaying(true)
            val parsed = config ?: when (source) {
                is VapSource.AbsolutePath -> parseMp4File(source.path)
                is VapSource.Asset -> parseMp4Asset(source.assetPath)
                is VapSource.Bytes -> parseMp4Bytes(source.data)
                is VapSource.Region -> parseMp4Region(source)
            }
            try {
                val bindExtractor: (MediaExtractor) -> Unit = when (source) {
                    is VapSource.Bytes -> { extractor ->
                        extractor.setDataSource(VapByteArrayMediaDataSource(source.data))
                    }
                    is VapSource.AbsolutePath, is VapSource.Asset, is VapSource.Region -> {
                        val opened = openAndroidVapExtractorSource(source)
                        extractorSource = opened
                        opened::bind
                    }
                }
                this@AndroidVapFrameDecoder.config = parsed
                pendingBindExtractor = bindExtractor
                // WindowSurface has no PTS. fps=0 presents every vsync and lets MediaCodec
                // race ahead (easy H.264 clips melt the SoC). Fall back to vapc fps.
                // WindowSurface 没有 PTS。fps=0 会每个 vsync 都 present，MediaCodec
                // 会抢跑（简单 H.264 会把 SoC 打满）。回退到 vapc 帧率。
                setTargetFrameRate(fpsOverride?.takeIf { it > 0 } ?: parsed.fps)
                startPresentPipelineLocked(parsed)
                if (decode) {
                    startCodecPipelineLocked(bindExtractor, parsed)
                }
                applyDisplaySize(parsed, decoderConfig.displayWidth, decoderConfig.displayHeight)
                parsed
            } catch (e: CancellationException) {
                withContext(NonCancellable) { closeInternalLocked() }
                throw e
            } catch (t: Throwable) {
                withContext(NonCancellable) { closeInternalLocked() }
                throw t
            }
        }
    }

    override suspend fun nextFrame(): VapPlatformFrame? {
        if (decoderConfig.outputMode == VapOutputMode.WindowSurface) {
            error("nextFrame() is not used in WindowSurface mode; call awaitFramePresented()")
        }
        if (config == null || stopped.load()) return null
        playbackGate.awaitActive { stopped.load() }
        if (config == null || stopped.load()) return null
        return frameChannel.receiveCatching().getOrNull()
    }

    override suspend fun releaseDecodeSession() {
        // Mirror openInternal: codec stop/release and Vulkan teardown must not run on the
        // caller (Compose present-loop finally blocks run on Main).
        // 与 openInternal 对称：codec stop/release 与 Vulkan 拆除不得跑在调用方线程
        // （Compose present 循环的 finally 块在 Main 上执行）。
        withContext(Dispatchers.IO) {
            sessionMutex.withLock {
                closeInternalLocked()
            }
        }
    }

    override fun close() {
        runBlocking {
            sessionMutex.withLock { closeInternalLocked() }
        }
    }

    override fun setDisplaySize(widthPx: Int, heightPx: Int) {
        if (widthPx == decoderConfig.displayWidth && heightPx == decoderConfig.displayHeight) return
        decoderConfig = decoderConfig.copy(displayWidth = widthPx, displayHeight = heightPx)
        val cfg = config ?: return
        applyDisplaySize(cfg, widthPx, heightPx)
    }

    override fun setPlaying(playing: Boolean) {
        applyGateChange(playbackGate.setPlaying(playing))
    }

    override fun setTargetFrameRate(fps: Int) {
        decoderConfig = decoderConfig.copy(targetFrameRate = fps.coerceAtLeast(0))
        vkSession?.setTargetFrameRate(decoderConfig.targetFrameRate)
        vkSession?.resetPresentClock()
    }

    /** Codec callback counters plus pump-side gates. Safe for idle-watch. /
     *  codec 回调计数 + 泵侧门控。idle-watch 可直接拼。 */
    internal fun codecDiag(): String {
        val async = codecAsync
        return (async?.codecDiag() ?: "inN=0 outN=0 inAgo=na outAgo=na") +
            " queued=${queuedOutputs.load()} deferred=${deferredInputIndices.size} " +
            "inputDone=${inputDone.load()} gate=${playbackGate.isActive()}"
    }

    internal fun applyGateChange(resumed: Boolean) {
        // Pause freezes the decode position: keep the pending Image / decode slot so
        // vsync does not present, and do not discard in-flight work. Input is deferred
        // in feedInputInline. Discarding here used to drop the next frame and free the
        // slot, so the codec kept running and resume jumped ahead.
        // 暂停要冻住解码位置：保留 pending Image / 解码槽位，vsync 不再呈现，
        // 也不丢弃在飞的工作。input 在 feedInputInline 里暂缓。以前这里丢掉下一帧
        // 并释放槽位，codec 继续跑，恢复时画面会往前跳。
        vkSession?.setPlaying(playbackGate.isActive())
        // 唤醒 awaitFramePresented 的一次 select：门关闭时退出等 tick，恢复时重新进入。
        presentEvents.trySend(Unit)
        if (!playbackGate.isActive()) {
            wakePresentSlotWaiters()
        }
        if (resumed) {
            vkSession?.resetPresentClock()
        }
    }

    internal fun applyDisplaySize(cfg: VapConfig, widthPx: Int, heightPx: Int) {
        // WindowSurface: output is sized by the Surface itself.
        // WindowSurface：输出尺寸由 Surface 自身决定。
    }

    internal fun drainFrameChannel() {
        // Drop any pending Bitmaps so the pool's onRelease fires; close()/detach must not
        // leak native pixels when consumers stop awaiting.
        // 丢弃所有待处理 Bitmap 以触发池的 onRelease；close()/detach 时不能让原生像素泄漏。
        while (true) {
            val frame = frameChannel.tryReceive().getOrNull() ?: break
            frame.release()
        }
    }

    /**
     * Decode pump finished (EOS / error). Buffered ticks/frames remain receivable;
     * further [awaitFramePresented] / [nextFrame] then see the close as end-of-stream.
     * 解码泵结束（EOS / 出错）。通道里已有的 tick / 帧仍可取；之后
     * [awaitFramePresented] / [nextFrame] 把关闭当成流结束。
     */
    internal fun signalStreamEnded() {
        presentedTicks.close()
        frameChannel.close()
    }

    /**
     * After a pause/resume seek, drop outputs at or before the freeze PTS so
     * ImageReader does not replace the on-screen frame with an earlier I-frame.
     *
     * 暂停恢复 seek 之后，丢掉冻帧 PTS 及更早的输出，避免 ImageReader
     * 用更早的关键帧盖掉屏幕上的冻帧。
     */
    internal fun shouldDropUntilResume(ptsUs: Long): Boolean {
        val until = resumeCatchUpUntilUs
        if (until < 0L) return false
        if (ptsUs <= until) return true
        resumeCatchUpUntilUs = -1L
        return false
    }

    internal fun noteReleasedPts(ptsUs: Long) {
        if (ptsUs >= 0L) lastReleasedPtsUs = ptsUs
    }

    internal fun drainPresentedTicks() {
        while (presentedTicks.tryReceive().isSuccess) {
        }
    }

    /** Caller must hold [sessionMutex]. / 调用方必须持有 [sessionMutex]。 */
    internal suspend fun closeInternalLocked() {
        stopPipelineLocked()
        config = null
        loop = false
        codecStarted = false
        resumeSampleTimeUs = -1L
        resumeCatchUpUntilUs = -1L
        lastReleasedPtsUs = -1L
        pendingBindExtractor = null

        extractorSource?.close()
        extractorSource = null
    }

    internal fun selectVideoTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) return i
        }
        return -1
    }
}
