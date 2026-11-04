package com.zxhhyj.vap.decode

import com.zxhhyj.vap.player.VapSource
import java.io.FileInputStream

/** FFmpeg subfile 原生限制可见区间并保持可 seek，不解包或生成临时媒体文件。
 * / FFmpeg's native subfile protocol bounds the seekable input without extraction or temporary media files.
 */
internal fun jvmVapRegionInput(region: VapSource.Region): String {
    val base = region.source as? VapSource.AbsolutePath
        ?: error("VapSource.Region with an Asset source is Android-only")
    FileInputStream(base.path).channel.use { channel ->
        val available = channel.size()
        require(region.offset <= available && region.length <= available - region.offset) {
            "MP4 region offset=${region.offset} length=${region.length} exceeds source length=$available"
        }
    }
    return "subfile,,start,${region.offset},end,${region.offset + region.length},,:file:${base.path}"
}
