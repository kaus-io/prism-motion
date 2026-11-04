package com.zxhhyj.kap.android

import android.view.Surface

/**
 * JNI bridge for `libkap_vk.so`. Handles are opaque and become no-ops after
 * [nativeDestroy], so a late Surface callback cannot crash the process.
 *
 * `libkap_vk.so` 的 JNI 桥。句柄是不透明 id，[nativeDestroy] 之后的迟到调用
 * 会安全地变成空操作，Surface 回调晚到也不会崩溃。
 */
internal object KtxNative {
    init {
        System.loadLibrary("kap_vk")
    }

    external fun nativePrewarm()

    external fun nativeCreate(): Long

    external fun nativeDestroy(handle: Long)
    external fun nativeSetNarrowTeardown(handle: Long, enabled: Boolean)
    external fun nativeSetHueRows(handle: Long, rows: FloatArray): Boolean
    external fun nativeSetTextureArrayFile(
        handle: Long, format: Int, width: Int, height: Int, layers: Int,
        fd: Int, offset: Long, length: Int,
    ): Boolean

    external fun nativeSetOutputSurface(
        handle: Long,
        surface: Surface,
        width: Int,
        height: Int,
    ): Boolean

    external fun nativeClearOutputSurface(handle: Long)

    external fun nativeSetTextureArray(handle: Long, data: ByteArray): Boolean

    external fun nativeSetTextureArrayRaw(
        handle: Long,
        format: Int,
        width: Int,
        height: Int,
        layers: Int,
        data: ByteArray,
    ): Boolean

    external fun nativeSetTextureArrayZstd(
        handle: Long,
        format: Int,
        width: Int,
        height: Int,
        layers: Int,
        compressed: ByteArray,
        uncompressedLength: Int,
    ): Boolean

    external fun nativeDecompressZstd(
        compressed: ByteArray,
        uncompressedLength: Int,
    ): ByteArray?

    external fun nativeDecompressZstdToFile(
        inputFd: Int,
        offset: Long,
        compressedLength: Long,
        outputFd: Int,
        decodedLength: Long,
    ): Boolean

    external fun nativeDecompressZstdPrefix(
        inputFd: Int, offset: Long, compressedLength: Long, outputFd: Int,
        decodedLength: Long, prefixLength: Long,
    ): Boolean

    external fun nativePresentFrame(handle: Long, layer: Int): Boolean
    external fun nativePresentFrameBytes(handle: Long, layer: Int, bytes: ByteArray): Boolean

    external fun nativePresentFrameFile(
        handle: Long, layer: Int, fd: Int, offset: Long, length: Int,
    ): Boolean

    external fun nativeReady(handle: Long): Boolean
}
