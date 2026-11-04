package com.zxhhyj.vap.decode

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.SystemClock
import com.zxhhyj.vap.log.VapLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * MediaCodec asynchronous mode bridged to inline handlers + a suspending output queue.
 * 将 MediaCodec 异步模式桥接到内联 handler 与挂起输出队列。
 *
 * Input feeding has no backpressure constraint, so [inputFeeder] runs **inline on the
 * shared `vap-codec-cb` callback thread** — no Channel handoff, no Dispatchers.IO wakeup.
 * Input 侧无背压约束，因此 [inputFeeder] **直接在共享的 `vap-codec-cb` 回调线程上内联运行**，
 * 没有 Channel 转发、没有 Dispatchers.IO 唤醒。
 *
 * Output buffers first try [outputHandler] (an inline fast path that may release the
 * buffer immediately when the compositor slot is free); unhandled packets are queued to
 * [outputs] for the decode pump, preserving arrival order.
 * Output 缓冲先尝试 [outputHandler]（合成槽位空闲时可立即释放缓冲的内联快速路径）；
 * 未被处理的包则按到达顺序入队到 [outputs] 供解码泵消费。
 *
 * Call [attach] before [MediaCodec.configure], then [start] after configure.
 * After [MediaCodec.flush], call [clearPending] then [start] again (async flush rule).
 * 在 [MediaCodec.configure] 之前调用 [attach]，之后调用 [start]；调用 [MediaCodec.flush]
 * 之后必须先 [clearPending] 再 [start]（异步模式的 flush 规则）。
 *
 * Callbacks run on [VapSharedCodecCallbackRuntime] so N codecs do not spawn N
 * dedicated MediaCodec callback threads.
 * 回调在 [VapSharedCodecCallbackRuntime] 上运行，避免 N 个 codec 各生成 N 条专用回调线程。
 */
@OptIn(ExperimentalAtomicApi::class)
internal class VapMediaCodecAsync {
    data class OutputBuffer(
        val index: Int,
        val offset: Int,
        val size: Int,
        val presentationTimeUs: Long,
        val flags: Int,
    ) {
        val isEos: Boolean
            get() = flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0

        companion object {
            fun from(index: Int, info: MediaCodec.BufferInfo): OutputBuffer =
                OutputBuffer(
                    index = index,
                    offset = info.offset,
                    size = info.size,
                    presentationTimeUs = info.presentationTimeUs,
                    flags = info.flags,
                )
        }
    }

    private val outputs = Channel<OutputBuffer>(capacity = Channel.UNLIMITED)
    private val sharedCallbackHeld = AtomicBoolean(false)
    private val inputCbN = AtomicInt(0)
    private val outputCbN = AtomicInt(0)
    private val lastInputElapsed = AtomicLong(0L)
    private val lastOutputElapsed = AtomicLong(0L)

    /** Decoder session id for logs only. / 仅用于日志的解码会话号。 */
    var logSid: Int = 0

    fun codecDiag(): String {
        val now = SystemClock.elapsedRealtime()
        val lastIn = lastInputElapsed.load()
        val lastOut = lastOutputElapsed.load()
        val inAgo = if (lastIn == 0L) "never" else "${now - lastIn}ms"
        val outAgo = if (lastOut == 0L) "never" else "${now - lastOut}ms"
        return "inN=${inputCbN.load()} outN=${outputCbN.load()} inAgo=$inAgo outAgo=$outAgo"
    }

    /**
     * Inline input feed on the callback thread. Must be non-blocking and serialized
     * against extractor seek/flush by the owner ([VapFrameDecoder] holds an input lock).
     * 在回调线程上内联执行 input 喂入。必须是非阻塞的，且与 extractor seek/flush 由
     * 拥有者（[VapFrameDecoder] 持有一把 input 锁）串行化。
     */
    var inputFeeder: ((MediaCodec, Int) -> Unit)? = null

