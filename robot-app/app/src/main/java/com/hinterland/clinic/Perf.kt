package com.hinterland.clinic

import android.app.Activity
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.view.Choreographer
import android.view.FrameMetrics
import android.view.Window
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

// how smoothly the app runs (v3.3): frame times and dropped frames, the app's cpu share and the radar's time
// per frame. the staff menu shows the numbers live, the log gets one line every 30 s. the counting is plain
// kotlin (FrameWindow, CpuStat), unit tested; PerfMonitor wires it to android.

data class PerfSnapshot(
    val windowMs: Long,
    val frames: Int,
    // frames that took longer than one refresh (16.7 ms at 60 Hz)
    val slowFrames: Int,
    val p50Ms: Double?,
    val p95Ms: Double?,
    val maxMs: Double?,
    // refreshes the main thread missed (gaps between choreographer frames)
    val droppedFrames: Int,
    // the main thread was blocked for STALL_MS or longer
    val stalls: Int,
    val worstStallMs: Double?,
    // % of one core, null = not measured
    val cpuPercent: Double?,
    val cores: Int,
    val radarFrames: Int,
    val radarAvgMs: Double?,
    val radarMaxMs: Double?,
) {
    val fps: Double get() = if (windowMs <= 0) 0.0 else frames * 1000.0 / windowMs

    // nothing a person would notice: no stall, at most 2 % slow frames
    val smooth: Boolean get() = stalls == 0 && slowFrames * 50 <= frames.coerceAtLeast(1)

    // the staff menu
    fun short(): String = buildString {
        append("Screen ${fps.roundToInt()} fps")
        append(" · $slowFrames slow frames")
        if (stalls > 0) append(" · $stalls freezes")
        cpuPercent?.let { append(" · CPU ${it.roundToInt()} %") }
        radarAvgMs?.let { append(" · radar ${fmt(it)} ms") }
    }

    // the log
    fun line(): String = buildString {
        append("Perf (${windowMs / 1000} s): ${fmt(fps)} fps")
        if (p50Ms != null) append(", frames p50 ${fmt(p50Ms)} ms / p95 ${fmt(p95Ms)} ms / max ${fmt(maxMs)} ms")
        append(", $slowFrames slow, $droppedFrames dropped, $stalls freezes")
        worstStallMs?.let { append(" (worst ${it.roundToInt()} ms)") }
        cpuPercent?.let { append("; CPU ${it.roundToInt()} % of one core ($cores cores)") }
        if (radarFrames > 0) append("; radar ${fmt(radarAvgMs)} ms per frame (max ${fmt(radarMaxMs)}, $radarFrames frames)")
    }

    private fun fmt(v: Double?): String = if (v == null) "-" else String.format(Locale.ROOT, "%.1f", v)
}

// frame times in 0.5 ms steps (no list per frame), dropped frames from refresh gaps, the radar's time
class FrameWindow(frameMs: Double = 1000.0 / 60) {
    private var frameNs = (frameMs * 1e6).toLong()
    private val hist = IntArray(BUCKETS)
    private var frames = 0
    private var slow = 0
    private var maxNs = 0L
    private var dropped = 0
    private var stalls = 0
    private var worstStallNs = 0L
    private var radarFrames = 0
    private var radarNs = 0L
    private var radarMaxNs = 0L

    @Synchronized
    fun setFrameMs(ms: Double) {
        if (ms > 1.0) frameNs = (ms * 1e6).toLong()
    }

    // one drawn frame: how long it took from the refresh to the screen (FrameMetrics.TOTAL_DURATION)
    @Synchronized
    fun frame(totalNs: Long) {
        if (totalNs <= 0) return
        frames++
        if (totalNs > frameNs) slow++
        if (totalNs > maxNs) maxNs = totalNs
        hist[(totalNs / BUCKET_NS).toInt().coerceIn(0, BUCKETS - 1)]++
    }

    // the time between two choreographer frames on the main thread
    @Synchronized
    fun vsync(gapNs: Long) {
        if (gapNs <= 0) return
        if (gapNs * 2 > frameNs * 3) dropped += ((gapNs + frameNs / 2) / frameNs - 1).toInt().coerceAtLeast(1)
        if (gapNs >= STALL_NS) {
            stalls++
            if (gapNs > worstStallNs) worstStallNs = gapNs
        }
    }

    @Synchronized
    fun radar(ns: Long) {
        if (ns <= 0) return
        radarFrames++
        radarNs += ns
        if (ns > radarMaxNs) radarMaxNs = ns
    }

    @Synchronized
    fun snapshot(windowMs: Long, cpuPercent: Double?, cores: Int, reset: Boolean): PerfSnapshot {
        val s = PerfSnapshot(
            windowMs = windowMs,
            frames = frames,
            slowFrames = slow,
            p50Ms = percentile(0.50),
            p95Ms = percentile(0.95),
            maxMs = if (frames == 0) null else maxNs / 1e6,
            droppedFrames = dropped,
            stalls = stalls,
            worstStallMs = if (stalls == 0) null else worstStallNs / 1e6,
            cpuPercent = cpuPercent,
            cores = cores,
            radarFrames = radarFrames,
            radarAvgMs = if (radarFrames == 0) null else radarNs / 1e6 / radarFrames,
            radarMaxMs = if (radarFrames == 0) null else radarMaxNs / 1e6,
        )
        if (reset) clear()
        return s
    }

