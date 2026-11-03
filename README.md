# Prism Motion

统一动画门面：`VAP` 与 `KAP` 作为可替换的引擎实现。

## 结构

```
prism-motion-core         // 源、请求、引擎、资源、注册表（无 Compose）
prism-motion-compose      // AnimationOutput / AnimationRenderer / AnimationState / Animation()
prism-motion-engine-kap   // KAP（KTX2 + Vulkan）实现
```

VAP 引擎按同样的契约实现 `AnimationEngine` + `AnimationRenderer` 即可接入。

## 契约

- 引擎只负责“能否打开”和“如何打开”：`supports(source)` / `open(source)`。
- 打开后是 `AnimationResource`：尺寸、时长、进度、是否已有可显示内容，以及
  `attach(Surface, w, h)` / `detach()`；宿主（TextureView / SurfaceView / 外部 Surface）由上层选。
- 目前只实现 `AnimationOutput.TextureView`；`SurfaceView` / `External` 留接口，
  传入未实现的宿主会明确失败，不静默退回其它合成方式。
- 加载失败是 `AnimationStatus.Failed` 状态，不向组合抛异常；取消异常照常上抛。
- 引擎显式指定优先，否则按 `supports` 选择，**不做跨引擎静默降级**。
