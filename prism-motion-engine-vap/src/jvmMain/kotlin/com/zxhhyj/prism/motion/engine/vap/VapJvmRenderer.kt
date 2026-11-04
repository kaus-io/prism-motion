package com.zxhhyj.prism.motion.engine.vap

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.zxhhyj.prism.motion.AnimationEngineId
import com.zxhhyj.prism.motion.compose.AnimationRegistry
import com.zxhhyj.prism.motion.compose.JvmFrameRenderer

/** JVM 渲染器：绘制逻辑在共享基类里。 / Renderer: drawing logic lives in the shared base. */
public object VapJvmRenderer : JvmFrameRenderer()

/** 便捷入口：只注册 VAP 一个引擎（JVM）。 / Convenience: register only the JVM VAP engine. */
@Composable
public fun rememberVapAnimationRegistry(): AnimationRegistry = remember {
    AnimationRegistry(
        engines = listOf(VapAnimationEngine()),
        renderers = mapOf(AnimationEngineId.Vap to VapJvmRenderer),
    )
}
