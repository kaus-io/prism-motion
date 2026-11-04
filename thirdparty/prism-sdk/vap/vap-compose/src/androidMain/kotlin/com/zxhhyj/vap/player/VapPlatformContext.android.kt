package com.zxhhyj.vap.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.zxhhyj.vap.decode.VapAndroid

@Composable
internal actual fun installVapPlatformContext() {
    VapAndroid.install(LocalContext.current.applicationContext)
}
