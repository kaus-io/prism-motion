package com.zxhhyj.kap.android

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.zxhhyj.kap.core.Ktx2Ring
import java.io.Closeable
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

/**
 * Owns one native Vulkan engine and the KTX2 payload across Surface attachments.
 *
 * Lifecycle contract: [attach] / [upload] / [present] are called from one playback
 * coroutine. Operations and [close] are serialized; [detachOutput] releases only
 * the output, while [close] also releases the uploaded texture. The JNI handle
 * registry additionally makes late callbacks after destruction harmless.
 *
 * Surface 重建时保留引擎与纹理；输出解绑和最终释放分离，所有操作串行化。
 */
public class KtxSurfacePlayer(
    context: Context,
    private val composition: KtxAnimationComposition,
    private val playbackMode: KtxPlaybackMode,
) : Closeable {
    private val appContext = context.applicationContext
    private val debug = KtxDebugOptions.load(appContext)
    private var handle: Long = 0L
    private var input: KtxNativeInput? = null
    private var ringSize: Int = 0
    private var residentFrames: KtxResidentFrames? = null
    private var pendingCache = false
    private var preparationError: Exception? = null
    private var preparationScope: CoroutineScope? = null
    private var uploaded: Boolean = false
    @Volatile
    private var closed: Boolean = false
    private val info = composition.info

    @Synchronized
    fun attach(surface: Surface, width: Int, height: Int): Boolean {
        if (closed) return false
        if (handle == 0L) {
            handle = KtxNative.nativeCreate()
            if (handle != 0L) KtxNative.nativeSetNarrowTeardown(handle, debug.narrowTeardown)
            val source = composition.source.let { if (it is KtxAnimationSource.Region) it.source else it }
            val sourcePath = when (source) {
                is KtxAnimationSource.Asset -> source.path
                is KtxAnimationSource.File -> source.path
                is KtxAnimationSource.Region -> error("Nested KTX2 regions are not supported")
            }
            if (handle != 0L && debug.nativeHue && debug.filterTest && sourcePath == debug.filterSource) {
                if (!KtxNative.nativeSetHueRows(handle, KtxHueRows.rotate(-150f))) {
                    throw IOException("native hue configuration failed")
                }
            }
        }
        if (handle == 0L) {
            Log.e(TAG, "nativeCreate failed")
            return false
        }
        val ok = KtxNative.nativeSetOutputSurface(handle, surface, width, height)
        if (!ok) Log.e(TAG, "nativeSetOutputSurface failed ${width}x$height")
        return ok
    }

    /**
     * Uploads the static array or the initial ring. Must run off the main thread.
     * 上传静态数组或初始环形层；必须在非主线程执行。
     */
    @Synchronized
    fun upload() {
        check(!closed) { "player is closed" }
        check(handle != 0L) { "attach() must be called before upload()" }
        // Keep the texture across Surface recreations; a second upload would copy
        // the same 14-36MB payload again for no benefit.
        // Surface 重建后保留纹理；重复 upload 只会白拷一遍 14-36MB 数据。
        if (uploaded) return
        when (val mode = playbackMode) {
            KtxPlaybackMode.Static -> uploadStatic()
            is KtxPlaybackMode.Streaming -> uploadStreaming(mode.ringSize)
        }
        uploaded = true
    }

    private fun uploadStatic() {
        if (info.isZstd) {
            val format = info.format?.vkFormat
                ?: throw IOException("unsupported KTX2 format ${info.vkFormat}")
            val decodeStarted = SystemClock.elapsedRealtime()
            if (info.uncompressedLength > Int.MAX_VALUE) {
                throw IOException("KTX2 level is too large to decompress in memory")
            }
            val raw = KtxDecodedCache.take(composition.source)
            val decodeMs = SystemClock.elapsedRealtime() - decodeStarted
            val uploadStarted = SystemClock.elapsedRealtime()
            // Consume startup prewarm when available. Otherwise decompress in native
            // memory: static playback never needs a full decoded Java array.
            val uploaded = if (raw != null) {
                KtxNative.nativeSetTextureArrayRaw(
                    handle,
                    format,
                    info.width,
                    info.height,
                    info.layerCount,
                    raw,
                )
            } else {
                KtxNative.nativeSetTextureArrayZstd(
                    handle,
                    format,
                    info.width,
                    info.height,
                    info.layerCount,
                    readCompressedLevel(),
                    info.uncompressedLength.toInt(),
                )
            }
            if (!uploaded) throw IOException("static Zstd texture upload failed")
            val uploadMs = SystemClock.elapsedRealtime() - uploadStarted
            Log.i(
                TAG,
                "static zstd upload decoded=${info.uncompressedLength} layers=${info.layerCount} " +
                    "decode_or_wait_ms=$decodeMs upload_ms=$uploadMs",
            )
            return
        }
        val source = openKtxReader(appContext, composition.source)
        try {
            val bytes = source.readAll()
            if (!KtxNative.nativeSetTextureArray(handle, bytes)) {
                throw IOException("nativeSetTextureArray failed (${bytes.size} bytes)")
            }
            Log.i(TAG, "static upload ${bytes.size} B layers=${info.layerCount}")
        } finally {
            source.close()
        }
    }

    private fun readCompressedLevel(): ByteArray =
        readKtxLevel(appContext, composition.source, info)

    private fun uploadStreaming(requestedRing: Int) {
        val ring = (if (requestedRing == 1) 1 else debug.ringSize ?: requestedRing).coerceIn(1, info.layerCount)
        if (info.isZstd) {
            val format = info.format?.vkFormat
                ?: throw IOException("unsupported KTX2 format ${info.vkFormat}")
            val initialBytes = ring.toLong() * info.layerBytes
            if (initialBytes > Int.MAX_VALUE) throw IOException("KTX2 ring is too large")
            var prefixOnly = false
            val source = if (debug.earlyCache) {
                KtxLevelFileCache.openPrepared(appContext, composition)
                    ?: KtxPrefixFile.open(appContext, composition, initialBytes).also { prefixOnly = true }
            } else {
                KtxLevelFileCache.open(appContext, composition)
            }
            try {
                if (debug.initialFileUpload) {
                    uploadInitialFile(source, format, ring, 0L, initialBytes.toInt())
                } else {
                    val initial = ByteArray(initialBytes.toInt())
                    source.readAt(0, initial, 0, initial.size)
                    if (!KtxNative.nativeSetTextureArrayRaw(
                            handle, format, info.width, info.height, ring, initial,
                        )
                    ) {
                        throw IOException("nativeSetTextureArrayRaw failed for zstd ring=$ring")
                    }
                    input = source.openNativeInput()
                }
                ringSize = ring
                if (debug.residentFrames) residentFrames = KtxResidentFrames(ring)
                if (prefixOnly) startFullPreparation()
                Log.i(TAG, "file-backed ring upload ring=$ring bytes=$initialBytes frames=${info.layerCount}")
            } finally {
                source.close()
            }
            return
        }
        val source = openKtxReader(appContext, composition.source)
        try {
            if (debug.initialFileUpload) {
                uploadInitialFile(source, info.vkFormat, ring, info.levelOffset, info.layerBytes * ring)
            } else {
                val prefix = readPrefix(source)
                val header = Ktx2Ring.header(prefix, info, ring)
                val ringBytes = info.layerBytes * ring
                val data = ByteArray(header.size + ringBytes)
                System.arraycopy(header, 0, data, 0, header.size)
                source.readAt(info.levelOffset, data, header.size, ringBytes)
                if (!KtxNative.nativeSetTextureArray(handle, data)) {
                    throw IOException("nativeSetTextureArray failed for ring=$ring")
                }
                input = source.openNativeInput()
            }
            ringSize = ring
            if (debug.residentFrames) residentFrames = KtxResidentFrames(ring)
            Log.i(TAG, "ring upload ring=$ring frameBytes=${info.layerBytes * ring} frames=${info.layerCount}")
        } finally {
            source.close()
        }
    }

    private fun uploadInitialFile(source: KtxByteReader, format: Int, ring: Int, offset: Long, length: Int) {
        val nativeInput = source.openNativeInput()
        var keep = false
        try {
            if (!nativeInput.upload(handle, format, info.width, info.height, ring, offset, length)) {
                throw IOException("file-backed initial upload failed")
            }
            input = nativeInput
            keep = true
        } finally {
            if (!keep) nativeInput.close()
        }
    }

    private fun startFullPreparation() {
        pendingCache = true
        if (debug.measure) Log.i(TAG, "progressive cache start source=${composition.source}")
        val scope = CoroutineScope(Dispatchers.IO)
        preparationScope = scope
        scope.launch {
            try {
                // Open, transfer ownership, and close inside the interruptible
                // block: cancellation cannot discard an unowned returned reader.
                runInterruptible {
                    KtxLevelFileCache.open(appContext, composition).use { reader ->
                        val prepared = reader.openNativeInput()
                        var keep = false
                        try {
                            synchronized(this@KtxSurfacePlayer) {
                                if (!closed) {
                                    input?.close()
                                    input = prepared
                                    pendingCache = false
                                    keep = true
                                    Log.i(TAG, "progressive cache ready source=${composition.source}")
                                }
                            }
                        } finally {
                            if (!keep) prepared.close()
                        }
                    }
                }
            } catch (e: CancellationException) {
                if (debug.measure) Log.i(TAG, "progressive cache cancelled source=${composition.source}")
                throw e
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                synchronized(this@KtxSurfacePlayer) {
                    if (!closed) preparationError = e
                }
            }
        }
    }

    /**
     * Presents [frame] (absolute timeline index). Returns false when the surface
     * is not ready yet; callers may retry on the next tick.
     *
     * 呈现绝对时间轴上的 [frame]；Surface 尚未就绪时返回 false，调用方可下一拍重试。
     */
    @Synchronized
    fun present(frame: Int): Boolean {
        if (closed || !uploaded || handle == 0L) return false
        preparationError?.let { throw IOException("KTX background cache preparation failed", it) }
        return when (playbackMode) {
            KtxPlaybackMode.Static -> KtxNative.nativePresentFrame(
                handle,
                frame % info.layerCount,
            )
            is KtxPlaybackMode.Streaming -> {
                val ring = ringSize
                val layer = frame % info.layerCount
                if (pendingCache) {
                    // The prefix's ring is already uploaded. Do not read beyond it
                    // or draw uninitialized layers while the full decode catches up.
                    return layer < ring && KtxNative.nativePresentFrame(handle, layer)
                }
                val source = input ?: return false
                val offset = if (info.isZstd) info.decodedLayerOffset(layer) else info.layerOffset(layer)
                residentFrames?.present(
                    layer,
                    { slot -> KtxNative.nativePresentFrame(handle, slot) },
                    { slot -> source.present(handle, slot, offset, info.layerBytes, debug.legacyFrameUpload) },
                ) ?: source.present(handle, layer % ring, offset, info.layerBytes, debug.legacyFrameUpload)
            }
        }
    }

    /**
     * Releases the swapchain / ANativeWindow but keeps the uploaded texture array.
     * Reattaching a new Surface then only recreates the swapchain.
     *
     * 释放交换链 / ANativeWindow，但保留已上传的纹理数组；重新挂 Surface 只需重建交换链。
     */
    @Synchronized
    fun detachOutput() {
        if (closed) return
        val current = handle
        if (current != 0L) {
            KtxNative.nativeClearOutputSurface(current)
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        preparationScope?.cancel()
        preparationScope = null
        val current = handle
        handle = 0L
        if (current != 0L) {
            KtxNative.nativeClearOutputSurface(current)
            KtxNative.nativeDestroy(current)
        }
        input?.close()
        input = null
        uploaded = false
        ringSize = 0
        residentFrames = null
    }

    private companion object {
        const val TAG = "KtxAnim"
    }
}
