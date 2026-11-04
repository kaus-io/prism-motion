package com.zxhhyj.kap.compose

import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** Experimental: one display callback while any player is waiting; no queued ticks. */
internal object KtxSharedVsyncClock {
    private data class Waiter(val deadline: Long, val continuation: CancellableContinuation<Long>)
    private val main = Handler(Looper.getMainLooper())
    // Every list mutation and posted flag access happens on Main.
    private val waiters = ArrayList<Waiter>()
    private var posted = false
    private val callback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            posted = false
            for (index in waiters.lastIndex downTo 0) {
                val waiter = waiters[index]
                if (!waiter.continuation.isActive) {
                    waiters.removeAt(index)
                } else if (frameTimeNanos >= waiter.deadline) {
                    waiters.removeAt(index)
                    waiter.continuation.resume(frameTimeNanos)
                }
            }
            schedule()
        }
    }

    private fun schedule() {
        if (waiters.isNotEmpty() && !posted) {
            posted = true
            Choreographer.getInstance().postFrameCallback(callback)
        }
    }

    suspend fun awaitFrame(deadline: Long): Long = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { continuation ->
            val waiter = Waiter(deadline, continuation)
            waiters.add(waiter)
            continuation.invokeOnCancellation {
                main.post {
                    waiters.remove(waiter)
                    if (waiters.isEmpty() && posted) {
                        Choreographer.getInstance().removeFrameCallback(callback)
                        posted = false
                    }
                }
            }
            schedule()
        }
    }
}
