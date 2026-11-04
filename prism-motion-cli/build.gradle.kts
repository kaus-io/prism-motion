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

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("all")
    mergeServiceFiles()
    // 单文件运行：java -jar prism-motion-1.0.0-all.jar，不需要额外的 lib/ 目录。
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class")
}

dependencies {
    implementation(projects.prismMotionProbe)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.vap.encode)
    implementation(libs.kap.encode)
    testImplementation(kotlin("test"))
}
