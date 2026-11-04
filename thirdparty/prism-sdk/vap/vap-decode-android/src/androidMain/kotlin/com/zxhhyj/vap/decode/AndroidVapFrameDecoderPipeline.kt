package com.zxhhyj.vap.decode

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import com.zxhhyj.vap.log.VapLog
import com.zxhhyj.vap.player.VapConfig
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/** Caller must hold [AndroidVapFrameDecoder.sessionMutex]. / 调用方必须持有 [AndroidVapFrameDecoder.sessionMutex]。 */
internal suspend fun AndroidVapFrameDecoder.startPresentPipelineLocked(cfg: VapConfig) {
    stopPipelineLocked()
    stopped.store(false)
    inputDone.store(false)
    queuedOutputs.store(0)
    codecStarted = false
    compositorSlot.reset()
    frameChannel = kotlinx.coroutines.channels.Channel(capacity = 2)
    presentedTicks = kotlinx.coroutines.channels.Channel(capacity = 2)

    val resolvedGpu = VapGpu.resolve(decoderConfig.gpuBackend)
    decoderConfig = decoderConfig.copy(gpuBackend = resolvedGpu)

    // Surface always goes through Vulkan so `rgbFrame` is cropped (16-align pad stays off-screen).
    // Opaque clips force output alpha=1 in the compositor; they do not Direct-blit the packed canvas.
    // Surface 一律走 Vulkan，按 `rgbFrame` 裁掉 16 对齐补边。不透明片段在合成器里写死 alpha=1，
    // 不再把打包画布 Direct 直出到用户 Surface。
    if (resolvedGpu == VapGpuBackend.Vulkan) {
        val session = VapVulkanSession(
            sessionSeq = sessionSeq,
            config = cfg,
            presentedTicks = presentedTicks.takeIf { decoderConfig.outputMode == VapOutputMode.WindowSurface },
            stopped = stopped,
            onFrameComposited = { releasePresentSlot() },
            extraDiag = { codecDiag() },
        )
        check(session.start()) { "Failed to start Vulkan 1.1 pipeline" }
        session.setTargetFrameRate(decoderConfig.targetFrameRate)
        session.setPlaying(playbackGate.isActive())
        vkSession = session
        applyPendingWindowOutputVk(session)
    } else {
        error("Unsupported GPU backend $resolvedGpu")
    }
}

/** Caller must hold [AndroidVapFrameDecoder.sessionMutex]. Present path must already be up. /
 * 调用方必须持有 [AndroidVapFrameDecoder.sessionMutex]。呈现路径必须已经打开。 */
