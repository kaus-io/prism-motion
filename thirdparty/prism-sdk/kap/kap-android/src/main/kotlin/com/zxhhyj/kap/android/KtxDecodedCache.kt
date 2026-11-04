package com.zxhhyj.kap.android

/**
 * Process-wide cache for pre-decoded Zstd levels. A single lock also makes the
 * prewarm and the first Surface upload share one decompression instead of racing
 * two 36MB decodes.
 *
 * 进程级预解压缓存。同一把锁让预解压与首次 Surface 上传共享一次解压，避免并发做两次
 * 36MB 解码。
 */
internal object KtxDecodedCache {
    private val lock = Any()
    private val entries = HashMap<KtxAnimationSource, ByteArray>()

    /** Returns cached bytes or runs [loader] once while holding the cache lock. / 命中缓存或持锁执行一次 loader。 */
    fun getOrLoad(source: KtxAnimationSource, loader: () -> ByteArray?): ByteArray? =
        synchronized(lock) {
            entries[source] ?: loader()?.also { entries[source] = it }
        }

    fun remove(source: KtxAnimationSource) {
        synchronized(lock) {
            entries.remove(source)
        }
    }

    /** Transfers a prewarmed level to its uploader without retaining another reference. */
    fun take(source: KtxAnimationSource): ByteArray? = synchronized(lock) {
        entries.remove(source)
    }

}
