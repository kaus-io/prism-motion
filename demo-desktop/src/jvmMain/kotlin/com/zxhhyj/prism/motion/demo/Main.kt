package com.zxhhyj.prism.motion.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.zxhhyj.prism.motion.PrismMotionEngineId
import com.zxhhyj.prism.motion.PrismMotionRequest
import com.zxhhyj.prism.motion.PrismMotionSource
import com.zxhhyj.prism.motion.compose.PrismMotion
import com.zxhhyj.prism.motion.compose.PrismMotionRegistry
import com.zxhhyj.prism.motion.compose.rememberPrismMotionState
import com.zxhhyj.prism.motion.engine.kap.PrismMotionKapEngine
import com.zxhhyj.prism.motion.engine.kap.KapJvmRenderer
import com.zxhhyj.prism.motion.engine.vap.PrismMotionVapEngine
import com.zxhhyj.prism.motion.engine.vap.VapJvmRenderer
import kotlin.system.exitProcess

fun demoRegistry(): PrismMotionRegistry = PrismMotionRegistry(
    engines = listOf(PrismMotionVapEngine(), PrismMotionKapEngine()),
    renderers = mapOf(
        PrismMotionEngineId.Vap to VapJvmRenderer,
        PrismMotionEngineId.Kap to KapJvmRenderer,
    ),
)

/** 演示资产：目录下的 mp4/ktx 文件。 / Demo assets: mp4/ktx files in the directory. */
fun demoAssets(): List<java.io.File> {
    val dir = run {
        System.getProperty("prism.demo.data")?.let { return@run java.io.File(it) }
        val local = java.io.File("demo-data")
        if (local.isDirectory) local else java.io.File("../demo-data")
    }
    return dir.listFiles { file -> file.isFile && (file.name.endsWith(".mp4") || file.name.endsWith(".ktx")) }
        .orEmpty()
        .sortedBy { it.name }
}

fun main(args: Array<String>) {
    application {
        Window(onCloseRequest = ::exitApplication, title = "prism-motion demo") {
            MaterialTheme { DemoApp() }
        }
    }
}

@Composable
fun DemoApp() {
    val registry = remember { demoRegistry() }
    val assets = remember { demoAssets() }
    var assetIndex by remember { mutableStateOf(0) }
    val asset = assets.getOrNull(assetIndex)
    var count by remember { mutableStateOf(4) }
    val frames = remember { ArrayDeque<Long>(120) }
    var fps by remember { mutableStateOf(0.0) }

    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    frames.addLast(now - last)
                    while (frames.size > 120) frames.removeFirst()
                }
                last = now
            }
            fps = if (frames.isEmpty()) 0.0 else 1_000_000_000.0 / frames.average()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = { assetIndex = (assetIndex - 1 + assets.size) % assets.size }) { Text("◀", fontSize = 12.sp) }
            Text(asset?.name ?: "没有资产", fontSize = 12.sp)
            Button(onClick = { assetIndex = (assetIndex + 1) % assets.size }) { Text("▶", fontSize = 12.sp) }
            Button(onClick = { count = (count / 2).coerceAtLeast(1) }) { Text("− 数量", fontSize = 12.sp) }
            Text("×$count", fontSize = 12.sp)
            Button(onClick = { count = (count * 2).coerceAtMost(64) }) { Text("+ 数量", fontSize = 12.sp) }
            Text(
                "fps ${"%.1f".format(fps)}",
                fontSize = 12.sp,
            )
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(160.dp),
            modifier = Modifier.fillMaxSize().background(Color(0xFF101014)),
        ) {
            items(count) { index ->
                key(index) {
                    val state = rememberPrismMotionState(
                        remember(asset, index) {
                            PrismMotionRequest(PrismMotionSource.File(asset?.absolutePath.orEmpty()))
                        },
                        registry,
                    )
                    Box(Modifier.padding(4.dp).fillMaxWidth()) {
                        PrismMotion(state, contentScale = ContentScale.Fit)
                    }
                }
            }
        }
    }
}
