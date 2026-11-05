# Prism Motion 使用文档

[返回项目首页](<../README.md>)

## 接入播放库

在已配置 Compose 的工程中启用 `mavenCentral()`，添加需要的模块；只使用一种引擎时可移除另一项：

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    val prismVersion = "1.0.0-alpha01"
    implementation("com.kaus-io:prism-motion-compose:$prismVersion")
    implementation("com.kaus-io:prism-motion-engine-kap:$prismVersion")
    implementation("com.kaus-io:prism-motion-engine-vap:$prismVersion")
}
```

版本以 [Releases](<https://github.com/kaus-io/prism-motion/releases>) 为准；仓库当前版本见 [gradle.properties](<../gradle.properties>)。Android 最低 API 为 33，完整工具链版本见[依赖清单](<../gradle/libs.versions.toml>)。

Android 工程还需原样打包 `.prism` 资产，供播放器通过文件区域读取：

```kotlin
android {
    androidResources { noCompress += "prism" }
}
```

使用 `PrismMotionRegistry` 注册引擎与渲染器，通过 `rememberPrismMotionState(request, registry)` 加载资源，再交给 `PrismMotion(state)` 展示。请求默认自动播放；`repeatCount = 0` 播放一次，`-1` 无限循环。完整接入示例见 [Android Demo](<../demo-android/src/main/kotlin/com/zxhhyj/prism/motion/demo/android/MainActivity.kt>) 与 [Desktop Demo](<../demo-desktop/src/jvmMain/kotlin/com/zxhhyj/prism/motion/demo/Main.kt>)。

## 打包与检查资源

CLI 下载入口见 [Releases](<https://github.com/kaus-io/prism-motion/releases>)，也可以从源码构建。以下命令从仓库根目录运行，以 Linux x86_64 的本地构建产物为例：

```sh
PRISM=./prism-motion-cli/build/bin/linuxX64/releaseExecutable/prism-motion.kexe
"$PRISM" engines
"$PRISM" pack kap --frames frames/ --out texture.prism --fps 24 --format rgba8
"$PRISM" pack vap --frames frames/ --out video.prism --fps 30
"$PRISM" probe texture.prism video.prism
"$PRISM" check assets/
```

命令支持 `--json`，便于脚本或 agent 调用。PNG 解码与 H.264 编码使用内置栈；ASTC 打包需要 `astcenc`，支持内嵌工具或通过 `--astcenc` 指定。退出码为 `0` 成功、`1` 校验失败、`2` 用法错误、`3` 运行失败。

Windows CLI 当前仅支持 `probe` / `check` / `engines`；macOS 未纳入当前发行范围。

## 使用边界

- 播放入口只接受 `.prism`，不直接加载裸 MP4 / KTX / KTX2；按容器载荷选择引擎，不做跨引擎静默降级。
- 当前支持单载荷、sRGB 与整数固定帧率；Compose 输出仅实现 `TextureView`，其它输出选项会明确失败。
- Android 使用 MediaCodec / Vulkan 后端，并发能力受设备资源限制；JVM 使用软件解码和原生库，仍会在打开时预解码整段动画，长片段会增加内存与加载耗时。
- 加载失败通过 `PrismMotionStatus.Failed` 表达，协程取消照常传播；资源随请求变化或离开组合而释放。
- JVM 的 OpenH264 / ASTC 原生库需单独准备，不依赖 FFmpeg；当前解码栈不宣称完整支持 B-frame MP4。

更多说明：[Prism 格式规范](<prism-format.md>) · [性能验证与测量边界](<performance.md>) · [发行与制品说明](<releasing.md>)。历史未验证播放的旧图表不能用于比较引擎性能。

## 从源码构建

本地开发需准备 JDK、Android SDK；Linux 原生工具构建还需 C/C++ 工具链、`make` 与 `nasm`。克隆时初始化第三方子模块，后续命令均在本仓库根目录执行：

```sh
git clone --recursive https://github.com/kaus-io/prism-motion.git
cd prism-motion
./scripts/build-thirdparty.sh linux-x86_64
./gradlew :prism-motion-core:jvmTest :prism-motion-probe:jvmTest
./gradlew nativeCli
```

原生构建脚本同时准备 JVM 播放所需的库。桌面演示可用以下命令生成素材并启动；素材生成器不会覆盖同名资源，已有素材时可直接启动演示：

```sh
./gradlew :demo-desktop:generateDemoAssets -PdemoSizes=128,256 -PdemoFrames=24 -PdemoBitrates=150,1500
./gradlew :demo-desktop:run
```

JVM 测试不代替真实设备上的播放与并发验证；正式性能对比应使用有效的同场景记录，不以演示界面的 UI 帧率代替动画实际输出。

## 许可证与第三方组件

项目代码采用 [MIT](<../LICENSE>) 许可证；随附的 OpenH264、stb、astcenc 与 KAP / VAP SDK 按各自许可证授权，来源见[上游子模块清单](<../.gitmodules>)与 [SDK 源码记录](<../thirdparty/prism-sdk/PROVENANCE.json>)。
