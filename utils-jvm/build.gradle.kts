plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// 纯 JVM 胶水：加载原生库等只在 JVM 上有意义的逻辑。
kotlin {
    jvm()
}
