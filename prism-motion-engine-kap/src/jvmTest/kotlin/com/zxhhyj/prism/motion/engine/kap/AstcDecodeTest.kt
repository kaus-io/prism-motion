package com.zxhhyj.prism.motion.engine.kap

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AstcDecodeTest {

    /** 默认跳过；设置 PRISM_TEST_ASTC_KTX2 指向一个 ASTC 版 .ktx2 时才真正解码。 */
    @Test
    fun decodesAstcWithNativeLibrary() {
        val path = System.getenv("PRISM_TEST_ASTC_KTX2") ?: return
        val engine = KapAnimationEngine()
        val resource = kotlinx.coroutines.runBlocking {
            engine.open(com.zxhhyj.prism.motion.AnimationSource.File(path))
        }
        assertEquals(64, resource.width)
        assertEquals(64, resource.height)
        assertTrue(resource.durationMs > 0)
        File(path).let { assertTrue(it.isFile) }
    }
}
