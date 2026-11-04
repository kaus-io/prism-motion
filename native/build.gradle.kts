import java.io.ByteArrayOutputStream

plugins {
    kotlin("multiplatform") version "2.4.21"
}

// macOS 目标只能在 macOS 主机上编译，Linux/Windows 上跳过。
val hostOs = System.getProperty("os.name").lowercase()
val onMac = hostOs.contains("mac")

kotlin {
    linuxX64 {
        compilations.getByName("main").cinterops {
            val stb by creating {
                defFile(project.file("src/nativeInterop/cinterop/stb.def"))
                includeDirs(project.file("thirdparty/include"))
            }
            val prismcodec by creating {
                defFile(project.file("src/nativeInterop/cinterop/prismcodec.def"))
                includeDirs(project.file("thirdparty/shim"))
            }
        }
        binaries {
            executable {
                entryPoint = "com.zxhhyj.prism.motion.clinative.main"
                baseName = "prism-motion"
                linkerOpts(
                    "-L${projectDir}/thirdparty/lib/linux-x86_64",
                    "-lprismcodec", "-lopenh264", "-lstb", "-lstdc++",
                )
            }
        }
    }
    mingwX64 { binaries { executable { entryPoint = "com.zxhhyj.prism.motion.clinative.main"; baseName = "prism-motion" } } }
    if (onMac) {
        macosX64 { binaries { executable { entryPoint = "com.zxhhyj.prism.motion.clinative.main"; baseName = "prism-motion" } } }
        macosArm64 { binaries { executable { entryPoint = "com.zxhhyj.prism.motion.clinative.main"; baseName = "prism-motion" } } }
    }

    sourceSets {
        val commonMain by getting {
            kotlin.srcDir("../prism-motion-core/src/commonMain/kotlin")
            kotlin.srcDir("../prism-motion-probe/src/commonMain/kotlin")
        }
        val posixMain by creating {
            dependsOn(commonMain)
            kotlin.srcDir("src/posixMain/kotlin")
        }
        val cinteropMain by creating {
            dependsOn(commonMain)
            kotlin.srcDir("src/cinteropMain/kotlin")
        }
        val mingwMain by creating {
            dependsOn(commonMain)
            kotlin.srcDir("src/mingwMain/kotlin")
            kotlin.srcDir("../prism-motion-probe/src/mingwMain/kotlin")
        }
        val linuxX64Main by getting {
            dependsOn(posixMain)
            dependsOn(cinteropMain)
            kotlin.srcDir("../prism-motion-probe/src/posixMain/kotlin")
        }
        val mingwX64Main by getting { dependsOn(mingwMain) }

        if (onMac) {
            val macosMain by creating {
                dependsOn(posixMain)
                kotlin.srcDir("src/macosMain/kotlin")
            }
            val macosX64Main by getting {
                dependsOn(macosMain)
                kotlin.srcDir("../prism-motion-probe/src/posixMain/kotlin")
            }
            val macosArm64Main by getting {
                dependsOn(macosMain)
                kotlin.srcDir("../prism-motion-probe/src/posixMain/kotlin")
            }
        }
    }
}

// ---- 内嵌原生工具 ----------------------------------------------------------

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
            val toolDir = rootProject.projectDir.parentFile.resolve("tools").resolve(toolPlatform)
            val suffix = if (toolPlatform.startsWith("windows")) ".exe" else ""
            val entries = listOf("astcenc")
                .map { toolDir.resolve(it + suffix) }
                .filter { it.isFile }
            if (entries.isEmpty()) {
                logger.lifecycle("跳过 $taskName：$toolDir 下没有工具")
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

// ---- 第三方库缺失时给出明确提示，而不是让链接报一堆找不到符号 ----

val thirdpartyPlatform = if (hostOs.contains("windows")) "windows-x86_64" else "linux-x86_64"
val requiredThirdparty = listOf(
    "include/stb_image.h",
    "include/openh264/codec_api.h",
    "lib/$thirdpartyPlatform/libstb.a",
    "lib/$thirdpartyPlatform/libopenh264.a",
    "lib/$thirdpartyPlatform/libprismcodec.a",
).map { projectDir.resolve("thirdparty/$it") }

tasks.configureEach {
    if (name.startsWith("link") || name.startsWith("cinterop")) {
        doFirst {
            val missing = requiredThirdparty.filterNot { it.exists() }
            if (missing.isNotEmpty()) {
                throw GradleException(
                    "缺少第三方库：\n" + missing.joinToString("\n") { "  - $it" } +
                        "\n先运行 ./thirdparty/build-thirdparty.sh $thirdpartyPlatform（需要 cc 与 nasm）",
                )
            }
        }
    }
}
