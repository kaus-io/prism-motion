package com.zxhhyj.vap.decode

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VapPlaybackGateTest {
    @Test
    fun awaitValueOrInactive_returnsValueWhenReceiveWins() = runBlocking {
        val gate = VapPlaybackGate()
        val value = gate.awaitValueOrInactive({ false }) { 7 }
        assertEquals(7, value)
    }

    @Test
    fun awaitValueOrInactive_returnsNullWhenAlreadyInactive() = runBlocking {
        val gate = VapPlaybackGate()
        gate.setPlaying(false)
        val hung = CompletableDeferred<Int>()
        val value = gate.awaitValueOrInactive({ false }) { hung.await() }
        assertNull(value)
    }

    @Test
    fun awaitValueOrInactive_returnsNullWhenPausedWhileReceiveHangs() = runBlocking {
        val gate = VapPlaybackGate()
        val hung = CompletableDeferred<Int>()
        val result = async {
            gate.awaitValueOrInactive({ false }) { hung.await() }
        }
        yield()
        delay(10)
        gate.setPlaying(false)
        assertNull(result.await())
    }
}
