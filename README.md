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
| `prism-motion-cli` | JVM | 给 agent / CI 用的命令行 |

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
./gradlew :prism-motion-cli:installDist      # 产物在 prism-motion-cli/build/install/prism-motion/bin/
```

`local.properties` 需要一行 `sdk.dir=<Android SDK>`。

KAP / VAP 没有公共仓库，只有本机 maven（`0.x-LOCAL`）。在各自仓库执行：

```bash
./gradlew publishToMavenLocal -Pversion=0.1.0-LOCAL -PskipSigning=true
```

## CLI

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
- `pack vap` 需要 `ffmpeg`（可用 `--ffmpeg` 指定）；`pack kap --format rgba8` 不需要外部工具，
  ASTC 格式额外需要 `astcenc`。
- `probe` 读取：VAP 的 `vapc` box（尺寸 / 帧数 / fps / hasAlpha）与 `mvhd` 时长；
  KAP 的 KTX2 头（尺寸 / layerCount）与 key/value（`anim.fps` 等）。

## 已知限制

- KAP / VAP 引擎仍是 Android-only（MediaCodec / Vulkan）；JVM 侧提供契约、探测与打包。
- CLI 依赖已发布的 `com.kaus-io:kap-*` / `com.kaus-io:vap-*`，版本见 `gradle/libs.versions.toml`。
