plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    jvm()
    android {
        namespace = "com.zxhhyj.prism.motion.engine.vap"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) }
    }
    sourceSets {
        commonMain.dependencies {
            api(projects.prismMotionCompose)
            api(projects.prismMotionFormat)
            api(compose.foundation)
        }
        androidMain.dependencies {
            api(libs.vap.compose)
        }
        jvmMain.dependencies {
            implementation(projects.utilsJvm)
            implementation(libs.jna)
            implementation(libs.kotlinx.coroutines.core)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