    @Synchronized
    fun clear() {
        hist.fill(0)
        frames = 0
        slow = 0
        maxNs = 0
        dropped = 0
        stalls = 0
        worstStallNs = 0
        radarFrames = 0
        radarNs = 0
        radarMaxNs = 0
    }

    // the upper edge of the bucket the p-th frame falls in, ms
    private fun percentile(p: Double): Double? {
        if (frames == 0) return null
        val want = (frames * p).toInt().coerceAtLeast(1)
        var seen = 0
        for (i in hist.indices) {
            seen += hist[i]
            if (seen >= want) return if (i == BUCKETS - 1) maxNs / 1e6 else (i + 1) * BUCKET_NS / 1e6
        }
        return maxNs / 1e6
    }

    companion object {
        const val BUCKET_NS = 500_000L
        // 0.5 ms steps up to 100 ms, the last bucket holds everything longer
        const val BUCKETS = 201
        const val STALL_NS = 100_000_000L
    }
}

object CpuStat {
    // /proc/self/stat -> utime + stime in clock ticks (fields 14 and 15; the name in field 2 may hold spaces)
    fun ticks(stat: String): Long? {
        val close = stat.lastIndexOf(')')
        if (close < 0) return null
        val f = stat.substring(close + 1).trim().split(Regex("\\s+"))
        // f[0] = field 3 (state), so field 14 = f[11] and field 15 = f[12]
        val user = f.getOrNull(11)?.toLongOrNull() ?: return null
        val system = f.getOrNull(12)?.toLongOrNull() ?: return null
        return user + system
    }

    // % of one core between two readings
    fun percent(ticks: Long, ticksPerSecond: Long, ms: Long): Double? =
        if (ms <= 0 || ticksPerSecond <= 0 || ticks < 0) null else ticks * 1000.0 / ticksPerSecond / ms * 100.0
}

// measures while the screen is in front (MainActivity): FrameMetrics for the frame times (on its own
// background thread), a choreographer callback for missed refreshes, /proc/self/stat for the cpu
object PerfMonitor {

    private const val WINDOW_MS = 30_000L

    private val window = FrameWindow()
    @Volatile private var windowStart = 0L
    @Volatile private var lastCpu: Double? = null
    private val cores = Runtime.getRuntime().availableProcessors()

    // the last full 30 s window
    @Volatile var latest: PerfSnapshot? = null
        private set

    // the radar worker's time for one frame
    fun radarFrame(ns: Long) = window.radar(ns)

    // for the staff menu: the window so far, or the last full one
    fun live(): PerfSnapshot? {
        val start = windowStart
        if (start == 0L) return latest
        val ms = SystemClock.elapsedRealtime() - start
        if (ms < 2_000) return latest
        return window.snapshot(ms, lastCpu, cores, reset = false)
    }

    // runs on the main thread while the activity is resumed
    suspend fun run(activity: Activity) {
        val thread = HandlerThread("otto-perf", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
        @Suppress("DEPRECATION")
        val hz = runCatching { activity.windowManager.defaultDisplay.refreshRate }.getOrNull()?.takeIf { it in 20f..240f } ?: 60f
        window.setFrameMs(1000.0 / hz)
        val metrics = Window.OnFrameMetricsAvailableListener { _, m, _ ->
            window.frame(m.getMetric(FrameMetrics.TOTAL_DURATION))
        }
        val vsync = object : Choreographer.FrameCallback {
            private var last = 0L

            override fun doFrame(frameTimeNanos: Long) {
                if (last != 0L) window.vsync(frameTimeNanos - last)
                last = frameTimeNanos
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
        val attached = runCatching { activity.window.addOnFrameMetricsAvailableListener(metrics, Handler(thread.looper)) }.isSuccess
        Choreographer.getInstance().postFrameCallback(vsync)
        val tick = runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }.getOrDefault(100L)
        try {
            window.clear()
            var startedAt = SystemClock.elapsedRealtime()
            windowStart = startedAt
            var ticks = readTicks()
            while (true) {
                delay(WINDOW_MS)
                val now = SystemClock.elapsedRealtime()
                val nowTicks = readTicks()
                val cpu = if (ticks != null && nowTicks != null) CpuStat.percent(nowTicks - ticks, tick, now - startedAt) else null
                lastCpu = cpu
                val snap = window.snapshot(now - startedAt, cpu, cores, reset = true)
                latest = snap
                windowStart = now
                RobotLog.i(LogSource.APP, snap.line() + if (attached) "" else " (no frame metrics on this system)")
                startedAt = now
                ticks = nowTicks
            }
        } finally {
            windowStart = 0L
            Choreographer.getInstance().removeFrameCallback(vsync)
            if (attached) runCatching { activity.window.removeOnFrameMetricsAvailableListener(metrics) }
            thread.quitSafely()
        }
    }

    private suspend fun readTicks(): Long? = withContext(Dispatchers.IO) {
        runCatching { CpuStat.ticks(File("/proc/self/stat").readText()) }.getOrNull()
    }
}
