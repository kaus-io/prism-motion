package com.zxhhyj.prism.motion.bench.android

import android.content.Context
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.zxhhyj.prism.motion.PrismMotionEngineId
import com.zxhhyj.prism.motion.PrismMotionFrameObservable
import com.zxhhyj.prism.motion.PrismMotionRequest
import com.zxhhyj.prism.motion.PrismMotionSource
import com.zxhhyj.prism.motion.PrismMotionStatus
import com.zxhhyj.prism.motion.compose.PrismMotion
import com.zxhhyj.prism.motion.compose.PrismMotionRegistry
import com.zxhhyj.prism.motion.compose.PrismMotionState
import com.zxhhyj.prism.motion.compose.rememberPrismMotionState
import com.zxhhyj.prism.motion.engine.kap.PrismMotionKapEngine
import com.zxhhyj.prism.motion.engine.kap.PrismMotionKapRenderer
import com.zxhhyj.prism.motion.engine.vap.PrismMotionVapEngine
import com.zxhhyj.prism.motion.engine.vap.PrismMotionVapRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.sqrt

/** 当前项目的真实播放基准；零实例测量观察器基线。 / Real playback benchmark for this project; zero players measure observer overhead. */
class BenchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val only = intent.getStringExtra("only")
        val runId = intent.getStringExtra("run_id") ?: "manual"
        val counts = intent.getStringExtra("counts")?.split(',')?.map { it.toInt() } ?: listOf(1, 8, 16)
        require(counts.isNotEmpty() && counts.all { it in 0..64 } && counts.distinct().size == counts.size)
        val config = BenchConfig(
            measureMs = intent.getLongExtra("measure_ms", 4_000L).coerceIn(1_000, 60_000),
            warmupMs = intent.getLongExtra("warmup_ms", 1_000L).coerceIn(0, 10_000),
            targetFps = intent.getIntExtra("target_fps", 30).coerceIn(1, 240).toDouble(),
            refresh = display?.refreshRate?.toDouble() ?: 60.0,
        )
        val report = File(getExternalFilesDir(null) ?: filesDir, "bench.csv")
        report.writeText(VERSION_HEADER + "," + HEADER + "\n")
        File(report.parentFile, "samples.csv").writeText("$VERSION_HEADER,asset,count,elapsed_ms,phase,cpu_percent,pss_mb,submitted_frames\n")
        File(report.parentFile, "presents.csv").writeText("$VERSION_HEADER,asset,count,instance,elapsed_ns,phase\n")
        Log.i("prism-bench", "BENCH_START $runId app=${BuildConfig.VERSION_NAME} bench=${BuildConfig.BENCH_VERSION}")
        setContent { MaterialTheme { BenchScreen(applicationContext, only, counts, config, report, runId) } }
    }
}

private data class BenchConfig(val measureMs: Long, val warmupMs: Long, val targetFps: Double, val refresh: Double)
private const val VERSION_HEADER = "app_version,bench_version,version_code"
private val VERSION_VALUES = "${BuildConfig.VERSION_NAME},${BuildConfig.BENCH_VERSION},${BuildConfig.VERSION_CODE}"
private const val HEADER = "asset,count,asset_bytes,valid,error,target_fps,realtime_ok,submitted_fps,min_instance_fps,submit_p95_ms,submit_p99_ms,submit_max_ms,submit_jank_ratio,ui_fps,ui_p95_ms,ui_p99_ms,ui_max_ms,ui_jank_ratio,ui_frame_std_ms,cpu_percent,pss_start_mb,pss_end_mb,pss_median_mb,pss_max_mb,first_output_ms,instances_with_output,submitted_frames,measure_ms,measure_start_ns,measure_end_ns,thermal_status_start,thermal_status_end"

