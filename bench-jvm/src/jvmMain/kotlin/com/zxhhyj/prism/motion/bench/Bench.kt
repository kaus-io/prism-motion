package com.zxhhyj.prism.motion.bench

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.zxhhyj.prism.motion.PrismMotionEngineId
import com.zxhhyj.prism.motion.PrismMotionRequest
import com.zxhhyj.prism.motion.PrismMotionSource
import com.zxhhyj.prism.motion.PrismMotionStatus
import com.zxhhyj.prism.motion.compose.PrismMotion
import com.zxhhyj.prism.motion.compose.PrismMotionRegistry
import com.zxhhyj.prism.motion.compose.rememberPrismMotionState
import com.zxhhyj.prism.motion.engine.kap.KapJvmRenderer
import com.zxhhyj.prism.motion.engine.kap.PrismMotionKapEngine
import com.zxhhyj.prism.motion.engine.vap.PrismMotionVapEngine
import com.zxhhyj.prism.motion.engine.vap.VapJvmRenderer
import java.io.File
import kotlin.system.exitProcess

/**
 * 探索性桌面工具：记录 CPU / RSS / UI 时钟 / 资产大小 / 加载就绪时间，非真实呈现基准。
 * Exploratory desktop tool: CPU, RSS, UI cadence, asset size and load readiness; not presentation timing.
 */
data class Scenario(
    val asset: AssetInfo,
    val count: Int,
    val warmupMs: Long = 800,
    val measureMs: Long = 2_500,
    val cooldownMs: Long = 300,
)

data class BenchResult(
    val scenario: Scenario,
    val stats: FrameStats,
    val pssStartMb: Long,
    val pssEndMb: Long,
    val pssMaxMb: Long,
    val cpuPercent: Double,
    val firstFrameMs: Double,
    val assetBytes: Long,
)

object Bench {

    fun run(args: Array<String>) {
        if (args.contains("--load")) {
            runLoad(args)
            exitProcess(0)
        }
        val scenarios = selectScenarios(args)
        val results = ArrayList<BenchResult>()
        application {
            Window(onCloseRequest = ::exitApplication, title = "prism-motion bench") {
                MaterialTheme { BenchHost(scenarios, results) }
            }
        }
    }

    fun selectScenarios(args: Array<String>): List<Scenario> {
        val counts = listOf(1, 8, 16)
        val sizeFilter = args.indexOf("--size").takeIf { it >= 0 }?.let { args.getOrNull(it + 1)?.toIntOrNull() }
        val countFilter = args.indexOf("--count").takeIf { it >= 0 }?.let { args.getOrNull(it + 1)?.toIntOrNull() }
        val engineFilter = args.indexOf("--engine").takeIf { it >= 0 }?.let { args.getOrNull(it + 1)?.uppercase() }
        return discoverAssets()
            .filter { sizeFilter == null || it.size == sizeFilter }
            .filter { engineFilter == null || it.engine.name.uppercase() == engineFilter }
            .flatMap { asset -> (countFilter?.let { listOf(it) } ?: counts).map { Scenario(asset, it) } }
    }
}

