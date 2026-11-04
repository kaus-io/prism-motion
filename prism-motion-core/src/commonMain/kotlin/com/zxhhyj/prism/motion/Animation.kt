package com.zxhhyj.prism.motion

/**
 * 一次动画的来源。引擎只按能力声明自己支持哪些来源，不做静默降级。
 */
public sealed interface AnimationSource {
    public data class Asset(public val path: String) : AnimationSource
    public data class File(public val path: String) : AnimationSource
    public data class Bytes(public val bytes: ByteArray) : AnimationSource {
        override fun equals(other: Any?): Boolean =
            other is Bytes && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = bytes.contentHashCode()
    }
}

/** 已注册的引擎。 */
public enum class AnimationEngineId { Kap, Vap }

/** 容器格式与负责它的引擎、后缀。CLI 与引擎共用这一份定义。 */
public enum class AnimationFormat(
    public val engine: AnimationEngineId,
    public val suffix: String,
) {
    Vap(AnimationEngineId.Vap, ".mp4"),
    Kap(AnimationEngineId.Kap, ".ktx2"),
    ;

    public companion object {
        /** 按文件名后缀识别格式；不认识时返回 null，由调用方明确失败。 */
        public fun ofFileName(fileName: String): AnimationFormat? =
            entries.firstOrNull { fileName.endsWith(it.suffix, ignoreCase = true) }
    }
}

/**
 * 引擎输出的目标。平台无关：Android 用 [AndroidAnimationSurface]，JVM 用
 * [HeadlessAnimationSurface] 或调用方自己的实现。
 */
public interface AnimationSurface

public data class AnimationRequest(
    val source: AnimationSource,
    /** 显式指定引擎；null 时按 [AnimationEngine.supports] 自动选择。 */
    val engine: AnimationEngineId? = null,
    val repeatCount: Int = 0,
)

public sealed interface AnimationStatus {
    public data object Idle : AnimationStatus
    public data object Preparing : AnimationStatus
    public data object Ready : AnimationStatus
    public data class Failed(val error: Throwable) : AnimationStatus
}

/**
 * 一次打开的资源。两个引擎都必须能把输出挂到外部 [AnimationSurface]；
 * 宿主（TextureView / SurfaceView / 外部 Surface）由调用方选择。
 */
public interface AnimationResource {
    public val width: Int
    public val height: Int
    public val durationMs: Long

    /** 当前输出是否已经有一帧可显示的内容。 */
    public val hasOutput: Boolean
    public val progress: Float
    public val isPlaying: Boolean

    public fun attach(surface: AnimationSurface, width: Int, height: Int)
    public fun resize(width: Int, height: Int)
    public fun detach()
    public fun play()
    public fun pause()
    public fun seekTo(progress: Float)
    public fun release()
}

/** 引擎只负责“能不能开”和“怎么开”。 */
public interface AnimationEngine {
    public val id: AnimationEngineId

    public fun supports(source: AnimationSource): Boolean

    public suspend fun open(source: AnimationSource): AnimationResource
}

/** 显式优先，其次按支持能力选；都不支持时返回 null，由调用方明确失败。 */
public class AnimationEngineRegistry(engines: List<AnimationEngine>) {
    private val engines = engines.associateBy { it.id }

    public val engineIds: Set<AnimationEngineId> get() = engines.keys

    public fun engineFor(request: AnimationRequest): AnimationEngine? {
        request.engine?.let { requested -> return engines[requested]?.takeIf { it.supports(request.source) } }
        return engines.values.firstOrNull { it.supports(request.source) }
    }
}
