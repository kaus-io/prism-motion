plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

// 容器格式层：MP4 box、KTX2、vapc，以及字节序读写。
// Container formats: MP4 boxes, KTX2, vapc, plus byte-order helpers.
kotlin {
    jvm()
    linuxX64()
    mingwX64()
    if (System.getProperty("os.name").lowercase().contains("mac")) {
        macosX64()
        macosArm64()
    }

    android {
        namespace = "com.zxhhyj.prism.motion.format"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) }
    }

    sourceSets {
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
