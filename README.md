# Prism Motion

统一动画门面：`VAP`（MP4 + alpha）与 `KAP`（KTX2 + Vulkan）作为可替换的引擎实现。

## 模块

| 模块 | 目标平台 | 作用 |
| --- | --- | --- |
| `prism-motion-core` | Android + JVM（KMP） | 源、请求、引擎、注册表、输出面、状态契约；无 Compose |
| `prism-motion-compose` | Android | `AnimationOutput` / `AnimationRenderer` / `AnimationState` / `Animation()` |
| `prism-motion-engine-kap` | Android | KAP（KTX2 + Vulkan）引擎 |
| `prism-motion-engine-vap` | Android | VAP（MediaCodec）引擎 |
| `prism-motion-probe` | JVM | MP4 / KTX2 容器探测与校验，不解码；源码同时供原生 CLI 复用 |
| `native/` | Kotlin/Native | CLI：探测、校验、图片帧打包，独立 Gradle 构建 |

## 契约

- 引擎只负责“能否打开”和“如何打开”：`supports(source)` / `open(source)`。
- 打开后是 `AnimationResource`：尺寸、时长、进度、`attach(AnimationSurface)` / `detach()`。
- `AnimationSurface` 平台无关：Android 传 `AndroidAnimationSurface(Surface)`，
  JVM 传 `HeadlessAnimationSurface`；宿主（TextureView / SurfaceView / 外部 Surface）由调用方选择。
- 加载失败是 `AnimationStatus.Failed` 状态，不向组合抛异常；取消异常照常上抛。
- 引擎显式指定优先，否则按 `supports` 选择，**不做跨引擎静默降级**。
- 目前只实现 `AnimationOutput.TextureView`；其余宿主会明确失败，不静默退回其它合成方式。

## 构建

```bash
./gradlew build                                  # Android / JVM 库
./gradlew :prism-motion-core:jvmTest :prism-motion-probe:jvmTest
./gradlew nativeCli                              # CLI：native/build/bin/linuxX64/releaseExecutable/prism-motion.kexe
```

`local.properties` 需要一行 `sdk.dir=<Android SDK>`。

引擎依赖的 KAP / VAP 没有公共仓库，只有本机 maven（`0.x-LOCAL`）。在各自仓库执行：

```bash
./gradlew publishToMavenLocal -Pversion=0.1.0-LOCAL -PskipSigning=true
```

## CLI

CLI 只有一个实现：Kotlin/Native 编译的原生可执行文件，不依赖 JVM。

```bash
./gradlew nativeCli
# Linux:   native/build/bin/linuxX64/releaseExecutable/prism-motion.kexe   （约 82 MB）
# Windows: native/build/bin/mingwX64/releaseExecutable/prism-motion.exe    （约 103 MB）
```

| 平台 | 构建方式 | 状态 |
| --- | --- | --- |
| Linux x86_64 | 任意主机交叉编译 | 已验证 |
| Windows x86_64 | 在 Linux 上交叉编译 | 可编译、产物为 PE32+；未在真机运行验证 |
| macOS arm64 / x86_64 | 只能在 macOS 主机上构建 | 代码已就绪，未在本机构建 |

`tools/` 按平台分目录（`tools/linux-x86_64/`、`tools/windows-x86_64/`、`tools/macos-arm64/` …），
构建时把对应平台的 ffmpeg / astcenc 追加到该平台的可执行文件末尾；缺哪个平台就跳过哪个。

```bash
prism-motion.kexe engines                                  # 引擎与平台支持
prism-motion.kexe probe assets/a.mp4 assets/b.ktx2         # 读容器元数据
prism-motion.kexe check assets/                            # 校验整批资源
prism-motion.kexe pack kap --frames frames/ --out out.ktx2 --fps 24 --format rgba8
prism-motion.kexe pack vap --frames frames/ --out out.mp4 --fps 30
```

- 所有命令支持 `--json`，输出 `{"ok":…,"command":…}`，便于 agent 解析。
- 退出码：`0` 成功、`1` 校验失败、`2` 用法错误、`3` 运行失败。
- `check` 把不认识的扩展名也算失败；读不了的目录直接跳过。
- `pack vap` 用 `ffmpeg`，`pack kap --format astc-*` 用 `astcenc`；`--format rgba8` 不需要任何外部工具。
  查找顺序：`--ffmpeg/--astcenc` > `PRISM_MOTION_FFMPEG` / `PRISM_MOTION_ASTCENC` > 可执行文件同级 `tools/`
  > `~/.cache/prism-motion/tools/` > **二进制内嵌**（首次使用自动解压）> `PATH`。
- `probe` 读取：VAP 的 `vapc` box（尺寸 / 帧数 / fps / hasAlpha）与 `mvhd` 时长；
  KAP 的 KTX2 头（尺寸 / layerCount）与 key/value（`anim.fps` 等）。

### 原生构建为什么单独

Kotlin/Native 的 `KotlinNativeBundleBuildService` 无法和 AGP 模块共存于同一个 Gradle build，
所以 `native/` 是独立构建，通过 `srcDir` 复用 `prism-motion-core` / `prism-motion-probe` 的
平台无关源码。链接完成后，`embedTools` 任务把 `tools/ffmpeg`、`tools/astcenc` 追加到可执行文件末尾
（`PMTOOLS1` payload），运行时首次使用解压到 `~/.cache/prism-motion/tools/`。

## 已知限制

- KAP / VAP 引擎仍是 Android-only（MediaCodec / Vulkan）；CLI 侧只做探测、校验与打包。
- Windows 产物只做了交叉编译验证，没有真机 / wine 运行；macOS 目标只能在 macOS 主机上构建。
- 打包逻辑在原生侧是独立实现（`VapPack` / `Ktx2`），与原 `vap-encode` / `kap-encode` 靠
  `vapc.json` 对拍，暂无跨实现一致性测试。