@Composable
private fun BenchHost(scenarios: List<Scenario>, results: ArrayList<BenchResult>) {
    var index by remember { mutableStateOf(0) }
    var done by remember { mutableStateOf(false) }

    if (done) {
        LaunchedEffect(Unit) {
            writeCsv(results)
            printSummary(results)
            exitProcess(0)
        }
        return
    }
    if (scenarios.isEmpty()) {
        Text("没有资产：先跑 :demo-desktop:generateDemoAssets 或设置 -Dprism.demo.data")
        exitProcess(2)
    }

    val scenario = scenarios[index]
    val registry = remember {
        PrismMotionRegistry(
            engines = listOf(PrismMotionVapEngine(), PrismMotionKapEngine()),
            renderers = mapOf(
                PrismMotionEngineId.Vap to VapJvmRenderer,
                PrismMotionEngineId.Kap to KapJvmRenderer,
            ),
        )
    }
    val samples = remember(index) { ArrayList<Long>() }
    val cpuStart = remember(index) { ProcStat.sample() }
    var readyCount by remember(index) { mutableStateOf(0) }
    var firstFrameMs by remember(index) { mutableStateOf(-1.0) }
    var pssStart by remember(index) { mutableStateOf(0L) }
    var pssMax by remember(index) { mutableStateOf(0L) }

    LaunchedEffect(index) {
        pssStart = Memory.rssMb()
        pssMax = pssStart
        val start = System.nanoTime()
        var last = 0L
        var measured = 0
        while (true) {
            withFrameNanos { now ->
                val elapsed = (now - start) / 1_000_000
                if (firstFrameMs < 0 && readyCount >= scenario.count) firstFrameMs = elapsed.toDouble()
                if (last != 0L && elapsed in scenario.warmupMs..(scenario.warmupMs + scenario.measureMs)) {
                    samples.add((now - last) / 1_000)
                    measured++
                }
                last = now
            }
            pssMax = maxOf(pssMax, Memory.rssMb())
            val elapsed = (System.nanoTime() - start) / 1_000_000
            if (measured > 0 && elapsed > scenario.warmupMs + scenario.measureMs + scenario.cooldownMs) break
            if (elapsed > scenario.warmupMs + scenario.measureMs + 5_000) break
        }

        val stats = FrameStats(samples)
        results += BenchResult(
            scenario = scenario,
            stats = stats,
            pssStartMb = pssStart,
            pssEndMb = Memory.rssMb(),
            pssMaxMb = pssMax,
            cpuPercent = ProcStat.percentBetween(cpuStart, ProcStat.sample()),
            firstFrameMs = firstFrameMs,
            assetBytes = scenario.asset.file.length(),
        )
        if (index + 1 < scenarios.size) index++ else done = true
    }

    Column(Modifier.fillMaxSize()) {
        Text(
            "bench ${index + 1}/${scenarios.size}: ${scenario.asset.file.name} × ${scenario.count}",
            modifier = Modifier.padding(8.dp),
        )
        LazyVerticalGrid(columns = GridCells.Adaptive(160.dp), modifier = Modifier.fillMaxSize()) {
            items(scenario.count) { item ->
                key(item) {
                    val state = rememberPrismMotionState(
                        remember(index, item) {
                            PrismMotionRequest(PrismMotionSource.File(scenario.asset.file.absolutePath), repeatCount = -1)
                        },
                        registry,
                    )
                    if (state.status == PrismMotionStatus.Ready) {
                        LaunchedEffect(state) { if (readyCount < scenario.count) readyCount++ }
                    }
                    PrismMotion(state, contentScale = ContentScale.Fit)
                }
            }
        }
    }
}

private fun writeCsv(results: List<BenchResult>) {
    val file = File("build/reports/bench.csv")
    file.parentFile.mkdirs()
    file.bufferedWriter().use { out ->
        out.write(
            "asset,engine,variant,size,quality,asset_bytes,count,frames,avg_ms,p95_ms,p99_ms,max_ms," +
                "ui_jank_ratio,ui_frame_std_ms,ui_fps,rss_start_mb,rss_end_mb,rss_max_mb,cpu_percent,first_ready_ms\n",
        )
        for (result in results) {
            val asset = result.scenario.asset
            out.write(
                listOf(
                    asset.file.name,
                    asset.engine.name.lowercase(),
                    asset.variant,
                    asset.size.toString(),
                    asset.quality,
                    result.assetBytes.toString(),
                    result.scenario.count.toString(),
                    result.stats.frames.toString(),
                    "%.2f".format(result.stats.avgMs),
                    "%.2f".format(result.stats.p95Ms),
                    "%.2f".format(result.stats.p99Ms),
                    "%.2f".format(result.stats.maxMs),
                    "%.3f".format(result.stats.jankRatio),
                    "%.2f".format(result.stats.fpsStd),
                    "%.1f".format(result.stats.fps),
                    result.pssStartMb.toString(),
                    result.pssEndMb.toString(),
                    result.pssMaxMb.toString(),
                    "%.1f".format(result.cpuPercent),
                    "%.1f".format(result.firstFrameMs),
                ).joinToString(",") + "\n",
            )
        }
    }
}

