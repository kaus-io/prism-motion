plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val appVersionName = rootProject.extra["prismVersionName"] as String
val appVersionCode = rootProject.extra["prismVersionCode"] as Int
val benchVersion = rootProject.extra["prismBenchVersion"] as String
val versionAssetsDirectory = layout.buildDirectory.dir("generated/prismBenchVersion/assets")

// 将版本烘焙进安装包，运行器不得用当前源码给旧 APK 改标签。 / Bakes versions into the APK so runners never relabel old APKs from current source.
val generateBenchVersionAsset by tasks.registering {
    inputs.property("app_version", appVersionName)
    inputs.property("bench_version", benchVersion)
    inputs.property("version_code", appVersionCode)
    outputs.dir(versionAssetsDirectory)
    doLast {
        val output = versionAssetsDirectory.get().file("prism-bench-version.json").asFile
        output.parentFile.mkdirs()
        output.writeText(
            """{"app_version":"$appVersionName","bench_version":"$benchVersion","version_code":$appVersionCode}""" + "\n",
            Charsets.UTF_8,
        )
    }
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
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "BENCH_VERSION", "\"$benchVersion\"")
    }

    buildTypes {
        create("benchmark") {
            initWith(getByName("release"))
            isDebuggable = false
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    // KAP 使用 openFd/mmap；压缩资产无法打开。 / KAP openFd/mmap requires stored assets.
    androidResources { noCompress += listOf("ktx", "ktx2") }

    val demoAssets = providers.gradleProperty("demoAssets").getOrElse("../demo-data")
    sourceSets.getByName("main").assets.srcDir(demoAssets)
    sourceSets.getByName("main").assets.srcDir(versionAssetsDirectory.get().asFile)
}

tasks.named("preBuild") { dependsOn(generateBenchVersionAsset) }
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(generateBenchVersionAsset)
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
