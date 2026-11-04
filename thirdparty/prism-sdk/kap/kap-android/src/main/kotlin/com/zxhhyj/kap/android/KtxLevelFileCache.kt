package com.zxhhyj.kap.android

import android.content.Context
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.IOException
import java.io.FileNotFoundException
import java.security.MessageDigest

/** Raw ASTC/RGBA level cache, not a second KTX2. All data stays in internal cacheDir. */
internal object KtxLevelFileCache {
    private val lock = Any()

    // Serializing preparation bounds peak decoder memory and prevents duplicate
    // decoding/publication. Playback reads do not hold this lock.
    fun open(context: Context, composition: KtxAnimationComposition): KtxByteReader {
        if (!KtxDebugOptions.load(context).fastCacheHit) {
            return synchronized(lock) { prepare(context, composition) }
        }
        return prepare(context, composition, fastHit = true)
    }

    private fun prepare(context: Context, composition: KtxAnimationComposition, fastHit: Boolean = false): KtxByteReader {
        checkInterrupted()
        val source = composition.source
        val info = composition.info
        val location = location(context, composition)
        val directory = location.directory
        val sourceKey = location.sourceKey
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Cannot create KTX level cache")
        }
        val target = location.target
        if (fastHit) {
            if (target.isFile && target.length() == info.uncompressedLength) {
                try {
                    return FileKtxReader(target.path)
                } catch (_: FileNotFoundException) {
                    // A different source version may have unlinked it before open.
                }
            }
            // Preparation/publication remains globally serialized and rechecks the
            // version under lock. Only an already-published hit bypasses the lock.
            return synchronized(lock) { prepare(context, composition) }
        }
        if (!target.isFile || target.length() != info.uncompressedLength) {
            val temporary = File.createTempFile("$sourceKey-", ".tmp", directory)
            try {
                ParcelFileDescriptor.open(
                    temporary,
                    ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE,
                ).use { output ->
                    val ok = openKtxReader(context, source).use { reader ->
                        reader.openNativeInput(info.levelOffset, info.levelLength).use { input ->
                            input.decompressZstd(output.fd, info.uncompressedLength)
                        }
                    }
                    checkInterrupted()
                    if (!ok) throw IOException("Zstd level decode failed: $source")
                }
                if (temporary.length() != info.uncompressedLength || !temporary.renameTo(target)) {
                    throw IOException("Cannot publish KTX level cache: $source")
                }
                // Keep only the current version for this source. Open old readers
                // remain valid on Android/Linux when their file is unlinked.
                directory.listFiles()?.filter {
                    it.name.startsWith("$sourceKey-") && it != target
                }?.forEach { it.delete() }
            } finally {
                temporary.delete()
            }
        }
        return FileKtxReader(target.path)
    }

    fun openPrepared(context: Context, composition: KtxAnimationComposition): KtxByteReader? {
        checkInterrupted()
        val target = location(context, composition).target
        if (!target.isFile || target.length() != composition.info.uncompressedLength) return null
        return try {
            FileKtxReader(target.path)
        } catch (_: FileNotFoundException) {
            null
        }
    }

    private data class Location(val directory: File, val target: File, val sourceKey: String)

    private fun location(context: Context, composition: KtxAnimationComposition): Location {
        val source = composition.source
        val info = composition.info
        val identity = sourceIdentity(context, source)
        val sourceKey = digest(source.toString())
        val versionKey = digest("v1:$identity:${info.levelOffset}:${info.levelLength}:${info.uncompressedLength}")
        val directory = cacheDirectory(context)
        return Location(directory, File(directory, "$sourceKey-$versionKey.level"), sourceKey)
    }

    private fun cacheDirectory(context: Context): File =
        File(context.applicationContext.cacheDir, "kap-levels")

    private fun sourceIdentity(context: Context, source: KtxAnimationSource): String = when (source) {
        is KtxAnimationSource.Asset -> {
            val apk = File(context.applicationInfo.sourceDir)
            "asset:${source.path}:${apk.path}:${apk.length()}:${apk.lastModified()}"
        }
        is KtxAnimationSource.File -> {
            val file = File(source.path).canonicalFile
            "file:${file.path}:${file.length()}:${file.lastModified()}"
        }
        is KtxAnimationSource.Region ->
            "region:${sourceIdentity(context, source.source)}:${source.offset}:${source.length}"
    }

    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("KTX decode interrupted")
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
