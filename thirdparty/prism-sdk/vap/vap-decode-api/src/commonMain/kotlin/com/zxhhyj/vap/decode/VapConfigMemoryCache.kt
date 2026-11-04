package com.zxhhyj.vap.decode

import com.zxhhyj.vap.player.VapConfig
import com.zxhhyj.vap.player.VapSource
import java.io.File

/**
 * Process-level cache of parsed `vapc` headers. Keyed like Coil's mapping cache:
 * file path + length + mtime, or `asset:` / identity for in-memory bytes.
 *
 * 进程级 `vapc` 头缓存。对齐 Coil mapping cache：文件用 path + length + mtime，
 * assets 用 `asset:` 前缀，内存字节用 identity。
 */
public object VapConfigMemoryCache {
    private const val MaxEntries = 64
    private val lock = Any()
    private val memory = object : LinkedHashMap<String, VapConfig>(MaxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, VapConfig>?): Boolean {
            return size > MaxEntries
        }
    }

    /**
     * Cache key for [source]. Files include length/mtime so an in-place replace does not
     * keep a stale header.
     *
     * [source] 的缓存键。文件带上 length/mtime，避免同路径被覆盖后仍命中旧头。
     */
    public fun key(source: VapSource): String = when (source) {
        is VapSource.AbsolutePath -> fileKey(source.path)
        is VapSource.Asset -> assetKey(source.assetPath)
        is VapSource.Bytes -> bytesKey(source.data)
        is VapSource.Region -> "region:${source.offset}:${source.length}:${key(source.source)}"
    }

    /** Filesystem MP4. / 文件系统 MP4。 */
    public fun fileKey(path: String): String {
        val file = File(path)
        return "file:$path:${file.length()}:${file.lastModified()}"
    }

    /** Android `assets/` path. / Android `assets/` 路径。 */
    public fun assetKey(assetPath: String): String = "asset:$assetPath"

    /** In-memory MP4. Identity so wrap() shares a key. / 内存 MP4。用 identity，wrap() 可共享键。 */
    public fun bytesKey(data: ByteArray): String =
        "bytes:${data.size}:${System.identityHashCode(data)}"

    /** Memory lookup. / 内存查找。 */
    public fun get(key: String): VapConfig? = synchronized(lock) { memory[key] }

    /** Compute on miss, then store. / 未命中时计算并写入。 */
    public fun getOrPut(key: String, compute: () -> VapConfig): VapConfig {
        synchronized(lock) { memory[key] }?.let { return it }
        val value = compute()
        synchronized(lock) { memory[key] = value }
        return value
    }
}
