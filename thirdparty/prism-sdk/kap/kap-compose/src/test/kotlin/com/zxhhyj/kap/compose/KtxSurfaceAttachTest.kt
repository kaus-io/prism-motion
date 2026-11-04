package com.zxhhyj.kap.compose

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.test.assertFailsWith

class KtxSurfaceAttachTest {
    @Test
    fun destroyedBeforeAttachDoesNotTouchNative() {
        val result = attachKtxSurfaceIfCurrent(
            isCurrent = { false },
            attach = { error("obsolete Surface must not attach") },
        )
        assertEquals(KtxSurfaceAttachResult.Stale, result)
    }

    @Test
    fun destroyedDuringFailedAttachIsNotAPlaybackFailure() {
        var current = true
        val result = attachKtxSurfaceIfCurrent(
            isCurrent = { current },
            attach = {
                current = false
                false
            },
        )
        assertEquals(KtxSurfaceAttachResult.Stale, result)
    }

    @Test
    fun replacementDuringSuccessfulAttachCannotStartOldPlayback() {
        var epoch = 1L
        val result = attachKtxSurfaceIfCurrent(
            isCurrent = { epoch == 1L },
            attach = {
                epoch = 2L
                true
            },
        )
        assertEquals(KtxSurfaceAttachResult.Stale, result)
    }

    @Test
    fun validSuccessfulAttachStartsPlayback() {
        assertEquals(
            KtxSurfaceAttachResult.Attached,
            attachKtxSurfaceIfCurrent(isCurrent = { true }, attach = { true }),
        )
    }

    @Test
    fun validFailedAttachStillReportsFailure() {
        assertEquals(
            KtxSurfaceAttachResult.Failed,
            attachKtxSurfaceIfCurrent(isCurrent = { true }, attach = { false }),
        )
    }

    @Test
    fun cancellationIsNeverSwallowed() {
        assertFailsWith<java.util.concurrent.CancellationException> {
            attachKtxSurfaceIfCurrent(
                isCurrent = { true },
                attach = { throw java.util.concurrent.CancellationException("cancelled") },
            )
        }
    }
}
