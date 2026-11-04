package com.zxhhyj.prism.motion.bench.android

import android.content.Context

/** APK assets 里符合命名约定的资产。 / Assets inside the APK matching the naming convention. */
fun listBenchAssets(context: Context, only: String? = null): List<String> =
    context.assets.list("").orEmpty()
        .filter { it.endsWith(".mp4") || it.endsWith(".ktx") }
        .filter { only == null || it.substringBeforeLast('.') == only }
        .sorted()
