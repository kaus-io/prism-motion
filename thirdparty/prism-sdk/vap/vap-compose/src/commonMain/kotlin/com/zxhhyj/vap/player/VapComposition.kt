package com.zxhhyj.vap.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.zxhhyj.vap.decode.parseMp4Asset
import com.zxhhyj.vap.decode.parseMp4Bytes
import com.zxhhyj.vap.decode.parseMp4File
import com.zxhhyj.vap.decode.parseMp4Region
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/**
 * Loaded VAP. Marked `@Stable` (not `@Immutable`) because [source] may be
 * `VapSource.Bytes` whose Snapshot state can change via `VapSource.Bytes.update`.
 *
 * 已加载的 VAP。标注为 `@Stable`（而非 `@Immutable`）是因为 [source] 可能是 `VapSource.Bytes`，
 * 其 Snapshot 状态会通过 `VapSource.Bytes.update` 发生变化。
 */
@Stable
public class VapComposition internal constructor(
    public val config: VapConfig,
    internal val source: VapSource,
) {
    /**
     * Frame-0 poster owned by this composition. Filled by [loadVapComposition] /
     * [loadVapCompositionAsync]; dropped when the composition is.
     *
     * 本 composition 持有的第 0 帧海报。由 [loadVapComposition] /
     * [loadVapCompositionAsync] 填入；composition 丢弃时一并释放。
     */
    internal val poster: VapPlatformPoster = VapPlatformPoster()
}


/**
 * Blocking load: parses the MP4 header and wraps the spec into a `VapSource`.
 * The poster is lazy: Surface playback decodes packed frame 0 directly into the
 * Vulkan opening texture, while the still-only / Canvas path warms the cropped
 * poster on demand. Same work as [loadVapCompositionAsync]; this variant runs on
 * the caller thread (do not call from the main thread unless a brief stall is
 * acceptable).
 *
 * 阻塞加载：解析 MP4 头部并将 spec 包装为 `VapSource`。海报按需加载：
 * Surface 播放把打包第 0 帧直接解码进 Vulkan opening 纹理；静帧 / Canvas 路径
 * 按需预热裁切海报。工作量与 [loadVapCompositionAsync] 相同；
 * 本变体跑在调用线程上（除非能接受短暂卡顿，否则不要在主线程调用）。
 */
public fun loadVapComposition(
    spec: VapCompositionSpec,
    prewarmGpu: Boolean = true,
): VapComposition {
    // Start the shared Vulkan device warmup before parsing/Compose work so the ~60ms
    // device creation overlaps the first-frame setup instead of sitting on it.
    // 在解析/Compose 之前启动共享 Vulkan 设备预热，让约 60ms 的设备创建与首帧
    // 准备重叠，而不是落在关键路径上。
    // Poster-only / KTX fallback paths pass false so they never create the VAP device.
    // 仅海报 / KTX 兜底路径传 false，避免创建 VAP Vulkan 设备。
    if (prewarmGpu) prewarmVapGpu()
    // Keep load header-only. Surface playback decodes packed frame 0 directly into the
    // Vulkan opening texture; the still-only / Canvas path warms the cropped poster on
    // demand. Eagerly compositing a 720x1280 poster here raised the Java heap high-water
    // mark even when the Surface path immediately dropped it.
    // load 只解析头部。Surface 播放会把打包第 0 帧直接解码进 Vulkan opening 纹理；
    // 静帧 / Canvas 路径按需预热裁切海报。若在这里提前合成 720x1280 海报，
    // 即便 Surface 路径随后立即丢弃，也会抬高 Java heap 高水位。
    return parseVapComposition(spec)
}


public fun loadVapComposition(
    source: VapSource,
    prewarmGpu: Boolean = true,
): VapComposition =
    loadVapComposition(source.toCompositionSpec(), prewarmGpu)

/**
 * Same load as [loadVapComposition] (header + poster) on `Dispatchers.IO`,
 * cancellable via `runInterruptible`. Use from a Composable / coroutine so the
 * main thread is not blocked.
 *
 * 与 [loadVapComposition] 相同的加载（头部 + 海报），在 `Dispatchers.IO` 上执行，
 * 可通过 `runInterruptible` 取消。从 Composable / 协程调用，避免堵住主线程。
 */
public suspend fun loadVapCompositionAsync(
    spec: VapCompositionSpec,
    prewarmGpu: Boolean = true,
): VapComposition =
    withContext(Dispatchers.IO) {
        runInterruptible {
            loadVapComposition(spec, prewarmGpu)
        }
    }

public suspend fun loadVapCompositionAsync(
    source: VapSource,
    prewarmGpu: Boolean = true,
): VapComposition =
    loadVapCompositionAsync(source.toCompositionSpec(), prewarmGpu)


/**
 * Remember a [VapComposition] across recompositions; reloads in the background when [spec] changes.
 *
 * 在多次重组之间记住 [VapComposition]；当 [spec] 变更时在后台重新加载。
 *
 * @param spec File, Android asset, or in-memory MP4 bytes. 文件、Android asset 或内存中的 MP4 字节。
 * @param onError Receives non-cancellation failures; defaults to re-throwing so the caller sees them.
 * 接收非取消失败；默认再抛出以便调用方可见。
 * @return A Compose `State` holding `null` until the first load completes.
 * 在首次加载完成前为 `null` 的 Compose `State`。
 */
@Composable
public fun rememberVapComposition(
    spec: VapCompositionSpec,
    onError: ((Throwable) -> Unit)? = null,
    prewarmGpu: Boolean = true,
): State<VapComposition?> {
    installVapPlatformContext()
    val state = remember(spec) { mutableStateOf<VapComposition?>(null) }
    val currentOnError by rememberUpdatedState(onError)

    LaunchedEffect(spec) {
        // Keep previous composition while reloading so consumers do not see a null flash.
        // 重新加载期间保留旧 composition，避免消费者看到 null 闪烁。
        try {
            state.value = loadVapCompositionAsync(spec, prewarmGpu)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            state.value = null
            currentOnError?.invoke(t) ?: throw t
        }
    }

    return state
}

/** Header parse only. Shared by the sync and async loaders. / 只解析头部，供同步/异步加载共用。 */
private fun parseVapComposition(spec: VapCompositionSpec): VapComposition {
    val source = spec.toVapSource()
    val config = when (source) {
        is VapSource.AbsolutePath -> parseMp4File(source.path)
        is VapSource.Asset -> parseMp4Asset(source.assetPath)
        is VapSource.Bytes -> parseMp4Bytes(source.data)
        is VapSource.Region -> parseMp4Region(source)
    }
    return VapComposition(config, source)
}
