package com.zxhhyj.vap.decode

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/**
 * Decode and present advance only while [playing]. Pause freezes the decode
 * position (input is deferred, pending frame is kept) so resume continues
 * from the same picture. Visibility-based pausing is left to the caller:
 * drive [setPlaying] from your own visibility tracking if needed.
 *
 * The gate defaults to active. It is intended for use from a single owner coroutine;
 * mutators are not safe to invoke concurrently with each other.
 *
 * 仅在 playing 时推进解码和呈现。暂停会冻住解码位置（暂缓 input、保留
 * pending 帧），恢复时从同一画面继续。基于可见性的暂停交给调用方：
 * 如有需要，用自己的可见性跟踪驱动 [setPlaying]。
 *
 * 门控默认开启。预期由单一所属协程使用；变更方法彼此之间不可并发调用。
 */
public class VapPlaybackGate {
    private var playing: Boolean = true
    private val active = MutableStateFlow(true)

    public fun isActive(): Boolean = active.value

    /** @return true if transitioned from inactive -> active. 从 inactive 转为 active 时为 true。 */
    public fun setPlaying(value: Boolean): Boolean {
        playing = value
        return publish()
    }

    /**
     * Suspend until the gate becomes active, or returns immediately when the
     * decoder has already been torn down ([isStopped] is true).
     *
     * 挂起直至门控变为 active；若解码器已销毁则立即返回。
     */
    public suspend fun awaitActive(isStopped: () -> Boolean) {
        if (isStopped() || active.value) return
        active.first { it || isStopped() }
    }

    /** Suspend until the gate becomes inactive, or the decoder is torn down. 挂起直至门控变为 inactive，或解码器已销毁。 */
    public suspend fun awaitInactive(isStopped: () -> Boolean) {
        if (isStopped() || !active.value) return
        active.first { !it || isStopped() }
    }

    /**
     * Wait for [receive], but abort with `null` as soon as the gate goes inactive
     * or the decoder stops. A parked Think/Speech session that sits in
     * `awaitOutputBuffer()` never reaches the pump-park path; resume then only
     * wakes [awaitActive], which nobody is waiting on.
     *
     * 等待 [receive]，门关闭或解码器停止时立刻返回 `null`。停住的 Think/Speech
     * 若卡在 `awaitOutputBuffer()`，就走不到 pump-park；恢复时只唤醒
     * [awaitActive]，而泵并不在那里等。
     */
    public suspend fun <T> awaitValueOrInactive(
        isStopped: () -> Boolean,
        receive: suspend () -> T,
    ): T? {
        if (!isActive() || isStopped()) return null
        return coroutineScope {
            val done = CompletableDeferred<T?>()
            launch {
                try {
                    done.complete(receive())
                } catch (t: CancellationException) {
                    done.complete(null)
                    throw t
                } catch (t: Throwable) {
                    done.completeExceptionally(t)
                }
            }
            launch {
                awaitInactive(isStopped)
                done.complete(null)
            }
            val value = done.await()
            coroutineContext.cancelChildren()
            value
        }
    }

    private fun publish(): Boolean {
        val prev = active.value
        active.value = playing
        return playing && !prev
    }
}
