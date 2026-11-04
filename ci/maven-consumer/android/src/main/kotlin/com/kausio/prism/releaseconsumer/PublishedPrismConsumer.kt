package com.kausio.prism.releaseconsumer

import android.content.Context
import com.zxhhyj.prism.motion.PrismMotionEngine
import com.zxhhyj.prism.motion.PrismMotionEngineRegistry
import com.zxhhyj.prism.motion.PrismMotionRequest
import com.zxhhyj.prism.motion.PrismMotionSource
import com.zxhhyj.prism.motion.engine.kap.PrismMotionKapEngine
import com.zxhhyj.prism.motion.engine.vap.PrismMotionVapEngine

/** 仅使用已发布 Android API 构造两个引擎，不依赖演示界面或设备。 / Constructs both engines using published Android APIs without a demo UI or device. */
public class PublishedPrismConsumer(context: Context) {
    private val registry = PrismMotionEngineRegistry(
        listOf(PrismMotionVapEngine(context), PrismMotionKapEngine(context)),
    )

    /** 由挂起的公开注册表读取容器头选择引擎，不按后缀猜测载荷。 / Selects by the container header through the suspending public registry, not payload suffix guesses. */
    public suspend fun engineFor(source: PrismMotionSource): PrismMotionEngine? =
        registry.engineFor(PrismMotionRequest(source = source, autoPlay = false))
}
