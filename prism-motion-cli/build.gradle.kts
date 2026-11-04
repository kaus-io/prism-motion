import java.io.ByteArrayOutputStream

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

val hostOs = System.getProperty("os.name").lowercase()
val onMac = hostOs.contains("mac")

kotlin {
    linuxX64 {
        binaries {
            executable {
                entryPoint = "com.zxhhyj.prism.motion.clinative.main"
                baseName = "prism-motion"
                linkerOpts(
                    "-L${rootProject.projectDir}/thirdparty/lib/linux-x86_64",
                    "-lprismcodec", "-lopenh264", "-lstb", "-lstdc++",
                )
            }
        }
        compilations.getByName("main").cinterops {
            val stb by creating {
                defFile(project.file("src/nativeInterop/cinterop/stb.def"))
                includeDirs(rootProject.file("thirdparty/upstream/stb"))
            }
            val prismcodec by creating {
                defFile(project.file("src/nativeInterop/cinterop/prismcodec.def"))
                includeDirs(rootProject.file("thirdparty/shim"))
            }
        }
    }
    mingwX64 {
        binaries {
            executable {
                entryPoint = "com.zxhhyj.prism.motion.clinative.main"
                baseName = "prism-motion"
            }
        }
    }
    if (onMac) {
        macosX64 {
            binaries {
                executable {
                    entryPoint = "com.zxhhyj.prism.motion.clinative.main"
                    baseName = "prism-motion"
                }
            }
        }
        macosArm64 {
            binaries {
                executable {
                    entryPoint = "com.zxhhyj.prism.motion.clinative.main"
                    baseName = "prism-motion"
                }
            }
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(projects.prismMotionProbe)
                api(projects.prismMotionFormat)
                api(projects.prismMotionKtx)
                api(projects.prismMotionVap)
                api(projects.prismMotionUtils)
            }
        }
        val cinteropMain by creating { dependsOn(commonMain) }
        val posixMain by creating { dependsOn(commonMain) }
        val mingwMain by creating { dependsOn(commonMain) }
        val linuxX64Main by getting { dependsOn(posixMain); dependsOn(cinteropMain) }
        val mingwX64Main by getting { dependsOn(mingwMain) }
        if (onMac) {
            val macosMain by creating { dependsOn(posixMain) }
            val macosX64Main by getting { dependsOn(macosMain); dependsOn(cinteropMain) }
            val macosArm64Main by getting { dependsOn(macosMain); dependsOn(cinteropMain) }
        }
    }
}

// ---- 内嵌原生工具（astcenc）到可执行文件末尾 ------------------------------

val MAGIC = "PMTOOLS1"

fun littleEndianLong(value: Long): ByteArray = ByteArray(8) { ((value ushr (8 * it)) and 0xFF).toByte() }
fun littleEndianInt(value: Int): ByteArray = ByteArray(4) { ((value ushr (8 * it)) and 0xFF).toByte() }

fun registerEmbed(taskName: String, linkTask: String, exeRelative: String, toolPlatform: String) {
    tasks.register(taskName) {
        dependsOn(linkTask)
        doLast {
            val exe = layout.buildDirectory.file(exeRelative).get().asFile
            if (!exe.exists()) {
                logger.lifecycle("跳过 $taskName：找不到 $exe")
                return@doLast
            }
            val bytes = exe.readBytes()
            val tail = if (bytes.size >= 24) bytes.copyOfRange(bytes.size - 24, bytes.size) else ByteArray(0)
            if (tail.isNotEmpty() && String(tail, 0, 8) == MAGIC) {
                logger.lifecycle("跳过 $taskName：已带工具 payload")
                return@doLast
            }
            val toolDir = rootProject.projectDir.resolve("thirdparty/tools/$toolPlatform")
            val suffix = if (toolPlatform.startsWith("windows")) ".exe" else ""
            val entries = listOf("astcenc").map { toolDir.resolve(it + suffix) }.filter { it.isFile }
            if (entries.isEmpty()) {
                logger.lifecycle("跳过 $taskName：$toolDir 下没有 astcenc")
                return@doLast
            }
            val buffer = ByteArrayOutputStream()
            for (file in entries) {
                val name = file.name.toByteArray()
                buffer.write(littleEndianInt(name.size))
                buffer.write(name)
                val data = file.readBytes()
                buffer.write(littleEndianLong(data.size.toLong()))
                buffer.write(data)
            }
            val payload = buffer.toByteArray()
            val offset = exe.length()
            exe.appendBytes(payload)
            exe.appendBytes(MAGIC.toByteArray())
            exe.appendBytes(littleEndianLong(offset))
            exe.appendBytes(littleEndianLong(payload.size.toLong()))
            logger.lifecycle("$taskName：已内嵌 ${entries.joinToString { it.name }}（${payload.size / 1024 / 1024} MB）")
        }
    }
}

registerEmbed("embedToolsLinux", "linkReleaseExecutableLinuxX64", "bin/linuxX64/releaseExecutable/prism-motion.kexe", "linux-x86_64")
registerEmbed("embedToolsWindows", "linkReleaseExecutableMingwX64", "bin/mingwX64/releaseExecutable/prism-motion.exe", "windows-x86_64")
if (onMac) {
    registerEmbed("embedToolsMacosArm64", "linkReleaseExecutableMacosArm64", "bin/macosArm64/releaseExecutable/prism-motion.kexe", "macos-arm64")
    registerEmbed("embedToolsMacosX64", "linkReleaseExecutableMacosX64", "bin/macosX64/releaseExecutable/prism-motion.kexe", "macos-x86_64")
}

tasks.register("embedTools") {
    dependsOn("embedToolsLinux", "embedToolsWindows")
    if (onMac) dependsOn("embedToolsMacosArm64", "embedToolsMacosX64")
}

// ---- 第三方库缺失时给出明确提示 ------------------------------------------

val hostToolPlatform = if (hostOs.contains("windows")) "windows-x86_64" else "linux-x86_64"
val requiredThirdparty = listOf(
    "upstream/stb/stb_image.h",
    "upstream/openh264/codec/api/wels/codec_api.h",
    "lib/$hostToolPlatform/libstb.a",
    "lib/$hostToolPlatform/libopenh264.a",
    "lib/$hostToolPlatform/libprismcodec.a",
).map { rootProject.file("thirdparty/$it") }

tasks.configureEach {
    if (name.startsWith("link") || name.startsWith("cinterop")) {
        doFirst {
            val missing = requiredThirdparty.filterNot { it.exists() }
            if (missing.isNotEmpty()) {
                throw GradleException(
                    "缺少第三方库：\n" + missing.joinToString("\n") { "  - $it" } +
                        "\n先运行 ./scripts/build-thirdparty.sh $hostToolPlatform（需要 cc 与 nasm）",
                )
            }
        }
    }
}
