package com.zxhhyj.prism.motion.bench.android

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

/** 只运行当前项目；计数为 0 可测量采样器基线。 / Runs this project only; count 0 measures sampler overhead. */
class BenchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val only = intent.getStringExtra("only")
        val runId = intent.getStringExtra("run_id") ?: "manual"
        val counts = intent.getStringExtra("counts")?.split(',')?.map { it.toInt() } ?: listOf(1, 8, 16)
        require(counts.all { it in 0..64 })
        val measureMs = intent.getLongExtra("measure_ms", 2_500L).coerceIn(1_000, 60_000)
        val refresh = display?.refreshRate?.toDouble() ?: 60.0
        val report = File(getExternalFilesDir(null) ?: filesDir, "bench.csv")
        report.writeText(HEADER + "\n")
        File(report.parentFile, "samples.csv").writeText("asset,count,elapsed_ms,cpu_percent,pss_mb,submitted_frames\n")
        Log.i("prism-bench", "BENCH_START $runId")
        setContent {
            MaterialTheme { BenchScreen(applicationContext, only, counts, measureMs, refresh, report, runId) }
        }
    }
}

private const val HEADER = "asset,count,asset_bytes,valid,error,submitted_fps,min_instance_fps,ui_fps,ui_p95_ms,ui_p99_ms,ui_max_ms,ui_jank_ratio,ui_frame_std_ms,cpu_percent,pss_start_mb,pss_end_mb,pss_max_mb,first_output_ms,instances_with_output,submitted_frames,measure_ms"

/** 不用 Snapshot 状态逐帧重组 UI；只轮询真正的引擎输出。 / Polls engine output without per-frame UI recomposition. */
private class Run(val asset: String, val count: Int, val tilePx: Int) {
    val startNs = System.nanoTime()
    val slots = arrayOfNulls<PrismMotionState>(count)
    val firstOutputNs = LongArray(count)
    var layoutError: String? = null
    fun failure(): String? = layoutError ?: slots.firstNotNullOfOrNull { state ->
        (state?.status as? PrismMotionStatus.Failed)?.error?.toString() ?: state?.resource?.playbackError?.toString()
    }
    fun counts(): LongArray = LongArray(count) { slots[it]?.resource?.presentedFrameCount ?: 0 }
    fun observeOutput() {
        for (i in slots.indices) {
            if (firstOutputNs[i] == 0L && slots[i]?.hasOutput == true) firstOutputNs[i] = System.nanoTime()
        }
    }
    fun ready(): Boolean = slots.all { it?.hasOutput == true && (it.resource?.presentedFrameCount ?: 0L) > 0L }
}

