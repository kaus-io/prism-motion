package com.zxhhyj.prism.motion.demo

import com.zxhhyj.prism.motion.PrismMotionEngineId
import java.io.File

/**
 * 一个演示资产：引擎 × 透明度 × 尺寸 × 质量，全部从文件名解析。
 * One demo asset: engine x transparency x size x quality, parsed from the file name.
 *
 * 命名约定 / naming: `{alpha|opaque}-{size}-{quality}.{mp4|ktx}`，例如
 * `alpha-512-b6000.mp4`、`opaque-256-rgba8.ktx`。
 */
data class AssetInfo(
    val engine: PrismMotionEngineId,
    val hasAlpha: Boolean,
    val size: Int,
    val quality: String,
    val file: File,
) {
    val label: String
        get() = "${if (hasAlpha) "透明" else "不透明"} ${engine.name} ${size}px $quality"

    val variant: String
        get() = if (hasAlpha) "alpha" else "opaque"
}

/** 资产目录；可用 `-Dprism.demo.data=` 覆盖。 / Asset directory, overridable by a system property. */
val demoDataDir: File = run {
    System.getProperty("prism.demo.data")?.let { return@run File(it) }
    val local = File("demo-data")
    if (local.isDirectory) local else File("../demo-data")
}

/** 扫描目录里符合命名约定的资产。 / Scans the directory for assets matching the naming convention. */
fun discoverAssets(dir: File = demoDataDir): List<AssetInfo> {
    if (!dir.isDirectory) return emptyList()
    return dir.listFiles { file ->
        file.isFile && (file.name.endsWith(".mp4") || file.name.endsWith(".ktx"))
    }.orEmpty().mapNotNull { file ->
        val base = file.name.substringBeforeLast('.')
        val parts = base.split('-')
        if (parts.size != 3) return@mapNotNull null
        val size = parts[1].toIntOrNull() ?: return@mapNotNull null
        AssetInfo(
            engine = if (file.name.endsWith(".mp4")) PrismMotionEngineId.Vap else PrismMotionEngineId.Kap,
            hasAlpha = parts[0] == "alpha",
            size = size,
            quality = parts[2],
            file = file,
        )
    }.sortedWith(compareBy({ it.size }, { it.hasAlpha }, { it.engine }, { it.quality }))
}
