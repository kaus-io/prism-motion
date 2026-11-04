package com.zxhhyj.vap.decode

import android.media.MediaCodec
import android.media.MediaExtractor
import com.zxhhyj.vap.log.VapLog

/** Inline input feed on the codec callback thread; serialized against softRewind. / 在 codec 回调线程上内联执行的 input 喂入；与 softRewind 串行化。 */
internal fun AndroidVapFrameDecoder.feedInputInline(extractor: MediaExtractor, codec: MediaCodec, index: Int) {
    if (stopped.load()) return
    val gen = inputGeneration.load()
    synchronized(inputLock) {
        // generation check: a softRewind that just completed bumped [inputGeneration], so
        // the captured index is invalidated and any queueInputBuffer() would crash with BAD_INDEX.
        // generation 检查：刚完成的 softRewind 已递增 [inputGeneration]，捕获到的索引失效，
        // 继续 queueInputBuffer() 会以 BAD_INDEX 崩溃。
        if (stopped.load() || inputGeneration.load() != gen) return
        if (!playbackGate.isActive()) {
            deferredInputCodec = codec
            deferredInputGeneration = gen
            deferredInputIndices.addLast(index)
            VapLog.wRateLimited("feed-defer-$sessionSeq", 5_000L, "VapLoop") {
                "sid=$sessionSeq feed-defer n=${deferredInputIndices.size} ${codecAsync?.codecDiag() ?: ""}"
            }
            return
        }
        feedInputBuffer(extractor, codec, index)
    }
}

/** Caller must hold [AndroidVapFrameDecoder.inputLock]. / 调用方必须持有 [AndroidVapFrameDecoder.inputLock]。 */
internal fun AndroidVapFrameDecoder.clearDeferredInputsLocked() {
    deferredInputIndices.clear()
    deferredInputCodec = null
    deferredInputGeneration = 0
}

/**
 * Feed input indices that arrived while paused. Must run on the decode pump (not Main):
 * [feedInputBuffer] reads the extractor. New [onInputBufferAvailable] callbacks cannot
 * overtake these slots — the codec still considers them outstanding.
 *
 * 把暂停期间到达的 input 索引喂进去。必须跑在解码泵上（不能在 Main）：
 * [feedInputBuffer] 会读 extractor。新的 [onInputBufferAvailable] 抢不走这些槽位 ——
 * codec 仍认为它们在使用中。
 */
internal fun AndroidVapFrameDecoder.flushDeferredInputs(extractor: MediaExtractor) {
    synchronized(inputLock) {
        val codec = deferredInputCodec ?: run {
            clearDeferredInputsLocked()
            return
        }
        if (inputGeneration.load() != deferredInputGeneration) {
            clearDeferredInputsLocked()
            return
        }
        while (deferredInputIndices.isNotEmpty()) {
            if (stopped.load() || !playbackGate.isActive()) break
            feedInputBuffer(extractor, codec, deferredInputIndices.removeFirst())
        }
        if (deferredInputIndices.isEmpty()) {
            deferredInputCodec = null
            deferredInputGeneration = 0
        }
    }
}

internal fun AndroidVapFrameDecoder.feedInputBuffer(extractor: MediaExtractor, codec: MediaCodec, index: Int) {
    if (stopped.load()) return
    try {
        val buffer = codec.getInputBuffer(index) ?: run {
            // getInputBuffer() can return null transiently on some devices during flush;
            // queue an empty buffer to release the slot back to the codec.
            // getInputBuffer() 在某些设备上 flush 期间可能短暂返回 null；提交一个空缓冲以归还槽位。
            codec.queueInputBuffer(index, 0, 0, 0L, 0)
            return
        }
        val sample = extractor.readSampleData(buffer, 0)
        if (sample < 0) {
            codec.queueInputBuffer(
                index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
            )
            inputDone.store(true)
        } else {
            codec.queueInputBuffer(index, 0, sample, extractor.sampleTime, 0)
            extractor.advance()
        }
    } catch (e: Exception) {
        // Surface disconnected / codec flushed while a buffer index was already selected,
        // a MediaCodec.CodecException on a stale index, or readSampleData rejecting an
        // oversized sample (native -ENOMEM surfaces as IllegalArgumentException).
        // Swallow to keep the callback thread alive.
        // 在已选中 buffer index 后 surface 断开 / codec flush、陈旧 index 上的
        // MediaCodec.CodecException，或 readSampleData 拒绝超大样本（底层 -ENOMEM
        // 以 IllegalArgumentException 抛出）。吞掉以保持回调线程存活。
        if (!stopped.load()) {
            VapLog.w("VapDecode", e) { "sid=$sessionSeq feedInputBuffer failed" }
        }
        inputDone.store(true)
    }
}
