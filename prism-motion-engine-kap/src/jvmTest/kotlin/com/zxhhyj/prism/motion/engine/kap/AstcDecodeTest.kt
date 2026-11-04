package com.zxhhyj.prism.motion.engine.kap

import com.zxhhyj.prism.motion.ktx.KtxReader
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AstcDecodeTest {

    /** 设置环境变量后测试真实 KTX2 文件。 / Opt in to a real KTX2 fixture via the environment. */
    @Test
    fun decodesAstcWithNativeLibrary() {
        val path = System.getenv("PRISM_TEST_ASTC_KTX2") ?: return
        val engine = PrismMotionKapEngine()
        val resource = kotlinx.coroutines.runBlocking {
            engine.open(com.zxhhyj.prism.motion.PrismMotionSource.File(path))
        }
        try {
            val parsed = KtxReader.read(File(path).readBytes())
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
