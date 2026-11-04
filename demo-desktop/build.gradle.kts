import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvm()
    sourceSets {
        jvmMain.dependencies {
            implementation(projects.prismMotionCompose)
            implementation(projects.prismMotionEngineKap)
            implementation(projects.prismMotionEngineVap)
            implementation(compose.desktop.currentOs)
            implementation(compose.material3)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.zxhhyj.prism.motion.demo.MainKt"
        jvmArgs += listOf("-Xmx3g")
        nativeDistributions { targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb) }
    }
}

/**
 * 生成演示资产：尺寸 × 透明/不透明 × 质量，直接调 CLI 打包，不依赖外部脚本。
 * Generates demo assets (size x variant x quality) by invoking the CLI.
 */
val demoDataDir = rootProject.layout.projectDirectory.dir("demo-data")

tasks.register("generateDemoAssets") {
    group = "demo"
    description = "生成 demo-data 下的演示资产 / generates demo assets"
    dependsOn(":prism-motion-cli:linkReleaseExecutableLinuxX64")

    val sizes = (providers.gradleProperty("demoSizes").getOrElse("128,256,512")).split(',').map { it.trim().toInt() }
    val frames = providers.gradleProperty("demoFrames").getOrElse("24").toInt()
    val bitrates = (providers.gradleProperty("demoBitrates").getOrElse("1000,6000")).split(',').map { it.trim() }
    val formats = (providers.gradleProperty("demoFormats").getOrElse("rgba8,astc-8x8")).split(',').map { it.trim() }
    val cli = project(":prism-motion-cli").layout.buildDirectory.file(
        "bin/linuxX64/releaseExecutable/prism-motion.kexe",
    )

    doLast {
        val cliFile = cli.get().asFile
        check(cliFile.isFile) { "缺少 CLI：${cliFile.absolutePath}" }
        val outDir = demoDataDir.asFile.apply { mkdirs() }

        for (size in sizes) {
            for (variant in listOf("tr", "op")) {
                val frameDir = File(rootProject.layout.buildDirectory.asFile.get(), "demo-frames/$variant-$size")
                frameDir.deleteRecursively()
                frameDir.mkdirs()
                val name = if (variant == "tr") "alpha" else "opaque"
                writeFrames(frameDir, size, frames, alpha = variant == "tr")

                for (bitrate in bitrates) {
                    execCli(cliFile, listOf("pack", "vap", "--frames", frameDir.absolutePath,
                        "--out", File(outDir, "$name-$size-b$bitrate.mp4").absolutePath,
                        "--fps", "30", "--bitrate", bitrate))
                }
                for (format in formats) {
                    execCli(cliFile, listOf("pack", "kap", "--frames", frameDir.absolutePath,
                        "--out", File(outDir, "$name-$size-${format.replace("-", "")}.ktx").absolutePath,
                        "--fps", "30", "--format", format))
                }
            }
        }
    }
}

/** 最小 PNG 编码器（避免依赖 java.desktop）。 / Minimal PNG encoder, no java.desktop needed. */
fun writeFrames(dir: File, size: Int, frames: Int, alpha: Boolean) {
    for (i in 0 until frames) {
        val channels = if (alpha) 4 else 3
        val raw = ByteArrayOutputStream()
        for (y in 0 until size) {
            raw.write(0) // filter: none
            for (x in 0 until size) {
                val r = x * 255 / size
                val g = y * 255 / size
                val b = i * 255 / frames
                raw.write(r); raw.write(g); raw.write(b)
                if (alpha) raw.write((x + y + i * size / frames) % 256)
            }
        }
        File(dir, "f%03d.png".format(i)).writeBytes(
            png(size, size, channels, raw.toByteArray()),
        )
    }
}

/** 拼一个 PNG：signature + IHDR + IDAT + IEND。 / Assembles a PNG file. */
fun png(width: Int, height: Int, channels: Int, raw: ByteArray): ByteArray {
    val deflated = ByteArrayOutputStream()
    DeflaterOutputStream(deflated).use { it.write(raw) }

    fun chunk(type: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        fun u32(value: Int) {
            out.write(value ushr 24); out.write(value ushr 16); out.write(value ushr 8); out.write(value)
        }
        u32(data.size)
        out.write(type.toByteArray(Charsets.US_ASCII))
        out.write(data)
        val crc = CRC32()
        crc.update(type.toByteArray(Charsets.US_ASCII))
        crc.update(data)
        u32(crc.value.toInt())
        return out.toByteArray()
    }

    val ihdr = ByteArrayOutputStream()
    fun put32(value: Int) {
        ihdr.write(value ushr 24); ihdr.write(value ushr 16); ihdr.write(value ushr 8); ihdr.write(value)
    }
    put32(width); put32(height)
    ihdr.write(8) // bit depth
    ihdr.write(if (channels == 4) 6 else 2) // color type
    ihdr.write(0); ihdr.write(0); ihdr.write(0)

    val out = ByteArrayOutputStream()
    out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
    out.write(chunk("IHDR", ihdr.toByteArray()))
    out.write(chunk("IDAT", deflated.toByteArray()))
    out.write(chunk("IEND", ByteArray(0)))
    return out.toByteArray()
}

fun execCli(cli: File, args: List<String>) {
    val process = ProcessBuilder(listOf(cli.absolutePath) + args).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor() == 0) { "CLI 失败：$args\n$output" }
}
