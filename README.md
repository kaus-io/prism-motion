# Prism Motion

统一动画门面：`VAP`（MP4 + alpha）与 `KAP`（KTX2 + Vulkan）作为可替换的引擎实现。

## 模块

| 模块 | 目标平台 | 作用 |
| --- | --- | --- |
| `prism-motion-core` | Android + JVM（KMP） | 源、请求、引擎、注册表、输出面、状态契约；无 Compose |
| `prism-motion-compose` | Android + JVM（Compose Multiplatform） | `PrismMotionOutput` / `PrismMotionRenderer` / `PrismMotionState` / `Animation()` |
| `prism-motion-engine-kap` | Android + JVM | KAP 引擎：Android 走 KTX2+Vulkan，JVM 走自研解码 + Compose 绘制 |
| `prism-motion-engine-vap` | Android + JVM | VAP 引擎（Android 走 MediaCodec；JVM 走自有 MP4 解封装 + OpenH264 解码） |
| `prism-motion-probe` | JVM + Kotlin/Native | MP4 / KTX2 容器探测与校验，不解码 |
| `prism-motion-format` | Android + JVM + Kotlin/Native | 通用容器：字节序、MP4 box 遍历/装箱 |
| `prism-motion-ktx` | Android + JVM + Kotlin/Native | KTX2 容器读写（只给 KAP 用） |
| `prism-motion-vap` | Android + JVM + Kotlin/Native | vapc 配置解析/序列化（只给 VAP 用） |
| `prism-motion-utils` | JVM + Kotlin/Native | 平台工具：文件读写（kotlinx-io）、进程、env、临时目录、可执行路径 |
| `utils-jvm` | JVM | 纯 JVM 胶水：原生库（JNA）查找 |
| `prism-motion-cli` | Kotlin/Native | CLI：探测、校验、图片帧打包（与上面同一个 Gradle build） |
| `demo-desktop` | JVM（Compose Multiplatform） | 演示与基准：同屏多实例播放、帧率/RSS 采样 |
| `demo-android` | Android App | 同款演示；bench 模式输出帧间隔与 PSS 曲线 |

## 契约

- 引擎只负责“能否打开”和“如何打开”：`supports(source)` / `open(source)`。
- 打开后是 `PrismMotionResource`：尺寸、时长、进度、`attach(PrismMotionSurface)` / `detach()`。
- `PrismMotionSurface` 平台无关：Android 传 `AndroidPrismMotionSurface(Surface)`，
  JVM 传 `HeadlessPrismMotionSurface`；宿主（TextureView / SurfaceView / 外部 Surface）由调用方选择。
- 加载失败是 `PrismMotionStatus.Failed` 状态，不向组合抛异常；取消异常照常上抛。
- 引擎显式指定优先，否则按 `supports` 选择，**不做跨引擎静默降级**。
- 目前只实现 `PrismMotionOutput.TextureView`；其余宿主会明确失败，不静默退回其它合成方式。

## 演示与基准

`demo-desktop` 是 Compose Multiplatform 桌面应用，同时充当基准工具。

```bash
# 1. 生成资产：尺寸 × 透明/不透明 × 质量（VAP 码率、KAP 像素格式）
./gradlew :demo-desktop:generateDemoAssets               # 默认 128/256/512
./gradlew :demo-desktop:generateDemoAssets -PdemoSizes=128,256 -PdemoFormats=astc-8x8

# 2. 交互演示：同屏多实例、切换资产、看 fps / RSS
./gradlew :demo-desktop:run

# 3. 窗口基准：帧间隔 + RSS 曲线
./gradlew :demo-desktop:run --args="--bench --size 256"

# 4. 加载基准：打开 N 个实例、解出全部帧的耗时与内存
./gradlew :demo-desktop:run --args="--bench --load --size 512"
```

结果写入 `demo-desktop/build/reports/bench.csv` 与 `bench-load.csv`。

### Android

```bash
# 生成小体积资产（默认全部资产会让 APK 到几十 MB）
./gradlew :demo-desktop:generateDemoAssets -PdemoSizes=128,256 -PdemoFormats=astc-8x8

# 连接设备后跑基准（PSS 来自 Debug.MemoryInfo，每个场景写一行）
./gradlew :demo-android:assembleDebug
adb install -r demo-android/build/outputs/apk/debug/demo-android-debug.apk
adb shell am start -n com.zxhhyj.prism.motion.demo.android/.MainActivity --ez bench true
adb logcat -s prism-bench:I          # 等到 BENCH_DONE
adb pull /sdcard/Android/data/com.zxhhyj.prism.motion.demo.android/files/bench.csv

# 只想手动玩一下
./gradlew :demo-android:installDebug
```

资产目录默认是 `../demo-data`，可用 `-PdemoAssets=<dir>` 指向别的目录。

资产命名：`{alpha|opaque}-{size}-{quality}.{mp4|ktx}`，例如
`alpha-512-b6000.mp4`、`opaque-256-rgba8.ktx`。`--size` / `--count` / `--engine` 可以过滤场景。

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
prism-motion.kexe probe assets/a.mp4 assets/b.ktx         # 读容器元数据
prism-motion.kexe check assets/                            # 校验整批资源
prism-motion.kexe pack kap --frames frames/ --out out.ktx --fps 24 --format rgba8
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
| `ARM-software/astc-encoder` | 4.8.0 | Apache-2.0 |

克隆时带上子模块：

```bash
git clone --recursive https://github.com/kaus-io/prism-motion.git
# 已克隆过：
git submodule update --init --recursive
```

构建前先编译成静态库（产物不入库）：

```bash
./scripts/build-thirdparty.sh linux-x86_64   # 需要 cc 与 nasm
# 没有系统 nasm 时：NASM_DIR=/path/to/nasm ./scripts/build-thirdparty.sh linux-x86_64
```

脚本只读 submodule、不联网，生成 `libstb.a`、`libopenh264.a`、`libprismcodec.a` 到
`thirdparty/lib/<platform>/`。

## 已知限制

- `engine-kap` 的 JVM 实现是自己写的：KTX2 解析 + 帧解码。RGBA8 直接读像素；
  ASTC 优先经 **JNA 调用 `libastcenc.so`**（进程内，无临时文件），库不可用时退回
  `astcenc -dl` 子进程。库查找顺序：`PRISM_MOTION_ASTC_LIB` > `~/.cache/prism-motion/native/`
  > `thirdparty/native/<platform>/` > 系统路径。
- `compose`、`engine-vap`、`engine-kap` 都已同时支持 Android 和 JVM（Compose Multiplatform）。
- **JVM 侧不再依赖 FFmpeg**：`engine-vap` 自带 MP4 解封装（`VapMp4`），解码用
  OpenH264 经 JNA 调 `libprismdecode.so`；`engine-kap` 解码用 `libastcenc.so`。两者都是
  `scripts/build-thirdparty.sh` 编出来的原生库。
- Windows 产物只做了交叉编译验证，没有真机 / wine 运行；且缺少 stb_image / OpenH264 的 mingw 交叉编译，
  因此 PNG 解码与 H.264 编码在 Windows 上会明确报错（其它命令正常）。
- macOS 目标只能在 macOS 主机上构建。
- H.264 输出固定为 Constrained Baseline（OpenH264 的能力上限），压缩效率低于 x264/x265，
  对 UI 动效够用；实测同码率下体积约大 15%、PSNR 低 2–3 dB。
