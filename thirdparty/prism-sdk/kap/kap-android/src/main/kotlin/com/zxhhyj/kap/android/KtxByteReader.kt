package com.zxhhyj.kap.android

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.Closeable
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.IOException
import java.io.InterruptedIOException

/** Random access over a KTX2 file / asset. / KTX2 文件或 asset 的随机读取。 */
internal interface KtxByteReader : Closeable {
    val size: Long

    fun readAt(offset: Long, destination: ByteArray, destinationOffset: Int, length: Int)

    /** 独立的有界描述符；读取器关闭后仍由调用方持有。 / Independent bounded descriptor owned by the caller. */
    fun openNativeInput(offset: Long = 0L, length: Long = size): KtxNativeInput

    fun readAll(): ByteArray {
        require(size <= Int.MAX_VALUE) { "KTX2 is too large to load into one ByteArray" }
        val bytes = ByteArray(size.toInt())
        readAt(0, bytes, 0, bytes.size)
        return bytes
    }
}

/** File-backed reader. / 文件读取器。 */
internal class FileKtxReader(path: String) : KtxByteReader {
    private val stream = FileInputStream(path)

    override val size: Long get() = stream.channel.size()

    override fun readAt(
        offset: Long,
        destination: ByteArray,
        destinationOffset: Int,
        length: Int,
    ) {
        requireKtxRead(size, offset, destination, destinationOffset, length)
        readFullyAt(stream.fd, offset, destination, destinationOffset, length)
    }

    override fun close() {
        stream.close()
    }

    override fun openNativeInput(offset: Long, length: Long): KtxNativeInput {
        requireKtxRange(size, offset, length)
        return KtxNativeInput(ParcelFileDescriptor.dup(stream.fd), offset, length)
    }
}

/**
 * Asset reader. The stream is created by [android.content.res.AssetFileDescriptor.createInputStream]
 * so the descriptor stays reachable for the whole read session instead of being
 * finalized underneath the channel.
 *
 * asset 读取器。通过 `createInputStream()` 创建流，保证 AssetFileDescriptor 在整个
 * 读取期间可达，不会被 finalizer 提前关闭。
 */
internal class AssetKtxReader(private val descriptor: AssetFileDescriptor) : KtxByteReader {
    constructor(context: Context, path: String) : this(context.assets.openFd(path))

    private val stream = descriptor.createInputStream() as FileInputStream
    private val base = descriptor.startOffset

    override val size: Long get() = descriptor.length

    override fun readAt(
        offset: Long,
        destination: ByteArray,
        destinationOffset: Int,
        length: Int,
    ) {
        requireKtxRead(size, offset, destination, destinationOffset, length)
        readFullyAt(stream.fd, absoluteOffset(offset, length.toLong()), destination, destinationOffset, length)
    }

    override fun close() {
        stream.close()
    }

    override fun openNativeInput(offset: Long, length: Long): KtxNativeInput {
        requireKtxRange(size, offset, length)
        val absolute = absoluteOffset(offset, length)
        return KtxNativeInput(ParcelFileDescriptor.dup(stream.fd), absolute, length)
    }

    private fun absoluteOffset(offset: Long, length: Long): Long {
        require(base >= 0L && offset <= Long.MAX_VALUE - base && length <= Long.MAX_VALUE - base - offset) {
            "KTX2 asset range overflows descriptor offsets"
        }
        return base + offset
    }
}

/** Bounds are relative to the asset, not the containing APK. Never closes during a present. */
internal class KtxNativeInput(
    private val descriptor: ParcelFileDescriptor,
    private val baseOffset: Long,
    private val size: Long,
) : Closeable {
    private var frameBuffer: ByteArray? = null

    /** 从独立描述符读取逻辑区域，不改变共享文件位置。 / Reads the owned descriptor without changing file position. */
    fun readAt(offset: Long, destination: ByteArray, destinationOffset: Int, length: Int) {
        requireKtxRead(size, offset, destination, destinationOffset, length)
        readFullyAt(descriptor.fileDescriptor, baseOffset + offset, destination, destinationOffset, length)
    }

    /** 对完整逻辑输入做原有 Zstd 文件解压。 / Runs the existing file Zstd decoder on this logical input. */
    fun decompressZstd(outputFd: Int, decodedLength: Long): Boolean =
        KtxNative.nativeDecompressZstdToFile(descriptor.fd, baseOffset, size, outputFd, decodedLength)

    /** 对逻辑输入做原有 Zstd 前缀解压。 / Runs the existing prefix Zstd decoder on this logical input. */
    fun decompressZstdPrefix(outputFd: Int, decodedLength: Long, prefixLength: Long): Boolean =
        KtxNative.nativeDecompressZstdPrefix(descriptor.fd, baseOffset, size, outputFd, decodedLength, prefixLength)

    fun upload(handle: Long, format: Int, width: Int, height: Int, layers: Int, offset: Long, length: Int): Boolean {
        requireKtxRange(size, offset, length.toLong())
        return KtxNative.nativeSetTextureArrayFile(handle, format, width, height, layers,
            descriptor.fd, baseOffset + offset, length)
    }

    fun present(handle: Long, slot: Int, offset: Long, length: Int, legacy: Boolean = false): Boolean {
        requireKtxRange(size, offset, length.toLong())
        if (legacy) {
            val bytes = frameBuffer?.takeIf { it.size == length } ?: ByteArray(length).also { frameBuffer = it }
            readAt(offset, bytes, 0, length)
            return KtxNative.nativePresentFrameBytes(handle, slot, bytes)
        }
        return KtxNative.nativePresentFrameFile(handle, slot, descriptor.fd, baseOffset + offset, length)
    }

    override fun close() = descriptor.close()
}

// FileChannel's heap-buffer reads allocate/cache a temporary direct buffer on
// every worker thread. Initial ring uploads left 1.4-3.7 MiB buffers in those
// thread-local caches. pread fills the player's existing array directly.
private fun readFullyAt(
    fd: FileDescriptor,
    offset: Long,
    destination: ByteArray,
    destinationOffset: Int,
    length: Int,
) {
    var done = 0
    while (done < length) {
        val read = try {
            Os.pread(fd, destination, destinationOffset + done, length - done, offset + done)
        } catch (e: ErrnoException) {
            if (e.errno != OsConstants.EINTR) throw IOException("KTX2 read failed", e)
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("KTX2 read interrupted")
            continue
        }
        if (read <= 0) throw IOException("unexpected end of KTX2 at ${offset + done}")
        done += read
    }
}

internal fun openKtxReader(context: Context, source: KtxAnimationSource): KtxByteReader =
    when (source) {
        is KtxAnimationSource.Asset -> AssetKtxReader(context, source.path)
        is KtxAnimationSource.File -> FileKtxReader(source.path)
        is KtxAnimationSource.Region -> {
            val reader = openKtxReader(context, source.source)
            var owned = false
            try {
                RegionKtxReader(reader, source.offset, source.length).also { owned = true }
            } finally {
                if (!owned) reader.close()
            }
        }
    }
