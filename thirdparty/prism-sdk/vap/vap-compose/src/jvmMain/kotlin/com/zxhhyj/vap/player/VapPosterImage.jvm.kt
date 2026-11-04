package com.zxhhyj.vap.player

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale

@Composable
internal actual fun VapPosterImpl(
    composition: VapComposition?,
    modifier: Modifier,
    contentScale: ContentScale,
) {
    // Desktop poster cache is a no-op; the gallery does not need a live decode loop.
    // 桌面端海报缓存是空操作；图集不必为此再开一路解码循环。
    Box(modifier)
}
