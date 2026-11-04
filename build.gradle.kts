// Prism Motion：统一动画门面，VAP / KAP 作为引擎实现。
//
// 所有插件在根项目声明一次并 apply false：AGP 模块与 Kotlin/Native 目标需要在
// 同一个插件 classloader 下解析，否则 K/N 的 build service 会在兄弟项目间冲突。
plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose.multiplatform) apply false
}

tasks.register("nativeCli") {
    group = "build"
    description = "构建并内嵌工具的原生 CLI（当前平台）"
    dependsOn(":prism-motion-cli:embedTools")
}
