package com.zxhhyj.prism.motion.demo

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.zxhhyj.prism.motion.AnimationRequest
import com.zxhhyj.prism.motion.AnimationSource
import com.zxhhyj.prism.motion.compose.Animation
import com.zxhhyj.prism.motion.compose.AnimationRegistry
import com.zxhhyj.prism.motion.compose.rememberAnimationState
import com.zxhhyj.prism.motion.engine.kap.KapAnimationEngine
import com.zxhhyj.prism.motion.engine.vap.VapAnimationEngine
import java.io.File
import kotlin.system.exitProcess

/** 一个测量场景：某资产 × 同屏数量。 / One scenario: an asset at a given on-screen count. */
data class Scenario(
    val asset: AssetInfo,
    val count: Int,
    val warmupMs: Long = 1_500,
    val measureMs: Long = 5_000,
)

/** 单次场景的统计结果。 / Result of one scenario. */
data class BenchResult(
    val scenario: Scenario,
    val frames: Int,
    val avgFrameMs: Double,
    val p95FrameMs: Double,
    val fps: Double,
    val rssStartMb: Long,
    val rssEndMb: Long,
    val rssMaxMb: Long,
    val heapEndMb: Long,
)

/**
 * 自动基准：按场景播放，采样帧间隔与 RSS，最后写 CSV 并退出。
 * Automated bench: plays each scenario, records frame intervals and RSS, writes CSV, exits.
 */
object Bench {

    fun run(args: Array<String>) {
        if (args.contains("--load")) {
            runLoad(args)
            exitProcess(0)
        }
        val assets = discoverAssets()
        val counts = listOf(1, 8, 16)
        val sizeFilter = args.indexOf("--size").takeIf { it >= 0 }?.let { args.getOrNull(it + 1)?.toIntOrNull() }
        val countFilter = args.indexOf("--count").takeIf { it >= 0 }?.let { args.getOrNull(it + 1)?.toIntOrNull() }
        val engineFilter = args.indexOf("--engine").takeIf { it >= 0 }?.let { args.getOrNull(it + 1)?.uppercase() }
        val scenarios = assets
            .filter { sizeFilter == null || it.size == sizeFilter }
            .filter { engineFilter == null || it.engine.name.uppercase() == engineFilter }
            .flatMap { asset -> (countFilter?.let { listOf(it) } ?: counts).map { Scenario(asset, it) } }

        val results = ArrayList<BenchResult>()
        application {
            Window(onCloseRequest = ::exitApplication, title = "prism-motion bench") {
                MaterialTheme { BenchHost(scenarios, results) }
            }
        }
    }
}

private class BenchState(val results: ArrayList<BenchResult>)

@Composable
private fun BenchHost(scenarios: List<Scenario>, results: ArrayList<BenchResult>) {
    var index by remember { mutableStateOf(0) }
    var finished by remember { mutableStateOf(false) }

    if (finished) {
        LaunchedEffect(Unit) {
            writeCsv(results)
            printSummary(results)
            exitProcess(0)
        }
        return
    }
    if (scenarios.isEmpty()) {
        Text("没有找到 demo 资产，先运行 demo-desktop/generate-assets.sh")
        exitProcess(2)
    }

    val scenario = scenarios[index]
    val registry = remember { demoRegistry() }
    val samples = remember(scenario) { ArrayList<Long>() }
    var rssStart by remember { mutableStateOf(0L) }
    var rssMax by remember { mutableStateOf(0L) }

    // RSS 采样。 / RSS sampling.
    LaunchedEffect(scenario) {
        rssStart = Memory.rssMb()
        rssMax = rssStart
        while (true) {
            val current = Memory.rssMb()
            if (current > rssMax) rssMax = current
            kotlinx.coroutines.delay(100)
        }
    }

    // 帧间隔记录与场景切换。 / Frame interval recording and scenario switching.
    LaunchedEffect(scenario) {
        val start = System.nanoTime()
        var last = 0L
        var recorded = 0
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    val elapsed = (now - start) / 1_000_000
                    if (elapsed > scenario.warmupMs) {
                        samples.add((now - last) / 1_000)
                        recorded++
                    }
                }
                last = now
            }
            val elapsed = (System.nanoTime() - start) / 1_000_000
            if (elapsed > scenario.warmupMs + scenario.measureMs) break
        }
        val sorted = samples.sorted()
        val avg = if (sorted.isEmpty()) 0.0 else sorted.average() / 1000.0
        val p95 = if (sorted.isEmpty()) 0.0 else sorted[(sorted.size * 95 / 100).coerceAtMost(sorted.size - 1)] / 1000.0
        results += BenchResult(
            scenario = scenario,
            frames = sorted.size,
            avgFrameMs = avg,
            p95FrameMs = p95,
            fps = if (avg > 0) 1000.0 / avg else 0.0,
            rssStartMb = rssStart,
            rssEndMb = Memory.rssMb(),
            rssMaxMb = rssMax,
            heapEndMb = Memory.heapMb(),
        )
        if (index + 1 < scenarios.size) {
            index++
        } else {
            finished = true
        }
    }

    Column(Modifier.fillMaxSize()) {
        Text("bench ${index + 1}/${scenarios.size}: ${scenario.asset.label} × ${scenario.count}", modifier = Modifier.padding(8.dp))
        LazyVerticalGrid(columns = GridCells.Adaptive(160.dp), modifier = Modifier.fillMaxSize()) {
            items(scenario.count) { item ->
                key(item) {
                    val state = rememberAnimationState(
                        remember(scenario, item) {
                            AnimationRequest(AnimationSource.File(scenario.asset.file.absolutePath))
                        },
                        registry,
                    )
                    Animation(state, contentScale = ContentScale.Fit)
                }
            }
        }
    }
}

