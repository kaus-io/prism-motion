package com.zxhhyj.vap.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal actual fun VapPosterImpl(
    composition: VapComposition?,
    modifier: Modifier,
    contentScale: ContentScale,
) {
    // Load is header-only now; the poster is warmed on demand here for the still-only
    // / gallery path. Surface playback never reaches this composable.
    // load 现在只解析头部；静帧 / 图集路径在这里按需预热海报。Surface 播放不会走到这里。
    val image by produceState<ImageBitmap?>(
        initialValue = composition?.poster?.snapshot()
            ?.cropped
            ?.takeUnless { it.isRecycled }
            ?.asImageBitmap(),
        composition,
    ) {
        val c = composition ?: return@produceState
        val entry = c.poster.snapshot() ?: withContext(Dispatchers.IO) {
            c.poster.warm(c.source, c.config)
            c.poster.snapshot()
        }
        value = entry?.cropped?.takeUnless { it.isRecycled }?.asImageBitmap()
    }
    val bitmap = image
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = modifier,
            contentScale = contentScale,
        )
    } else {
        Box(modifier)
    }
}
