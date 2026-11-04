package com.zxhhyj.vap.decode

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VapCompositorSlotTest {
    @Test
    fun tryAcquire_takesTheSinglePermit() {
        val slot = VapCompositorSlot()
        assertTrue(slot.tryAcquire())
        assertFalse(slot.tryAcquire())
        assertFalse(slot.available)
    }

    @Test
    fun release_returnsOnlyOnePermit() {
        val slot = VapCompositorSlot()
        assertTrue(slot.tryAcquire())
        slot.release()
        slot.release()
        assertTrue(slot.tryAcquire())
        assertFalse(slot.tryAcquire())
    }

    @Test
    fun acquireOrPark_unblocksWhenPermitReturnsWithoutSideWake() = runBlocking {
        val slot = VapCompositorSlot()
        assertTrue(slot.tryAcquire())
        val parked = async { slot.acquireOrPark { true } }
        yield()
        delay(5)
        slot.release()
        assertTrue(withTimeout(200) { parked.await() })
        assertFalse(slot.available)
    }

    @Test
    fun acquireOrPark_returnsFalseWhenParkedWhileWaiting() = runBlocking {
        val slot = VapCompositorSlot()
        assertTrue(slot.tryAcquire())
        var waiting = true
        val parked = async { slot.acquireOrPark { waiting } }
        yield()
        delay(5)
        waiting = false
        slot.requestPark()
        assertFalse(withTimeout(200) { parked.await() })
        assertFalse(slot.available)
        slot.release()
        assertTrue(slot.tryAcquire())
    }

    @Test
    fun reset_restoresSinglePermit() {
        val slot = VapCompositorSlot()
        assertTrue(slot.tryAcquire())
        slot.reset()
        assertTrue(slot.available)
        assertTrue(slot.tryAcquire())
        assertFalse(slot.tryAcquire())
    }

    @Test
    fun acquireOrPark_ignoresStaleParkAfterResume() = runBlocking {
        val slot = VapCompositorSlot()
        slot.requestPark()
        assertTrue(slot.acquireOrPark { true })
        assertEquals(false, slot.available)
    }
}
