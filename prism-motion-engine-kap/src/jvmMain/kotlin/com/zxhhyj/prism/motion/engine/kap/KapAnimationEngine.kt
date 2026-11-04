package com.zxhhyj.prism.motion.engine.kap

import com.zxhhyj.prism.motion.AnimationEngine
import com.zxhhyj.prism.motion.AnimationEngineId
import com.zxhhyj.prism.motion.AnimationResource
import com.zxhhyj.prism.motion.AnimationSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * KAP 引擎的 JVM 实现：KTX2 + ASTC/RGBA8 在 JVM 上软件解码，不依赖 Vulkan。
 * JVM KAP engine: decodes KTX2 + ASTC/RGBA8 in software, no Vulkan required.
 */
public class KapAnimationEngine : AnimationEngine {

    override val id: AnimationEngineId = AnimationEngineId.Kap

    override fun supports(source: AnimationSource): Boolean = when (source) {
        is AnimationSource.Asset -> source.path.endsWith(KTX_SUFFIX, ignoreCase = true)
        is AnimationSource.File -> source.path.endsWith(KTX_SUFFIX, ignoreCase = true)
        is AnimationSource.Bytes -> true
    }

    override suspend fun open(source: AnimationSource): AnimationResource {
        val bytes = when (source) {
            is AnimationSource.Asset -> error("KAP JVM 不支持 Android asset: ${source.path}")
            is AnimationSource.File -> File(source.path).readBytes()
            is AnimationSource.Bytes -> source.bytes
        }
        val animation = withContext(Dispatchers.IO) { Ktx2Reader.read(bytes) }
        return KapJvmResource(animation)
    }

    private companion object {
        const val KTX_SUFFIX = ".ktx2"
    }
}