private fun printSummary(results: List<BenchResult>) {
    println()
    println(
        "%-24s %5s %8s %8s %8s %8s %8s %8s %9s".format(
            "asset", "count", "fps", "p95", "p99", "jank", "cpu%", "pssMB", "first(ms)",
        ),
    )
    for (result in results) {
        println(
            "%-24s %5d %8.1f %8.2f %8.2f %8.3f %8.1f %8d %9.1f".format(
                result.scenario.asset.file.name,
                result.scenario.count,
                result.stats.fps,
                result.stats.p95Ms,
                result.stats.p99Ms,
                result.stats.jankRatio,
                result.cpuPercent,
                result.pssMaxMb,
                result.firstFrameMs,
            ),
        )
    }
    println()
    println("CSV: build/reports/bench.csv")
}

/** 只测加载成本：打开 N 个实例、解出全部帧、生成首帧位图。 / Load-only bench. */
private fun runLoad(args: Array<String>) {
    val sizeFilter = args.indexOf("--size").takeIf { it >= 0 }?.let { args.getOrNull(it + 1)?.toIntOrNull() }
    val counts = listOf(1, 8, 16)
    val assets = discoverAssets().filter { sizeFilter == null || it.size == sizeFilter }

    val file = File("build/reports/bench-load.csv")
    file.parentFile.mkdirs()
    file.bufferedWriter().use { out ->
        out.write("asset,engine,variant,size,quality,asset_bytes,count,open_ms,bitmap_ms,cpu_percent,rss_delta_mb\n")
        println("%-24s %4s %5s %9s %9s %9s".format("asset", "size", "count", "open(ms)", "bitmap(ms)", "cpu%"))
        for (asset in assets) {
            for (count in counts) {
                kotlinx.coroutines.runBlocking {
                    val engine = when (asset.engine) {
                        PrismMotionEngineId.Vap -> PrismMotionVapEngine()
                        PrismMotionEngineId.Kap -> PrismMotionKapEngine()
                    }
                    val cpuStart = ProcStat.sample()
                    val rssBefore = Memory.rssMb()
                    val start = System.nanoTime()
                    val resources = List(count) {
                        engine.open(PrismMotionSource.File(asset.file.absolutePath))
                    }
                    val openMs = (System.nanoTime() - start) / 1_000_000
                    val bitmapStart = System.nanoTime()
                    resources.forEach { resource ->
                        (resource as? com.zxhhyj.prism.motion.compose.JvmFrameResource)?.currentBitmap()
                    }
                    val bitmapMs = (System.nanoTime() - bitmapStart) / 1_000_000
                    val cpu = ProcStat.percentBetween(cpuStart, ProcStat.sample())
                    val rssAfter = Memory.rssMb()
                    resources.forEach { it.release() }
                    println(
                        "%-24s %4d %5d %9d %9d %9.1f".format(
                            asset.file.name, asset.size, count, openMs, bitmapMs, cpu,
                        ),
                    )
                    out.write(
                        "${asset.file.name},${asset.engine.name.lowercase()},${asset.variant},${asset.size}," +
                            "${asset.quality},${asset.file.length()},$count,$openMs,$bitmapMs," +
                            "${"%.1f".format(cpu)},${rssAfter - rssBefore}\n",
                    )
                }
            }
        }
    }
    println()
    println("CSV: build/reports/bench-load.csv")
}
