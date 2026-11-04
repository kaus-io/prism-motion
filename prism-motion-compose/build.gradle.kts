plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    jvm()
    android {
        namespace = "com.zxhhyj.prism.motion.compose"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) }
    }
    sourceSets {
        commonMain.dependencies {
            api(projects.prismMotionCore)
            api(compose.runtime)
            api(compose.ui)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
