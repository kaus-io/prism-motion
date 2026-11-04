package com.zxhhyj.vap.decode

import android.os.Handler
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One-shot wake pulse on a [Handler]. Re-arming replaces the pending callback;
 * [cancel] drops it. There is no Choreographer path: overlay windows often have
 * no Activity, so vsync callbacks are not a reliable clock.
 *
 * [Handler] 上的一次性唤醒脉冲。再次 [arm] 会替换未触发的回调；[cancel] 取消。
 * 没有 Choreographer：overlay 经常没有 Activity，vsync 回调当不了可靠时钟。
 */
internal class VapFramePulse(
    private val handlerProvider: () -> Handler?,
    private val onPulse: (frameTimeNanos: Long) -> Unit,
) {
    private val armed = AtomicBoolean(false)
    private val pulse = Runnable {
        armed.set(false)
        onPulse(System.nanoTime())
    }

    /**
     * Schedule the next pulse in [delayMillis] (0 = next looper turn).
     * Returns false when the handler is gone.
     *
     * [delayMillis] 后打一拍（0 = 下一圈 looper）。handler 不在时返回 false。
     */
    fun arm(delayMillis: Long): Boolean {
        val handler = handlerProvider() ?: return false
        handler.removeCallbacks(pulse)
        armed.set(true)
        val delay = delayMillis.coerceAtLeast(0L)
        if (delay == 0L) {
            handler.post(pulse)
        } else {
            handler.postDelayed(pulse, delay)
        }
        return true
    }

    /**
     * Arm only when nothing is queued. ImageReader must not cancel an fps-grid wait
     * with `arm(0)` — that runs the shared clock at decode rate and starves siblings.
     *
     * 只有还没预约时才 arm。ImageReader 不能用 `arm(0)` 拆掉 fps 格子等待，
     * 否则共享时钟会跟着解码速率空转，把其它会话饿死。
     */
    fun armIfIdle(delayMillis: Long): Boolean {
        if (armed.get()) return true
        return arm(delayMillis)
    }

    fun cancel() {
        armed.set(false)
        handlerProvider()?.removeCallbacks(pulse)
    }

    fun isArmed(): Boolean = armed.get()
}