/** 全部实例采用固定像素尺寸，超出屏幕直接判无效，绝不用懒加载减少实际数量。 / Uses fixed pixel sizes; overflow is invalid rather than lazily omitting players. */
@Composable
private fun PlayerGrid(run: Run, registry: PrismMotionRegistry, modifier: Modifier) {
    BoxWithConstraints(modifier) {
        val geometry = gridFor(run.count, constraints.maxWidth, constraints.maxHeight, run.tilePx)
        SideEffect { run.layoutError = if (geometry == null) "requested pixel grid does not fit screen" else null }
        if (geometry == null) return@BoxWithConstraints
        Layout(modifier = Modifier.fillMaxSize(), content = {
            repeat(run.count) { item ->
                val state = rememberPrismMotionState(
                    remember(run) { PrismMotionRequest(PrismMotionSource.Asset(run.asset), repeatCount = -1) },
                    registry,
                )
                SideEffect { run.slots[item] = state }
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

/** 兼顾横屏实际可用高度；不缩放也不裁掉待测实例。 / Fits landscape insets without scaling or clipping measured players. */
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

/** 等待真实输出后才计量；加载失败、停帧、缺实例均保留错误记录。 / Measures only after real output; failures and stalled/missing players remain explicit. */
@Composable
private fun BenchScreen(context: Context, only: String?, counts: List<Int>, measureMs: Long, refresh: Double, report: File, runId: String) {
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
        val result = measureRun(context, run, measureMs, refresh, File(report.parentFile, "samples.csv"))
        withContext(Dispatchers.IO) { report.appendText(result + "\n") }
        Log.i("prism-bench", "SCENARIO_DONE $result")
        mounted = false
        withFrameNanos { }
        delay(300)
        index++
        mounted = true
    }
    Column(Modifier.fillMaxSize()) {
        Text("${index + 1}/${scenarios.size}: $asset × $count", Modifier.padding(8.dp))
        if (mounted) key(run) { PlayerGrid(run, registry, Modifier.weight(1f).fillMaxSize()) }
    }
}

/** PSS 降为后台 4Hz；CPU 与帧统计只覆盖预热后的稳定测量窗口。 / Samples PSS off-main at 4Hz; CPU/cadence cover only the post-warmup interval. */
private suspend fun measureRun(context: Context, run: Run, measureMs: Long, refresh: Double, trace: File): String = coroutineScope {
    val assetBytes = withContext(Dispatchers.IO) { assetSize(context, run.asset) }
    val pssStart = withContext(Dispatchers.Default) { Memory.pssMb() }
    val pssValues = ArrayList<Long>()
    val traceRows = ArrayList<String>()
    val sampler = launch(Dispatchers.Default) {
        var cpuPrevious = ProcStat.sample()
        while (isActive) {
            val memory = Memory.pssMb()
            pssValues.add(memory)
            val cpuNow = ProcStat.sample()
            traceRows.add(listOf(run.asset, run.count, (System.nanoTime() - run.startNs) / 1_000_000,
                decimal(ProcStat.percentBetween(cpuPrevious, cpuNow)), memory, run.counts().sum()).joinToString(","))
            cpuPrevious = cpuNow
            delay(250)
        }
    }
    val frameIntervals = ArrayList<Long>()
    var error: String? = null
    var framesBefore = LongArray(run.count)
    var framesAfter = framesBefore
    var cpuBefore: ProcStat.Sample? = null
    var cpuAfter: ProcStat.Sample? = null
    try {
        while (!run.ready()) {
            error = run.failure()
            if (error != null) break
            if ((System.nanoTime() - run.startNs) / 1_000_000 > 10_000) {
                error = "timeout waiting for all players to produce output"
                break
            }
            run.observeOutput()
            withFrameNanos { }
        }
        run.observeOutput()
        if (error == null) {
            delay(500)
            framesBefore = run.counts()
            cpuBefore = ProcStat.sample()
            var last = withFrameNanos { it }
            while ((System.nanoTime() - cpuBefore.nanos) / 1_000_000 < measureMs) {
                val now = withFrameNanos { it }
                frameIntervals.add((now - last) / 1000)
                last = now
                error = run.failure()
                if (error != null) break
            }
            cpuAfter = ProcStat.sample()
            framesAfter = run.counts()
            if (framesBefore.indices.any { framesAfter[it] <= framesBefore[it] }) error = "one or more players stopped submitting frames"
        }
    } finally {
        sampler.cancelAndJoin()
    }
    val pssEnd = withContext(Dispatchers.Default) { Memory.pssMb() }
    val pssMax = maxOf(pssStart, pssEnd, pssValues.maxOrNull() ?: 0L)
    withContext(Dispatchers.IO) { trace.appendText(traceRows.joinToString("\n", postfix = "\n")) }
    val elapsed = if (cpuBefore != null && cpuAfter != null) (cpuAfter.nanos - cpuBefore.nanos) / 1e9 else 0.0
    val fps = framesBefore.indices.map { if (elapsed > 0) (framesAfter[it] - framesBefore[it]) / elapsed else 0.0 }
    val stats = FrameStats(frameIntervals, refresh)
    val firstMs = if (run.count > 0 && run.firstOutputNs.all { it > 0 }) (run.firstOutputNs.max() - run.startNs) / 1e6 else -1.0
    val cpu = if (cpuBefore != null && cpuAfter != null) ProcStat.percentBetween(cpuBefore, cpuAfter) else 0.0
    listOf(run.asset, run.count, assetBytes, error == null, error?.replace(',', ';')?.replace('\n', ' ') ?: "",
        decimal(fps.average().takeIf { it.isFinite() } ?: 0.0), decimal(fps.minOrNull() ?: 0.0),
        decimal(stats.fps), decimal(stats.p95Ms), decimal(stats.p99Ms), decimal(stats.maxMs),
        decimal(stats.jankRatio), decimal(stats.frameStdMs), decimal(cpu), pssStart, pssEnd, pssMax,
        decimal(firstMs), run.firstOutputNs.count { it > 0 }, framesAfter.indices.sumOf { framesAfter[it] - framesBefore[it] },
        (elapsed * 1000).toLong()).joinToString(",")
}

/** 不构造整个文件的字节数组来统计大小。 / Counts file bytes without retaining the entire asset. */
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
