package com.zxhhyj.prism.motion

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PrismMotionEngineRegistryTest {
    private class FakeEngine(
        override val id: PrismMotionEngineId,
        private val accepts: (PrismMotionSource) -> Boolean,
    ) : PrismMotionEngine {
        override suspend fun supports(source: PrismMotionSource): Boolean = accepts(source)
        override suspend fun open(source: PrismMotionSource): PrismMotionResource = error("not used in this test")
    }

    private val texture = PrismMotionSource.Asset("texture.prism")
    private val video = PrismMotionSource.Asset("video.prism")
    private val textureEngine = FakeEngine(PrismMotionEngineId.Kap) { it === texture }
    private val videoEngine = FakeEngine(PrismMotionEngineId.Vap) { it === video }
    private val registry = PrismMotionEngineRegistry(listOf(textureEngine, videoEngine))

    @Test
    fun picksTheEngineThatSupportsThePayloadInsteadOfTheSharedSuffix() = runBlocking {
        assertEquals(textureEngine, registry.engineFor(PrismMotionRequest(texture)))
        assertEquals(videoEngine, registry.engineFor(PrismMotionRequest(video)))
    }

    @Test
    fun explicitEngineWinsWhenItSupportsTheSource() = runBlocking {
        assertEquals(videoEngine, registry.engineFor(PrismMotionRequest(video, engine = PrismMotionEngineId.Vap)))
    }

    @Test
    fun explicitEngineThatDoesNotSupportTheSourceFailsInsteadOfFallingBack() = runBlocking {
        assertNull(registry.engineFor(PrismMotionRequest(texture, engine = PrismMotionEngineId.Vap)))
    }

    @Test
    fun unknownSourceHasNoEngine() = runBlocking {
        assertNull(registry.engineFor(PrismMotionRequest(PrismMotionSource.Asset("a.gif"))))
    }

    @Test
    fun onlyPrismIsAnExternalResourceFormat() {
        assertEquals(PrismMotionFormat.Prism, PrismMotionFormat.ofFileName("a.PRISM"))
        for (name in listOf("a.mp4", "b.ktx", "b.ktx2", "c.gif")) assertNull(PrismMotionFormat.ofFileName(name))
    }
}
