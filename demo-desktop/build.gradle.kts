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
