plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.kausio.prism.releaseconsumer"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.kausio.prism.releaseconsumer"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.compileSdk.get().toInt()
        versionCode = 1
        versionName = providers.gradleProperty("prismVersion").get()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    androidResources { noCompress += "prism" }
    packaging {
        // 两个 SDK 的同 ABI C++ 运行库字节相同，仅合并此共享文件。 / Both SDKs ship identical per-ABI C++ runtimes; coalesce only this shared file.
        jniLibs.pickFirsts += "**/libc++_shared.so"
    }
}

val prismVersion = providers.gradleProperty("prismVersion").get()
dependencies {
    implementation("com.kaus-io:prism-motion-core:$prismVersion")
    implementation("com.kaus-io:prism-motion-compose:$prismVersion")
    implementation("com.kaus-io:prism-motion-engine-kap:$prismVersion")
    implementation("com.kaus-io:prism-motion-engine-vap:$prismVersion")
    implementation("com.kaus-io:prism-motion-format:$prismVersion")
    implementation("com.kaus-io:prism-motion-ktx:$prismVersion")
    implementation("com.kaus-io:prism-motion-vap:$prismVersion")
}
