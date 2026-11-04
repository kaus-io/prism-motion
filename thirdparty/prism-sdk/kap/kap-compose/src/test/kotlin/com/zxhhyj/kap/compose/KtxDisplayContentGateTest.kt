package com.zxhhyj.kap.compose

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KtxDisplayContentGateTest {
    @Test
    fun noOutputHasNoDisplayContent() {
        val gate = KtxDisplayContentGate()
        assertFalse(gate.hasDisplayContent)
        assertFalse(gate.needsConsumption)
    }

    @Test
    fun successfulProducerSubmissionAloneIsNotDisplayContent() {
        val output = KtxSurfaceOutput(epoch = 1, revision = 0, initialTimestamp = 0)
        val gate = KtxDisplayContentGate()
        gate.reset(output)
        gate.onSubmitted(output)
        assertFalse(gate.hasDisplayContent)
        assertTrue(gate.needsConsumption)
    }

    @Test
    fun consumerBeforeProducerReturnIsRemembered() {
        val output = KtxSurfaceOutput(1, 0, 0)
        val gate = KtxDisplayContentGate()
        gate.reset(output)
        gate.onConsumed(output, timestamp = 123)
        assertFalse(gate.hasDisplayContent)
        assertFalse(gate.needsConsumption)
        gate.onSubmitted(output)
        assertTrue(gate.hasDisplayContent)
    }

    @Test
    fun producerBeforeConsumerBecomesReadyOnlyAfterConsumption() {
        val output = KtxSurfaceOutput(1, 0, 0)
        val gate = KtxDisplayContentGate()
        gate.reset(output)
        gate.onSubmitted(output)
        assertFalse(gate.hasDisplayContent)
        gate.onConsumed(output, timestamp = 123)
        assertTrue(gate.hasDisplayContent)
    }

    @Test
    fun geometryOnlyUpdateWithUnchangedTimestampDoesNotReveal() {
        val output = KtxSurfaceOutput(1, 0, 88)
        val gate = KtxDisplayContentGate()
        gate.reset(output)
        gate.onSubmitted(output)
        repeat(3) { gate.onConsumed(output, timestamp = 88) }
        assertFalse(gate.hasDisplayContent)
        assertTrue(gate.needsConsumption)
        gate.onConsumed(output, timestamp = 89)
        assertTrue(gate.hasDisplayContent)
    }

    @Test
    fun replacingSurfaceRejectsBothOldCallbacksAndCumulativeProducerHistory() {
        val old = KtxSurfaceOutput(1, 0, 0)
        val current = KtxSurfaceOutput(2, 0, 0)
        val gate = KtxDisplayContentGate()
        gate.reset(old)
        gate.onSubmitted(old)
        gate.onConsumed(old, 123)
        assertTrue(gate.hasDisplayContent)
        gate.reset(current)
        gate.onSubmitted(old)
        gate.onConsumed(old, 124)
        assertFalse(gate.hasDisplayContent)
        gate.onSubmitted(current)
        assertFalse(gate.hasDisplayContent)
        gate.onConsumed(current, 125)
        assertTrue(gate.hasDisplayContent)
    }

    @Test
    fun resizeRejectsOldOutputRevisionOnSameSurface() {
        val old = KtxSurfaceOutput(1, 0, 0)
        val resized = KtxSurfaceOutput(1, 1, 123)
        val gate = KtxDisplayContentGate()
        gate.reset(old)
        gate.onSubmitted(old)
        gate.onConsumed(old, 123)
        gate.reset(resized)
        gate.onSubmitted(old)
        gate.onConsumed(old, 124)
        assertFalse(gate.hasDisplayContent)
        gate.onSubmitted(resized)
        gate.onConsumed(resized, 123)
        assertFalse(gate.hasDisplayContent)
        gate.onConsumed(resized, 124)
        assertTrue(gate.hasDisplayContent)
    }

    @Test
    fun destroyInvalidatesEvenWhenBothCallbacksArriveLate() {
        val output = KtxSurfaceOutput(1, 0, 0)
        val gate = KtxDisplayContentGate()
        gate.reset(output)
        gate.invalidate()
        gate.onSubmitted(output)
        gate.onConsumed(output, 123)
        assertFalse(gate.hasDisplayContent)
        assertFalse(gate.needsConsumption)
    }

    @Test
    fun alreadyConsumedOutputStaysReadyUntilExplicitlyInvalidated() {
        val output = KtxSurfaceOutput(1, 0, 0)
        val gate = KtxDisplayContentGate()
        gate.reset(output)
        gate.onSubmitted(output)
        gate.onConsumed(output, 123)
        gate.onSubmitted(output)
        gate.onConsumed(output, 0)
        assertTrue(gate.hasDisplayContent)
        gate.invalidate()
        assertFalse(gate.hasDisplayContent)
    }
}
