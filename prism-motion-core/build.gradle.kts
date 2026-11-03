plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.zxhhyj.prism.motion"
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
    testImplementation("junit:junit:4.13.2")
    testImplementation(libs.robolectric)
}
