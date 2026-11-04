plugins {
    alias(libs.plugins.kotlinJvm)
}

kotlin {
    explicitApi()
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.zstd.jni)
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}
