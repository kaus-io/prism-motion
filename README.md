# Prism Motion

统一动画门面：`VAP`（MP4 + alpha）与 `KAP`（KTX2 + Vulkan）作为可替换的引擎实现。

## 模块

| 模块 | 目标平台 | 作用 |
| --- | --- | --- |
| `prism-motion-core` | Android + JVM（KMP） | 源、请求、引擎、注册表、输出面、状态契约；无 Compose |
| `prism-motion-compose` | Android | `AnimationOutput` / `AnimationRenderer` / `AnimationState` / `Animation()` |
| `prism-motion-engine-kap` | Android | KAP（KTX2 + Vulkan）引擎 |
| `prism-motion-engine-vap` | Android | VAP（MediaCodec）引擎 |
| `prism-motion-probe` | JVM | MP4 / KTX2 容器探测与校验，不解码 |
| `prism-motion-cli` | JVM | 给 agent / CI 用的命令行（单文件 jar） |
| `native/` | Kotlin/Native | 同一套命令的原生可执行文件，独立 Gradle 构建 |

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
./gradlew build
./gradlew :prism-motion-core:jvmTest :prism-motion-probe:test
./gradlew :prism-motion-cli:shadowJar        # 单文件 CLI：prism-motion-cli/build/libs/prism-motion-1.0.0-all.jar
```

`local.properties` 需要一行 `sdk.dir=<Android SDK>`。

KAP / VAP 没有公共仓库，只有本机 maven（`0.x-LOCAL`）。在各自仓库执行：

```bash
./gradlew publishToMavenLocal -Pversion=0.1.0-LOCAL -PskipSigning=true
```

## CLI

单文件发行：`shadowJar` 会把 kotlin stdlib、coroutines、probe、vap-encode、kap-encode，
以及 `tools/` 下的 `ffmpeg` / `astcenc` 一起打进 jar（本机约 40 MB）。
运行时按需把原生工具解压到 `~/.cache/prism-motion/tools/`，不需要 `lib/` 目录、不需要 Gradle：

```bash
java -jar prism-motion-1.0.0-all.jar probe assets/a.mp4
java -jar prism-motion-1.0.0-all.jar pack kap --frames frames/ --out out.ktx2
```

也可以用 `./gradlew :prism-motion-cli:installDist` 生成带启动脚本的 `bin/prism-motion`。

```bash
prism-motion engines                                  # 引擎与平台支持
prism-motion probe assets/a.mp4 assets/b.ktx2         # 读容器元数据
prism-motion check assets/                            # 校验整批资源
prism-motion pack kap --frames frames/ --out out.ktx2 --fps 24 --format rgba8
prism-motion pack vap --frames frames/ --out out.mp4 --fps 30
```

- 所有命令支持 `--json`，输出 `{"ok":…,"command":…}`，便于 agent 解析。
- 退出码：`0` 成功、`1` 校验失败、`2` 用法错误、`3` 运行失败。
- `check` 把不认识的扩展名也算失败；读不了的目录直接跳过。
- `pack vap` 用 `ffmpeg`，`pack kap --format astc-*` 用 `astcenc`；`--format rgba8` 不需要任何外部工具。
  查找顺序：`--ffmpeg/--astcenc` > `PRISM_MOTION_FFMPEG` / `PRISM_MOTION_ASTCENC` > jar 同级 `tools/`
  > `~/.cache/prism-motion/tools/` > **jar 内嵌**（缺失时自动解压）> `PATH`。
- 内嵌的是构建机 `tools/` 里的二进制（当前为 ffmpeg 7.0.2 静态版 + astcenc 4.8.0 avx2），
  只覆盖构建机平台（`embedded-tools/<os>-<arch>/`）；`tools/` 不入库。
- `probe` 读取：VAP 的 `vapc` box（尺寸 / 帧数 / fps / hasAlpha）与 `mvhd` 时长；
  KAP 的 KTX2 头（尺寸 / layerCount）与 key/value（`anim.fps` 等）。

## 原生 CLI（Kotlin/Native）

命令、参数、JSON 与 JVM 版一致，产物是单个可执行文件（约 82 MB，内含 ffmpeg/astcenc）：

```bash
./gradlew nativeCli
# native/build/bin/linuxX64/releaseExecutable/prism-motion.kexe
```

`native/` 是**独立 Gradle 构建**：Kotlin/Native 的 build service 无法与 AGP 模块共存于同一个
Gradle build，所以它通过 `srcDir` 复用 `prism-motion-core` / `prism-motion-probe` 的平台无关源码。
构建后会把 `tools/ffmpeg`、`tools/astcenc` 追加到可执行文件末尾（`PMTOOLS1` payload），
运行时首次使用解压到 `~/.cache/prism-motion/tools/`。

## 已知限制

- KAP / VAP 引擎仍是 Android-only（MediaCodec / Vulkan）；JVM / 原生侧提供契约、探测与打包。
- 原生 CLI 目前只构建 `linuxX64`；其它平台需要给 `native/build.gradle.kts` 加对应 target 并重新内嵌二进制。
- CLI 依赖已发布的 `com.kaus-io:kap-*` / `com.kaus-io:vap-*`，版本见 `gradle/libs.versions.toml`。
