package com.zxhhyj.kap.android

import android.content.Context
import com.zxhhyj.kap.core.Ktx2Info
import com.zxhhyj.kap.core.Ktx2Parser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Loaded KTX2 animation. Header-only until play; the Surface session reads the
 * payload (static) or the ring layers (streaming).
 *
 * 已加载的 KTX2 动画。加载时只解析头部；播放时由 Surface 会话读取完整数据
 * （静态）或环形层（流式）。
 */
public class KtxAnimationComposition internal constructor(
    public val source: KtxAnimationSource,
    public val info: Ktx2Info,
) {
    public val width: Int get() = info.width
    public val height: Int get() = info.height
    public val frameCount: Int get() = info.layerCount
    public val fps: Int get() = info.fps
    public val loop: Boolean get() = info.loop
    public val format: com.zxhhyj.kap.core.KtxFormat? get() = info.format
}

/**
 * Blocking header load. Prefer [loadKtxAnimationAsync] from UI code.
 *
 * 阻塞式头部加载。UI 代码优先使用 [loadKtxAnimationAsync]。
 */
public fun loadKtxAnimation(
    context: Context,
    source: KtxAnimationSource,
): KtxAnimationComposition {
    KtxNative.nativePrewarm()
    val reader = openKtxReader(context.applicationContext, source)
    return try {
        val prefix = readPrefix(reader)
        val info = Ktx2Parser.parse(prefix, requireLevelData = false)
        if (info.levelOffset > reader.size || info.levelLength > reader.size - info.levelOffset) {
            throw IOException(
                "KTX2 is truncated: file=${reader.size} " +
                    "levelOffset=${info.levelOffset} levelLength=${info.levelLength}",
            )
        }
        KtxAnimationComposition(source = source, info = info)
    } finally {
        reader.close()
    }
}

/** Header load on `Dispatchers.IO`, cancellable via `runInterruptible`. / IO 线程头部加载，可取消。 */
public suspend fun loadKtxAnimationAsync(
    context: Context,
    source: KtxAnimationSource,
): KtxAnimationComposition = withContext(Dispatchers.IO) {
    runInterruptible {
        loadKtxAnimation(context, source)
    }
}

/**
 * Prepare a Zstd level before mounting a Surface. Static uses an in-memory level;
 * Streaming creates/reuses an internal file with bounded-memory decompression.
 *
 * This is data preparation only; it does not create a Surface or upload textures.
 *
 * 按播放模式预解压：Static 使用内存，Streaming 使用应用内部缓存文件。只做数据准备，
 * 不创建 Surface、不上传纹理。
 */
public fun prewarmKtxLevel(
    context: Context,
    source: KtxAnimationSource,
    playbackMode: KtxPlaybackMode = KtxPlaybackMode.Static,
): Boolean {
    val appContext = context.applicationContext
    val composition = loadKtxAnimation(appContext, source)
    val info = composition.info
    if (!info.isZstd) return false
    if (playbackMode is KtxPlaybackMode.Streaming) {
        // The cold-start experiment prepares its preview on demand; prewarming
        // the whole level here would hide the very gate being measured.
        if (KtxDebugOptions.load(appContext).earlyCache) return false
        KtxLevelFileCache.open(appContext, composition).use { return true }
    }
    if (info.uncompressedLength > Int.MAX_VALUE) return false
    val raw = KtxDecodedCache.getOrLoad(source) {
        val compressed = readKtxLevel(appContext, source, info)
        KtxNative.nativeDecompressZstd(
            compressed,
            info.uncompressedLength.toInt(),
        )
    }
    return raw != null
}

/** IO-thread variant of [prewarmKtxLevel]. / [prewarmKtxLevel] 的 IO 线程版本。 */
public suspend fun prewarmKtxLevelAsync(
    context: Context,
    source: KtxAnimationSource,
    playbackMode: KtxPlaybackMode = KtxPlaybackMode.Static,
): Boolean = withContext(Dispatchers.IO) {
    runInterruptible {
        prewarmKtxLevel(context.applicationContext, source, playbackMode)
    }
}

internal fun readKtxLevel(
    context: Context,
    source: KtxAnimationSource,
    info: Ktx2Info,
): ByteArray {
    if (info.levelLength > Int.MAX_VALUE) {
        throw IOException("KTX2 level is too large to read")
    }
    val reader = openKtxReader(context.applicationContext, source)
    return try {
        val bytes = ByteArray(info.levelLength.toInt())
        reader.readAt(info.levelOffset, bytes, 0, bytes.size)
        bytes
    } finally {
        reader.close()
    }
}

internal fun readPrefix(reader: KtxByteReader): ByteArray {
    val header = ByteArray(Ktx2Parser.HEADER_BYTES)
    reader.readAt(0, header, 0, header.size)
    val length = Ktx2Parser.keyValuePrefixLength(header)
    if (length == header.size) return header
    requireKtxRange(reader.size, 0L, length.toLong())
    val prefix = ByteArray(length)
    reader.readAt(0, prefix, 0, prefix.size)
    return prefix
}
