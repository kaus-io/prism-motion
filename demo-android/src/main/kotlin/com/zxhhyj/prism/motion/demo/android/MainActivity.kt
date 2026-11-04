package com.zxhhyj.prism.motion.demo.android

import android.app.ActivityManager
import android.content.Context
import android.os.Bundle
import android.os.Debug
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import com.zxhhyj.prism.motion.PrismMotionRequest
import com.zxhhyj.prism.motion.PrismMotionSource
import com.zxhhyj.prism.motion.compose.PrismMotion
import com.zxhhyj.prism.motion.compose.PrismMotionRegistry
import com.zxhhyj.prism.motion.compose.rememberPrismMotionState
import com.zxhhyj.prism.motion.PrismMotionEngineId
import com.zxhhyj.prism.motion.engine.kap.PrismMotionKapEngine
import com.zxhhyj.prism.motion.engine.kap.PrismMotionKapRenderer
import com.zxhhyj.prism.motion.engine.vap.PrismMotionVapEngine
import com.zxhhyj.prism.motion.engine.vap.PrismMotionVapRenderer
import java.io.File

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bench = intent.getBooleanExtra("bench", false)
        val only = intent.getStringExtra("only")
        setContent {
            MaterialTheme {
                if (bench) BenchScreen(applicationContext, only) else DemoApp(applicationContext)
            }
        }
    }
}

/** 资产列表：来自 assets 根目录，命名与桌面版一致。 / Asset names from the assets root. */
fun listAssets(context: Context): List<String> =
    context.assets.list("").orEmpty()
        .filter { it.endsWith(".mp4") || it.endsWith(".ktx") }
        .sorted()

/** 桌面版同款：两个引擎 + 各自的渲染器。 / Same as the desktop demo: both engines. */
@Composable
fun rememberDemoRegistry(context: Context): PrismMotionRegistry = remember(context) {
    PrismMotionRegistry(
        engines = listOf(PrismMotionVapEngine(), PrismMotionKapEngine(context)),
        renderers = mapOf(
            PrismMotionEngineId.Vap to PrismMotionVapRenderer,
            PrismMotionEngineId.Kap to PrismMotionKapRenderer,
        ),
    )
}

@Composable
fun DemoApp(context: Context) {
    val assets = remember { listAssets(context) }
    val registry = rememberDemoRegistry(context)
    var index by remember { mutableStateOf(0) }
    var count by remember { mutableStateOf(4) }
    var fps by remember { mutableStateOf(0.0) }
    var pss by remember { mutableStateOf(0L) }
    val frames = remember { ArrayDeque<Long>(120) }

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
    LaunchedEffect(Unit) {
        while (true) {
            pss = Memory.pssMb(context)
            kotlinx.coroutines.delay(500)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { if (assets.isNotEmpty()) index = (index - 1 + assets.size) % assets.size }) { Text("◀") }
            Text(assets.getOrNull(index) ?: "没有资产", fontSize = 11.sp)
            Button(onClick = { if (assets.isNotEmpty()) index = (index + 1) % assets.size }) { Text("▶") }
            Button(onClick = { count = (count / 2).coerceAtLeast(1) }) { Text("−") }
            Text("×$count", fontSize = 11.sp)
            Button(onClick = { count = (count * 2).coerceAtMost(32) }) { Text("+") }
        }
        Text("fps ${"%.1f".format(fps)}   PSS $pss MB", fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp))

        LazyVerticalGrid(columns = GridCells.Adaptive(120.dp), modifier = Modifier.fillMaxSize().background(Color.Black)) {
            items(count) { item ->
                key(item) {
                    val asset = assets.getOrNull(index)
                    val state = rememberPrismMotionState(
                        remember(asset, item) { PrismMotionRequest(PrismMotionSource.Asset(asset.orEmpty())) },
                        registry,
                    )
                    PrismMotion(state, modifier = Modifier.fillMaxWidth().padding(2.dp), contentScale = ContentScale.Fit)
                }
            }
        }
    }
}

/** 自动基准：逐个场景播放，把帧间隔与 PSS 写到外部文件目录。 / Bench mode writing CSV to the app's files dir. */
@Composable
fun BenchScreen(context: Context, only: String?) {
    val assets = remember(only) { listAssets(context).filter { only == null || it.startsWith(only) } }
    val counts = remember { listOf(1, 8, 16) }
    val scenarios = remember(assets) { assets.flatMap { a -> counts.map { a to it } } }
    var index by remember { mutableStateOf(0) }
    var done by remember { mutableStateOf(false) }
    val registry = rememberDemoRegistry(context)

    if (done) {
        LaunchedEffect(Unit) { android.util.Log.i("prism-bench", "BENCH_DONE " + File(context.getExternalFilesDir(null), "bench.csv")) }
        Text("bench 完成：${context.getExternalFilesDir(null)}/bench.csv")
        return
    }

    val (asset, count) = scenarios[index]
    val samples = remember(index) { ArrayList<Long>() }
    var pssMax by remember(index) { mutableStateOf(0L) }

    LaunchedEffect(index) {
        val start = System.nanoTime()
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L && (now - start) / 1_000_000 > 2_000) samples.add((now - last) / 1_000)
                last = now
            }
            pssMax = maxOf(pssMax, Memory.pssMb(context))
            if ((System.nanoTime() - start) / 1_000_000 > 8_000) break
        }
        val sorted = samples.sorted()
        val avg = if (sorted.isEmpty()) 0.0 else sorted.average() / 1000.0
        val p95 = if (sorted.isEmpty()) 0.0 else sorted[(sorted.size * 95 / 100).coerceAtMost(sorted.size - 1)] / 1000.0
        appendCsv(context, "$asset,$count,${sorted.size},$avg,$p95,${Memory.pssMb(context)},$pssMax")
        if (index + 1 < scenarios.size) index++ else done = true
    }

    Column(Modifier.fillMaxSize()) {
        Text("bench ${index + 1}/${scenarios.size}: $asset × $count", fontSize = 11.sp, modifier = Modifier.padding(8.dp))
        LazyVerticalGrid(columns = GridCells.Adaptive(120.dp), modifier = Modifier.fillMaxSize()) {
            items(count) { item ->
                key(item) {
                    val state = rememberPrismMotionState(
                        remember(index, item) { PrismMotionRequest(PrismMotionSource.Asset(asset)) },
                        registry,
                    )
                    PrismMotion(state, modifier = Modifier.fillMaxWidth().padding(2.dp), contentScale = ContentScale.Fit)
                }
            }
        }
    }
}

fun appendCsv(context: Context, row: String) {
    val dir = context.getExternalFilesDir(null) ?: context.filesDir
    val file = File(dir, "bench.csv")
    if (!file.exists()) file.writeText("asset,count,frames,avg_frame_ms,p95_frame_ms,pss_mb,pss_max_mb\n")
    file.appendText(row + "\n")
}

/** PSS 采样：Debug.MemoryInfo 的 totalPss。 / Samples PSS via Debug.MemoryInfo. */
object Memory {
    fun pssMb(context: Context): Long {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        return info.totalPss / 1024L
    }

    fun availMb(context: Context): Long {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        return info.availMem / (1024 * 1024)
    }
}
