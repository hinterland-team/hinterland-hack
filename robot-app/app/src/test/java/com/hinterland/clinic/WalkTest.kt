package com.hinterland.clinic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// the walk planner: check-back timing, what nova does per distance, the lost-patient timeline
class WalkTest {

    private val t0 = 10_000_000_000L

    // ticks every 250 ms like the navigation watcher
    private fun CheckBackClock.drive(from: Long, ms: Long, blocked: Boolean = false): Long {
        var t = from
        while (t <= from + ms) {
            tick(t, blocked)
            t += 250
        }
        return t - 250
    }

    // ---- check-back clock ----

    @Test
    fun checkBackAfterAbout25SecondsOfDriving() {
        val c = CheckBackClock()
        val t = c.drive(t0, 24_750)
        assertFalse(c.due())
        c.tick(t + 250, false)
        assertTrue(c.due())
        assertFalse(c.obstacleWasReason())
    }

    @Test
    fun waitingInFrontOfAnObstacleIsNotDriving() {
        val c = CheckBackClock()
        var t = c.drive(t0, 10_000)
        // 2 s blocked: too short to count as "had to wait"
        t = c.drive(t + 250, 2_000, blocked = true)
        t = c.drive(t + 250, 1_000)
        assertFalse(c.due())
        assertTrue(c.drivenMs() < 12_000)
    }

    @Test
    fun rightAfterAnObstacleWaitNovaChecksBack() {
        val c = CheckBackClock()
        var t = c.drive(t0, 5_000)
        t = c.drive(t + 250, 4_000, blocked = true)
        assertFalse(c.due())
        // the way is free again
        c.tick(t + 250, false)
        assertTrue(c.due())
        assertTrue(c.obstacleWasReason())
    }

    @Test
    fun stopsDoNotCountAndResetStartsOver() {
        val c = CheckBackClock()
        val t = c.drive(t0, 20_000)
        c.pause()
        // 60 s later (a check-back, a pause): the gap is not driving
        c.tick(t + 60_000, false)
        assertFalse(c.due())
        c.drive(t + 60_250, 5_000)
        assertTrue(c.due())
        c.reset()
        assertFalse(c.due())
        assertEquals(0, c.drivenMs())
    }

    @Test
    fun aLongGapNeverCountsAsMinutes() {
        val c = CheckBackClock()
        c.tick(t0, false)
        c.tick(t0 + 120_000, false)
        assertFalse(c.due())
    }

    // ---- what nova sees when she turns around ----

    @Test
    fun distancesDecideTheOutcome() {
        assertEquals(Proximity.CLOSE, CheckBack.classify(listOf(listOf(2.4))))
        assertEquals(Proximity.CLOSE, CheckBack.classify(listOf(listOf(3.0))))
        assertEquals(Proximity.FAR, CheckBack.classify(listOf(listOf(3.1))))
        assertEquals(Proximity.FAR, CheckBack.classify(listOf(listOf(6.0))))
        assertEquals(Proximity.NOBODY, CheckBack.classify(listOf(listOf(6.5))))
        assertEquals(Proximity.NOBODY, CheckBack.classify(listOf(emptyList())))
    }

    @Test
    fun theNearestOfAllLooksCounts() {
        // the vision needs a moment after the turn: someone seen in any look counts
        assertEquals(Proximity.CLOSE, CheckBack.classify(listOf(emptyList(), listOf(5.0, 9.0), listOf(1.8))))
        assertEquals(1.8, CheckBack.nearest(listOf(emptyList(), listOf(5.0), listOf(1.8)))!!, 1e-9)
        // zero or negative distances are "unknown", not "right here"
        assertEquals(Proximity.NOBODY, CheckBack.classify(listOf(listOf(0.0, -1.0))))
    }

    @Test
    fun noVisionIsNotNobody() {
        assertEquals(Proximity.UNKNOWN, CheckBack.classify(listOf(null, null)))
        assertEquals(Proximity.UNKNOWN, CheckBack.classify(emptyList()))
        assertEquals(Proximity.NOBODY, CheckBack.classify(listOf(null, emptyList())))
    }

    @Test
    fun exactEventsPerOutcome() {
        assertEquals(listOf("check_back"), CheckBack.events(Proximity.CLOSE, Pace.NORMAL))
        assertEquals(listOf("waiting_for_patient", "slowed_down"), CheckBack.events(Proximity.FAR, Pace.NORMAL))
        assertEquals(listOf("waiting_for_patient", "slowed_down"), CheckBack.events(Proximity.FAR, Pace.SLOW))
        // nothing slower than very slow
        assertEquals(listOf("waiting_for_patient"), CheckBack.events(Proximity.FAR, Pace.VERY_SLOW))
        assertEquals(listOf("lost_patient"), CheckBack.events(Proximity.NOBODY, Pace.NORMAL))
        assertEquals(listOf("check_back"), CheckBack.events(Proximity.UNKNOWN, Pace.NORMAL))
    }

    @Test
    fun noTurningAroundRightBeforeTheDoor() {
        // v3.2: by the meters left, not the progress share (ScheduleTest has the details)
        assertFalse(NearGoal.skipCheck(null, null))
        assertFalse(NearGoal.skipCheck(Point(0.0, 0.0), Point(6.0, 0.0)))
        assertTrue(NearGoal.skipCheck(Point(5.0, 0.0), Point(6.0, 0.0)))
    }

    // ---- patient 3-6 m behind ----

