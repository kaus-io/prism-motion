import java.io.ByteArrayOutputStream

plugins {
    kotlin("multiplatform") version "2.4.21"
}

kotlin {
    // 复用主构建里的平台无关源码，避免两份实现。
    linuxX64 {
        binaries {
            executable {
                entryPoint = "com.zxhhyj.prism.motion.clinative.main"
                baseName = "prism-motion"
            }
        }
    }

    sourceSets {
        val commonMain by getting {
            kotlin.srcDir("../prism-motion-core/src/commonMain/kotlin")
            kotlin.srcDir("../prism-motion-probe/src/commonMain/kotlin")
        }
        val linuxX64Main by getting {
            kotlin.srcDir("../prism-motion-probe/src/linuxX64Main/kotlin")
        }
    }
}

// 把 tools/ 下的原生工具追加到可执行文件末尾，运行时由 Tools 解析并解压。
val MAGIC = "PMTOOLS1"

fun littleEndianLong(value: Long): ByteArray =
    ByteArray(8) { ((value ushr (8 * it)) and 0xFF).toByte() }

fun littleEndianInt(value: Int): ByteArray =
    ByteArray(4) { ((value ushr (8 * it)) and 0xFF).toByte() }

val embedTools by tasks.registering {
    dependsOn("linkReleaseExecutableLinuxX64")
    doLast {
        val exe = layout.buildDirectory
            .file("bin/linuxX64/releaseExecutable/prism-motion.kexe").get().asFile
        if (!exe.exists()) {
            logger.lifecycle("跳过内嵌：找不到 $exe")
            return@doLast
        }
        val bytes = exe.readBytes()
        val tail = if (bytes.size >= 24) bytes.copyOfRange(bytes.size - 24, bytes.size) else ByteArray(0)
        if (tail.isNotEmpty() && String(tail, 0, 8) == MAGIC) {
            logger.lifecycle("跳过内嵌：$exe 已带工具 payload")
            return@doLast
        }
        val toolsDir = rootProject.projectDir.parentFile.resolve("tools")
        val entries = listOf("ffmpeg", "astcenc")
            .map { toolsDir.resolve(it) }
            .filter { it.isFile }
        if (entries.isEmpty()) {
            logger.lifecycle("跳过内嵌：$toolsDir 下没有 ffmpeg/astcenc")
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
        logger.lifecycle("已内嵌 ${entries.joinToString { it.name }}（${payload.size / 1024 / 1024} MB）到 $exe")
    }
}

tasks.register<Delete>("cleanNative") {
    delete(layout.buildDirectory)
}
