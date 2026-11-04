package com.zxhhyj.kap.compose

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import android.view.ViewTreeObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/** Embedded output with consumer acknowledgement; experimental SurfaceView hosts stay separate. */
@Composable
internal fun KtxEmbeddedSurface(animationState: KtxAnimationState, modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val owner = remember(context, scope, animationState) {
        KtxEmbeddedSurfaceOwner(context, scope, animationState)
    }
    AndroidView(
        factory = { viewContext ->
            TextureView(viewContext).apply {
                isOpaque = false
                owner.bind(this)
                surfaceTextureListener = owner
            }
        },
        modifier = modifier,
        // The Surface lifetime belongs to this state, not to AndroidView's reusable-view pool.
        onReset = null,
        onRelease = { view ->
            owner.dispose()
            view.surfaceTextureListener = null
        },
        update = { view ->
            owner.bind(view)
            view.isOpaque = false
        },
    )
}

private class KtxEmbeddedSurfaceOwner(
    private val context: Context,
    private val scope: CoroutineScope,
    private val animationState: KtxAnimationState,
) : TextureView.SurfaceTextureListener {
    private class SurfaceLease(
        val texture: SurfaceTexture,
        val surface: Surface,
        val epoch: Long,
    ) {
        val sizeVersion = AtomicLong()
        var updateSequence = 0L // Main only, like TextureView callbacks.
    }

    private var view: TextureView? = null
    @Volatile private var lease: SurfaceLease? = null
    @Volatile private var disposed = false
    private var surfaceJob: Job? = null
    private var pendingOutput: KtxSurfaceOutput? = null
    private var pendingObserver: ViewTreeObserver? = null
    private var pendingCommit: Runnable? = null

    fun bind(view: TextureView) {
        this.view = view
        animationState.frameSnapshot = { snapshot(view) }
    }

    /** 抓当前帧做内存缓存；TextureView 还没内容时会返回 null。 */
    private fun snapshot(view: TextureView): ImageBitmap? = try {
        view.bitmap?.asImageBitmap()
    } catch (_: Throwable) {
        null
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        if (disposed) return
        invalidateLease()
        val surface = Surface(texture)
        val epoch = animationState.onSurfaceAttached(width, height, texture.timestamp)
        val current = SurfaceLease(texture, surface, epoch)
        lease = current
        val previousJob = surfaceJob
        // Enter try/finally even when the scope was already cancelled. If a replacement arrives
        // before native attach starts, its Java Surface still has exactly one cleanup owner.
        surfaceJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                previousJob?.cancelAndJoin()
                animationState.runSurface(context, surface, epoch)
            } finally {
                // runSurface's NonCancellable native detach completes before this release.
                withContext(NonCancellable + Dispatchers.Default) { surface.release() }
            }
        }
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        val current = lease?.takeIf { it.texture === texture } ?: return
        if (width <= 0 || height <= 0) return
        val sizeVersion = current.sizeVersion.incrementAndGet()
        val initialTimestamp = texture.timestamp
        scope.launch(Dispatchers.Default) {
            animationState.resize(current.surface, width, height, current.epoch, initialTimestamp) {
                !disposed && lease === current && current.sizeVersion.get() == sizeVersion
            }
        }
    }

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        if (lease?.texture === texture) invalidateLease()
        // Preserve TextureView/Compose's normal ownership of the SurfaceTexture. Only the
        // separate Java Surface wrapper is released by the render coroutine, off Main.
        return true
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
        val current = lease?.takeIf { it.texture === texture } ?: return
        current.updateSequence++
        val output = animationState.awaitingDisplayOutput(current.epoch) ?: return
        requestConsumerCommit(current, output)
    }

    private fun requestConsumerCommit(current: SurfaceLease, output: KtxSurfaceOutput) {
        val target = view ?: return
        if (disposed || lease !== current || pendingOutput == output) return
        clearPendingCommit()
        pendingOutput = output
        val updateSequence = current.updateSequence
        // Updated can be a geometry-only notification, and HWUI's texture update is deferred.
        // Register outside draw, then force a hardware frame: only its commit + a changed
        // SurfaceTexture timestamp can acknowledge a real consumed buffer.
        target.post {
            if (disposed || lease !== current || pendingOutput != output ||
                animationState.awaitingDisplayOutput(current.epoch) != output
            ) return@post
            val observer = target.viewTreeObserver
            if (!target.isHardwareAccelerated || !observer.isAlive) {
                clearPendingCommit()
                return@post
            }
            val callback = Runnable {
                target.post {
                    finishConsumerCommit(current, output, updateSequence)
                }
            }
            pendingObserver = observer
            pendingCommit = callback
            observer.registerFrameCommitCallback(callback)
            target.invalidate()
        }
    }

    private fun finishConsumerCommit(
        current: SurfaceLease,
        output: KtxSurfaceOutput,
        updateSequence: Long,
    ) {
        if (disposed || lease !== current || pendingOutput != output) return
        clearPendingCommit()
        if (animationState.awaitingDisplayOutput(current.epoch) != output) return
        animationState.onDisplayBufferConsumed(output, current.texture.timestamp)
        // A real update may have arrived while this commit was pending. Do not lose that
        // notification, but also do not invalidate/poll indefinitely for a geometry-only update.
        if (current.updateSequence != updateSequence &&
            animationState.awaitingDisplayOutput(current.epoch) == output
        ) {
            requestConsumerCommit(current, output)
        }
    }

    private fun clearPendingCommit() {
        val observer = pendingObserver
        val callback = pendingCommit
        if (observer != null && observer.isAlive && callback != null) {
            observer.unregisterFrameCommitCallback(callback)
        }
        pendingObserver = null
        pendingCommit = null
        pendingOutput = null
    }

    private fun invalidateLease() {
        clearPendingCommit()
        lease?.let { current ->
            lease = null
            current.sizeVersion.incrementAndGet()
            animationState.onSurfaceDestroyed(current.epoch)
        }
        surfaceJob?.cancel()
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        invalidateLease()
        animationState.frameSnapshot = null
        view = null
    }
}
