// Prism Motion：统一动画门面，VAP / KAP 作为引擎实现。
//
// 原生 CLI 在 native/ 下是一个独立构建：Kotlin/Native 的 build service 无法与
// AGP 模块共存在同一个 Gradle build 里，所以这里只提供一个转发任务。

tasks.register<GradleBuild>("nativeCli") {
    dir = file("native")
    tasks = listOf("embedTools")
}
