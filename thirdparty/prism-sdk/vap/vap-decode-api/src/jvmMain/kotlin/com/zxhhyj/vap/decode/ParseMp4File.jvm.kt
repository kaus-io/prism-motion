package com.zxhhyj.vap.decode

import com.zxhhyj.vap.player.VapConfig
import com.zxhhyj.vap.player.VapSource

/** JVM 文件型 VAP 元数据解析器。 / JVM file-backed VAP metadata parser. */
public actual fun parseMp4File(path: String): VapConfig =
    VapConfigMemoryCache.getOrPut(VapConfigMemoryCache.fileKey(path)) {
        openVapFileMp4Source(path).use { VapcParser.parseMp4(it) }
    }

public actual fun parseMp4Asset(assetPath: String): VapConfig =
    error("VapSource.Asset is Android-only")

public actual fun parseMp4Region(region: VapSource.Region): VapConfig {
    val base = region.source as? VapSource.AbsolutePath
        ?: error("VapSource.Region with an Asset source is Android-only")
    return openVapFileMp4Source(base.path, region.offset, region.length).use { mp4 ->
        VapConfigMemoryCache.getOrPut(VapConfigMemoryCache.key(region)) { VapcParser.parseMp4(mp4) }
    }
}
