package com.zxhhyj.vap.decode

import android.os.ParcelFileDescriptor
import com.zxhhyj.vap.player.VapSource
import java.io.Closeable
import java.io.FileDescriptor
import java.io.FileInputStream

/** 持有海报/元数据本次打开的独占句柄及物理区间。 / Owns this poster/metadata opening's handle and physical region. */
internal class VapAndroidSourceRegion(
    val descriptor: FileDescriptor,
    val offset: Long,
    val length: Long,
    private val owner: Closeable,
) : Closeable {
    fun openMp4Source(): VapFileChannelMp4Source {
        val channel = ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(descriptor)).channel
        var retained = false
        try {
            return VapFileChannelMp4Source(channel, offset, length).also { retained = true }
        } finally {
            if (!retained) channel.close()
        }
    }

    override fun close() = owner.close()
}

/** 区间以原文件/asset 为原点；不解包、不复制、不改变共享 seek。 / Resolves relative file/asset bounds without extraction, copies, or shared seeks. */
internal fun openVapAndroidSourceRegion(source: VapSource): VapAndroidSourceRegion {
    val base = if (source is VapSource.Region) source.source else source
    val relativeOffset = (source as? VapSource.Region)?.offset ?: 0L
    val requestedLength = (source as? VapSource.Region)?.length
    when (base) {
        is VapSource.AbsolutePath -> {
            val input = FileInputStream(base.path)
            var retained = false
            try {
                val length = requestedLength ?: input.channel.size()
                requireVapRegionBounds(relativeOffset, length, input.channel.size())
                return VapAndroidSourceRegion(input.fd, relativeOffset, length, input).also { retained = true }
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
                requireVapRegionBounds(relativeOffset, length, available)
                require(start >= 0L && start <= Long.MAX_VALUE - relativeOffset - length) { "Asset region end overflows Long" }
                return VapAndroidSourceRegion(asset.fileDescriptor, start + relativeOffset, length, asset).also { retained = true }
            } finally {
                if (!retained) asset.close()
            }
        }
        is VapSource.Bytes, is VapSource.Region -> error("File region requires an AbsolutePath or Asset source")
    }
}
