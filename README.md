# Prism Motion

统一动画资源与播放门面：对外只接受 `.prism`，由容器声明选择视频或纹理播放引擎。裸 `.mp4/.ktx/.ktx2` 资源入口已废弃，不做静默兼容。

## 原生资源格式

固定头、统一元数据、载荷索引之后，以对齐的区域原样保存完整 VAP MP4 或 KTX2；不是 ZIP，不重复编码。格式独立版本化，带前缀 CRC32 和载荷 SHA-256，具体布局与校验边界见 [Prism 格式规范](<docs/prism-format.md>)。

Android 通过底层播放器的只读区域来源直接加载，不提取临时 MP4/KTX。JVM 软件解码仍会在内存中物化载荷，不能将整个链路称为零拷贝。

## 模块

| 模块 | 目标平台 | 作用 |
| --- | --- | --- |
| `prism-motion-core` | Android + JVM（KMP） | 源、请求、引擎、注册表、输出面、状态契约；无 Compose |
| `prism-motion-compose` | Android + JVM（Compose Multiplatform） | `PrismMotionOutput` / `PrismMotionRenderer` / `PrismMotionState` / `PrismMotion()` |
| `prism-motion-engine-kap` | Android + JVM | KAP 引擎：Android 走 KTX2+Vulkan，JVM 走自研解码 + Compose 绘制 |
| `prism-motion-engine-vap` | Android + JVM | VAP 引擎（Android 走 MediaCodec；JVM 走自有 MP4 解封装 + OpenH264 解码） |
| `prism-motion-probe` | JVM + Kotlin/Native | Prism 容器完整性与原生载荷一致性校验，不解码 |
| `prism-motion-format` | Android + JVM + Kotlin/Native | Prism 二进制协议、统一元数据、CRC/SHA、内部字节序与 MP4 工具 |
| `prism-motion-ktx` | Android + JVM + Kotlin/Native | KTX2 容器读写（只给 KAP 用） |
| `prism-motion-vap` | Android + JVM + Kotlin/Native | vapc 配置解析/序列化（只给 VAP 用） |
| `prism-motion-utils` | JVM + Kotlin/Native | 平台工具：文件读写（kotlinx-io）、进程、env、临时目录、可执行路径 |
| `utils-jvm` | JVM | 纯 JVM 胶水：原生库（JNA）查找 |
| `prism-motion-cli` | Kotlin/Native | CLI：探测、校验、图片帧打包（与上面同一个 Gradle build） |
| `demo-desktop` | JVM（Compose Multiplatform） | 交互演示 |
| `demo-android` | Android App | Android 交互演示 |
| `bench-jvm` | JVM | 独立桌面测试工具（RSS，不是 PSS） |
| `bench-android` | Android App | 独立基准，校验真实输出、提交帧率、CPU、PSS |

## 契约

- 引擎只负责“能否打开”和“如何打开”：`supports(source)` / `open(source)`。
- `supports(source)` 与 `open(source)` 均可挂起；按容器载荷选择引擎的头部读取在 IO 上执行。
- 打开后是 `PrismMotionResource`：尺寸、时长、进度、`attach(PrismMotionSurface)` / `detach()`。
- `PrismMotionSurface` 平台无关：Android 传 `AndroidPrismMotionSurface(Surface)`，
  JVM 传 `HeadlessPrismMotionSurface`；宿主（TextureView / SurfaceView / 外部 Surface）由调用方选择。
- 加载失败是 `PrismMotionStatus.Failed` 状态，不向组合抛异常；取消异常照常上抛。
- 引擎显式指定优先，否则按 `supports` 选择，**不做跨引擎静默降级**。
- 目前只实现 `PrismMotionOutput.TextureView`；其余宿主会明确失败，不静默退回其它合成方式。

## 演示与基准

Demo 只负责交互，基准是独立应用。正式基准数据和图表按版本保存在 `benchmarks/<version>/`；临时日志等产物使用系统临时目录。对外比较使用明确的 1× 基准，不展示具体测试配置。

