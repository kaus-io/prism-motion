package com.zxhhyj.prism.motion.engine.kap

import com.zxhhyj.prism.motion.AnimationEngine
import com.zxhhyj.prism.motion.AnimationEngineId
import com.zxhhyj.prism.motion.AnimationResource
import com.zxhhyj.prism.motion.AnimationSource
import com.zxhhyj.prism.motion.ktx.KtxFormat
import com.zxhhyj.prism.motion.ktx.KtxReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 解码后的一帧 RGBA 像素。 / One decoded RGBA frame. */
internal class KtxFrame(val width: Int, val height: Int, val rgba: ByteArray)

/** 一个 KTX2 动画的解码结果。 / Decoded KTX2 animation. */
internal class KtxAnimation(
    val width: Int,
    val height: Int,
    val fps: Int,
    val frames: List<KtxFrame>,
)

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
        val animation = withContext(Dispatchers.IO) { decode(bytes) }
        return KapJvmResource(animation)
    }

    private fun decode(bytes: ByteArray): KtxAnimation {
        val parsed = KtxReader.read(bytes)
        val frames = parsed.layers.mapIndexed { index, layer ->
            val rgba = when (parsed.format) {
                KtxFormat.RGBA8 -> layer
                null -> error("不支持的 vkFormat=${parsed.vkFormat}")
                else -> AstcDecoder.decode(layer, parsed.width, parsed.height, parsed.vkFormat)
            }
            if (rgba.size != parsed.width * parsed.height * 4) {
                error("第 $index 帧解码后大小不符")
            }
            KtxFrame(parsed.width, parsed.height, rgba)
        }
        return KtxAnimation(parsed.width, parsed.height, parsed.fps, frames)
    }

    private companion object {
        const val KTX_SUFFIX = ".ktx2"
    }
}
