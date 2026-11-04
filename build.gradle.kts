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

// 产品与测量软件独立递增，Android 序号由单独属性声明。 / Product and benchmark software advance independently; Android codes use a separate property.
val semVerPattern = Regex("(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)(?:-(?:alpha|beta|rc)(?:0[1-9]|[1-9][0-9]))?")
val prismVersionName = providers.gradleProperty("version").get()
require(semVerPattern.matches(prismVersionName)) {
    "version must use MAJOR.MINOR.PATCH with optional -alphaNN/-betaNN/-rcNN (01..99): $prismVersionName"
}
val androidVersionCode = providers.gradleProperty("androidVersionCode").get()
val prismVersionCode = requireNotNull(androidVersionCode.takeIf { Regex("[1-9][0-9]*").matches(it) }
    ?.toIntOrNull()?.takeIf { it in 1..2100000000 }) {
    "androidVersionCode must be an explicit positive integer (1..2100000000): $androidVersionCode"
}
val prismBenchVersion = providers.gradleProperty("benchVersion").get()
require(semVerPattern.matches(prismBenchVersion)) {
    "benchVersion must use MAJOR.MINOR.PATCH with optional -alphaNN/-betaNN/-rcNN (01..99): $prismBenchVersion"
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
