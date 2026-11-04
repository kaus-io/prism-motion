package com.zxhhyj.vap.decode

import android.media.MediaCodec
import android.media.MediaExtractor
import com.zxhhyj.vap.log.VapLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

/**
 * Outputs-only decode pump running on the decoder `CoroutineScope`.
 * Only consumes packets already queued by the callback thread; input feeding is
 * done inline on `vap-codec-cb` so the pump never starves.
 * 运行在解码器 `CoroutineScope` 上的仅消费 output 的解码泵。
 * 仅消费已由回调线程入队的 packet；input 喂入在 `vap-codec-cb` 上内联完成，
 * 因此泵永远不会饥饿。
 */
internal suspend fun AndroidVapFrameDecoder.pumpCodecAsync(
    extractor: MediaExtractor,
    codec: MediaCodec,
    async: VapMediaCodecAsync,
    totalFrames: Int,
) {
    var renderedInLoop = 0
    var heldPacket: VapMediaCodecAsync.OutputBuffer? = null
    try {
        while (coroutineContext.isActive && !stopped.load()) {
            if (!playbackGate.isActive()) {
                playbackGate.awaitActive { stopped.load() }
                if (stopped.load() || !coroutineContext.isActive) break
                // Reset pacing anchors after pause: the vk present clock realigns to
                // the next release.
                // 暂停后重置节拍锚点：vk 呈现时钟对齐到下一次 release。
                vkSession?.resetPresentClock()
                flushDeferredInputs(extractor)
                continue
            }

            // Outputs-only pump: inputs are fed inline on the codec callback thread.
            // 仅消费的泵：input 喂入在 codec 回调线程上内联完成。
            // Pause while waiting for the first output (Think parked at open)
            // used to sit in receive() forever. Resume only woke awaitActive.
            // 开局就停住的 Think 会永远卡在 receive()；恢复只唤醒 awaitActive。
            val nextPacket = heldPacket ?: playbackGate.awaitValueOrInactive({ stopped.load() }) {
                async.awaitOutputBuffer()
            }
            if (nextPacket == null) {
                continue
            }
            var packet: VapMediaCodecAsync.OutputBuffer = nextPacket
            heldPacket = null
            if (!playbackGate.isActive()) {
                heldPacket = packet
                continue
            }
            while (true) {
                val result: OutputHandleResult
                var parked = false
                try {
                    result = handleOutputBuffer(codec, packet, renderedInLoop, totalFrames)
                    parked = result.parked
                    if (parked) heldPacket = packet
                } finally {
                    // Park keeps the codec buffer; leave queuedOutputs high so the
                    // inline path cannot overtake this earlier frame.
                    // 停住时 codec 缓冲仍在手里；queuedOutputs 保持 > 0，避免内联路径抢先。
                    if (!parked) queuedOutputs.addAndFetch(-1)
                }
                if (parked) break
                renderedInLoop = result.renderedInLoop
                if (result.endLoop) {
                    if (loop && !stopped.load() && coroutineContext.isActive) {
                        softRewind(extractor, codec, async)
                        renderedInLoop = 0
                        heldPacket = null
                        break
                    }
                    // Last codec buffer is in the compositor; wait for its present so
                    // the final tick is sent before we close the present channel.
                    // 最后一块 codec 缓冲已在合成器里；等它 present，关闭通道前把最后
                    // 一个 tick 发出去。
                    if (acquirePresentSlotOrPark()) {
                        releasePresentSlot()
                    }
                    return
                }
                // Burst drain: process already-queued outputs without another dispatch.
                // 批量排空：处理已入队的 output，无需再次分派。
                packet = async.pollOutputBuffer() ?: break
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: IllegalStateException) {
        // MediaCodec.CodecException / async onError during teardown or surface loss.
        // teardown 或 surface 丢失期间的 MediaCodec.CodecException / async onError。
        if (!stopped.load()) {
            VapLog.w("VapDecode", e) {
                "sid=$sessionSeq MediaCodec pump stopped ${codecAsync?.codecDiag() ?: "codec=null"}"
            }
        }
        throw e
    }
}

internal fun AndroidVapFrameDecoder.softRewind(
    extractor: MediaExtractor,
    codec: MediaCodec,
    async: VapMediaCodecAsync,
) {
    synchronized(inputLock) {
        // Invalidate any input callback that is currently blocked on this lock; those
        // buffer indices were just flushed and would otherwise crash in getInputBuffer().
        // 使任何正阻塞在该锁上的 input 回调失效；这些 buffer index 刚被 flush，
        // 继续使用会在 getInputBuffer() 处崩溃。
        inputGeneration.addAndFetch(1)
        clearDeferredInputsLocked()
        extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        // Async mode: flush invalidates outstanding buffer indices; must start() again.
        // 异步模式：flush 会令已发出的 buffer 索引失效，必须再次 start()。
        async.clearPending()
        codec.flush()
        async.clearPending()
        // Stale queued events were dropped without pump handling; re-zero the gate so
        // the inline fast path stays enabled. Fresh callbacks only come after start().
        // 没有经过泵处理的陈旧入队事件已被丢弃；将计数门归零，使内联快速路径保持启用。
        // 新的回调只有在 start() 之后才会到来。
        queuedOutputs.store(0)
        async.start(codec)
        inputDone.store(false)
    }
    vkSession?.discardPendingPresent()
    vkSession?.resetPresentClock()
    drainFrameChannel()
    drainPresentedTicks()
}