    /**
     * Output router on the callback thread, called with the raw buffer fields
     * (`codec, index, offset, size, presentationTimeUs, flags`). Implementations may release
     * the buffer inline (fast path) or fall back to [enqueueOutput]; ordering rules are owned
     * by [VapFrameDecoder] (queued-count gate). Passing primitives keeps the fast path free of
     * the per-frame [OutputBuffer] allocation; the object is built only when enqueuing.
     * 在回调线程上的 output 路由器，以原始缓冲字段调用
     *（`codec, index, offset, size, presentationTimeUs, flags`）。实现可选择内联释放缓冲
     *（快速路径）或回退到 [enqueueOutput]；顺序规则由 [VapFrameDecoder] 通过排队计数门控制。
     * 传原始值让快速路径免去每帧的 [OutputBuffer] 分配；仅在入队时才构造对象。
     */
    var outputHandler: ((MediaCodec, Int, Int, Int, Long, Int) -> Unit)? = null

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            inputCbN.addAndFetch(1)
            lastInputElapsed.store(SystemClock.elapsedRealtime())
            val feeder = inputFeeder
            if (feeder == null) {
                VapLog.wRateLimited("codec-in-null-$logSid", 5_000L, "VapLoop") {
                    "sid=$logSid codec-input-no-feeder index=$index ${codecDiag()}"
                }
                return
            }
            feeder.invoke(codec, index)
        }

        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo,
        ) {
            outputCbN.addAndFetch(1)
            lastOutputElapsed.store(SystemClock.elapsedRealtime())
            // Copy fields — MediaCodec may reuse [info].
            // 拷贝字段 —— MediaCodec 可能会复用 [info]。
            val handler = outputHandler
            if (handler != null) {
                // Fast path: primitives only, no per-frame OutputBuffer allocation.
                // 快速路径：只传原始值，不做每帧 OutputBuffer 分配。
                handler(codec, index, info.offset, info.size, info.presentationTimeUs, info.flags)
            } else {
                outputs.trySend(OutputBuffer.from(index, info))
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            VapLog.w("VapLoop", e) {
                "sid=$logSid codec-error recoverable=${e.isRecoverable} " +
                    "transient=${e.isTransient} diag=${e.diagnosticInfo} ${codecDiag()}"
            }
            outputs.close(IllegalStateException("MediaCodec error: ${e.diagnosticInfo}", e))
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
        }
    }

    fun attach(codec: MediaCodec) {
        if (sharedCallbackHeld.compareAndSet(expectedValue = false, newValue = true)) {
            VapSharedCodecCallbackRuntime.acquire()
        }
        val handler = VapSharedCodecCallbackRuntime.handler()
            ?: error("Missing shared MediaCodec callback handler")
        codec.setCallback(callback, handler)
    }

    fun start(codec: MediaCodec) {
        try {
            codec.start()
        } catch (t: Throwable) {
            // MediaCodec.start() 可能抛 CodecException（编解码器资源耗尽 / surface 失效）
            // 或 IllegalStateException（已 release）。先记录可诊断信息并关闭输出队列，
            // 让等待中的解码泵快速失败（而不是挂死），再把异常交给上层
            //（open / startDecode）按既有失败路径处理。
            // MediaCodec.start() can throw CodecException (codec resource exhaustion /
            // dead surface) or IllegalStateException (already released). Log diagnostics,
            // close the output channel so a waiting pump fails fast, then rethrow.
            val diag = (t as? MediaCodec.CodecException)?.let {
                "recoverable=${it.isRecoverable} transient=${it.isTransient} diag=${it.diagnosticInfo}"
            } ?: t.message
            VapLog.e("VapLoop", t) { "sid=$logSid codec.start failed: $diag ${codecDiag()}" }
            outputs.close(IllegalStateException("MediaCodec.start failed: $diag", t))
            throw t
        }
    }

    /** Queue an output packet to the decode pump (arrival order preserved). / 将 output 包入队至解码泵（保留到达顺序）。 */
    fun enqueueOutput(packet: OutputBuffer): Boolean = outputs.trySend(packet).isSuccess

    /** Drop stale buffer indices after [MediaCodec.flush] (indices become invalid). / 在 [MediaCodec.flush] 之后丢弃失效的缓冲索引（索引已无效）。 */
    fun clearPending() {
        while (outputs.tryReceive().isSuccess) {
        }
    }

    fun close() {
        // Drop handlers first so in-flight callbacks after teardown cannot
        // releaseOutputBuffer on a codec that is about to stop/release.
        // 先摘掉 handler，避免拆除后仍在飞的回调对即将 stop/release 的 codec
        // 再调 releaseOutputBuffer。
        inputFeeder = null
        outputHandler = null
        outputs.close()
        clearPending()
        if (sharedCallbackHeld.compareAndSet(expectedValue = true, newValue = false)) {
            VapSharedCodecCallbackRuntime.release()
        }
    }

    /**
     * Wait for the next codec output. Logs every 5s while empty so a stuck pump
     * is visible; timeout only cancels this receive (the packet stays in the
     * channel). Playback is unchanged.
     * 等下一帧 codec 输出。队列空时每 5 秒打一条，泵卡住能看见；超时只取消
     * 这次 receive（包还在 channel 里），播放路径不变。
     */
    suspend fun awaitOutputBuffer(): OutputBuffer {
        var waitedSec = 0
        while (true) {
            val packet = try {
                withTimeoutOrNull(5_000L) { outputs.receive() }
            } catch (e: ClosedReceiveChannelException) {
                throw CancellationException("MediaCodec output closed", e)
            }
            if (packet != null) return packet
            waitedSec += 5
            VapLog.w("VapLoop") {
                "sid=$logSid pump-output-wait ${waitedSec}s ${codecDiag()}"
            }
        }
    }

    /** Non-blocking burst drain after [awaitOutputBuffer]. / [awaitOutputBuffer] 之后的非阻塞批量排空。 */
    fun pollOutputBuffer(): OutputBuffer? = outputs.tryReceive().getOrNull()
}
