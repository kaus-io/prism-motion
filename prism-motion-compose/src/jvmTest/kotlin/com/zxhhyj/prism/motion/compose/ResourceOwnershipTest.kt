package com.zxhhyj.prism.motion.compose

import com.zxhhyj.prism.motion.PrismMotionResource
import com.zxhhyj.prism.motion.PrismMotionRequest
import com.zxhhyj.prism.motion.PrismMotionSource
import com.zxhhyj.prism.motion.PrismMotionStatus
import com.zxhhyj.prism.motion.PrismMotionSurface
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/** 验证资源归属，不依赖窗口或 GPU。 / Verifies ownership without a window or GPU. */
class ResourceOwnershipTest {
    @Test
    fun runtimePlaybackErrorOverridesLoadedReadyStatus() {
        val resource = FakeResource()
        val state = PrismMotionState(PrismMotionRequest(PrismMotionSource.Asset("test.prism")), null,
            resource, null, PrismMotionStatus.Ready)
        assertEquals(PrismMotionStatus.Ready, state.status)
        val error = IllegalStateException("decoder failed")
        resource.failure = error
        assertEquals(PrismMotionStatus.Failed(error), state.status)
        assertEquals(false, state.hasOutput)
    }

    @Test
    fun readyDoesNotReleaseResourceAndDisposalReleasesExactlyOnce() = runBlocking {
        val resource = FakeResource()
        var published: PrismMotionResource? = null
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            ownPrismMotionResource({ resource }) { published = it }
        }
        assertSame(resource, published)
        assertEquals(0, resource.releases)
        job.cancelAndJoin()
        assertEquals(1, resource.releases)
    }

    @Test
    fun cancellationDuringSlowOpenReleasesInsteadOfPublishing() = runBlocking {
        val resource = FakeResource()
        val gate = CompletableDeferred<Unit>()
        var published = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            ownPrismMotionResource(open = {
                withContext(NonCancellable) { gate.await() }
                resource
            }) { published = true }
        }
        job.cancel()
        gate.complete(Unit)
        job.join()
        assertEquals(false, published)
        assertEquals(1, resource.releases)
    }

    @Test
    fun publicationFailureReleasesTheOpenedResource() = runBlocking {
        val resource = FakeResource()
        assertFailsWith<IllegalStateException> {
            ownPrismMotionResource({ resource }) { error("publish failed") }
        }
        assertEquals(1, resource.releases)
    }

    private class FakeResource : PrismMotionResource {
        var releases = 0
        var failure: Throwable? = null
        override val playbackError: Throwable? get() = failure
        override val width = 1
        override val height = 1
        override val durationMs = 1L
        override val hasOutput = false
        override val progress = 0f
        override val isPlaying = false
        override fun attach(surface: PrismMotionSurface, width: Int, height: Int) = Unit
        override fun resize(width: Int, height: Int) = Unit
        override fun detach() = Unit
        override fun play() = Unit
        override fun pause() = Unit
        override fun seekTo(progress: Float) = Unit
        override fun release() { releases++ }
    }
}
