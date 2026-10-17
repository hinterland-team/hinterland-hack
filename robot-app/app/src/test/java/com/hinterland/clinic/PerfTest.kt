package com.hinterland.clinic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// the lag numbers: frame times, dropped frames, freezes, cpu and the radar's time per frame
class PerfTest {

    private val ms = 1_000_000L

    @Test
    fun frameTimesSlowFramesAndPercentiles() {
        val w = FrameWindow()
        // 30 s at 60 fps: mostly 8 ms frames, 5 % at 20 ms (slow at 60 Hz), one 45 ms frame
        repeat(1_700) { w.frame(8 * ms) }
        repeat(90) { w.frame(20 * ms) }
        w.frame(45 * ms)
        val s = w.snapshot(30_000, cpuPercent = 31.0, cores = 8, reset = true)
        assertEquals(1_791, s.frames)
        assertEquals(59.7, s.fps, 0.05)
        assertEquals(91, s.slowFrames)
        assertEquals(8.5, s.p50Ms!!, 1e-9)
        assertEquals(20.5, s.p95Ms!!, 1e-9)
        assertEquals(45.0, s.maxMs!!, 1e-9)
        assertFalse(s.smooth)
        assertTrue(s.short(), s.short().startsWith("Screen 60 fps") && s.short().contains("CPU 31 %"))
        assertTrue(s.line(), s.line().contains("p95 20.5 ms") && s.line().contains("8 cores"))
        // the window starts again after a snapshot
        assertEquals(0, w.snapshot(30_000, null, 8, reset = false).frames)
    }

    @Test
    fun missedRefreshesAndFreezes() {
        val w = FrameWindow(frameMs = 1000.0 / 60)
        w.vsync(16_700_000)
        // one refresh missed, then two
        w.vsync(33_400_000)
        w.vsync(50_100_000)
        // the main thread blocked for 250 ms
        w.vsync(250 * ms)
        val s = w.snapshot(10_000, null, 4, reset = true)
        assertEquals(1 + 2 + 14, s.droppedFrames)
        assertEquals(1, s.stalls)
        assertEquals(250.0, s.worstStallMs!!, 1e-9)
        assertTrue(s.line().contains("1 freezes"))
    }

    @Test
    fun radarTimePerFrame() {
        val w = FrameWindow()
        w.radar(2 * ms)
        w.radar(4 * ms)
        val s = w.snapshot(1_000, null, 4, reset = false)
        assertEquals(2, s.radarFrames)
        assertEquals(3.0, s.radarAvgMs!!, 1e-9)
        assertEquals(4.0, s.radarMaxMs!!, 1e-9)
        assertTrue(s.short().contains("radar 3.0 ms"))
        // nothing measured yet
        val empty = FrameWindow().snapshot(1_000, null, 4, reset = false)
        assertNull(empty.p50Ms)
        assertTrue(empty.smooth)
    }

    @Test
    fun cpuFromProcSelfStat() {
        // the name may hold spaces and brackets; utime and stime are fields 14 and 15
        val stat = "1234 (com.hinterland.relay (main)) S 1 1234 1234 0 -1 4194624 51234 0 12 0 1500 300 0 0 20 0 35 0 9876 0"
        assertEquals(1_800L, CpuStat.ticks(stat))
        assertNull(CpuStat.ticks("garbage"))
        // 750 ticks in 30 s at 100 ticks/s = a quarter of one core
        assertEquals(25.0, CpuStat.percent(750, 100, 30_000)!!, 1e-9)
        assertNull(CpuStat.percent(10, 100, 0))
    }
}
