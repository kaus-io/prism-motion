package com.zxhhyj.vap.decode

import android.media.MediaCodec
import com.zxhhyj.vap.log.VapLog
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext


internal data class OutputHandleResult(
    val renderedInLoop: Int,
    val endLoop: Boolean,
    /** Gate closed before release — caller must keep [packet] and not decrement queuedOutputs.
     *  释放前播放门已关 —— 调用方必须保住 [packet]，且不能递减 queuedOutputs。 */
    val parked: Boolean = false,
)

/**
 * Output router on the codec callback thread: inline fast path first; otherwise queue
 * to the pump. The queued-count gate keeps frame order (see [AndroidVapFrameDecoder.queuedOutputs]).
 * [async] is the owning pipeline's queue, captured at attach time.
 * 在 codec 回调线程上的 output 路由器：先尝试内联快速路径，否则入队给泵。
 * 排队计数门保证帧顺序（参见 [AndroidVapFrameDecoder.queuedOutputs]）。[async] 是
 * 当前管线自有的队列，在 attach 时捕获。
 */
internal fun AndroidVapFrameDecoder.routeOutputBuffer(
    async: VapMediaCodecAsync,
    codec: MediaCodec,
    index: Int,
    offset: Int,
    size: Int,
    presentationTimeUs: Long,
    flags: Int,
) {
    if (tryHandleOutputInline(codec, index, size, presentationTimeUs, flags)) return
    queuedOutputs.addAndFetch(1)
    // Roll back the count if the queue rejected (closed during teardown): otherwise
    // a stale "queued > 0" would block the inline fast path on the next session.
    // 入队失败（teardown 中已关闭）时回滚计数：否则陈旧的「queued > 0」会在下一会话
    // 阻塞内联快速路径。
    // Build the packet only on the fallback path so the inline fast path stays allocation-free.
    // 仅在回退路径构造 packet，使内联快速路径保持无分配。
    val packet = VapMediaCodecAsync.OutputBuffer(index, offset, size, presentationTimeUs, flags)
    if (!async.enqueueOutput(packet)) {
        queuedOutputs.addAndFetch(-1)
    }
}

/**
 * [MediaCodec.releaseOutputBuffer] throws [IllegalStateException] when the codec is
 * already flushed / stopped / released. Pause/resume on the settings pager does that
 * while `vap-codec-cb` still has in-flight output callbacks — must never escape.
 * [MediaCodec.releaseOutputBuffer] 在 codec 已 flush / stop / release 时抛
 * [IllegalStateException]。设置页频繁暂停恢复时，`vap-codec-cb` 上仍有在飞的
 * output 回调，异常绝不能漏出去（会直接杀死进程）。
 */
internal fun AndroidVapFrameDecoder.safeReleaseOutputBuffer(
    codec: MediaCodec,
    index: Int,
    render: Boolean,
): Boolean {
    return try {
        codec.releaseOutputBuffer(index, render)
        true
    } catch (e: IllegalStateException) {
        if (!stopped.load() && !releaseCodecOnly.load()) {
            VapLog.w("VapDecode", e) { "releaseOutputBuffer(render=$render) failed" }
        }
        false
    }
}

internal fun AndroidVapFrameDecoder.tryHandleOutputInline(
    codec: MediaCodec,
    index: Int,
    size: Int,
    presentationTimeUs: Long,
    flags: Int,
): Boolean {
    if (stopped.load() || releaseCodecOnly.load()) return false
    if (decoderConfig.outputMode != VapOutputMode.WindowSurface) return false
    // EOS / empty buffers carry endLoop bookkeeping — pump handles them.
    // EOS / 空 buffer 携带 endLoop 簿记，由泵处理。
    if (flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || size <= 0) return false
    if (!playbackGate.isActive()) return false
    // An earlier output is queued or being handled by the pump — do not overtake it.
    // 更早的 output 已被入队或正在被泵处理 —— 不可抢先。
    if (queuedOutputs.load() != 0) return false
    if (shouldDropUntilResume(presentationTimeUs)) {
        safeReleaseOutputBuffer(codec, index, render = false)
        return true
    }
    if (!compositorSlot.tryAcquire()) return false
    vkSession?.noteCompositorSlotAcquired()
    if (safeReleaseOutputBuffer(codec, index, render = true)) {
        noteReleasedPts(presentationTimeUs)
    } else {
        // Stale index during flush/teardown: the codec reclaims the buffer itself.
        // flush / teardown 期间的陈旧索引：codec 会自行回收该 buffer。
        vkSession?.let { it.releaseCompositorSlotForFailedOutput() } ?: releasePresentSlot()
    }
    return true
}

internal suspend fun AndroidVapFrameDecoder.handleOutputBuffer(
    codec: MediaCodec,
    packet: VapMediaCodecAsync.OutputBuffer,
    renderedInLoop: Int,
    totalFrames: Int,
): OutputHandleResult {
    val eos = packet.isEos
    val render = packet.size > 0 && !eos
    var rendered = renderedInLoop
    if (render) {
        if (shouldDropUntilResume(packet.presentationTimeUs)) {
            safeReleaseOutputBuffer(codec, packet.index, render = false)
            return OutputHandleResult(rendered, endLoop = eos)
        }
        if (!playbackGate.isActive()) {
            return OutputHandleResult(rendered, endLoop = false, parked = true)
        }
        var submittedToGl = false
        if (!acquirePresentSlotOrPark()) {
            return OutputHandleResult(rendered, endLoop = false, parked = true)
        }
        try {
            if (stopped.load() || !coroutineContext.isActive) {
                safeReleaseOutputBuffer(codec, packet.index, render = false)
                return OutputHandleResult(rendered, endLoop = true)
            }
            if (!playbackGate.isActive()) {
                return OutputHandleResult(rendered, endLoop = false, parked = true)
            }
            // WindowSurface: the fps grid + vsync pace present; no preRender sleep.
            // WindowSurface：fps 格子 + vsync 控制 present，不做 preRender 睡眠。
            if (safeReleaseOutputBuffer(codec, packet.index, render = true)) {
                noteReleasedPts(packet.presentationTimeUs)
                submittedToGl = true
                rendered++
            }
        } finally {
            if (!submittedToGl) {
                vkSession?.releaseCompositorSlotForFailedOutput() ?: releasePresentSlot()
            }
        }
    } else {
        safeReleaseOutputBuffer(codec, packet.index, render = false)
    }

    return OutputHandleResult(rendered, endLoop = eos || rendered >= totalFrames)
}

/**
 * Wait on the compositor permit itself ([VapCompositorSlot]); pause aborts via
 * [VapCompositorSlot.requestPark] so [select] cannot lose a side-channel wake.
 * 等的就是合成许可本身（[VapCompositorSlot]）；暂停走 [VapCompositorSlot.requestPark]，
 * [select] 不会丢掉另一根叫醒信号。
 */
internal suspend fun AndroidVapFrameDecoder.acquirePresentSlotOrPark(): Boolean {
    val ctx = coroutineContext
    if (!compositorSlot.available) {
        VapLog.wRateLimited("pump-slot-wait-$sessionSeq", 5_000L, "VapLoop") {
            "sid=$sessionSeq pump-slot-wait gate=${playbackGate.isActive()} " +
                "queued=${queuedOutputs.load()} slotFree=${compositorSlot.available} " +
                (vkSession?.idleDiag() ?: "vk=null")
        }
    }
    val got = compositorSlot.acquireOrPark {
        playbackGate.isActive() && !stopped.load() && ctx.isActive
    }
    if (got) vkSession?.noteCompositorSlotAcquired()
    return got
}
