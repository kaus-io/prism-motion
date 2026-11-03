plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.zxhhyj.prism.motion.compose"
    compileSdk = 37

    defaultConfig {
        minSdk = 33
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    api(projects.prismMotionCore)
    // 公开签名暴露 Modifier / ContentScale / State。
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.ui)
    // 与主工程保持同一 lifecycle 版本，避免解析到未缓存的旧版本。
    api(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.core)
}
