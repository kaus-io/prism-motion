package com.zxhhyj.kap.android

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.io.IOException

/** Private real KTX frames only. Never published as a validated full cache entry. */
internal object KtxPrefixFile {
    fun open(context: Context, composition: KtxAnimationComposition, length: Long): KtxByteReader {
        val info = composition.info
        val measured = KtxDebugOptions.load(context).measure
        val started = System.nanoTime()
        if (measured) Log.i("KtxAnim", "prefix decode start source=${composition.source} bytes=$length")
        val directory = File(context.cacheDir, "kap-prefix")
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Cannot create KTX prefix directory")
        }
        val file = File.createTempFile("prefix-", ".tmp", directory)
        try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_WRITE_ONLY).use { output ->
                val ok = openKtxReader(context, composition.source).use { reader ->
                    reader.openNativeInput(info.levelOffset, info.levelLength).use { input ->
                        input.decompressZstdPrefix(output.fd, info.uncompressedLength, length)
                    }
                }
                if (Thread.currentThread().isInterrupted) throw InterruptedException("KTX prefix cancelled")
                if (!ok || file.length() != length) throw IOException("KTX prefix decode failed")
            }
            return FileKtxReader(file.path).also {
                if (measured) Log.i("KtxAnim", "prefix decode ready source=${composition.source} ms=${(System.nanoTime() - started) / 1_000_000}")
            }
        } finally {
            // Linux keeps the already-open reader alive. There is no orphan prefix
            // path after cancellation, disposal, or successful full-cache handoff.
            file.delete()
        }
    }
}
