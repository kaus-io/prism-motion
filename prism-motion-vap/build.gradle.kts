plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

// VAP 容器：vapc 配置的解析与序列化，供 VAP 引擎、探测与 CLI 使用。
// VAP container: parsing and serialising the vapc config.
kotlin {
    jvm()
    linuxX64()
    mingwX64()
    if (System.getProperty("os.name").lowercase().contains("mac")) {
        macosX64()
        macosArm64()
    }
    android {
        namespace = "com.zxhhyj.prism.motion.vap"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) }
    }
}