internal fun AndroidVapFrameDecoder.startCodecPipelineLocked(
    bindExtractor: (MediaExtractor) -> Unit,
    cfg: VapConfig,
) {
    val session = vkSession ?: error("startPresentPipelineLocked first")
    session.resetImportPool()
    val surface = session.codecSurface ?: error("Missing Vulkan codec ImageReader surface")
    check(surface.isValid) { "Codec surface released before MediaCodec.configure" }

    // MediaExtractor / MediaCodec have no finalizers: any failure between creation and
    // ownership handoff must release them explicitly, or the native peers leak for the
    // rest of the process lifetime (head units retry failed opens in a loop).
    // MediaExtractor / MediaCodec 没有 finalize：从创建到所有权移交之间的任何失败都
    // 必须显式释放，否则 native 对象会在进程剩余生命周期内泄漏（车机会循环重试打不开的源）。
    val ext = MediaExtractor()
    val async = VapMediaCodecAsync()
    val decoder = try {
        bindExtractor(ext)
        val track = selectVideoTrack(ext)
        require(track >= 0) { "No video track" }
        ext.selectTrack(track)
        if (resumeSampleTimeUs > 0L) {
            ext.seekTo(resumeSampleTimeUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            VapLog.i("VapLoop") { "sid=$sessionSeq codec-seek us=$resumeSampleTimeUs" }
        }
        resumeCatchUpUntilUs = if (resumeSampleTimeUs >= 0L) resumeSampleTimeUs else -1L
        val format = ext.getTrackFormat(track)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: error("missing mime")

        val created = MediaCodec.createDecoderByType(mime)
        try {
            // Input feeding runs inline on the shared codec-callback thread (no pump dispatch);
            // outputs try an inline fast path first and only fall back to the pump queue.
            // Capture `async` directly: a stale codec callback must never enqueue into a
            // successor pipeline's queue during open->close->open races.
            // Input 喂入在共享 codec 回调线程上内联运行（不经泵分派）；output 先尝试内联
            // 快速路径，只有失败才回退到泵队列。直接捕获 `async`：在 open->close->open 竞争中，
            // 陈旧的 codec 回调绝不能入队到后继管线的队列里。
            async.logSid = sessionSeq
            async.inputFeeder = { c, index -> feedInputInline(ext, c, index) }
            async.outputHandler = { c, index, offset, size, pts, flags ->
                routeOutputBuffer(async, c, index, offset, size, pts, flags)
            }
            // Async callback must be set before configure.
            // 异步回调必须在 configure 之前设置。
            async.attach(created)
            inputGeneration.addAndFetch(1)
            applyLowLatency(format)
            created.configure(format, surface, null, 0)
            async.start(created)
            VapLog.i("VapLoop") { "sid=$sessionSeq codec-up name=${created.name} mime=$mime" }
            created
        } catch (t: Throwable) {
            runCatching { created.release() }
            async.close()
            throw t
        }
    } catch (t: Throwable) {
        ext.release()
        throw t
    }

    codecAsync = async
    codec = decoder
    extractor = ext
    codecStarted = true
    inputDone.store(false)
    queuedOutputs.store(0)

    decodeJob = scope.launch {
        runDecodePumpUntilStopped()
    }
}

/** Caller must hold [AndroidVapFrameDecoder.sessionMutex]. Keeps Vulkan / last frame. /
 * 调用方必须持有 [AndroidVapFrameDecoder.sessionMutex]。保留 Vulkan 与最后一帧。 */
internal suspend fun AndroidVapFrameDecoder.stopCodecPipelineLocked() {
    releaseCodecOnly.store(true)
    try {
        decodeJob?.cancelAndJoin()
        decodeJob = null
        codecAsync?.close()
        codecAsync = null
        synchronized(inputLock) {
            clearDeferredInputsLocked()
            try {
                codec?.stop()
                codec?.release()
            } catch (_: IllegalStateException) {
            }
            codec = null
            extractor?.release()
            extractor = null
        }
        codecStarted = false
    } finally {
        releaseCodecOnly.store(false)
    }
}

/** Caller must hold [AndroidVapFrameDecoder.sessionMutex]. / 调用方必须持有 [AndroidVapFrameDecoder.sessionMutex]。 */
internal suspend fun AndroidVapFrameDecoder.startPipelineLocked(
    bindExtractor: (MediaExtractor) -> Unit,
    cfg: VapConfig,
) {
    startPresentPipelineLocked(cfg)
    startCodecPipelineLocked(bindExtractor, cfg)
}

/** Caller must hold [AndroidVapFrameDecoder.sessionMutex]. / 调用方必须持有 [AndroidVapFrameDecoder.sessionMutex]。 */
internal suspend fun AndroidVapFrameDecoder.stopPipelineLocked() {
    stopped.store(true)
    // Drop ST listener before joining the pump so in-flight frames cannot updateTexImage
    // after MediaCodec disconnects (BufferQueue "no connected producer").
    // 在 join 泵之前先取消 SurfaceTexture 监听器，避免 MediaCodec 断开后还有
    // 在飞行的帧去 updateTexImage（BufferQueue "no connected producer"）。
    decodeJob?.cancelAndJoin()
    decodeJob = null
    codecAsync?.close()
    codecAsync = null
    // The inline input feeder runs on the shared vap-codec-cb thread under inputLock.
    // A callback that already passed the `stopped` check can be inside readSampleData()
    // right now; releasing the extractor here would free the native peer mid-call and
    // SIGSEGV in NuMediaExtractor. Take inputLock so teardown waits for any in-flight
    // feed; feeds blocked afterwards bail out on the `stopped` re-check.
    // 内联 input 喂入在共享 vap-codec-cb 线程上、持 inputLock 运行。已通过 `stopped`
    // 检查的回调此刻可能正在 readSampleData() 里；此处直接释放 extractor 会在调用
    // 途中释放 native 对象，在 NuMediaExtractor 里 SIGSEGV。拿住 inputLock 让拆除
    // 等待在飞的喂入完成；之后被阻塞的喂入会在 `stopped` 复查时退出。
    synchronized(inputLock) {
        clearDeferredInputsLocked()
        try {
            codec?.stop()
            codec?.release()
        } catch (_: IllegalStateException) {
        }
        codec = null
        extractor?.release()
        extractor = null
    }
    codecStarted = false

    drainFrameChannel()
    frameChannel.close()
    drainFrameChannel()
    drainPresentedTicks()
    presentedTicks.close()
    drainPresentedTicks()

    val vk = vkSession
    vkSession = null
    vk?.release()
}

/**
 * Decode pump must survive Surface detach / MediaCodec ISE. Closing [presentedTicks]
 * makes the present loop treat the session as EOS and release it, after which
 * AnimatedContent can never revive the ball (AvatarBall Think/Speech stay at 0).
 *
 * 解码泵必须扛住 Surface 拆除 / MediaCodec ISE。关掉 [presentedTicks] 会让
 * present 循环把会话当成播完并拆掉，之后 AnimatedContent 再也救不活这个球
 * （AvatarBall 的 Think/Speech 会一直停在 0）。
 */
internal suspend fun AndroidVapFrameDecoder.runDecodePumpUntilStopped() {
    try {
        while (coroutineContext.isActive && !stopped.load()) {
            val ext = extractor
            val decoder = codec
            val async = codecAsync
            val cfg = config
            if (ext == null || decoder == null || async == null || cfg == null) break
            try {
                pumpCodecAsync(ext, decoder, async, cfg.totalFrames)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                VapLog.w("VapDecode", e) { "sid=$sessionSeq decode pump terminated" }
            }
            if (stopped.load() || !coroutineContext.isActive || !loop) break
            VapLog.i("VapLoop") { "sid=$sessionSeq pump-retry after unexpected end" }
            try {
                recreateCodecAfterFailure()
            } catch (e: Throwable) {
                VapLog.w("VapDecode", e) { "sid=$sessionSeq pump-retry rewind failed" }
                break
            }
        }
    } finally {
        if (!releaseCodecOnly.load()) {
            signalStreamEnded()
        }
    }
}

/** Recreate MediaCodec after ISE without closing present ticks. / 出错后重建 codec，不关 present 通道。 */
internal fun AndroidVapFrameDecoder.recreateCodecAfterFailure() {
    val bind = pendingBindExtractor ?: error("no bind extractor")
    val session = vkSession ?: error("no vulkan session")
    session.resetImportPool()
    val surface = session.codecSurface ?: error("missing codec surface")
    check(surface.isValid) { "codec surface invalid" }

    synchronized(inputLock) {
        inputGeneration.addAndFetch(1)
        clearDeferredInputsLocked()
        try {
            codec?.stop()
            codec?.release()
        } catch (_: IllegalStateException) {
        }
        codec = null
        codecAsync?.close()
        codecAsync = null
        extractor?.release()
        extractor = null

        val ext = MediaExtractor()
        val async = VapMediaCodecAsync()
        val decoder = try {
            bind(ext)
            val track = selectVideoTrack(ext)
            require(track >= 0) { "No video track" }
            ext.selectTrack(track)
            val format = ext.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: error("missing mime")
            val created = MediaCodec.createDecoderByType(mime)
            try {
                async.logSid = sessionSeq
                async.inputFeeder = { c, index -> feedInputInline(ext, c, index) }
                async.outputHandler = { c, index, offset, size, pts, flags ->
                routeOutputBuffer(async, c, index, offset, size, pts, flags)
            }
                async.attach(created)
                inputGeneration.addAndFetch(1)
                applyLowLatency(format)
                created.configure(format, surface, null, 0)
                async.start(created)
                VapLog.i("VapLoop") { "sid=$sessionSeq codec-recreate name=${created.name} mime=$mime" }
                created
            } catch (t: Throwable) {
                runCatching { created.release() }
                async.close()
                throw t
            }
        } catch (t: Throwable) {
            ext.release()
            throw t
        }
        codecAsync = async
        codec = decoder
        extractor = ext
        codecStarted = true
        inputDone.store(false)
        queuedOutputs.store(0)
    }
    vkSession?.discardPendingPresent()
    vkSession?.resetPresentClock()
    drainFrameChannel()
    drainPresentedTicks()
}

internal fun AndroidVapFrameDecoder.applyPendingWindowOutputVk(session: VapVulkanSession) {
    if (decoderConfig.outputMode != VapOutputMode.WindowSurface) return
    pendingOpeningFrame.load()?.let { opening ->
        session.setOpeningFrame(opening.pixels, opening.width, opening.height)
    }
    pendingWindowSurface.load()?.let { pending ->
        session.attachOutputSurface(pending.surface, pending.width, pending.height)
    }
}

/** Try to reduce decode-to-output latency. API 30+; harmless if unsupported. / 尝试降低解码到输出的延迟，API 30+；不支持时无害。 */
private fun applyLowLatency(format: MediaFormat) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
    }
}
