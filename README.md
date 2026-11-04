# Prism Motion

统一动画门面：`VAP`（MP4 + alpha）与 `KAP`（KTX2 + Vulkan）作为可替换的引擎实现。

## 模块

| 模块 | 目标平台 | 作用 |
| --- | --- | --- |
| `prism-motion-core` | Android + JVM（KMP） | 源、请求、引擎、注册表、输出面、状态契约；无 Compose |
| `prism-motion-compose` | Android | `AnimationOutput` / `AnimationRenderer` / `AnimationState` / `Animation()` |
| `prism-motion-engine-kap` | Android | KAP（KTX2 + Vulkan）引擎 |
| `prism-motion-engine-vap` | Android | VAP（MediaCodec）引擎 |
| `prism-motion-probe` | JVM + Kotlin/Native | MP4 / KTX2 容器探测与校验，不解码 |
| `prism-motion-utils` | JVM + Kotlin/Native | 平台工具：文件读写（kotlinx-io）、进程、env、临时目录、可执行路径 |
| `prism-motion-cli` | Kotlin/Native | CLI：探测、校验、图片帧打包（与上面同一个 Gradle build） |

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
# Linux:   native/build/bin/linuxX64/releaseExecutable/prism-motion.kexe   （约 6 MB）
# Windows: native/build/bin/mingwX64/releaseExecutable/prism-motion.exe    （约 2.4 MB）
```

| 平台 | 构建方式 | 状态 |
| --- | --- | --- |
| Linux x86_64 | 任意主机交叉编译 | 已验证 |
| Windows x86_64 | 在 Linux 上交叉编译 | 只支持 `probe` / `check` / `engines` / `pack kap --format astc-*`；PNG 解码与 H.264 编码未接入 |
| macOS arm64 / x86_64 | 只能在 macOS 主机上构建 | 代码已就绪，未在本机构建 |

`tools/` 按平台分目录（`tools/linux-x86_64/`、`tools/windows-x86_64/`、`tools/macos-arm64/` …），
构建时把对应平台的 `astcenc` 追加到该平台的可执行文件末尾；缺哪个平台就跳过哪个。

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
- `pack vap` / `pack kap --format rgba8` 用内置栈：stb_image 解 PNG → OpenH264 编码 → 自写 MP4 封装，
  **不需要任何外部工具**；只有 `pack kap --format astc-*` 需要 `astcenc`（内嵌，也可用 `--astcenc` 覆盖）。
- `probe` 读取：VAP 的 `vapc` box（尺寸 / 帧数 / fps / hasAlpha）与 `mvhd` 时长；
  KAP 的 KTX2 头（尺寸 / layerCount）与 key/value（`anim.fps` 等）。

### 构建结构

所有模块（含 Android 和 Kotlin/Native）在**同一个 Gradle build** 里。关键是根项目
`build.gradle.kts` 把所有插件声明一次并 `apply false`：AGP 模块和 K/N 目标必须在同一套
插件 classloader 下解析，否则 K/N 的 build service 会在兄弟项目间冲突（同 `mandysa` 的做法）。

文件系统统一走 [kotlinx-io](https://github.com/Kotlin/kotlinx-io)（`0.9.2`），
Linux / Windows / macOS / JVM 共用一份读写实现；只有进程、环境变量、权限这些系统调用保留平台 actual。
这些平台代码集中在 `prism-motion-utils`（约 15 行/平台），CLI 与后续工具都可复用。

链接完成后 `embedTools` 任务把 `thirdparty/tools/<platform>/astcenc` 追加到可执行文件末尾
（`PMTOOLS1` payload），运行时首次使用解压到 `~/.cache/prism-motion/tools/`。

## 内置编解码栈

| 职责 | 组件 | 许可 |
| --- | --- | --- |
| PNG 解码 | [stb_image](https://github.com/nothings/stb) | 公有领域 |
| H.264 编码 | [OpenH264](https://github.com/cisco/openh264) 2.6.0 | BSD-2-Clause |
| MP4 封装 | 本仓库 `Mp4Writer`（纯 Kotlin） | 同本仓库 |
| ASTC 压缩 | [astcenc](https://github.com/ARM-software/astc-encoder) 4.8.0 | Apache-2.0 |

上游源码以 **git submodule** 固定版本，放在 `thirdparty/upstream/`：

| submodule | 版本 | 许可 |
| --- | --- | --- |
| `cisco/openh264` | v2.6.0 | BSD-2-Clause |
| `nothings/stb` | d14d46f | 公有领域 |

克隆时带上子模块：

```bash
git clone --recursive https://github.com/kaus-io/prism-motion.git
# 已克隆过：
git submodule update --init --recursive
```

构建前先编译成静态库（产物不入库）：

```bash
./thirdparty/build-thirdparty.sh linux-x86_64   # 需要 cc 与 nasm
# 没有系统 nasm 时：NASM_DIR=/path/to/nasm ./thirdparty/build-thirdparty.sh linux-x86_64
```

脚本只读 submodule、不联网，生成 `libstb.a`、`libopenh264.a`、`libprismcodec.a` 到
`thirdparty/lib/<platform>/`。

## 已知限制

- KAP / VAP 引擎仍是 Android-only（MediaCodec / Vulkan）；CLI 侧只做探测、校验与打包。
- Windows 产物只做了交叉编译验证，没有真机 / wine 运行；且缺少 stb_image / OpenH264 的 mingw 交叉编译，
  因此 PNG 解码与 H.264 编码在 Windows 上会明确报错（其它命令正常）。
- macOS 目标只能在 macOS 主机上构建。
- H.264 输出固定为 Constrained Baseline（OpenH264 的能力上限），压缩效率低于 x264/x265，
  对 UI 动效够用；实测同码率下体积约大 15%、PSNR 低 2–3 dB。