```bash
./gradlew :demo-desktop:generateDemoAssets -PdemoSizes=128,256 -PdemoFrames=24 -PdemoBitrates=150,1500
./gradlew :demo-desktop:run
./gradlew :demo-android:installDebug

./gradlew :bench-jvm:run --args="--size 256"
ASSETS='<private asset set>' ./scripts/run-android-bench.sh <adb-serial> \
  --variant benchmark --counts '<private loads>' --isolate-scenarios --repeats 3 --allow-invalid
python3 scripts/analyze-android-bench.py \
  --input benchmarks/alpha01/data/raw.csv --json
```

产品版本、独立 bench 版本与递增 Android versionCode 由 [gradle.properties](<gradle.properties>) 管理。软件版本使用 SemVer 及两位预发布序号；已归档的历史 `alpha01` 标记不重写。
Android/CLI 产物使用同一产品版本；bench 界面、每条原始记录和 manifest 同时保留产品/协议版本。
运行器校验安装包内烘焙的版本、APK 与素材指纹，拒绝把旧 APK 或不同协议的数据冒充当前版本。
原始记录按实际版本保存到 `benchmarks/<version>/runs/<run_id>/`，避免覆盖已有基线；正式归档的数据与图表分别放在 `data/`、`charts/`。当前归档：[alpha01 数据](<benchmarks/alpha01/data/>) · [alpha01 六项倍率图](<benchmarks/alpha01/charts/>)。manifest 保留实际运行时参数与指纹，迁移不改写历史记录。公开图固定 KAP 为 1× 基准，不导出具体配置、临时 Markdown 报告或压缩包。
`benchmark` 是非 debuggable 专用构建。进程隔离、逐帧回调校验和 CPU/PSS 分阶段测量用于避免回归误判；场景失败会保留，采集/崩溃/版本错误不会被 `--allow-invalid` 吞掉。
公开图中的倍率是同场景相对开销，不是综合跑分或等画质效率；呈现回调也不等同物理屏幕扫描。

资产目录默认是模块相对路径 `../demo-data`，Gradle 可用 `-PdemoAssets=<dir>` 覆盖；运行脚本时可设置 `DEMO_ASSETS`。
示例资产命名：`{alpha|opaque}-{size}-{quality}.prism`；`ASSETS` 使用不带后缀的精确名称。引擎以容器索引声明为准，不从文件名猜测。

**旧图已作废**：[历史图表](<docs/charts/fps_vs_count.png>) 来自未验证播放的旧 bench，不能用来比较引擎性能；
特别是 KAP `first_frame=-1` 的行实际上没有成功加载。源码对齐、修复和当前验证见 [性能说明](<docs/performance.md>)。

### Android 资产打包

两个 Android 后端通过 `AssetManager.openFd()` 获取 Prism 区域；宿主必须原样打包 `.prism`：

```kotlin
android {
    androidResources { noCompress += "prism" }
}
```

文件来源同样使用只读 `offset/length` 区域。资源探测与引擎选择可挂起，在 IO 上执行头部读取；不会因使用同一个后缀而选错引擎。

统一请求默认自动播放，`autoPlay=false` 只准备首帧；`repeatCount=0` 播一次、`-1` 无限循环。
播放模式默认保持 Hanna 实际使用的 `Streaming(32)`，可向 Android `PrismMotionKapEngine` 传入其它 `KtxPlaybackMode`；
`Static` 用更高显存换取较少的逐帧上传，不作为自动降级策略。

## 构建

```bash
./gradlew build                                  # Android / JVM 库
./gradlew :prism-motion-core:jvmTest :prism-motion-probe:jvmTest
./gradlew nativeCli                              # CLI：native/build/bin/linuxX64/releaseExecutable/prism-motion.kexe
```

