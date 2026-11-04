plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.zxhhyj.prism.motion.demo.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.zxhhyj.prism.motion.demo.android"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.compileSdk.get().toInt()
        versionCode = rootProject.extra["prismVersionCode"] as Int
        versionName = rootProject.extra["prismVersionName"] as String
    }

    buildFeatures { compose = true }
    // KAP 使用 openFd/mmap；压缩资产无法打开。 / KAP openFd/mmap requires stored assets.
    androidResources { noCompress += listOf("ktx", "ktx2") }

    // 演示资产直接从仓库根目录的 demo-data 取，避免复制。
    // Demo assets come from the repository-level demo-data directory.
    // 资产目录可覆盖：-PdemoAssets=../some-dir（默认用仓库根目录的 demo-data）。
    // Asset directory is overridable via -PdemoAssets.
    val demoAssets = providers.gradleProperty("demoAssets").getOrElse("../demo-data")
    sourceSets.getByName("main").assets.srcDir(demoAssets)
}

dependencies {
    implementation(projects.prismMotionCompose)
    implementation(projects.prismMotionEngineKap)
    implementation(projects.prismMotionEngineVap)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
}
