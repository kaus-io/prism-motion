package com.zxhhyj.prism.motion.bench.android

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zxhhyj.prism.motion.PrismMotionEngineId
import com.zxhhyj.prism.motion.PrismMotionFormat
import com.zxhhyj.prism.motion.PrismMotionRequest
import com.zxhhyj.prism.motion.PrismMotionSource
import com.zxhhyj.prism.motion.compose.PrismMotion
import com.zxhhyj.prism.motion.compose.PrismMotionRegistry
import com.zxhhyj.prism.motion.compose.rememberPrismMotionState
import com.zxhhyj.prism.motion.engine.kap.PrismMotionKapEngine
import com.zxhhyj.prism.motion.engine.kap.PrismMotionKapRenderer
import com.zxhhyj.prism.motion.engine.vap.PrismMotionVapEngine
import com.zxhhyj.prism.motion.engine.vap.PrismMotionVapRenderer
import java.io.File

class BenchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val only = intent.getStringExtra("only")
        setContent { MaterialTheme { BenchScreen(applicationContext, only) } }
    }
}

private const val WARMUP_MS = 800L
private const val MEASURE_MS = 2_500L
private const val COOLDOWN_MS = 300L
private const val FIRST_FRAME_TIMEOUT_MS = 5_000L

@Composable
fun BenchScreen(context: Context, only: String?) {
    val assets = remember(only) { listBenchAssets(context, only) }
    val counts = remember { listOf(1, 8, 16) }
    val scenarios = remember(assets) { assets.flatMap { asset -> counts.map { asset to it } } }
    var index by remember { mutableStateOf(0) }
    var done by remember { mutableStateOf(false) }
    val registry = remember {
        PrismMotionRegistry(
            engines = listOf(PrismMotionVapEngine(), PrismMotionKapEngine(context)),
            renderers = mapOf(
                PrismMotionEngineId.Vap to PrismMotionVapRenderer,
                PrismMotionEngineId.Kap to PrismMotionKapRenderer,
            ),
        )
    }

    if (done) {
        LaunchedEffect(Unit) {
            Log.i("prism-bench", "BENCH_DONE " + File(context.getExternalFilesDir(null), "bench.csv"))
        }
        Text("bench 完成", fontSize = 11.sp)
        return
    }

    val (asset, count) = scenarios[index]
    val samples = remember(index) { ArrayList<Long>() }
    val cpuStart = remember(index) { ProcStat.sample() }
    val startNs = remember(index) { System.nanoTime() }
    val drawn = remember(index) { androidx.compose.runtime.mutableStateMapOf<Int, Long>() }
    var pssStart by remember(index) { mutableStateOf(0L) }
    var pssMax by remember(index) { mutableStateOf(0L) }

    LaunchedEffect(index) {
        pssStart = Memory.pssMb()
        pssMax = pssStart
        val start = System.nanoTime()
        var last = 0L
        var measured = 0
        while (true) {
            withFrameNanos { now ->
                val elapsed = (now - start) / 1_000_000
                if (last != 0L && elapsed in WARMUP_MS..(WARMUP_MS + MEASURE_MS)) {
                    samples.add((now - last) / 1_000)
                    measured++
                }
                last = now
            }
            pssMax = maxOf(pssMax, Memory.pssMb())
            val elapsed = (System.nanoTime() - start) / 1_000_000
            if (measured > 0 && elapsed > WARMUP_MS + MEASURE_MS + COOLDOWN_MS) break
            if (elapsed > WARMUP_MS + MEASURE_MS + FIRST_FRAME_TIMEOUT_MS) break
        }

        val stats = FrameStats(samples)
        val firstFrameMs = if (drawn.size >= count) drawn.values.max().toDouble() else -1.0
        val cpu = ProcStat.percentBetween(cpuStart, ProcStat.sample())
        val assetBytes = assetSize(context, asset)
        appendCsv(
            context,
            listOf(
                asset,
                PrismMotionFormat.ofFileName(asset)?.engine?.name?.lowercase() ?: "unknown",
                asset.substringBefore('-'),
                asset.split('-').getOrNull(1) ?: "",
                asset.substringAfterLast('-').substringBeforeLast('.'),
                assetBytes.toString(),
                count.toString(),
                stats.frames.toString(),
                "%.2f".format(stats.avgMs),
                "%.2f".format(stats.p95Ms),
                "%.2f".format(stats.p99Ms),
                "%.2f".format(stats.maxMs),
                "%.3f".format(stats.jankRatio),
                "%.2f".format(stats.fpsStd),
                "%.1f".format(stats.fps),
                pssStart.toString(),
                Memory.pssMb().toString(),
                pssMax.toString(),
                "%.1f".format(cpu),
                "%.1f".format(firstFrameMs),
            ).joinToString(","),
        )
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
                    PrismMotion(
                        state,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(2.dp)
                            .drawWithContent {
                                drawContent()
                                if (item !in drawn) drawn[item] = (System.nanoTime() - startNs) / 1_000_000
                            },
                        contentScale = ContentScale.Fit,
                    )
                }
            }
        }
    }
}

/** 资产字节数；读一次后缓存。 / Asset size in bytes, read once. */
private val sizeCache = HashMap<String, Long>()

fun assetSize(context: Context, asset: String): Long = sizeCache.getOrPut(asset) {
    runCatching { context.assets.open(asset).use { it.readBytes().size.toLong() } }.getOrDefault(-1L)
}

fun appendCsv(context: Context, row: String) {
    val dir = context.getExternalFilesDir(null) ?: context.filesDir
    val file = File(dir, "bench.csv")
    if (!file.exists()) {
        file.writeText(
            "asset,engine,variant,size,quality,asset_bytes,count,frames,avg_ms,p95_ms,p99_ms,max_ms," +
                "jank_ratio,fps_std,fps,pss_start_mb,pss_end_mb,pss_max_mb,cpu_percent,first_frame_ms\n",
        )
    }
    file.appendText(row + "\n")
}
