package com.hinterland.clinic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// the walk-beside watch against the real radar data of 17.10 (b8, walks M23 and L25, demo-m23.log):
// the follower flickered seen / not seen every 1-2 s while following, with gaps of up to ~3.5 s.
// none of that may start a check-back; a patient who really stops must, within a few seconds.
class SideWatchTest {

    private val t0 = 10_000_000_000L

    // a drive: seen = the radar's confirmed follower in that 200 ms frame. returns when the watch fired
    // (ms after the start), checking every 250 ms like the navigation watcher
    private fun drive(totalMs: Long, seenAt: (Long) -> Boolean, everSeenFrom: Long = 0, watch: SideWatch = SideWatch()): Long? {
        watch.start(t0)
        var lastSeen: Long? = null
        var everSeen = false
        var t = 0L
        var nextFrame = 0L
        while (t <= totalMs) {
            while (nextFrame <= t) {
                if (seenAt(nextFrame)) {
                    lastSeen = t0 + nextFrame
                    if (nextFrame >= everSeenFrom) everSeen = true
                }
                nextFrame += 200
            }
            if (watch.check(t0 + t, everSeen, lastSeen)) return t
            t += 250
        }
        return null
    }

    // the unseen gaps while driving, from the log: "No follower behind for 1 s" comes ~1.5 s after the last
    // sighting (the tracker keeps a track that long), "Follower seen" ~0.3 s after the first new one (two
    // hits), so a gap is (seen time - lost time) + 1.2 s, the times in whole seconds:
    // walk 1, 13:59:03-13:59:14: lost/seen 07/07, 09/10, 11/12; 13:59:52-14:00:06: 52/52, 54/56, 00/00
    // walk 2, 14:01:58-14:02:14: 01/01, 04/04, 05/06, 08/08, 11/13
    private val walk1 = listOf(1_200L, 2_200L, 2_200L, 1_200L, 3_200L, 1_200L)
    private val walk2 = listOf(1_200L, 1_200L, 2_200L, 1_200L, 3_200L)

    // seen for leadMs, then each gap followed by a short sighting (pessimistic: only 3 frames seen)
    private fun flicker(gaps: List<Long>, extraMs: Long = 0, seenMs: Long = 600, leadMs: Long = 3_000): (Long) -> Boolean {
        val unseen = ArrayList<LongRange>()
        var t = leadMs
        for (g in gaps) {
            unseen += t until t + g + extraMs
            t += g + extraMs + seenMs
        }
        return { at -> unseen.none { at in it } }
    }

    @Test
    fun theRealFlickerOfOneToThreeSecondsNeverFires() {
        assertNull(drive(30_000, flicker(walk1)))
        assertNull(drive(30_000, flicker(walk2)))
        // with half a second more on every gap (the log has whole seconds)
        assertNull(drive(30_000, flicker(walk1, extraMs = 500)))
        assertNull(drive(30_000, flicker(walk2, extraMs = 500)))
    }

    @Test
    fun flickerOfOneToTwoSecondsForAMinuteNeverFires() {
        // 1.5 s seen, then 2 s not, again and again
        assertNull(drive(60_000, { at -> at % 3_500 < 1_500 }))
    }

    @Test
    fun aPatientWhoStopsIsMissedAfterFiveSeconds() {
        // beside otto for 8 s, then gone (stopped, sat down, went another way)
        val fired = drive(30_000, { at -> at < 8_000 })
        assertEquals((8_000L - 200 + SideWatch.LOST_MS).toDouble(), fired!!.toDouble(), 260.0)
    }

    @Test
    fun beforeThePatientWasEverBesideNothingFires() {
        // the radar can't tell this person from nobody yet: the schedule does the job
        assertNull(drive(30_000, { false }))
    }

    @Test
    fun afterAStopThePatientGetsAMomentToComeBackBeside() {
        val w = SideWatch()
        w.start(t0)
        // seen beside before the stop
        assertFalse(w.check(t0 + 1_000, everSeen = true, lastSeenAt = t0 + 900))
        // otto drives again at 20 s; the patient was last seen at 10 s, before the stop
        w.start(t0 + 20_000)
        assertFalse(w.check(t0 + 27_900, everSeen = true, lastSeenAt = t0 + 10_000))
        assertTrue(w.check(t0 + 28_000, everSeen = true, lastSeenAt = t0 + 10_000))
        // seen again after the restart: the normal 5 s from that sighting
        w.start(t0 + 40_000)
        assertFalse(w.check(t0 + 45_900, everSeen = true, lastSeenAt = t0 + 41_000))
        assertTrue(w.check(t0 + 46_000, everSeen = true, lastSeenAt = t0 + 41_000))
    }

    @Test
    fun standingAtAnObstacleIsNoAlarm() {
        val w = SideWatch()
        w.start(t0)
        w.hold()
        assertTrue(w.holding)
        assertFalse(w.check(t0 + 60_000, everSeen = true, lastSeenAt = t0))
        // driving again: counts from there
        w.start(t0 + 60_000)
        assertFalse(w.holding)
        assertFalse(w.check(t0 + 67_000, everSeen = true, lastSeenAt = t0))
        assertTrue(w.check(t0 + 68_000, everSeen = true, lastSeenAt = t0))
    }

    @Test
    fun falseAlarmsMakeItWaitLongerThenItStops() {
        val w = SideWatch()
        assertEquals(5_000L, w.thresholdMs)
        w.falseAlarm()
        assertEquals(7_000L, w.thresholdMs)
        w.falseAlarm()
        assertEquals(9_000L, w.thresholdMs)
        assertTrue(w.trusted)
        w.falseAlarm()
        assertFalse(w.trusted)
        w.start(t0)
        assertFalse(w.check(t0 + 120_000, everSeen = true, lastSeenAt = t0))
    }

    @Test
    fun itFiresOnceUntilTheNextStart() {
        val w = SideWatch()
        w.start(t0)
        assertTrue(w.check(t0 + 5_000, everSeen = true, lastSeenAt = t0))
        assertFalse(w.check(t0 + 9_000, everSeen = true, lastSeenAt = t0))
    }
}