/** UI 状态仅在主线程轮询，后台采样只读取线程安全的回调计数。 / UI state is polled on Main; background samples read only thread-safe callback counters. */
private class Run(val asset: String, val count: Int, val tilePx: Int) {
    val startNs = System.nanoTime()
    val slots = arrayOfNulls<PrismMotionState>(count)
    val probes = List(count) { FrameTrace() }
    val firstOutputNs = LongArray(count)
    var layoutError: String? = null
    var diagnosticsError: String? = null
    @Volatile var phase = "waiting"
    @Volatile var latestPss: Long? = null
    fun failure(): String? = layoutError ?: diagnosticsError ?: slots.firstNotNullOfOrNull { state ->
        (state?.status as? PrismMotionStatus.Failed)?.error?.toString() ?: state?.resource?.playbackError?.toString()
    } ?: if (probes.any { it.overflow || it.invalidTimestamp }) "invalid or overflowing present trace" else null
    fun submitted(): Long = probes.sumOf { it.count() }
    fun observeOutput() {
        for (i in slots.indices) {
            if (firstOutputNs[i] == 0L && slots[i]?.hasOutput == true) firstOutputNs[i] = System.nanoTime()
        }
    }
    fun ready(): Boolean = slots.indices.all { slots[it]?.hasOutput == true && probes[it].count() > 0 }
}

/** 全部实例固定为源像素尺寸；容纳不下就失败，不懒加载或缩小目标。 / All players use source pixel dimensions; overflow fails instead of lazy omission or scaling. */
@Composable
private fun PlayerGrid(run: Run, registry: PrismMotionRegistry, modifier: Modifier) {
    BoxWithConstraints(modifier) {
        val geometry = gridFor(run.count, constraints.maxWidth, constraints.maxHeight, run.tilePx)
        SideEffect { run.layoutError = if (geometry == null) "requested pixel grid does not fit screen" else null }
        if (geometry == null) return@BoxWithConstraints
        Layout(modifier = Modifier.fillMaxSize(), content = {
            repeat(run.count) { item ->
                val state = rememberPrismMotionState(
                    remember(run) { PrismMotionRequest(PrismMotionSource.Asset(run.asset), repeatCount = -1) }, registry,
                )
                val observable = state.resource as? PrismMotionFrameObservable
                SideEffect {
                    run.slots[item] = state
                    if (state.resource != null && observable == null) run.diagnosticsError = "engine does not expose frame callbacks"
                }
                DisposableEffect(observable) {
                    observable?.setFrameObserver(run.probes[item])
                    onDispose { observable?.setFrameObserver(null) }
                }
                PrismMotion(state, contentScale = ContentScale.Fit)
            }
        }) { children, bounds ->
            val measured = children.map { it.measure(Constraints.fixed(run.tilePx, run.tilePx)) }
            layout(bounds.maxWidth, bounds.maxHeight) {
                measured.forEachIndexed { index, placeable ->
                    placeable.place((index % geometry.columns) * run.tilePx, (index / geometry.columns) * run.tilePx)
                }
            }
        }
    }
}

internal data class GridGeometry(val columns: Int, val rows: Int)

/** 根据实际可用宽高排列固定像素网格。 / Fits a fixed-pixel grid into the actual available area. */
internal fun gridFor(count: Int, width: Int, height: Int, tile: Int): GridGeometry? {
    require(count >= 0 && tile > 0)
    if (count == 0) return GridGeometry(1, 0)
    val maxColumns = width / tile
    val maxRows = height / tile
    if (maxColumns <= 0 || maxRows <= 0 || count.toLong() > maxColumns.toLong() * maxRows) return null
    val minimumColumns = (count + maxRows - 1) / maxRows
    val columns = ceil(sqrt(count.toDouble())).toInt().coerceIn(minimumColumns, maxColumns)
    return GridGeometry(columns, (count + columns - 1) / columns)
}

