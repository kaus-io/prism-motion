package com.zxhhyj.vap.decode

import android.media.MediaExtractor
import com.zxhhyj.vap.player.VapSource
import java.io.Closeable
import java.io.FileDescriptor
import java.io.FileInputStream

/** 会话独占的只读 fd；保留到 extractor/native 会话释放后。 / Session-owned read-only fd, retained until extractor/native session release. */
internal class AndroidVapExtractorSource(
    private val descriptor: FileDescriptor,
    private val offset: Long,
    private val length: Long,
    private val owner: Closeable,
) : Closeable {
    fun bind(extractor: MediaExtractor) {
        extractor.setDataSource(descriptor, offset, length)
    }

    override fun close() = owner.close()
}

/** 打开新句柄并校验逻辑区间，不复制 payload。 / Opens a fresh handle and checks the logical region without copying the payload. */
internal fun openAndroidVapExtractorSource(source: VapSource): AndroidVapExtractorSource {
    val base = if (source is VapSource.Region) source.source else source
    val offset = (source as? VapSource.Region)?.offset ?: 0L
    val requestedLength = (source as? VapSource.Region)?.length
    when (base) {
        is VapSource.AbsolutePath -> {
            val input = FileInputStream(base.path)
            var retained = false
            try {
                val available = input.channel.size()
                val length = requestedLength ?: available
                checkExtractorRegionBounds(offset, length, available)
                return AndroidVapExtractorSource(input.fd, offset, length, input).also { retained = true }
            } finally {
                if (!retained) input.close()
            }
        }
        is VapSource.Asset -> {
            val asset = VapAndroid.requireContext().assets.openFd(base.assetPath)
            var retained = false
            try {
                val start = asset.startOffset
                val available = if (asset.declaredLength >= 0L) asset.declaredLength else asset.parcelFileDescriptor.statSize - start
                val length = requestedLength ?: available
                checkExtractorRegionBounds(offset, length, available)
                require(start >= 0L && start <= Long.MAX_VALUE - offset - length) { "Asset region end overflows Long" }
                return AndroidVapExtractorSource(asset.fileDescriptor, start + offset, length, asset).also { retained = true }
            } finally {
                if (!retained) asset.close()
            }
        }
        is VapSource.Bytes, is VapSource.Region -> error("File region requires an AbsolutePath or Asset source")
    }
}

private fun checkExtractorRegionBounds(offset: Long, length: Long, available: Long) {
    require(available >= 0L && offset >= 0L && length > 0L && offset <= available && length <= available - offset) {
        "MP4 region offset=$offset length=$length exceeds source length=$available"
    }
}