    @Test
    fun farWaitEndsWhenThePatientComesClose() {
        val w = FarWait(t0)
        assertEquals(FarStep.WAIT, w.next(t0 + 1_000, 4.5))
        assertEquals(FarStep.WAIT, w.next(t0 + 5_000, 3.5))
        assertEquals(FarStep.CAME, w.next(t0 + 8_000, 2.2))
    }

    @Test
    fun farWaitGivesUpAfter20Seconds() {
        val still = FarWait(t0)
        assertEquals(FarStep.WAIT, still.next(t0 + 19_000, 4.0))
        // still visible behind: walk on, slower
        assertEquals(FarStep.STILL_FAR, still.next(t0 + 20_000, 4.5))

        val gone = FarWait(t0)
        assertEquals(FarStep.WAIT, gone.next(t0 + 5_000, 5.0))
        assertEquals(FarStep.WAIT, gone.next(t0 + 12_000, null))
        // last seen 15 s ago: that is the lost-patient path
        assertEquals(FarStep.GONE, gone.next(t0 + 20_000, null))
    }

    // ---- nobody behind ----

    @Test
    fun lostTimelineCallsTwiceReportsAt30AndGivesUp60sLater() {
        val l = LostTimeline()
        val steps = mutableListOf<Pair<Long, LostStep>>()
        var t = 0L
        while (t <= 100_000) {
            val s = l.next(t)
            if (s != LostStep.WATCH) steps += t to s
            if (s == LostStep.GIVE_UP) break
            t += 500
        }
        assertEquals(
            listOf(0L to LostStep.CALL, 12_000L to LostStep.CALL, 30_000L to LostStep.REPORT, 90_000L to LostStep.GIVE_UP),
            steps,
        )
        assertEquals(2, l.callsDone)
        assertTrue(l.hasReported)
    }

    @Test
    fun lostTimelineNeverSkipsTheReport() {
        // a long stall: the report still comes before giving up
        val l = LostTimeline()
        assertEquals(LostStep.REPORT, l.next(95_000))
        assertEquals(LostStep.GIVE_UP, l.next(95_500))
    }

    // ---- milestones and progress ----

    @Test
    fun milestonesComeOnceInOrder() {
        val said = mutableSetOf(Milestone.START)
        assertNull(Milestones.due(null, said))
        assertNull(Milestones.due(0.3f, said))
        assertEquals(Milestone.HALF_WAY, Milestones.due(0.55f, said))
        said += Milestone.HALF_WAY
        assertNull(Milestones.due(0.6f, said))
        assertEquals(Milestone.ALMOST_THERE, Milestones.due(0.82f, said))
        said += Milestone.ALMOST_THERE
        assertNull(Milestones.due(0.95f, said))
        // jumping past both only says "almost there"
        assertEquals(Milestone.ALMOST_THERE, Milestones.due(0.9f, setOf(Milestone.START)))
    }

    @Test
    fun progressAlongTheStraightLineNeverGoesBack() {
        val start = Point(0.0, 0.0)
        val dest = Point(10.0, 0.0)
        assertEquals(0.5f, WalkMath.progress(start, Point(5.0, 0.0), dest, null)!!, 1e-6f)
        // a detour that is further away keeps the old value
        assertEquals(0.5f, WalkMath.progress(start, Point(3.0, 2.0), dest, 0.5f)!!, 1e-6f)
        assertEquals(0.95f, WalkMath.progress(start, Point(10.5, 0.0), dest, 0.9f)!!, 1e-6f)
        assertEquals(1f, WalkMath.progress(start, dest, dest, 0.9f)!!, 1e-6f)
        // unknown pose or destination: keeps what it had
        assertNull(WalkMath.progress(start, null, dest, null))
        assertEquals(0.4f, WalkMath.progress(null, Point(1.0, 1.0), dest, 0.4f)!!, 1e-6f)
        // already at the destination when it started
        assertNull(WalkMath.progress(dest, dest, dest, null))
    }

    @Test
    fun nearestSpotAndFindOnTheMap() {
        val map = listOf(MapPlace("Lift", Point(0.0, 0.0)), MapPlace("Room Two", Point(10.0, 0.0)), MapPlace("Desk", Point(5.0, 5.0)))
        assertEquals("Room Two", WalkMath.nearestSpot(Point(8.0, 1.0), map))
        assertNull(WalkMath.nearestSpot(null, map))
        assertNull(WalkMath.nearestSpot(Point(1.0, 1.0), emptyList()))
        assertEquals(Point(10.0, 0.0), WalkMath.find("room two", map)?.point)
        assertNull(WalkMath.find("Room Three", map))
    }

    @Test
    fun walkStatusFactsAreHonest() {
        val f = WalkMath.statusFacts(23.0, Pace.SLOW, "Lift", "Room B204")
        assertTrue(f, f.contains("About 25 meters"))
        assertTrue(f, f.contains("about one minute"))
        assertTrue(f, f.contains("near the spot called Lift"))
        assertTrue(WalkMath.statusFacts(3.0, Pace.NORMAL, null, "Exit").contains("Only a few meters"))
        assertTrue(WalkMath.statusFacts(null, Pace.NORMAL, null, "Exit").contains("not known"))
        assertEquals("about 3 minutes", WalkMath.duration(170.0))
        assertEquals(15, WalkMath.roundMeters(14.0))
        assertEquals(4, WalkMath.roundMeters(3.2))
    }
}
