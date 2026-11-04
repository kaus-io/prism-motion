package com.zxhhyj.prism.motion.engine.kap

import com.zxhhyj.prism.motion.ktx.KtxReader
import com.zxhhyj.prism.motion.PrismJvmSources
import com.zxhhyj.prism.motion.PrismMotionSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AstcDecodeTest {

    /** 设置环境变量后测试真实 Prism 纹理资源。 / Opt in to a real Prism texture fixture via the environment. */
    @Test
    fun decodesAstcWithNativeLibrary() {
        val path = System.getenv("PRISM_TEST_ASTC_ASSET") ?: return
        val engine = PrismMotionKapEngine()
        val resource = kotlinx.coroutines.runBlocking {
            engine.open(com.zxhhyj.prism.motion.PrismMotionSource.File(path))
        }
        try {
            val parsed = KtxReader.read(PrismJvmSources.payload(PrismMotionSource.File(path)).bytes)
            assertTrue(AstcNative.available, "Native ASTC fixture test requires libastcenc")
            assertTrue(parsed.vkFormat in 157..184, "Fixture must contain ASTC blocks")
            assertEquals(parsed.width, resource.width)
            assertEquals(parsed.height, resource.height)
            assertTrue(resource.durationMs > 0)
        } finally {
            resource.release()
            AstcNative.clearCache()
        }
    }
}
