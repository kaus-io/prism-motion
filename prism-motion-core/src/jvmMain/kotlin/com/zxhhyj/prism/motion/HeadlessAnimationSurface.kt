package com.zxhhyj.prism.motion

/**
 * JVM 侧的无头输出目标：只承载状态与尺寸，不渲染像素。
 * CLI 与单元测试用它代替 Android 的 `Surface`。
 */
public object HeadlessAnimationSurface : AnimationSurface
