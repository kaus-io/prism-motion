package com.zxhhyj.prism.motion.bench

import com.zxhhyj.prism.motion.PrismMotionEngineId
import com.zxhhyj.prism.motion.PrismMotionSource
import com.zxhhyj.prism.motion.PrismJvmSources
import com.zxhhyj.prism.motion.engineId
import com.zxhhyj.prism.motion.format.PrismAlphaMode
import java.io.File

/**
 * 演示资产：引擎、透明度和尺寸取自容器，质量标签取自名称。
 * Demo asset: the container defines engine, alpha and dimensions; its name supplies the quality label.
 * 命名约定 / naming: `{alpha|opaque}-{size}-{quality}.prism`.
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
        file.isFile && file.name.endsWith(".prism")
    }.orEmpty().mapNotNull { file ->
        val base = file.name.substringBeforeLast('.')
        val parts = base.split('-')
        if (parts.size != 3) return@mapNotNull null
        val size = parts[1].toIntOrNull() ?: return@mapNotNull null
        val descriptor = PrismJvmSources.inspect(PrismMotionSource.File(file.path))
        require(descriptor.metadata.width == size && descriptor.metadata.height == size) { "Asset name disagrees with its Prism canvas" }
        AssetInfo(
            engine = descriptor.kind.engineId,
            hasAlpha = descriptor.metadata.alphaMode != PrismAlphaMode.Opaque,
            size = size,
            quality = parts[2],
            file = file,
        )
    }.sortedWith(compareBy({ it.size }, { it.hasAlpha }, { it.engine }, { it.quality }))
}
