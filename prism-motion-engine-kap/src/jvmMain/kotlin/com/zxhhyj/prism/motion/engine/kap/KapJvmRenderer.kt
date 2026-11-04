package com.zxhhyj.prism.motion.engine.kap

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.zxhhyj.prism.motion.AnimationEngineId
import com.zxhhyj.prism.motion.compose.AnimationRegistry
import com.zxhhyj.prism.motion.compose.JvmFrameRenderer

/** JVM 渲染器：绘制逻辑在共享基类里。 / Renderer: drawing logic lives in the shared base. */
public object KapJvmRenderer : JvmFrameRenderer()

/** 便捷入口：只注册 KAP 一个引擎（JVM）。 / Convenience: register only the JVM KAP engine. */
@Composable
public fun rememberKapAnimationRegistry(): AnimationRegistry = remember {
    AnimationRegistry(
        engines = listOf(KapAnimationEngine()),
        renderers = mapOf(AnimationEngineId.Kap to KapJvmRenderer),
    )
}
