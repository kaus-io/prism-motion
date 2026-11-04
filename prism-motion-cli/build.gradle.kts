import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
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

dependencies {
    implementation(projects.prismMotionProbe)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.vap.encode)
    implementation(libs.kap.encode)
    testImplementation(kotlin("test"))
}
