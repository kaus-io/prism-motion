# KAP / VAP 性能修复与验证

## 对照范围

仅对照用户指定的原版 KAP **源码**，没有编译、安装或运行原版基准。
当前项目 Android KAP 本身依赖 `com.kaus-io:kap-compose:0.1.0-LOCAL`，不是另写一套解码器。
检查到发布包的 11 个 Compose、13 个 Android Kotlin 源文件与参考源码一致。

参考生产配置为 [HannaKtxAnimation.kt:48–55](</home/zxhhyj/AndroidStudioProjects/hanna/app-hanna/src/main/java/com/teyes/hanna/ui/common/HannaKtxAnimation.kt#L48-L55>)：
`Streaming(32)`、无限循环、显式 play/pause。不是通用 API 的 `Static` 默认值。
Prism 保持同一流式模式，继续直接调用原版 `KtxAnimation`，没有添加 Java 位图转换或逐帧字节数组拷贝。
共享 Vulkan 设备、FD 到 staging 的原生读取、纹理保留和 Zstd 解码文件缓存仍由原版实现负责。
共享 vsync 等实验选项没有擅自打开，不能把它们说成丢失后恢复的生产优化。
设备上已核实不存在 KAP 实验覆盖文件；demo / bench 的 manifest 还固定禁用覆盖，避免后续调试残留改变配置。

这说明门面没有另加一层逐帧解码/复制开销，**不等于未经实测就保证所有设备的性能数值与原版完全相同**。
Hanna 还会预热指定资产，未预热的 Prism 首帧不能拿来与其缓存热启动直接比较。

## 修复了什么

- **KAP 资产无法加载**：旧 APK 把 KTX 压缩，而原版 [KtxByteReader.kt:66–89](</home/zxhhyj/AndroidStudioProjects/hanna/library-teyes/kap/kap-android/src/main/kotlin/com/zxhhyj/kap/android/KtxByteReader.kt#L66-L89>) 依赖 `AssetManager.openFd()`。
  Demo、bench 的 APK 都为 `ktx` / `ktx2` 添加 `noCompress`，运行脚本也检查 ZIP 存储方式。
- **刚加载就被释放**：原先 `DisposableEffect(state)` 的释放闭包读取可变的最新 state，Preparing→Ready 时可能释放新资源。
  改为加载协程持有具体资源，在请求取消/离开组合时 `finally` 释放；取消到达和发布失败均有回归测试。
- **静帧被当作播放**：请求自动播放和额外重复次数现在会传递到底层。基准无限循环，演示也明确循环；状态读取实际播放器。
- **Android 重复加载**：同引擎最近一个不可变 asset 的 composition/header 共享，并发加载合并；文件和 Bytes 不缓存，避免变更后读到旧内容。
  不共享不同实例的播放时钟、纹理环或解码器。KAP 的模式可配置，默认仍是 `Streaming(32)`。
- **VAP 失败导致进程崩溃**：上游启动失败时，没有错误监听就会重新抛出。
  门面现在提供错误回调并公开运行期失败；不把 MediaCodec 资源不足当作零开销或偷偷减少实例。
  释放会移除上游播放组合，复用其异步 MediaCodec/Vulkan 拆除，避免在主线程重复销毁。
- **JVM ASTC**：修正 astcenc 结构/size_t/切片指针 ABI、线性/sRGB profile，以及 CLI fallback 的尺寸和 depth 头字段；
  同尺寸/格式复用一个 native context 与输入输出工作区，失败、换形状或退出时释放，不再每帧重建上下文。
- **JVM VAP**：每次动画复用一对 native 输入/I420 输出缓冲，异常路径也释放；检查返回状态、尺寸和无输出情况。
- **JVM 播放**：按共享 Compose 帧时钟与单调时间推进，迟到跳过已错过的时间，不用每实例 delay 累积漂移；暂停不等待帧时钟。
  实现有限/无限循环、最多 4 帧且 8MiB 的额外位图缓存、真实 draw 确认和幂等释放；释放同时丢弃预解码帧引用。

实现分别见 [统一生命周期](<../prism-motion-compose/src/commonMain/kotlin/com/zxhhyj/prism/motion/compose/PrismMotion.kt>)、
[Android KAP](<../prism-motion-engine-kap/src/androidMain/kotlin/com/zxhhyj/prism/motion/engine/kap/KapPrismMotionEngine.kt>)、
[Android VAP](<../prism-motion-engine-vap/src/androidMain/kotlin/com/zxhhyj/prism/motion/engine/vap/VapPrismMotionEngine.kt>)、
[JVM 帧播放器](<../prism-motion-compose/src/jvmMain/kotlin/com/zxhhyj/prism/motion/compose/JvmFramePlayer.kt>)、
[ASTC 绑定](<../prism-motion-engine-kap/src/jvmMain/kotlin/com/zxhhyj/prism/motion/engine/kap/AstcNative.kt>)、
[OpenH264 绑定](<../prism-motion-engine-vap/src/jvmMain/kotlin/com/zxhhyj/prism/motion/engine/vap/VapDecodeNative.kt>)。

## 验证方法

42 个回归测试通过：core 5、Compose/时间轴/缓存/生命周期 17、KAP 10、VAP 7、Android bench 3。
其中真实 Linux x86_64 astcenc、CLI 线性/sRGB 7×9 边缘尺寸、并发 ASTC 调用和 OpenH264/VAP 文件测试均通过环境变量启用，不只是 mock。
两个 Android 应用构建成功，桌面 demo 和 bench 编译成功。

```bash
PRISM_MOTION_ASTC_LIB="$PWD/thirdparty/native/linux-x86_64/libastcenc.so" \
PRISM_MOTION_DECODE_LIB="$PWD/thirdparty/native/linux-x86_64/libprismdecode.so" \
PRISM_TEST_ASTCENC="$PWD/thirdparty/tools/linux-x86_64/astcenc" \
PRISM_TEST_ASTC_KTX2="$PWD/demo-data/alpha-128-astc8x8.ktx" \
PRISM_TEST_VAP_MP4="$PWD/demo-data/alpha-128-b1500.mp4" \
./gradlew :prism-motion-core:jvmTest :prism-motion-compose:jvmTest \
  :prism-motion-engine-kap:jvmTest :prism-motion-engine-vap:jvmTest \
  :bench-android:testDebugUnitTest :bench-android:assembleDebug :demo-android:assembleDebug \
  :bench-jvm:compileKotlinJvm :demo-desktop:compileKotlinJvm
```

设备验证只运行当前项目。CC4PRO，Android API 33，arm64-v8a，物理显示 2000×1200、约 60Hz；应用可用区域更小。
每个实例固定为源素材像素尺寸，网格依据实际可用宽高安排，全部实例真实挂载；放不下就失败，不缩放或懒加载省略实例。
素材由同一 PNG 序列生成，24 帧 / 30fps，比较 ASTC8×8、RGBA8、VAP b1500，包含透明/不透明、128/256px。
每个资产重启进程，按 0/1/8/16 个实例顺序运行，等全部有真实输出后预热 500ms，再测量约 2500ms。

- `submitted_fps`：各实例成功提交帧率的平均值；`min_instance_fps` 同时暴露最慢实例。
- `ui_*`：UI 帧时钟稳定性，不是动画帧率；jank 阈值随显示刷新率调整。
- CPU：进程累计 CPU 毫秒数；100% 为一个逻辑核，包含采样器开销，未扣基线。
- PSS：后台 4Hz 采样；峰值覆盖加载/预热/测量，不等于纯稳态峰值。
- `first_output_ms`：所有实例具备引擎可显示内容的最晚观察时间，包含加载；不是物理屏幕扫描时间。
  KAP 使用原版 submit + TextureView 消费 + HWUI commit 门禁，VAP 使用其 Ready 状态。
- KAP 提交计数约每 100ms 发布一次，因此短窗口会产生边界误差，不能把 29.x 或 30.x 解读成精确掉帧/超帧。
- `valid=false` 行不能参与性能胜负比较；第一个单实例场景和后续多实例场景的缓存温度不同，首帧不可直接做并发缩放结论。

## 当前设备结果

完整数据为 [汇总 CSV](<results/cc4pro-current.csv>)、[采样时序 CSV](<results/cc4pro-current-samples.csv>)、
[设备参数与资产 SHA256](<results/cc4pro-current.json>)。28 行中有 26 行有效（含 7 行零实例基线），2 行 VAP16 失败；
脚本因此有意返回非零，没有把它们混入成功结果。

下表均为透明 256px、24 帧/30fps 的 **8 实例** 场景，总进程 PSS/CPU 包含观察器，编码质量并未做感知质量等价匹配：

| 编码 | 文件字节 | 平均提交 FPS/实例 | CPU % | 峰值 PSS MiB | 全部输出观察 ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| KAP ASTC8×8 | 393520 | 30.31 | 59.67 | 128 | 170 |
| KAP RGBA8 | 6291796 | 30.72 | 64.27 | 182 | 204 |
| VAP b1500 | 27181 | 30.19 | 133.61 | 171 | 613 |

这些是单次短采样，不是显著性对比，也不是优化前后提升百分比。KAP 计数发布粒度会使短窗口 FPS 偶尔超过素材 30fps。
零实例 CPU 约 15.2–15.6%，已保留在原始数据，未自动扣除；PSS 峰值包含启动过程。
UI 时钟 jank 均为 0，但**没有据此宣称动画逐提交间隔也完全没有抖动**。

KAP 的所有受测 16 实例场景均有输出并持续提交；透明 256px ASTC 的平均值约 29.98 FPS/实例，峰值总 PSS 153MiB。
VAP 的透明/不透明 8 实例均通过，16 实例报 `MediaCodec$CodecException: start failed`；门面现已记录失败而不崩进程。
另用全新进程单独启动透明 VAP16 仍失败，见 [冷启动复核](<results/cc4pro-vap16-cold.csv>)。
这不是“最多只能开 11/14 个”的会话上限测定，不能从失败前已有输出的数量推断硬件上限。

复现当前矩阵：

```bash
ASSETS='alpha-128-astc8x8 alpha-256-astc8x8 alpha-256-rgba8 opaque-256-astc8x8 opaque-256-rgba8 alpha-256-b1500 opaque-256-b1500' \
OUT="$PWD/bench-android/build/reports/verified-current.csv" \
./scripts/run-android-bench.sh <adb-serial> --counts 0,1,8,16 --measure-ms 2500
```

## 剩余边界

- JVM 仍在打开时解码整段 RGBA 帧；位图缓存预算不限制这些基础帧，长片段的内存和首帧延迟仍随帧数增长。
- ASTC 工作区目前全局串行、只保留一种尺寸/格式；不同尺寸并发可能切换上下文。ABI 仅验证了当前 Linux x86_64 非诊断版 astcenc。
- OpenH264 的现有 shim 把部分错误归为“需要更多数据”，也没有延迟帧 flush；当前不宣称完整支持 B-frame MP4。
- Android 同时可用的 MediaCodec 会话受设备资源限制；不能以少播实例、空白输出或软件静帧冒充同屏满负载成功。
- 本轮修正了旧测量本身，**没有可靠的修复前有效样本，不能给出“优化提升 X%”**。
  [旧图表](<charts/fps_vs_count.png>) 及其它同批图表作废，不作为当前性能证明。
