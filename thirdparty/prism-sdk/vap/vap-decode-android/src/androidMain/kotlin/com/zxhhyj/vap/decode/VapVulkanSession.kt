@file:OptIn(ExperimentalAtomicApi::class)

package com.zxhhyj.vap.decode

import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.view.Surface
import com.zxhhyj.vap.log.VapLog
import com.zxhhyj.vap.player.VapConfig
import com.zxhhyj.vap.vk.VapVkNative
import kotlinx.coroutines.channels.Channel
import java.util.ArrayDeque
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * One Vulkan decode+present session: MediaCodec → [ImageReader] AHB → native VkImage
 * import → VAP alpha composite → swapchain.
 *
 * 一路 Vulkan 解码+呈现会话：MediaCodec → [ImageReader] AHB → 原生 VkImage 导入 →
 * VAP alpha 合成 → 交换链。
 *
 * Timing: decode fills a pending slot; [VapVsyncClock] presents on the target-fps grid.
 * 时序：解码填入 pending 槽位；[VapVsyncClock] 按目标 fps 格子触发 present。
 *
 * Late-frame policy: when the present clock falls behind the wall clock by more than
 * [VapVulkanPipeline.kDropToleranceCells] target-fps cells, the stale pending frame is
 * dropped without presenting so playback catches up to real time (standard player
 * behavior) instead of stretching into slow motion. A burst cap
 * ([VapVulkanPipeline.kMaxConsecutiveDrops]) forces a present when decode itself is the
 * bottleneck, so the screen never freezes; and beyond
 * [VapVulkanPipeline.kMaxCatchupCells] of lag (long stall / background) the clock is
 * rebased onto the decode position instead of replaying the backlog.
 * 落后帧策略：当呈现时钟落后挂钟超过 [VapVulkanPipeline.kDropToleranceCells] 个目标
 * fps 格子时，过期的待呈现帧不呈现直接丢弃，使播放追平真实时间（标准播放器行为），
 * 而不是拉成慢动作。burst 上限（[VapVulkanPipeline.kMaxConsecutiveDrops]）在解码本身
 * 成为瓶颈时强制呈现，保证画面不会冻结；落后超过
 * [VapVulkanPipeline.kMaxCatchupCells]（长时间停顿 / 后台）时把时钟重置到解码位置，
 * 而不是重放积压。
 *
 * [Image] is kept open for [VapVulkanPipeline.kInflightImages] presents so BufferQueue
 * sees the consumer hold (closing Image immediately while GPU still samples was
 * incompatible with AHB reuse).
 * [Image] 在 [VapVulkanPipeline.kInflightImages] 次 present 期间保持打开，使
 * BufferQueue 看到消费者持有；若 GPU 仍在采样的同时立刻关闭 Image，会与 AHB 复用
 * 不兼容。
 */
