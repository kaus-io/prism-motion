import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

// 基准工具：无交互界面，跑完写 CSV 退出。 / Benchmark harness: no UI, writes CSV, exits.
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
        mainClass = "com.zxhhyj.prism.motion.bench.MainKt"
        jvmArgs += listOf("-Xmx3g")
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            // 安装器要求纯数字版本，产品仍使用 alpha 编号。 / Installers require numeric versions; the product retains alpha numbering.
            packageVersion = "1.0.${rootProject.extra["prismVersionCode"]}"
        }
    }
}
