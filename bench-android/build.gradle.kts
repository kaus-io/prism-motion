plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// 基准工具（Android）：播放固定场景，输出帧间隔 / PSS / CPU 到 CSV。
// Android benchmark harness: plays fixed scenarios and writes frame/PSS/CPU rows to CSV.
android {
    namespace = "com.zxhhyj.prism.motion.bench.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.zxhhyj.prism.motion.bench.android"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.compileSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures { compose = true }
    // KAP 使用 openFd/mmap；压缩资产无法打开。 / KAP openFd/mmap requires stored assets.
    androidResources { noCompress += listOf("ktx", "ktx2") }

    val demoAssets = providers.gradleProperty("demoAssets").getOrElse("../demo-data")
    sourceSets.getByName("main").assets.srcDir(demoAssets)
}

dependencies {
    implementation(projects.prismMotionCompose)
    implementation(projects.prismMotionEngineKap)
    implementation(projects.prismMotionEngineVap)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.activity.compose)
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    testImplementation(libs.junit)
}
