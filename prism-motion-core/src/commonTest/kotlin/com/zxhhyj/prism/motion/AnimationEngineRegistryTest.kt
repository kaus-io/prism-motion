package com.zxhhyj.prism.motion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PrismMotionEngineRegistryTest {
    private class FakeEngine(
        override val id: PrismMotionEngineId,
        private val accepts: (PrismMotionSource) -> Boolean,
    ) : PrismMotionEngine {
        override fun supports(source: PrismMotionSource): Boolean = accepts(source)

        override suspend fun open(source: PrismMotionSource): PrismMotionResource =
            error("not used in this test")
    }

    private val ktx = PrismMotionSource.Asset("a.ktx")
    private val mp4 = PrismMotionSource.Asset("a.mp4")
    private val ktxEngine = FakeEngine(PrismMotionEngineId.Kap) { it === ktx }
    private val vapEngine = FakeEngine(PrismMotionEngineId.Vap) { it === mp4 }
    private val registry = PrismMotionEngineRegistry(listOf(ktxEngine, vapEngine))

    @Test
    fun picksTheEngineThatSupportsTheSource() {
        assertEquals(ktxEngine, registry.engineFor(PrismMotionRequest(ktx)))
        assertEquals(vapEngine, registry.engineFor(PrismMotionRequest(mp4)))
    }

    @Test
    fun explicitEngineWinsWhenItSupportsTheSource() {
        assertEquals(vapEngine, registry.engineFor(PrismMotionRequest(mp4, engine = PrismMotionEngineId.Vap)))
    }

    @Test
    fun explicitEngineThatDoesNotSupportTheSourceFailsInsteadOfFallingBack() {
        assertNull(registry.engineFor(PrismMotionRequest(ktx, engine = PrismMotionEngineId.Vap)))
    }

    @Test
    fun unknownSourceHasNoEngine() {
        assertNull(registry.engineFor(PrismMotionRequest(PrismMotionSource.Asset("a.gif"))))
    }

    @Test
    fun formatIsRecognisedBySuffix() {
        assertEquals(PrismMotionFormat.Vap, PrismMotionFormat.ofFileName("a.MP4"))
        assertEquals(PrismMotionFormat.Kap, PrismMotionFormat.ofFileName("b.ktx"))
        assertNull(PrismMotionFormat.ofFileName("c.gif"))
    }
}
