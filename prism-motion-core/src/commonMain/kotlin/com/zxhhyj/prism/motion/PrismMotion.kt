package com.zxhhyj.prism.motion

/**
 * 一次动画的来源。引擎只按能力声明自己支持哪些来源，不做静默降级。
 */
public sealed interface PrismMotionSource {
    public data class Asset(public val path: String) : PrismMotionSource
    public data class File(public val path: String) : PrismMotionSource
    public data class Bytes(public val bytes: ByteArray) : PrismMotionSource {
        override fun equals(other: Any?): Boolean =
            other is Bytes && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = bytes.contentHashCode()
    }
}

/** 已注册的引擎。 */
public enum class PrismMotionEngineId { Kap, Vap }

/** 容器格式与负责它的引擎、后缀。CLI 与引擎共用这一份定义。 */
public enum class PrismMotionFormat(
    public val engine: PrismMotionEngineId,
    public val suffix: String,
) {
    Vap(PrismMotionEngineId.Vap, ".mp4"),
    Kap(PrismMotionEngineId.Kap, ".ktx"),
    ;

    public companion object {
        /** 按文件名后缀识别格式；不认识时返回 null，由调用方明确失败。 */
        public fun ofFileName(fileName: String): PrismMotionFormat? =
            entries.firstOrNull { fileName.endsWith(it.suffix, ignoreCase = true) }
    }
}

/**
 * 引擎输出的目标。平台无关：Android 用 [AndroidPrismMotionSurface]，JVM 用
 * [HeadlessPrismMotionSurface] 或调用方自己的实现。
 */
public interface PrismMotionSurface

public data class PrismMotionRequest(
    val source: PrismMotionSource,
    /** 显式指定引擎；null 时按 [PrismMotionEngine.supports] 自动选择。 */
    val engine: PrismMotionEngineId? = null,
    val repeatCount: Int = 0,
)

public sealed interface PrismMotionStatus {
    public data object Idle : PrismMotionStatus
    public data object Preparing : PrismMotionStatus
    public data object Ready : PrismMotionStatus
    public data class Failed(val error: Throwable) : PrismMotionStatus
}

/**
 * 一次打开的资源。两个引擎都必须能把输出挂到外部 [PrismMotionSurface]；
 * 宿主（TextureView / SurfaceView / 外部 Surface）由调用方选择。
 */
public interface PrismMotionResource {
    public val width: Int
    public val height: Int
    public val durationMs: Long

    /** 当前输出是否已经有一帧可显示的内容。 */
    public val hasOutput: Boolean
    public val progress: Float
    public val isPlaying: Boolean

    public fun attach(surface: PrismMotionSurface, width: Int, height: Int)
    public fun resize(width: Int, height: Int)
    public fun detach()
    public fun play()
    public fun pause()
    public fun seekTo(progress: Float)
    public fun release()
}

/** 引擎只负责“能不能开”和“怎么开”。 */
public interface PrismMotionEngine {
    public val id: PrismMotionEngineId

    public fun supports(source: PrismMotionSource): Boolean

    public suspend fun open(source: PrismMotionSource): PrismMotionResource
}

/** 显式优先，其次按支持能力选；都不支持时返回 null，由调用方明确失败。 */
public class PrismMotionEngineRegistry(engines: List<PrismMotionEngine>) {
    private val engines = engines.associateBy { it.id }

    public val engineIds: Set<PrismMotionEngineId> get() = engines.keys

    public fun engineFor(request: PrismMotionRequest): PrismMotionEngine? {
        request.engine?.let { requested -> return engines[requested]?.takeIf { it.supports(request.source) } }
        return engines.values.firstOrNull { it.supports(request.source) }
    }
}