/**
 * 只测加载成本：打开 N 个实例、解出全部帧、生成首帧位图，记录耗时与 RSS 增量。
 * Load-only bench: opens N instances, decodes every frame, builds the first bitmap.
 */
private fun runLoad(args: Array<String>) {
    val sizeFilter = args.indexOf("--size").takeIf { it >= 0 }?.let { args.getOrNull(it + 1)?.toIntOrNull() }
    val counts = listOf(1, 8, 16)
    val assets = discoverAssets().filter { sizeFilter == null || it.size == sizeFilter }

    val file = File("build/reports/bench-load.csv")
    file.parentFile.mkdirs()
    file.bufferedWriter().use { out ->
        out.write("file,engine,variant,size,quality,count,open_ms,bitmap_ms,rss_before_mb,rss_after_mb,heap_after_mb\n")
        println("%-22s %4s %5s %9s %9s %9s %9s".format("asset", "size", "count", "open(ms)", "bitmap(ms)", "rss+MB", "heapMB"))
        for (asset in assets) {
            for (count in counts) {
                kotlinx.coroutines.runBlocking {
                    val engine = when (asset.engine) {
                        com.zxhhyj.prism.motion.AnimationEngineId.Vap -> VapAnimationEngine()
                        com.zxhhyj.prism.motion.AnimationEngineId.Kap -> KapAnimationEngine()
                    }
                    System.gc()
                    val rssBefore = Memory.rssMb()
                    val start = System.nanoTime()
                    val resources = List(count) {
                        engine.open(com.zxhhyj.prism.motion.AnimationSource.File(asset.file.absolutePath))
                    }
                    val openMs = (System.nanoTime() - start) / 1_000_000
                    val bitmapStart = System.nanoTime()
                    resources.forEach { resource ->
                        (resource as? com.zxhhyj.prism.motion.compose.JvmFrameResource)?.currentBitmap()
                    }
                    val bitmapMs = (System.nanoTime() - bitmapStart) / 1_000_000
                    val rssAfter = Memory.rssMb()
                    resources.forEach { it.release() }
                    println(
                        "%-22s %4d %5d %9d %9d %9d %9d".format(
                            asset.file.name, asset.size, count, openMs, bitmapMs,
                            rssAfter - rssBefore, Memory.heapMb(),
                        ),
                    )
                    out.write(
                        "${asset.file.name},${asset.engine.name.lowercase()},${asset.variant},${asset.size}," +
                            "${asset.quality},$count,$openMs,$bitmapMs,$rssBefore,$rssAfter,${Memory.heapMb()}\n",
                    )
                }
            }
        }
    }
    println()
    println("CSV: build/reports/bench-load.csv")
}

private fun writeCsv(results: List<BenchResult>) {
    val file = File("build/reports/bench.csv")
    file.parentFile.mkdirs()
    file.bufferedWriter().use { out ->
        out.write(
            "file,engine,variant,size,quality,count,frames,avg_frame_ms,p95_frame_ms,fps," +
                "rss_start_mb,rss_end_mb,rss_max_mb,heap_end_mb\n",
        )
        for (result in results) {
            val asset = result.scenario.asset
            out.write(
                "${asset.file.name},${asset.engine.name.lowercase()},${asset.variant},${asset.size},${asset.quality}," +
                    "${result.scenario.count},${result.frames}," +
                    "${"%.2f".format(result.avgFrameMs)},${"%.2f".format(result.p95FrameMs)}," +
                    "${"%.1f".format(result.fps)},${result.rssStartMb},${result.rssEndMb}," +
                    "${result.rssMaxMb},${result.heapEndMb}\n",
            )
        }
    }
}

private fun printSummary(results: List<BenchResult>) {
    println()
    println("%-22s %4s %5s %8s %8s %7s %8s %8s".format("asset", "size", "count", "avg(ms)", "p95(ms)", "fps", "rssMB", "rssMax"))
    for (result in results) {
        println(
            "%-22s %4d %5d %8.2f %8.2f %7.1f %8d %8d".format(
                result.scenario.asset.file.name,
                result.scenario.asset.size,
                result.scenario.count,
                result.avgFrameMs,
                result.p95FrameMs,
                result.fps,
                result.rssEndMb,
                result.rssMaxMb,
            ),
        )
    }
    println()
    println("CSV: build/reports/bench.csv")
}
