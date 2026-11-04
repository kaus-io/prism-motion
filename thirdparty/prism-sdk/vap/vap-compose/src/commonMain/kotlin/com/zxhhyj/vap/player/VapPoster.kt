package com.zxhhyj.vap.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale

/**
 * Frame-0 still from [composition]. No MediaCodec, no Surface, no present loop.
 * Use this for galleries; open [VapAnimation] only when the clip is actually playing.
 *
 * 只画 [composition] 的第 0 帧静图。不开 MediaCodec、不建 Surface、不跑 present 循环。
 * 图集用这个；真正播放时再开 [VapAnimation]。
 */
@Composable
public fun VapPoster(
    composition: VapComposition?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    VapPosterImpl(composition = composition, modifier = modifier, contentScale = contentScale)
}

@Composable
internal expect fun VapPosterImpl(
    composition: VapComposition?,
    modifier: Modifier,
    contentScale: ContentScale,
)
