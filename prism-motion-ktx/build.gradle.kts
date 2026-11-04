plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

// KTX2 容器：读写与像素格式识别，供 KAP 引擎、探测与 CLI 使用。
// KTX2 container: reading/writing and pixel format detection.
kotlin {
    jvm()
    linuxX64()
    mingwX64()
    if (System.getProperty("os.name").lowercase().contains("mac")) {
        macosX64()
        macosArm64()
    }
    android {
        namespace = "com.zxhhyj.prism.motion.ktx"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) }
    }
    sourceSets {
        commonMain.dependencies { api(projects.prismMotionFormat) }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