/** 请求变更会取消测量，资源仍由门面加载协程持有和释放。 / Changing the request cancels measurement; the facade's loader still owns and releases resources. */
@Composable
private fun BenchScreen(context: Context, only: String?, counts: List<Int>, config: BenchConfig, report: File, runId: String) {
    val assets = remember { listBenchAssets(context, only) }
    val scenarios = remember { assets.flatMap { asset -> counts.map { asset to it } } }
    var index by remember { mutableStateOf(0) }
    var mounted by remember { mutableStateOf(true) }
    val registry = remember {
        PrismMotionRegistry(
            listOf(PrismMotionVapEngine(), PrismMotionKapEngine(context)),
            mapOf(PrismMotionEngineId.Vap to PrismMotionVapRenderer, PrismMotionEngineId.Kap to PrismMotionKapRenderer),
        )
    }
    if (index >= scenarios.size) {
        LaunchedEffect(Unit) { Log.i("prism-bench", "BENCH_DONE $runId ${report.absolutePath}") }
        Text(if (scenarios.isEmpty()) "No matching assets" else "Bench complete")
        return
    }
    val (asset, count) = scenarios[index]
    val run = remember(index) { Run(asset, count, asset.split('-').getOrNull(1)?.toIntOrNull() ?: 128) }
    LaunchedEffect(run) {
        val result = measureRun(context, run, config, report.parentFile!!)
        withContext(Dispatchers.IO) { report.appendText(result + "\n") }
        Log.i("prism-bench", "SCENARIO_DONE $result")
        mounted = false
        withFrameNanos { }
        delay(300)
        index++
        mounted = true
    }
    Column(Modifier.fillMaxSize()) {
        Text("Prism Motion ${BuildConfig.VERSION_NAME} · Bench ${BuildConfig.BENCH_VERSION} · ${index + 1}/${scenarios.size}", Modifier.padding(8.dp))
        if (mounted) key(run) { PlayerGrid(run, registry, Modifier.weight(1f).fillMaxSize()) }
    }
}

