package com.zxhhyj.vap.decode

import android.content.res.AssetFileDescriptor
import android.os.ParcelFileDescriptor
import com.zxhhyj.vap.player.VapConfig
import com.zxhhyj.vap.player.VapSource

/** Android 文件型 VAP 元数据解析器。 / Android file-backed VAP metadata parser. */
public actual fun parseMp4File(path: String): VapConfig =
    VapConfigMemoryCache.getOrPut(VapConfigMemoryCache.fileKey(path)) {
        openVapFileMp4Source(path).use { VapcParser.parseMp4(it) }
    }

public actual fun parseMp4Asset(assetPath: String): VapConfig =
    VapConfigMemoryCache.getOrPut(VapConfigMemoryCache.assetKey(assetPath)) {
        VapAndroid.requireContext().assets.openFd(assetPath).use { parseMp4AssetFileDescriptor(it) }
    }

public actual fun parseMp4Region(region: VapSource.Region): VapConfig =
    openVapAndroidSourceRegion(region).use { opened ->
        opened.openMp4Source().use { mp4 ->
            VapConfigMemoryCache.getOrPut(VapConfigMemoryCache.key(region)) { VapcParser.parseMp4(mp4) }
        }
    }

internal fun parseMp4AssetFileDescriptor(afd: AssetFileDescriptor): VapConfig {
    val channel = ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(afd.fileDescriptor)).channel
    channel.use {
        val start = afd.startOffset
        val length = if (afd.declaredLength >= 0L) afd.declaredLength else channel.size() - start
        return VapcParser.parseMp4(VapFileChannelMp4Source(channel, start, length))
    }
}