internal class VapVulkanSession(
    private val sessionSeq: Int,
    private val config: VapConfig,
    private val presentedTicks: Channel<VapFrameAdvance>?,
    private val stopped: AtomicBoolean,
    private val onFrameComposited: () -> Unit,
    private val extraDiag: () -> String = { "" },
) {
    private data class PendingWindow(val surface: Surface, val width: Int, val height: Int)
    private class OpeningFrame(val pixels: IntArray, val width: Int, val height: Int)

    private val acquired = AtomicBoolean(false)
    private val playing = AtomicBoolean(true)
    private val pendingPresent = AtomicBoolean(false)
    private val decodeSlotHeld = AtomicBoolean(false)
    private val vsyncRegistered = AtomicBoolean(false)
    private val surfaceEpoch = AtomicInt(0)
    private val targetFps = AtomicInt(0)
    private val presentationFpsLimit = AtomicInt(0)
    private val rateLimitOriginUs = AtomicLong(Long.MIN_VALUE)
    private val lastPresentedBucket = AtomicLong(-1L)
    private val rateLimitDrops = AtomicLong(0L)
    private val successfulPresents = AtomicLong(0L)
    private val playStartMonoNs = AtomicLong(0L)
    private val playStartPtsNs = AtomicLong(0L)
    private val lastPresentedLogicalFrame = AtomicLong(-1L)
    /** PTS of the last frame actually shown on the swapchain. `-1` before first present. /
     *  交换链上最后一帧的 PTS。首帧 present 前为 `-1`。 */
    private val lastPresentedPtsUs = AtomicLong(-1L)
    /** PTS stamped when the pending Image was acquired (never read back after present). /
     *  pending Image 在 acquire 时记下的 PTS（present 之后不再回读 Image）。 */
    private val pendingPtsUs = AtomicLong(-1L)
    /** False until the first vsync present, so frame 0 is not overwritten by acquireLatest.
     *  首次 vsync present 之前为 false，避免 acquireLatest 把第 0 帧冲掉。 */
    private val firstPresentDone = AtomicBoolean(false)

    /**
     * Codec-delivered frame counter since the last present-clock reset; each acquired
     * image is stamped with its 1-based sequence so the tick can compare the pending
     * frame against the wall-clock cell and drop stale ones. Accurate because the
     * single decode slot keeps codec output in lock-step with acquires.
     * 自上次呈现时钟重置以来的解码交付帧计数；每张 acquire 到的图像打上 1 基序号，
     * 供 tick 将待呈现帧与挂钟格子比较并丢弃过期帧。单解码槽位使 codec 输出与
     * acquire 保持锁步，因此计数准确。
     */
    private val deliveredFrames = AtomicLong(0L)
    private val pendingImageSeq = AtomicLong(0L)
    /** Pending-frame arrival time (System.nanoTime domain, same as vsync frameTimeNanos).
     *  Lets drop logs tell "frame arrived late" apart from "tick ran late".
     *  pending 帧到达时刻（System.nanoTime 时钟域，与 vsync frameTimeNanos 同域）。
     *  让丢帧日志能区分「帧到晚了」与「tick 跑晚了」。 */
    private val pendingArrivalNanos = AtomicLong(0L)
    /** Consecutive stale drops without an intervening present. / 连续过期丢帧数（期间无任何 present）。 */
    private val dropStreak = AtomicInt(0)
    /** Pending frame overwritten by a newer acquire before any present — should never happen
     *  while the single decode slot gates delivery; a nonzero count signals a gating hole.
     *  pending 帧未呈现就被新 acquire 覆盖的次数 —— 单解码槽位门控下不应发生，非零即门控漏洞信号。 */
    private val overwriteDrops = AtomicInt(0)
    /** Native present/prepare failures (swapchain loss etc.). / 原生 present/prepare 失败次数（交换链丢失等）。 */
    private val presentFailures = AtomicInt(0)
    /**
     * Consecutive ticks that found no pending frame (predictive pre-arm budget);
     * touched only on the vap-vk-shared thread.
     * 连续没有发现待呈现帧的 tick 数（预测性预排额度）；仅在 vap-vk-shared 线程访问。
     */
    private var emptyTicks = 0
    /**
     * Last [nextPresentDelayMillis] idle reason on the vk thread; null while the
     * session is still asking the shared clock for a pulse.
     * 该会话在 vk 线程上最近一次 [nextPresentDelayMillis] 休眠原因；仍在向共享时钟
     * 要脉冲时为 null。
     */
    private var idleLoggedReason: String? = null
    private val clockIdle = AtomicBoolean(false)
    private val imageAvailableN = AtomicInt(0)
    private val imageAcquireOkN = AtomicInt(0)
    private val imageAcquireEmptyN = AtomicInt(0)
    private val lastImageAvailableElapsed = AtomicLong(0L)

    private val openingPixels = AtomicReference<OpeningFrame?>(null)
    private val openingPresented = AtomicBoolean(false)
    /** Guarded by [VapVulkanPipeline.tickLock]; identifies the opening pixels already in native.
     *  由 [VapVulkanPipeline.tickLock] 保护；标记已上传到 native 的 opening 像素。 */
    private var openingUploadedFor: OpeningFrame? = null
    private val pendingWindow = AtomicReference<PendingWindow?>(null)
    private val appliedWindow = AtomicReference<PendingWindow?>(null)
    /** Latest decoded frame; closed only when superseded or after inflight window.
     * 最新解码帧；仅在被覆盖或经过 in-flight 窗口后才关闭。 */
    private val pendingImage = AtomicReference<Image?>(null)

    /** Images still referenced by in-flight GPU work. Touched only on vap-vk-shared.
     * 仍被 in-flight GPU 工作引用的 Image。仅在 vap-vk-shared 线程上访问。 */
    private val inflightImages = ArrayDeque<Image>(VapVulkanPipeline.kInflightImages + 1)

    private val nativeBusy = AtomicInt(0)
    private var batchPrepared = false
    private var openingRetryScheduled = false
    private var handle: Long = 0L
    private var imageReader: ImageReader? = null
    private var sharedAcquired = false

    /** Native engine handle for the batched submit step (0 = destroyed). / 批处理 submit 步骤使用的原生引擎句柄（0 = 已销毁）。 */
    internal val nativeHandle: Long
        get() = if (nativeBusy.load() > 0) 0L else handle

    /**
     * Free batch-slot holds for frames the GPU already finished. Called every tick for
     * every registered session — even ones skipping present — so holds never pin stale batches.
     * 释放 GPU 已完成帧的批处理槽位持有。每个 tick 都会为所有已注册的会话调用 —— 即便
     * 该会话本 tick 跳过 present —— 以防持有陈旧的批次。
     */
    internal fun retireSignaled() {
        if (!VapVulkanPipeline.BATCH_PRESENT) return
        val h = handle
        if (h != 0L && acquired.load() && nativeBusy.load() == 0) VapVkNative.nativeRetireSignaled(h)
    }

    val codecSurface: Surface?
        get() = imageReader?.surface

    fun setTargetFrameRate(fps: Int) {
        // Decode/acquire stays on the source grid. Only GPU presentation is capped;
        // using the lower rate as the source clock would slow a 30fps clip to 80% speed.
        presentationFpsLimit.store(if (fps in 1 until config.fps) fps else 0)
        targetFps.store(VapPresentationRate.timelineFps(config.fps, fps))
    }

    /** PTS of the freeze frame currently on screen. / 当前停在画面上的冻帧 PTS。 */
    fun lastPresentedPtsUs(): Long = lastPresentedPtsUs.load()

    fun resetPresentClock() {
        playStartMonoNs.store(0L)
        playStartPtsNs.store(0L)
        lastPresentedLogicalFrame.store(-1L)
        rateLimitOriginUs.store(Long.MIN_VALUE)
        lastPresentedBucket.store(-1L)
        // Loop / resume restart both the wall-clock grid and the decode sequence.
        // Keep a still-pending frame as seq 1 of the new grid so resume does not
        // wait out the old index (or drop it as stale after a long pause).
        // 循环 / 恢复会同时重启挂钟网格与解码序号。
        // 仍待呈现的帧记为新网格的第 1 帧，避免恢复后空等旧序号，
        // 或被长时间暂停后的过期策略丢掉。
        if (pendingPresent.load()) {
            deliveredFrames.store(1L)
            pendingImageSeq.store(1L)
        } else {
            deliveredFrames.store(0L)
            pendingImageSeq.store(0L)
        }
        dropStreak.store(0)
        overwriteDrops.store(0)
        presentFailures.store(0)
    }

    /**
     * Pause keeps the pending Image and decode slot (freeze). Resume re-arms vsync
     * if a frame is waiting so playback continues from that same frame.
     * 暂停保留 pending Image 和解码槽位（冻帧）。恢复时若有等待中的帧，
     * 重新挂上 vsync，从同一帧继续播。
     */
    fun setPlaying(value: Boolean) {
        val prev = playing.exchange(value)
        if (prev == value) return
        if (!value) {
            unregisterVsync()
        } else {
            // Always re-arm. pending=false used to skip registerVsync, so a
            // pause/resume with an empty slot left the clock dead until the
            // next ImageReader event — which never came if the pump was parked.
            // 一律重新挂上。以前 pending=false 会跳过 registerVsync，空槽位上的
            // 暂停/恢复会让时钟死掉，直到下一次 ImageReader 事件 —— 泵若已停住就永远等不到。
            registerVsync()
        }
    }

    fun attachOutputSurface(surface: Surface, width: Int, height: Int) {
        pendingWindow.store(PendingWindow(surface, width.coerceAtLeast(1), height.coerceAtLeast(1)))
        applyPendingWindow()
        // playing may already be true (resume before a new Surface is attached). vsync
        // is often already idle for lack of a window; re-arm now — ImageReader alone is not enough.
        // playing 可能已经是 true（先 resume 再挂上新 Surface）。此时 vsync 往往
        // 已经因为没窗口 idle 掉；必须再打一拍，不能只靠 ImageReader。
        if (playing.load()) {
            registerVsync()
            VapVsyncClock.requestImmediateTick()
        }
    }

    fun setOpeningFrame(pixels: IntArray?, width: Int, height: Int) {
        val opening = if (pixels != null && width > 0 && height > 0) {
            OpeningFrame(pixels, width, height)
        } else {
            null
        }
        openingPixels.store(opening)
        openingPresented.store(false)
        applyPendingWindow()
    }

    fun resizeOutput(width: Int, height: Int) {
        val cur = pendingWindow.load() ?: return
        pendingWindow.store(cur.copy(width = width.coerceAtLeast(1), height = height.coerceAtLeast(1)))
        applyPendingWindow()
    }

    fun detachOutputSurface() {
        pendingWindow.store(null)
        appliedWindow.store(null)
        firstPresentDone.store(false)
        openingPresented.store(false)
        surfaceEpoch.addAndFetch(1)
        VapSharedVkRuntime.runOnVkThreadBlocking {
            synchronized(VapVulkanPipeline.tickLock) {
                nativeBusy.addAndFetch(1)
                batchPrepared = false
                val h = handle
                VapSharedVkRuntime.launchMaintenance {
                    if (h != 0L) VapVkNative.nativeClearOutputSurface(h)
                    VapSharedVkRuntime.imageHandler()?.post {
                        if (acquired.load()) {
                            nativeBusy.addAndFetch(-1)
                            applyPendingWindow()
                        }
                    }
                }
            }
        }
    }

    /** Called at a new codec generation, off the render looper. In-flight imports retire normally. */
    fun resetImportPool() {
        val h = handle
        if (h != 0L) VapVkNative.nativeResetImportPool(h)
    }

    fun discardPendingPresent() {
        discardPending(notify = false)
    }

    fun start(): Boolean {
        if (!VapVulkanPipeline.IMPLEMENTED) return false
        VapSharedVkRuntime.acquire()
        sharedAcquired = true
        try {
            handle = VapVkNative.nativeCreate(
                videoW = config.videoWidth,
                videoH = config.videoHeight,
                alphaX = config.alphaFrame.x,
                alphaY = config.alphaFrame.y,
                alphaW = config.alphaFrame.w,
                alphaH = config.alphaFrame.h,
                rgbX = config.rgbFrame.x,
                rgbY = config.rgbFrame.y,
                rgbW = config.rgbFrame.w,
                rgbH = config.rgbFrame.h,
            )
        } catch (t: Throwable) {
            VapLog.e("VapVk", t) { "nativeCreate failed" }
            handle = 0L
            releaseShared()
            return false
        }
        if (handle == 0L) {
            releaseShared()
            return false
        }

        val handler = VapSharedVkRuntime.imageHandler()
        if (handler == null) {
            VapLog.e("VapVk") { "Shared Vulkan runtime has no image handler" }
            if (handle != 0L) {
                VapVkNative.nativeDestroy(handle)
                handle = 0L
            }
            releaseShared()
            return false
        }

        val w = config.videoWidth.coerceAtLeast(1)
        val h = config.videoHeight.coerceAtLeast(1)
        // in-flight held open + pending + one free slot for MediaCodec producer.
        // 容量 = 在飞行（in-flight）持有 + pending + 一格空闲槽位供 MediaCodec 生产者使用。
        val maxImages = VapVulkanPipeline.kInflightImages + 2
        val usage = HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
        imageReader = if (Build.VERSION.SDK_INT >= 29) {
            ImageReader.newInstance(w, h, ImageFormat.PRIVATE, maxImages, usage)
        } else {
            ImageReader.newInstance(w, h, ImageFormat.PRIVATE, maxImages)
        }
        firstPresentDone.store(false)
        openingPresented.store(false)
        lastPresentedPtsUs.store(-1L)
        pendingPtsUs.store(-1L)
        imageReader?.setOnImageAvailableListener({ reader ->
            if (stopped.load() || !acquired.load()) return@setOnImageAvailableListener
            val wasIdle = clockIdle.load()
            imageAvailableN.addAndFetch(1)
            lastImageAvailableElapsed.store(android.os.SystemClock.elapsedRealtime())
            val image = try {
                if (!firstPresentDone.load()) reader.acquireNextImage() else reader.acquireLatestImage()
            } catch (t: Throwable) {
                // Reader closing mid-stream or maxImages exhausted; the frame is lost either way.
                // reader 流途中关闭或 maxImages 耗尽；无论哪种这帧都丢了。
                VapLog.wRateLimited("vk-acquire-fail", 1_000L, "VapVk", t) { "acquire image failed, frame lost" }
                null
            }
            if (image == null) {
                imageAcquireEmptyN.addAndFetch(1)
                if (wasIdle) {
                    VapLog.wRateLimited("vk-idle-empty-$sessionSeq", 1_000L, "VapLoop") {
                        "${idleDiag()} idle-wakeup acquired=false"
                    }
                }
                // Pump already holds the compositor slot for this release. A null acquire
                // (spurious callback or dropped buffer) must free that slot or
                // the decoder waits forever while present sees pending=false.
                // 泵已经为这次 release 占着 compositorSlot。acquire 为空（空回调 / 缓冲被丢）
                // 必须把槽还回去，否则解码永远等、present 却看见 pending=false。
                if (!pendingPresent.load()) {
                    VapLog.wRateLimited("vk-acquire-empty-$sessionSeq", 1_000L, "VapLoop") {
                        "${idleDiag()} image-acquire-empty"
                    }
                    releaseDecodeSlot()
                } else {
                    VapLog.wRateLimited("vk-acquire-empty-pending-$sessionSeq", 1_000L, "VapLoop") {
                        "${idleDiag()} image-acquire-empty pending-kept"
                    }
                }
                return@setOnImageAvailableListener
            }
            imageAcquireOkN.addAndFetch(1)
            val seq = deliveredFrames.addAndFetch(1L)
            val ptsUs = imageTimestampUs(image)
            if (!firstPresentDone.load()) {
                if (!pendingImage.compareAndSet(null, image)) {
                    closeQuietly(image)
                    return@setOnImageAvailableListener
                }
            } else {
                val previous = pendingImage.exchange(image)
                if (previous != null) {
                    // The decode-slot gate should prevent this; log loudly when it happens.
                    // 解码槽位门控本应杜绝此情况；一旦发生要大声报出来。
                    val total = overwriteDrops.addAndFetch(1)
                    VapLog.wRateLimited("vk-overwrite", 1_000L, "VapVk") {
                        "pending frame overwritten before present (seq=$seq), total=$total"
                    }
                    closeQuietly(previous)
                }
            }
            pendingPtsUs.store(ptsUs)
            // Stamp before raising the flag: the tick reads flag → image → seq.
            // 先打序号再置标志：tick 按 标志 → 图像 → 序号 的顺序读取。
            pendingArrivalNanos.store(System.nanoTime())
            pendingImageSeq.store(seq)
            emptyTicks = 0
            pendingPresent.store(true)
            decodeSlotHeld.store(true)
            markClockActive()
            registerVsync()
            // Sibling sessions releasing the shared device run vkDeviceWaitIdle, which
            // blocks this handler long enough for the pre-arm budget to burn out
            // (cold start: Touch froze right after 3 sibling opens). A fresh frame
            // must re-arm the grid even when a pulse is already queued.
            // 兄弟会话释放共享设备会跑 vkDeviceWaitIdle，把这条 handler 堵到
            // 预排额度耗尽（冷启动时 Touch 在 3 个兄弟 open 后冻住）。新帧到达
            // 必须重新预约格子，哪怕已有脉冲在排队。
            VapVsyncClock.requestImmediateTick()
        }, handler)

        acquired.store(true)
        applyPendingWindow()
        return true
    }

    fun release() {
        if (!acquired.exchange(false)) return
        unregisterVsync()
        // Detach from the ticker first. Maintenance owns the captured native handle and
        // ImageReader until GPU completion; releaseShared must run only after destruction.
        VapSharedVkRuntime.runOnVkThreadBlocking {
            synchronized(VapVulkanPipeline.tickLock) {
                nativeBusy.addAndFetch(1)
                val h = handle
                handle = 0L
                imageReader?.setOnImageAvailableListener(null, null)
                val reader = imageReader
                imageReader = null
                val pending = pendingImage.exchange(null)
                pendingPresent.store(false)
                val inflight = inflightImages.toList()
                inflightImages.clear()
                VapSharedVkRuntime.launchMaintenance {
                    try {
                        if (h != 0L) VapVkNative.nativeDestroy(h)
                    } finally {
                        pending?.let { closeQuietly(it) }
                        inflight.forEach { closeQuietly(it) }
                        reader?.close()
                        releaseShared()
                    }
                }
            }
        }
    }

    private fun releaseShared() {
        if (!sharedAcquired) return
        sharedAcquired = false
        VapSharedVkRuntime.release()
    }

    /** @return true if a native present was attempted this tick. / 若本 tick 尝试了一次原生 present 则返回 true。 */
    internal fun onVsyncTick(frameTimeNanos: Long): Boolean {
        if (stopped.load() || !acquired.load() || !playing.load() || nativeBusy.load() > 0) return false
        if (!pendingPresent.load()) {
            // Pre-armed tick that found no frame yet; spends pre-arm budget.
            // 预排的 tick 还没等到帧；消耗预排额度。
            emptyTicks++
            return false
        }
        // Fewer presents: no valid output yet → skip GPU but keep pending Image.
        // 减少 present 次数：尚无可用输出 → 跳过 GPU，但保留 pending Image。
        val out = appliedWindow.load() ?: pendingWindow.load()
        if (out == null || !out.surface.isValid) {
            VapLog.wRateLimited("vk-surf-invalid-$sessionSeq", 1_000L, "VapLoop") {
                "sid=$sessionSeq tick-skip surface=${out?.let { "${it.width}x${it.height} valid=${it.surface.isValid}" } ?: "null"} " +
                    "pending=${pendingPresent.load()} playing=${playing.load()}"
            }
            return false
        }
        applyPendingWindow()
        if (nativeBusy.load() > 0) return false
        if (appliedWindow.load() == null) {
            // Window bind is deferred or failed. Keep the pending image for the next tick.
            // 窗口还没挂上或失败：保留 pending，等下一拍再试。
            VapLog.wRateLimited("vk-bind-fail-$sessionSeq", 1_000L, "VapLoop") {
                "sid=$sessionSeq tick-skip bind-failed ${out.width}x${out.height}"
            }
            return false
        }
        val image = pendingImage.load() ?: run {
            // Inconsistent state: flag set but no image — self-heal, but leave a trace.
            // 不一致状态：标志已置但没有图像 —— 自愈合，但留下痕迹。
            VapLog.wRateLimited("vk-pending-missing", 1_000L, "VapVk") { "pendingPresent set but image missing" }
            discardPending(notify = false)
            return false
        }
        val fps = targetFps.load()
        // PTS unknown for AHB path; wall-clock grid when targetFps > 0, else present every vsync.
        // AHB 路径无 PTS 可用：targetFps > 0 时使用挂钟时间网格，否则每个 vsync 都 present。
        if (fps > 0 && !batchPrepared) {
            val cell = cellPeriodNs(fps)
            val start = playStartMonoNs.load()
            if (start == 0L) {
                playStartMonoNs.store(frameTimeNanos)
            }
            val elapsed = (frameTimeNanos - playStartMonoNs.load()).coerceAtLeast(0L)
            var logical = VapPresentGrid.logicalIndex(elapsed, cell)
            val pendingIndex = pendingImageSeq.load() - 1L
            // Gate on the frame's OWN due time (with wake lead), not last-presented+1.
            // 以该帧自己的到期时刻（含唤醒提前量）做判定，而不是上次 present+1。
            val due = VapPresentGrid.dueNanos(playStartMonoNs.load(), pendingIndex, cell)
            if (!VapPresentGrid.isDue(frameTimeNanos, due)) {
                // Keep pending Image + decode slot until the frame's own cell.
                // 保持 pending Image 和解码槽位直到该帧自己的格子。
                return false
            }
            if (firstPresentDone.load()) {
                val behind = logical - pendingIndex
                val arrivalNs = pendingArrivalNanos.load()
                val arrivalAgeNs = (frameTimeNanos - arrivalNs).coerceAtLeast(0L)
                when (
                    VapLateFramePolicy.decide(
                        behind = behind,
                        dropStreak = dropStreak.load(),
                        arrivalAgeNs = arrivalAgeNs,
                        cellNs = cell,
                        dropToleranceCells = VapVulkanPipeline.kDropToleranceCells,
                        maxCatchupCells = VapVulkanPipeline.kMaxCatchupCells,
                        maxConsecutiveDrops = VapVulkanPipeline.kMaxConsecutiveDrops,
                    )
                ) {
                    VapLateFrameAction.Present -> {
                    }
                    VapLateFrameAction.Rebase -> {
                        playStartMonoNs.store(frameTimeNanos - pendingIndex * cell)
                        logical = pendingIndex
                        dropStreak.store(0)
                    }
                    VapLateFrameAction.Drop -> {
                        dropStreak.addAndFetch(1)
                        discardPending(notify = false)
                        return false
                    }
                }
            }
            lastPresentedLogicalFrame.store(logical)
        }
        dropStreak.store(0)
        emptyTicks = 0
        markClockActive()
        // Consume unselected frames on their original due times, before importing AHB
        // or submitting GPU work. Never touch prepared batches while they await retry.
        if (!batchPrepared && presentationFpsLimit.load() > 0 &&
            pendingPresentationBucket() <= lastPresentedBucket.load()
        ) {
            rateLimitDrops.addAndFetch(1L)
            // Advance source progress without claiming a successful GPU present.
            presentedTicks?.trySend(VapFrameAdvance.SurfaceSkipped)
            discardPending(notify = false)
            return false
        }
        val buf = try {
            image.hardwareBuffer
        } catch (t: Throwable) {
            VapLog.wRateLimited("vk-hwbuf-fail", 1_000L, "VapVk", t) { "hardwareBuffer unavailable, frame dropped" }
            null
        }
        if (buf == null) {
            pendingPresent.store(false)
            batchPrepared = false
            pendingImage.compareAndSet(image, null)
            closeQuietly(image)
            releaseDecodeSlot()
            return false
        }
        val ok = if (VapVulkanPipeline.BATCH_PRESENT) {
            val result = VapVkNative.preparePresent(handle, buf)
            when (result) {
                VapVkNative.PrepareResult.NotReady -> {
                    buf.close()
                    return false
                }
                VapVkNative.PrepareResult.Recreate -> {
                    buf.close()
                    appliedWindow.store(null)
                    applyPendingWindow()
                    return false
                }
                VapVkNative.PrepareResult.Prepared -> {
                    buf.close()
                    batchPrepared = true
                    return true
                }
                VapVkNative.PrepareResult.Failed -> false
            }
        } else {
            VapVkNative.presentHardwareBuffer(handle, buf)
        }
        pendingPresent.store(false)
        batchPrepared = false
        if (!ok) {
            // Frame is consumed but never presented (swapchain loss / engine teardown).
            // 帧已被消费但未呈现（交换链丢失 / 引擎拆除）。
            val total = presentFailures.addAndFetch(1)
            VapLog.wRateLimited("vk-present-fail", 1_000L, "VapVk") {
                "native present failed (handle=$handle), total=$total"
            }
        }
        buf.close()
        finishPresentedImage(image, ok)
        return true
    }

    internal fun finishBatch(submitted: Boolean) {
        if (!batchPrepared) return
        if (!submitted) return // Retry the same prepared/pending frame on the next pulse.
        batchPrepared = false
        val image = pendingImage.load() ?: return
        pendingPresent.store(false)
        finishPresentedImage(image, true)
    }

    private fun finishPresentedImage(image: Image, ok: Boolean) {
        // Capture before returning the decode slot: the next Image callback can replace
        // pending PTS/sequence immediately after releaseDecodeSlot().
        val presentedPtsUs = pendingPtsUs.load()
        if (ok) {
            successfulPresents.addAndFetch(1L)
            if (presentationFpsLimit.load() > 0) {
                lastPresentedBucket.store(pendingPresentationBucket())
            }
        }
        pendingImage.compareAndSet(image, null)
        // Keep Image open until GPU in-flight window advances (BufferQueue consumer hold).
        // 保持 Image 打开直到 GPU in-flight 窗口前移（BufferQueue 消费者持有）。
        inflightImages.addLast(image)
        while (inflightImages.size > VapVulkanPipeline.kInflightImages) {
            closeQuietly(inflightImages.removeFirst())
        }
        releaseDecodeSlot()
        if (ok) {
            if (!firstPresentDone.load()) {
                // The opening texture's job ends with the first codec frame: past this
                // point presentOpeningIfNeededLocked always short-circuits on
                // firstPresentDone. Both references must be cleared — openingUploadedFor
                // also holds the OpeningFrame (and its ~1.8MB IntArray) alive.
                // opening 纹理的使命到首帧 present 为止；此后 presentOpeningIfNeededLocked
                // 永远因 firstPresentDone 短路。两个引用都要清 —— openingUploadedFor
                // 同样持有 OpeningFrame（及其约 1.8MB IntArray）。
                openingPixels.store(null)
                openingUploadedFor = null
                // Free the 720x1280 RGBA opening image + pipeline once the real codec
                // frame is on screen. A later surface reattach can recreate them through
                // setOpeningPixels() if needed.
                // 真实 codec 帧上屏后释放 720x1280 RGBA opening 纹理与管线；
                // 后续 Surface 重挂时若需要，setOpeningPixels() 会按需重建。
                val h = handle
                if (h != 0L) VapVkNative.nativeReleaseOpeningResources(h)
            }
            firstPresentDone.store(true)
            if (presentedPtsUs > 0L) lastPresentedPtsUs.store(presentedPtsUs)
            presentedTicks?.trySend(VapFrameAdvance.SurfacePresented)
        }
    }

    private fun pendingPresentationBucket(): Long {
        val sourceUs = (pendingImageSeq.load() - 1L).coerceAtLeast(0L) *
            1_000_000L / config.fps.coerceAtLeast(1)
        val pts = pendingPtsUs.load()
        val elapsedUs = if (pts >= 0L) {
            // Rebase after loop/resume, preserving the source position even if frame 0
            // had no Image timestamp. State uses atomics like the existing clock reset.
            rateLimitOriginUs.compareAndSet(Long.MIN_VALUE, pts - sourceUs)
            (pts - rateLimitOriginUs.load()).coerceAtLeast(0L)
        } else {
            sourceUs
        }
        return VapPresentationRate.bucket(elapsedUs, presentationFpsLimit.load().coerceAtLeast(1))
    }

    /**
     * Milliseconds until this session should tick again, or `null` to leave the
     * clock idle until the next ImageReader / surface attach.
     *
     * With no pending frame the clock would normally idle; while playback is warm
     * ([firstPresentDone]) it instead stays pre-armed on the cell grid for up to
     * [VapVulkanPipeline.kMaxPrearmedEmptyTicks] empty ticks (predictive pre-arm),
     * so an on-time frame presents on its boundary without the wake round trip.
     *
     * 距离下次应 tick 的毫秒数；`null` 表示时钟可以歇着，等下一帧 ImageReader 或 Surface 挂上。
     * 没有待呈现帧时时钟本会休眠；但在播放热态（[firstPresentDone]）下，
     * 时钟会在格子网格上继续预排至多 [VapVulkanPipeline.kMaxPrearmedEmptyTicks]
     * 次空 tick（预测性预排），使按时到达的帧正好踩在格子边界呈现，省掉唤醒回程。
     */
    internal fun nextPresentDelayMillis(nowNanos: Long): Long? {
        if (stopped.load() || !acquired.load()) {
            markClockIdle("stopped-or-released")
            return null
        }
        if (nativeBusy.load() > 0) return 8L
        val hasPending = pendingPresent.load()
        val out = appliedWindow.load() ?: pendingWindow.load()
        if (out == null || !out.surface.isValid) {
            markClockIdle("no-surface")
            return null
        }
        val fps = targetFps.load()
        if (fps <= 0) {
            if (hasPending) {
                markClockActive()
                return 2L
            }
            markClockIdle("fps0-no-pending")
            return null
        }
        val start = playStartMonoNs.load()
        if (start == 0L) {
            if (hasPending) {
                markClockActive()
                return 2L
            }
            markClockIdle("clock-unstarted")
            return null
        }
        val cell = cellPeriodNs(fps)
        if (!hasPending) {
            // No frame waiting: only keep the grid armed within the pre-arm budget.
            // 没有帧在等待：仅在预排额度内保持网格武装。
            if (!firstPresentDone.load()) {
                markClockIdle("pre-first-no-pending")
                return null
            }
            if (emptyTicks >= VapVulkanPipeline.kMaxPrearmedEmptyTicks) {
                markClockIdle("prearm-exhausted")
                return null
            }
        } else {
            // A waiting frame is due on its OWN cell (or immediately when the clock
            // already ran past it) — not on last-presented+1, which would hold an
            // on-time frame until it looks stale.
            // 等待中的帧在它自己的格子到期（时钟已越过则立即）——而不是上次 present+1 格，
            // 否则会把按时到达的帧压到看起来过期。
            val pendingIndex = pendingImageSeq.load() - 1L
            if (pendingIndex <= lastPresentedLogicalFrame.load()) {
                markClockActive()
                return 2L
            }
            val dueNanos = VapPresentGrid.dueNanos(start, pendingIndex, cell)
            markClockActive()
            return VapPresentGrid.delayMillisUntilDue(dueNanos, nowNanos).coerceAtLeast(2L)
        }
        val nextLogical = lastPresentedLogicalFrame.load() + 1L
        val nextNanos = VapPresentGrid.dueNanos(start, nextLogical, cell)
        markClockActive()
        return VapPresentGrid.delayMillisUntilDue(nextNanos, nowNanos)
    }

    /**
     * Snapshot for idle / ImageReader / pump-slot logs. Safe to call off the vk thread:
     * only atomics plus [emptyTicks] (benign race).
     * idle / ImageReader / 泵等槽日志用的快照。可在 vk 线程外调用：只读原子量和
     * [emptyTicks]（良性竞态）。
     */
    internal fun idleDiag(): String {
        val out = appliedWindow.load() ?: pendingWindow.load()
        val lastIr = lastImageAvailableElapsed.load()
        val irAgo = if (lastIr == 0L) {
            "never"
        } else {
            "${android.os.SystemClock.elapsedRealtime() - lastIr}ms"
        }
        return "sid=$sessionSeq playing=${playing.load()} pending=${pendingPresent.load()} " +
            "slot=${decodeSlotHeld.load()} vsync=${vsyncRegistered.load()} first=${firstPresentDone.load()} " +
            "empty=$emptyTicks delivered=${deliveredFrames.load()} pendingSeq=${pendingImageSeq.load()} " +
            "timelineFps=${targetFps.load()} capFps=${presentationFpsLimit.load()} " +
            "presentN=${successfulPresents.load()} capDropN=${rateLimitDrops.load()} " +
            "idle=${idleLoggedReason ?: "-"} irN=${imageAvailableN.load()} irOk=${imageAcquireOkN.load()} " +
            "irEmpty=${imageAcquireEmptyN.load()} irAgo=$irAgo " +
            "surf=${out?.let { "${it.width}x${it.height} valid=${it.surface.isValid}" } ?: "null"} " +
            "stopped=${stopped.load()} acquired=${acquired.load()}" +
            extraDiag().let { if (it.isEmpty()) "" else " $it" }
    }

    /** Playing session whose present clock has gone idle. / 仍在播、但呈现时钟已休眠的会话。 */
    internal fun isClockIdlePlaying(): Boolean =
        clockIdle.load() && playing.load() && !stopped.load() && acquired.load()

    private fun markClockIdle(reason: String) {
        clockIdle.store(true)
        idleLoggedReason = reason
    }

    private fun markClockActive() {
        clockIdle.store(false)
        idleLoggedReason = null
    }

    private fun cellPeriodNs(fps: Int): Long =
        VapPresentGrid.cellPeriodNanos(fps, VapDisplayRefresh.hz())

    private fun applyPendingWindow() {
        val handler = VapSharedVkRuntime.imageHandler() ?: return
        if (handler.looper.thread != Thread.currentThread()) {
            handler.post { applyPendingWindow() }
            return
        }
        if (!acquired.load() || nativeBusy.load() > 0) return
        synchronized(VapVulkanPipeline.tickLock) {
            val pending = pendingWindow.load() ?: return
            if (handle == 0L || !pending.surface.isValid) return
            val applied = appliedWindow.load()
            val opening = openingPixels.load().takeIf { !firstPresentDone.load() }
            val bind = applied == null || applied.surface !== pending.surface ||
                applied.width != pending.width || applied.height != pending.height
            val upload = opening != null && openingUploadedFor !== opening
            if (!bind && !upload) {
                presentOpeningIfNeededLocked()
                return
            }
            nativeBusy.addAndFetch(1)
            batchPrepared = false
            val h = handle
            VapSharedVkRuntime.launchMaintenance {
                val uploaded = !upload || runCatching {
                    VapVkNative.nativeSetOpeningPixels(h, opening.pixels, opening.width, opening.height)
                }.getOrDefault(false)
                val bound = uploaded && (!bind || runCatching {
                    VapVkNative.nativeSetOutputSurface(h, pending.surface, pending.width, pending.height)
                }.getOrDefault(false))
                handler.post {
                    if (!acquired.load()) return@post
                    synchronized(VapVulkanPipeline.tickLock) {
                        nativeBusy.addAndFetch(-1)
                        if (uploaded && upload) openingUploadedFor = opening
                        if (bound && bind && pendingWindow.load() === pending) {
                            appliedWindow.store(pending)
                            openingPresented.store(false)
                            surfaceEpoch.addAndFetch(1)
                        }
                        if (!bound) {
                            VapLog.wRateLimited("vk-bind-fail-$sessionSeq", 1_000L, "VapVk") { "surface maintenance failed" }
                        }
                        // A detach/resize may have arrived while maintenance was running.
                        if (pendingWindow.load() !== pending) applyPendingWindow()
                        else presentOpeningIfNeededLocked()
                        if (playing.load()) {
                            registerVsync()
                            VapVsyncClock.requestImmediateTick()
                        }
                    }
                }
            }
        }
    }

    /**
     * Present the packed frame-0 RGBA texture on the current swapchain before the first
     * codec AHB. This path never imports an AHB and never touches the YCbCr sampler cache,
     * so the first MediaCodec PRIVATE buffer still creates the correct conversion.
     *
     * 在首个 codec AHB 之前，把打包第 0 帧 RGBA 纹理 present 到当前交换链。该路径不导入
     * AHB，也不触碰 YCbCr sampler 缓存，因此第一个 MediaCodec PRIVATE 缓冲仍会创建正确的转换。
     */
    private fun presentOpeningIfNeededLocked() {
        if (!acquired.load() || nativeBusy.load() > 0 || openingPresented.load() || firstPresentDone.load()) return
        val h = handle
        if (h == 0L || appliedWindow.load() == null || openingPixels.load() == null) return
        val result = VapVkNative.nativePresentOpening(h)
        if (result == 1) {
            openingPresented.store(true)
        } else if (result == 3) {
            appliedWindow.store(null)
            applyPendingWindow()
        } else if (!openingRetryScheduled) {
            openingRetryScheduled = true
            VapSharedVkRuntime.imageHandler()?.postDelayed({
                openingRetryScheduled = false
                synchronized(VapVulkanPipeline.tickLock) { presentOpeningIfNeededLocked() }
            }, 8L)
        }
    }

    private fun discardPending(notify: Boolean) {
        // 必须与 vsync tick 互斥：onVsyncTick 在持有 tickLock 期间读取 pendingImage，
        // 随后访问 image.hardwareBuffer。discardPendingPresent() 由解码线程
        //（softRewind / recreateCodecAfterFailure）调用，若不加锁并发 close 同一张
        // Image，会在 native 层 SIGSEGV —— try/catch 抓不住，直接崩进程。
        // tick / release 调用本函数时已持有 tickLock，synchronized 可重入。
        synchronized(VapVulkanPipeline.tickLock) {
            if (batchPrepared) {
                batchPrepared = false
                nativeBusy.addAndFetch(1)
                val h = handle
                VapSharedVkRuntime.launchMaintenance {
                    if (h != 0L) VapVkNative.nativeCancelPrepared(h)
                    VapSharedVkRuntime.imageHandler()?.post {
                        if (acquired.load()) {
                            nativeBusy.addAndFetch(-1)
                            applyPendingWindow()
                            if (playing.load()) VapVsyncClock.wake()
                        }
                    }
                }
            }
            if (!pendingPresent.exchange(false)) {
                pendingImage.exchange(null)?.let { closeQuietly(it) }
                return
            }
            pendingImage.exchange(null)?.let { closeQuietly(it) }
            releaseDecodeSlot()
            if (notify) presentedTicks?.trySend(VapFrameAdvance.SurfacePresented)
        }
    }

    /**
     * [Image.timestamp] / [Image.close] throw if the producer already detached
     * (pause `MediaCodec.stop`) or the ImageReader recycled the buffer.
     * [Image.timestamp] / [Image.close] 在生产者已断开（暂停时 `MediaCodec.stop`）
     * 或 ImageReader 回收缓冲后会抛异常。
     */
    private fun imageTimestampUs(image: Image): Long {
        return try {
            val tsNs = image.timestamp
            if (tsNs > 0L) tsNs / 1_000L else -1L
        } catch (_: IllegalStateException) {
            -1L
        }
    }

    private fun closeQuietly(image: Image) {
        try {
            image.close()
        } catch (_: IllegalStateException) {
        }
    }

    /**
     * Pump / inline path just took [AndroidVapFrameDecoder.compositorSlot]. Mirror that onto
     * [decodeSlotHeld] so a failed ImageReader acquire can return the permit.
     *
     * 泵 / 内联路径刚拿到 [AndroidVapFrameDecoder.compositorSlot]。同步到 [decodeSlotHeld]，
     * 这样 ImageReader acquire 失败时能把许可还回去。
     */
    fun noteCompositorSlotAcquired() {
        decodeSlotHeld.store(true)
    }

    fun releaseCompositorSlotForFailedOutput() {
        releaseDecodeSlot()
    }

    private fun releaseDecodeSlot() {
        if (decodeSlotHeld.compareAndSet(expectedValue = true, newValue = false)) {
            onFrameComposited()
        }
    }

    private fun registerVsync() {
        if (!playing.load()) {
            return
        }
        if (vsyncRegistered.compareAndSet(false, true)) {
            if (!VapVsyncClock.registerVulkan(this)) {
                vsyncRegistered.store(false)
                VapLog.w("VapLoop") { "sid=$sessionSeq register-vsync failed no-vk-handler pending=${pendingPresent.load()}" }
            }
            return
        }
        // Already listed: do not force an immediate tick. ImageReader shares this
        // handler; arm(0) would cancel the fps-grid wait and spin the shared clock
        // at decode rate, starving sibling sessions (Home while Touch is playing).
        // 已在列表里：不要强行立刻打拍。ImageReader 和脉冲共用这条 handler；
        // arm(0) 会拆掉 fps 格子等待，共享时钟跟着解码速率空转，饿死其它会话
        //（Touch 在播时 Home 被冻住）。
        VapVsyncClock.wake()
    }

    private fun unregisterVsync() {
        if (!vsyncRegistered.compareAndSet(expectedValue = true, newValue = false)) return
        VapVsyncClock.unregisterVulkan(this)
    }
}