/** CPU/帧窗口不调用 PSS；保持播放后再单独采三次稳态内存。 / Never samples PSS in the CPU/frame window; samples steady memory three times afterward while playback continues. */
private suspend fun measureRun(context: Context, run: Run, config: BenchConfig, directory: File): String = coroutineScope {
    val assetBytes = withContext(Dispatchers.IO) { assetSize(context, run.asset) }
    val traceRows = ArrayList<String>()
    val sampler = launch(Dispatchers.Default) {
        var previous = ProcStat.sample()
        while (isActive) {
            val now = ProcStat.sample()
            traceRows.add(VERSION_VALUES + "," + listOf(run.asset, run.count, (System.nanoTime() - run.startNs) / 1_000_000, run.phase,
                decimal(ProcStat.percentBetween(previous, now)), run.latestPss ?: "", run.submitted()).joinToString(","))
            previous = now
            delay(250)
        }
    }
    val uiIntervals = ArrayList<Long>()
    val memory = ArrayList<Long>()
    var error: String? = null
    var cpuBefore: ProcStat.Sample? = null
    var cpuAfter: ProcStat.Sample? = null
    var events: List<LongArray> = List(run.count) { LongArray(0) }
    var thermalStart = thermalStatus(context)
    var thermalEnd = thermalStart
    try {
        while (!run.ready()) {
            error = run.failure()
            if (error != null) break
            if ((System.nanoTime() - run.startNs) / 1_000_000 > 10_000) {
                error = "timeout waiting for every player to produce real output"
                break
            }
            run.observeOutput()
            withFrameNanos { }
        }
        run.observeOutput()
        if (error == null) {
            run.phase = "warmup"
            delay(config.warmupMs)
            error = run.failure()
        }
        if (error == null) {
            thermalStart = thermalStatus(context)
            run.phase = "measure"
            cpuBefore = ProcStat.sample()
            var last = withFrameNanos { it }
            while ((System.nanoTime() - cpuBefore.nanos) / 1_000_000 < config.measureMs) {
                val now = withFrameNanos { it }
                uiIntervals.add((now - last) / 1000)
                last = now
                error = run.failure()
                if (error == null && !run.ready()) error = "one or more players lost output during measurement"
                if (error != null) break
            }
            cpuAfter = ProcStat.sample()
            thermalEnd = thermalStatus(context)
            events = run.probes.map { it.window(cpuBefore.nanos, cpuAfter.nanos) }
            if (events.any { it.size < 2 }) error = error ?: "one or more players stopped submitting frames"
        }
        run.phase = "memory"
        repeat(if (error == null) 3 else 1) { index ->
            val value = withContext(Dispatchers.Default) { Memory.pssMb() }
            memory.add(value)
            run.latestPss = value
            if (error == null && index < 2) delay(250)
        }
        error = error ?: run.failure()
    } finally {
        withContext(NonCancellable) { sampler.cancelAndJoin() }
    }
    val elapsed = if (cpuBefore != null && cpuAfter != null) (cpuAfter.nanos - cpuBefore.nanos) / 1e9 else 0.0
    val presents = PresentStats(events, elapsed, config.targetFps)
    val ui = FrameStats(uiIntervals, config.refresh)
    val firstMs = if (run.count > 0 && run.firstOutputNs.all { it > 0 }) (run.firstOutputNs.max() - run.startNs) / 1e6 else -1.0
    val cpu = if (cpuBefore != null && cpuAfter != null) ProcStat.percentBetween(cpuBefore, cpuAfter) else 0.0
    withContext(Dispatchers.IO) {
        File(directory, "samples.csv").appendText(traceRows.joinToString("\n", postfix = "\n"))
    }
    // 原始逐实例时间戳留作离线复核，不混入 CPU/内存测量窗口。
    // Retain raw per-player timestamps for offline checks, outside CPU/memory measurement.
    withContext(Dispatchers.IO) {
        File(directory, "presents.csv").appendText(buildString {
            events.forEachIndexed { instance, times ->
                for (time in times) append("$VERSION_VALUES,${run.asset},${run.count},$instance,${time - run.startNs},measure\n")
            }
        })
    }
    VERSION_VALUES + "," + listOf(run.asset, run.count, assetBytes, error == null, error?.replace(',', ';')?.replace('\n', ' ') ?: "",
        decimal(config.targetFps), error == null && presents.realtime,
        decimal(presents.fps), decimal(presents.minimumFps), decimal(presents.intervals.p95Ms),
        decimal(presents.intervals.p99Ms), decimal(presents.intervals.maxMs), decimal(presents.intervals.jankRatio),
        decimal(ui.fps), decimal(ui.p95Ms), decimal(ui.p99Ms), decimal(ui.maxMs), decimal(ui.jankRatio), decimal(ui.frameStdMs),
        decimal(cpu), memory.firstOrNull() ?: -1, memory.lastOrNull() ?: -1,
        memory.sorted().getOrNull(memory.size / 2) ?: -1, memory.maxOrNull() ?: -1,
        decimal(firstMs), run.firstOutputNs.count { it > 0 }, presents.frames, decimal(elapsed * 1000),
        cpuBefore?.let { it.nanos - run.startNs } ?: -1, cpuAfter?.let { it.nanos - run.startNs } ?: -1,
        thermalStart, thermalEnd).joinToString(",")
}

private fun thermalStatus(context: Context): Int = context.getSystemService(PowerManager::class.java)?.currentThermalStatus ?: -1

/** 只统计长度，不构造完整资产的字节数组。 / Counts bytes without retaining the entire asset. */
private fun assetSize(context: Context, asset: String): Long = runCatching {
    context.assets.openFd(asset).use { it.length }
}.getOrElse {
    context.assets.open(asset).use { stream ->
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) { val count = stream.read(buffer); if (count < 0) break; total += count }
        total
    }
}

private fun decimal(value: Double): String = String.format(Locale.ROOT, "%.3f", value)
