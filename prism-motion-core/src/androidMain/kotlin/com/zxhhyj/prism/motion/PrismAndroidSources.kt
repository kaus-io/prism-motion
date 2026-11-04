package com.zxhhyj.prism.motion

import android.content.Context
import android.content.res.AssetManager
import com.zxhhyj.prism.motion.format.PrismAssetDescriptor
import com.zxhhyj.prism.motion.format.PrismContainer
import com.zxhhyj.prism.motion.format.PrismPayloadKind
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

/** Android 的只读容器描述；不复制载荷，也不提取临时文件。 / Android container descriptors without payload copies or temporary extraction. */
public object PrismAndroidSources {
    private data class AssetKey(val manager: AssetManager, val path: String)
    private val assets = ConcurrentHashMap<AssetKey, PrismAssetDescriptor>()

    /** 只接受统一容器，再按载荷选择引擎；内存来源由后端显式声明能力。 / Dispatches Prism payloads with explicit backend support for in-memory sources. */
    public fun supports(context: Context, source: PrismMotionSource, kind: PrismPayloadKind, allowBytes: Boolean = true): Boolean {
        val isPrism = when (source) {
            is PrismMotionSource.Asset -> PrismMotionFormat.ofFileName(source.path) == PrismMotionFormat.Prism
            is PrismMotionSource.File -> PrismMotionFormat.ofFileName(source.path) == PrismMotionFormat.Prism
            is PrismMotionSource.Bytes -> allowBytes && PrismContainer.isPrism(source.bytes)
        }
        return isPrism && inspect(context, source).kind == kind
    }

    /** 在 IO 上调用；只缓存 APK 中不可变的头部，文件每次重读。 / Call on IO; caches immutable APK headers only and rereads mutable files. */
    public fun inspect(context: Context, source: PrismMotionSource): PrismAssetDescriptor = when (source) {
        is PrismMotionSource.Asset -> {
            requirePrismPath(source.path)
            assets.getOrPut(AssetKey(context.assets, source.path)) {
                context.assets.openFd(source.path).use { descriptor ->
                    val size = descriptor.length
                    val prefix = ByteArray(PrismContainer.PREFIX_SIZE)
                    descriptor.createInputStream().use { input ->
                        var read = 0
                        while (read < prefix.size) {
                            val count = input.read(prefix, read, prefix.size - read)
                            require(count > 0) { "Truncated Prism asset prefix" }
                            read += count
                        }
                    }
                    PrismContainer.readPrefix(prefix, size)
                }
            }
        }
        is PrismMotionSource.File -> {
            requirePrismPath(source.path)
            RandomAccessFile(source.path, "r").use { file ->
                val prefix = ByteArray(PrismContainer.PREFIX_SIZE)
                file.readFully(prefix)
                PrismContainer.readPrefix(prefix, file.length())
            }
        }
        is PrismMotionSource.Bytes -> PrismContainer.read(source.bytes)
    }

    private fun requirePrismPath(path: String) {
        require(PrismMotionFormat.ofFileName(path) == PrismMotionFormat.Prism) { "Only .prism resources are supported" }
    }
}
