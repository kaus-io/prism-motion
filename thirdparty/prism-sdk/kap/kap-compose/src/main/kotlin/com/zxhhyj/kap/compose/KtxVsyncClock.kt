package com.zxhhyj.kap.compose

import android.view.Choreographer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** All players use the main display's Choreographer timestamps; no queued ticks. */
internal object KtxVsyncClock {
    suspend fun awaitFrame(notBeforeNanos: Long): Long = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { continuation ->
            val choreographer = Choreographer.getInstance()
            val callback = object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    if (!continuation.isActive) return
                    if (frameTimeNanos >= notBeforeNanos) {
                        continuation.resume(frameTimeNanos)
                    } else {
                        // Filter on Main so a 30fps player does not wake its render
                        // coroutine on every 60/90/120Hz display refresh.
                        choreographer.postFrameCallback(this)
                        // Cancellation can race the repost after the entry check.
                        if (!continuation.isActive) choreographer.removeFrameCallback(this)
                    }
                }
            }
            choreographer.postFrameCallback(callback)
            continuation.invokeOnCancellation { choreographer.removeFrameCallback(callback) }
        }
    }
}