`local.properties` 需要一行 `sdk.dir=<Android SDK>`。

引擎依赖支持 Region 来源的 KAP / VAP 版本化包，坐标以 [依赖清单](<gradle/libs.versions.toml>) 为准。当前通过本机 Maven 接入，不引用其它仓库的源码或构建目录。在各 SDK 仓库验证后执行：

```bash
./gradlew publishToMavenLocal -PskipSigning=true
```

已发布版本不覆盖；修改 SDK 后应使用新的版本号。

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
| Windows x86_64 | 在 Linux 上交叉编译 | 当前支持 `probe` / `check` / `engines`；新打包流程需要 PNG 解码以生成准确元数据，Windows PNG/H.264 尚未接入 |
| macOS arm64 / x86_64 | 只能在 macOS 主机上构建 | 代码已就绪，未在本机构建 |

`tools/` 按平台分目录（`tools/linux-x86_64/`、`tools/windows-x86_64/`、`tools/macos-arm64/` …），
构建时把对应平台的 `astcenc` 追加到该平台的可执行文件末尾；缺哪个平台就跳过哪个。

```bash
prism-motion.kexe engines                                  # 引擎与平台支持
prism-motion.kexe probe assets/video.prism assets/texture.prism  # 读统一元数据与载荷声明
prism-motion.kexe check assets/                                 # 校验整批资源
prism-motion.kexe pack kap --frames frames/ --out texture.prism --fps 24 --format rgba8
prism-motion.kexe pack vap --frames frames/ --out video.prism --fps 30
```

- 所有命令支持 `--json`，输出 `{"ok":…,"command":…}`，便于 agent 解析。
- 退出码：`0` 成功、`1` 校验失败、`2` 用法错误、`3` 运行失败。
- `check` 把不认识的扩展名也算失败；读不了的目录直接跳过。
- `pack vap` / `pack kap --format rgba8` 用内置栈：stb_image 解 PNG → OpenH264 编码 → 自写 MP4 封装，
  **不需要任何外部工具**；只有 `pack kap --format astc-*` 需要 `astcenc`（内嵌，也可用 `--astcenc` 覆盖）。
- `probe` 先验证 Prism 外层结构、CRC32、SHA-256，再比对内部载荷类型、尺寸、帧数、帧率与透明语义；矛盾时报错，不静默改用另一份配置。
- 当前只支持一份载荷、sRGB 和整数 CFR 播放；有理帧率在格式层保留，当前后端明确拒绝不支持的分母。

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
  `astcenc -dl` / `-ds` 子进程（按线性 / sRGB profile）。库查找顺序：`PRISM_MOTION_ASTC_LIB` > `~/.cache/prism-motion/native/`
  > `thirdparty/native/<platform>/` > 系统路径。
- `compose`、`engine-vap`、`engine-kap` 都已同时支持 Android 和 JVM（Compose Multiplatform）。
- **JVM 侧不再依赖 FFmpeg**：`engine-vap` 自带 MP4 解封装（`VapMp4`），解码用
  OpenH264 经 JNA 调 `libprismdecode.so`；`engine-kap` 解码用 `libastcenc.so`。两者都是
  `scripts/build-thirdparty.sh` 编出来的原生库。
- Windows 产物只做了交叉编译验证，没有真机 / wine 运行；且缺少 stb_image / OpenH264 的 mingw 交叉编译，
  因此 PNG 解码与 H.264 编码在 Windows 上会明确报错（其它命令正常）。
- macOS 目标只能在 macOS 主机上构建。
- 当前 H.264 编码配置为 Constrained Baseline；没有可靠的同源感知质量对照，
  不宣称相对 x264/x265 的固定体积或 PSNR 差值。
- JVM 仍在打开时预解码整段帧，长片段内存与加载延迟随帧数增长；有界位图缓存不改变这一限制。
- 当前 OpenH264 shim 没有延迟帧 flush，不宣称完整支持 B-frame MP4。
