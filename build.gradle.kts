// Prism Motion：统一动画门面，VAP / KAP 作为引擎实现。
//
// 所有插件在根项目声明一次并 apply false：AGP 模块与 Kotlin/Native 目标需要在
// 同一个插件 classloader 下解析，否则 K/N 的 build service 会在兄弟项目间冲突。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose.multiplatform) apply false
}

// 产品与测量协议独立递增，拒绝非规范 alpha 版本。 / Product and measurement protocol advance independently with canonical alpha versions.
val alphaVersionPattern = Regex("alpha(0[1-9]|[1-9][0-9]+)")
val prismVersionName = providers.gradleProperty("version").get()
val prismVersionMatch = requireNotNull(alphaVersionPattern.matchEntire(prismVersionName)) {
    "version must use canonical alpha numbering, starting at alpha01: $prismVersionName"
}
val prismVersionCode = requireNotNull(prismVersionMatch.groupValues[1].toIntOrNull()?.takeIf { it in 1..2100000000 }) {
    "version alpha sequence must fit Android versionCode (1..2100000000): $prismVersionName"
}
val prismBenchVersion = providers.gradleProperty("benchVersion").get()
require(alphaVersionPattern.matches(prismBenchVersion)) {
    "benchVersion must use canonical alpha numbering, starting at alpha01: $prismBenchVersion"
}

version = prismVersionName
subprojects { version = rootProject.version }
extra["prismVersionName"] = prismVersionName
extra["prismVersionCode"] = prismVersionCode
extra["prismBenchVersion"] = prismBenchVersion

tasks.register("nativeCli") {
    group = "build"
    description = "构建并内嵌工具的原生 CLI（当前平台）"
    dependsOn(":prism-motion-cli:embedTools")
}
