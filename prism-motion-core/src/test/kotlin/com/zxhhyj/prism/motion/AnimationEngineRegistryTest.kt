package com.zxhhyj.prism.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnimationEngineRegistryTest {
    private class FakeEngine(
        override val id: AnimationEngineId,
        private val accepts: (AnimationSource) -> Boolean,
    ) : AnimationEngine {
        override fun supports(source: AnimationSource): Boolean = accepts(source)

        override suspend fun open(source: AnimationSource): AnimationResource =
            error("not used in this test")
    }

    private val ktx = AnimationSource.Asset("a.ktx2")
    private val mp4 = AnimationSource.Asset("a.mp4")
    private val ktxEngine = FakeEngine(AnimationEngineId.Kap) { it === ktx }
    private val vapEngine = FakeEngine(AnimationEngineId.Vap) { it === mp4 }
    private val registry = AnimationEngineRegistry(listOf(ktxEngine, vapEngine))

    @Test
    fun picksTheEngineThatSupportsTheSource() {
        assertEquals(ktxEngine, registry.engineFor(AnimationRequest(ktx)))
        assertEquals(vapEngine, registry.engineFor(AnimationRequest(mp4)))
    }

    @Test
    fun explicitEngineWinsWhenItSupportsTheSource() {
        assertEquals(vapEngine, registry.engineFor(AnimationRequest(mp4, engine = AnimationEngineId.Vap)))
    }

    @Test
    fun explicitEngineThatDoesNotSupportTheSourceFailsInsteadOfFallingBack() {
        assertNull(registry.engineFor(AnimationRequest(ktx, engine = AnimationEngineId.Vap)))
    }

    @Test
    fun unknownSourceHasNoEngine() {
        assertNull(registry.engineFor(AnimationRequest(AnimationSource.Asset("a.gif"))))
    }
}
