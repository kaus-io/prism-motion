package com.zxhhyj.vap.decode

import com.zxhhyj.vap.player.VapConfig
import com.zxhhyj.vap.player.VapSource

/**
 * Parses VAP metadata from the MP4 at [path] using the current platform's file I/O.
 *
 * 使用当前平台的文件 I/O，从 [path] 指向的 MP4 中解析 VAP 元数据。
 */
public expect fun parseMp4File(path: String): VapConfig

/**
 * Parses VAP metadata from an Android `assets/` path. On Android this requires
 * `VapAndroid.install(context)` first. JVM throws.
 *
 * 从 Android `assets/` 路径解析 VAP 元数据。Android 上需要先
 * `VapAndroid.install(context)`。JVM 会抛错。
 */
public expect fun parseMp4Asset(assetPath: String): VapConfig

/** 仅解析指定只读区间内的 VAP 元数据。 / Parses VAP metadata only within the specified read-only region. */
public expect fun parseMp4Region(region: VapSource.Region): VapConfig

/**
 * Parses VAP metadata from in-memory MP4 bytes. Cached by buffer identity.
 *
 * 从内存中的 MP4 字节解析 VAP 元数据。按缓冲区 identity 缓存。
 */
public fun parseMp4Bytes(data: ByteArray): VapConfig =
    VapConfigMemoryCache.getOrPut(VapConfigMemoryCache.bytesKey(data)) {
        VapcParser.parseMp4(data)
    }
