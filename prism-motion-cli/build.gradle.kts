import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.shadow)
    application
}

version = "1.0.0"

base {
    archivesName.set("prism-motion")
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

application {
    mainClass.set("com.zxhhyj.prism.motion.cli.MainKt")
    applicationName = "prism-motion"
    // 相对路径按仓库根目录解析，`gradlew :prism-motion-cli:run` 与安装后的脚本行为一致。
    tasks.named<JavaExec>("run") {
        workingDir = rootProject.projectDir
    }
}

// 把 tools/ 下的原生工具嵌进 jar：运行时按 embedded-tools/<os>-<arch>/ 解压到缓存。
val hostPlatform: String = run {
    val os = System.getProperty("os.name").lowercase()
    val name = when {
        os.contains("linux") -> "linux"
        os.contains("mac") || os.contains("darwin") -> "darwin"
        os.contains("windows") -> "windows"
        else -> os.replace(' ', '-')
    }
    val arch = System.getProperty("os.arch").lowercase()
    val archName = when (arch) {
        "amd64", "x86_64" -> "x86_64"
        "aarch64", "arm64" -> "aarch64"
        else -> arch
    }
    "$name-$archName"
}

val embeddedToolsDir = rootProject.layout.projectDirectory.dir("tools")

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("all")
    mergeServiceFiles()
    // 单文件运行：java -jar prism-motion-1.0.0-all.jar，不需要额外的 lib/ 目录。
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class")
    from(embeddedToolsDir) {
        include("ffmpeg", "ffmpeg.exe", "astcenc", "astcenc.exe")
        into("embedded-tools/$hostPlatform")
    }
    doFirst {
        val missing = listOf("ffmpeg", "astcenc").filter { !embeddedToolsDir.file(it).asFile.exists() }
        if (missing.isNotEmpty()) {
            logger.lifecycle(
                "未找到 tools/${missing.joinToString(", ")}，该平台的 jar 将不带内嵌工具（仍可用 --ffmpeg 或 PATH）。",
            )
        }
    }
}

dependencies {
    implementation(projects.prismMotionProbe)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.vap.encode)
    implementation(libs.kap.encode)
    testImplementation(kotlin("test"))
}
