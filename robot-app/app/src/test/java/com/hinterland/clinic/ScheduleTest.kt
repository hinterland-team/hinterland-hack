package com.hinterland.clinic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// v3.2 walk decisions: the adaptive check-back schedule, the talk window, blocked ways and detours
class ScheduleTest {

    private val t0 = 10_000_000_000L

    // a walk: ticks every 250 ms like the navigation watcher. at each due check-back otto stops for
    // checkBackMs (turn, look, talk, turn back); that time is not navigation time.
    private data class Walked(val reasons: List<CheckReason>, val atMs: List<Long>)

    private fun walk(
        driveMs: Long,
        distance: Double? = 1.0,
        checkBackMs: Long = 9_000,
        blocked: (Long) -> Boolean = { false },
        heading: (Long) -> Double? = { 0.0 },
        lost: (Long) -> Boolean = { false },
    ): Walked {
        val s = CheckSchedule()
        val reasons = ArrayList<CheckReason>()
        val at = ArrayList<Long>()
        var t = t0
        var driven = 0L
        while (driven <= driveMs) {
            s.tick(t, blocked(driven), heading(driven))
            if (lost(driven)) s.followerLost()
            val due = s.due(t)
            if (due != null) {
                reasons += due
                at += driven
                s.pause()
                t += checkBackMs
                s.done(distance, heading(driven))
            }
            t += 250
            driven += 250
        }
        return Walked(reasons, at)
    }

    @Test
    fun theFieldTestWalkOf34SecondsGetsAtLeastTwoCheckBacks() {
        // 17.10: Reception -> Dr. Blue took 34 s and otto never turned around
        val w = walk(34_000)
        assertTrue("check-backs at ${w.atMs}", w.reasons.size >= 2)
        assertEquals(CheckReason.FIRST, w.reasons.first())
        // the first one comes early, even on a short walk
        assertEquals(10_000L, w.atMs.first())
        assertTrue(w.atMs[1] <= 25_250)
    }

    @Test
    fun aBlockedFlagThatNeverClearsDoesNotStopTheChecks() {
        // the end-of-obstacle status never came: the old counter stood still, the new one does not
        val w = walk(34_000, blocked = { it > 3_000 })
        assertTrue("check-backs at ${w.atMs}", w.reasons.size >= 2)
    }

    @Test
    fun trustGrowsStepByStep() {
        // patient always close: 10 s, then 15, 20, 25, 30, 30
        assertEquals(15_000, CheckSchedule.intervalAfter(1, 1.0))
        assertEquals(20_000, CheckSchedule.intervalAfter(2, 1.0))
        assertEquals(25_000, CheckSchedule.intervalAfter(3, 1.0))
        assertEquals(30_000, CheckSchedule.intervalAfter(4, 1.0))
        assertEquals(30_000, CheckSchedule.intervalAfter(9, 1.0))
        // far behind: always 10 s; unknown: 20 s once trust allows it
        assertEquals(10_000, CheckSchedule.intervalAfter(5, 3.5))
        assertEquals(15_000, CheckSchedule.intervalAfter(1, null))
        assertEquals(20_000, CheckSchedule.intervalAfter(6, null))
        // in between: linear from 30 s at 1.5 m to 10 s at 3 m
        assertEquals(20_000, CheckSchedule.intervalFor(2.25))
    }

    @Test
    fun aLongWalkSpacesOutTheChecks() {
        val w = walk(120_000)
        val gaps = w.atMs.zipWithNext { a, b -> b - a }
        // the first tick after a check only starts the count: one tick on top of each interval
        assertEquals(listOf(15_000L, 20_000L, 25_000L), gaps.take(3).map { it - 250 })
        assertTrue(gaps.drop(3).all { it == 30_250L })
    }

    @Test
    fun aTurnOfMoreThan45DegreesGetsACheckWhenTheTurnIsOver() {
        // a corner: turning left from 16 s to 18 s (90 deg), then straight on
        val heading = { d: Long ->
            when {
                d < 16_000 -> 0.0
                d < 18_000 -> Math.toRadians(90.0 * (d - 16_000) / 2_000.0)
                else -> Math.toRadians(90.0)
            }
        }
        val w = walk(30_000, heading = heading)
        assertEquals(CheckReason.FIRST, w.reasons[0])
        val turn = w.reasons.indexOf(CheckReason.TURN)
        assertTrue("reasons ${w.reasons}", turn > 0)
        // 1.5 s after the heading settled
        assertTrue("${w.atMs}", w.atMs[turn] in 19_500..20_000)
    }

    @Test
    fun theTurnOnTheSpotAtTheStartIsNoCorner() {
        // navigation starts by turning 150 deg towards the path within 2 s
        val heading = { d: Long -> Math.toRadians(150.0 * (d.coerceAtMost(2_000) / 2_000.0)) }
        val w = walk(12_000, heading = heading)
        assertEquals(listOf(CheckReason.FIRST), w.reasons)
    }

    @Test
    fun smallWigglesAreNoTurn() {
        val heading = { d: Long -> Math.toRadians(if ((d / 1_000) % 2 == 0L) 20.0 else -20.0) }
        val w = walk(40_000, heading = heading)
        assertFalse(CheckReason.TURN in w.reasons)
    }

    @Test
    fun anObstacleWaitGetsACheckRightAfter() {
        // blocked from 3 s to 7 s at the start
        val w = walk(9_000, blocked = { it in 3_000..7_000 })
        assertEquals(CheckReason.OBSTACLE, w.reasons.first())
        assertTrue(w.atMs.first() in 7_000..7_500)
    }

    @Test
    fun theRadarCanAskForACheckAnyTime() {
        val w = walk(6_000, lost = { it == 5_000L })
        assertEquals(listOf(CheckReason.FOLLOWER_LOST), w.reasons)
    }

    @Test
    fun aCornerAfterACheckGetsItsOwnCheck() {
        val s = CheckSchedule()
        var t = t0
        repeat(41) { s.tick(t, false, 0.0); t += 250 }
        assertEquals(CheckReason.FIRST, s.due(t))
        s.done(1.0, 0.0)
        // straight on for the first seconds of the new drive, then a corner
        repeat(17) { s.tick(t, false, 0.0); t += 250 }
        repeat(8) { i -> s.tick(t, false, Math.toRadians(10.0 * i)); t += 250 }
        // still settling, and not 6 s of navigation since the last check yet
        repeat(4) { s.tick(t, false, Math.toRadians(80.0)); t += 250 }
        assertNull(s.due(t))
        repeat(4) { s.tick(t, false, Math.toRadians(80.0)); t += 250 }
        assertEquals(CheckReason.TURN, s.due(t))
    }

    @Test
    fun radarDistanceMovesTheNextCheck() {
        val s = CheckSchedule()
        // before the first check the distance changes nothing: the first check stays at 10 s
        assertFalse(s.noteDistance(3.5))
        assertEquals(10_000, s.intervalMs)
        s.done(1.0, null)
        assertEquals(15_000, s.intervalMs)
        // the patient falls back to 3.5 m: next check in 10 s, worth a log line
        assertTrue(s.noteDistance(3.5))
        assertEquals(10_000, s.intervalMs)
        assertFalse(s.noteDistance(3.4))
        assertEquals(10_000, s.nextInMs())
    }

    @Test
    fun noCheckInTheLastTwoMeters() {
        assertTrue(NearGoal.skipCheck(Point(0.0, 0.0), Point(1.5, 1.0)))
        assertFalse(NearGoal.skipCheck(Point(0.0, 0.0), Point(3.0, 0.0)))
        assertFalse(NearGoal.skipCheck(null, Point(1.0, 0.0)))
    }

    @Test
    fun cameraFirstRadarBacksItUp() {
        val nobody = listOf<List<Double>?>(emptyList(), emptyList())
        val blind = listOf<List<Double>?>(null, null)
        assertEquals(Proximity.CLOSE, CheckBack.decide(listOf(listOf(1.2)), lidarAhead = 5.0))
        // the camera saw nobody (dim corridor), the radar sees legs 1.4 m ahead
        assertEquals(Proximity.CLOSE, CheckBack.decide(nobody, 1.4))
        assertEquals(Proximity.FAR, CheckBack.decide(nobody, 4.2))
        assertEquals(Proximity.NOBODY, CheckBack.decide(nobody, null))
        assertEquals(Proximity.NOBODY, CheckBack.decide(nobody, 8.0))
        assertEquals(Proximity.CLOSE, CheckBack.decide(blind, 2.0))
        assertEquals(Proximity.UNKNOWN, CheckBack.decide(blind, null))
        assertEquals(1.4, CheckBack.nearestOf(2.0, 1.4)!!, 1e-9)
        assertNull(CheckBack.nearestOf(null, -1.0))
    }

    // the talk window is a short listening window since v3.3: StopsTest

    // after a stop facing the patient straight behind: the navigation plans for 1.5 s, then turns otto
    // back to the path at the slowest pace (0.6 rad/s, about 34 deg/s) for ~5.3 s
    private fun turnChecksAfterStop(turnedAround: Boolean): Int {
        val s = CheckSchedule()
        var t = t0
        repeat(41) { s.tick(t, false, 0.0); t += 250 }
        s.done(1.0, Math.toRadians(180.0), turnedAround = turnedAround)
        var turns = 0
        for (i in 0 until 60) {
            val ms = i * 250.0
            val deg = if (ms < 1_500) 180.0 else (180.0 - 34.0 * (ms - 1_500) / 1_000.0).coerceAtLeast(0.0)
            s.tick(t, false, Math.toRadians(deg))
            if (s.due(t) == CheckReason.TURN) {
                turns++
                s.done(1.0, Math.toRadians(deg))
            }
            t += 250
        }
        return turns
    }

    @Test
    fun afterAStopTheTurnBackToThePathIsNoCorner() {
        // with the normal 4 s at the start of a drive this was taken for a corner: a second stop right away
        assertEquals(1, turnChecksAfterStop(turnedAround = false))
        assertEquals(0, turnChecksAfterStop(turnedAround = true))
    }

    // ---- blocked way ----

    @Test
    fun blockedAsksTwiceThenGivesUp() {
        val b = BlockedWatch()
        assertEquals(BlockedStep.NONE, b.step(t0))
        b.blocked(t0)
        assertEquals(BlockedStep.ASK, b.step(t0))
        assertEquals(BlockedStep.NONE, b.step(t0 + 9_000))
        assertEquals(BlockedStep.ASK, b.step(t0 + 10_000))
        assertEquals(2, b.asksDone)
        assertEquals(BlockedStep.NONE, b.step(t0 + 17_000))
        assertEquals(BlockedStep.GIVE_UP, b.step(t0 + 18_000))
    }

    @Test
    fun theWayClearsInBetween() {
        val b = BlockedWatch()
        b.blocked(t0)
        assertEquals(BlockedStep.ASK, b.step(t0))
        b.clear(t0 + 4_000)
        assertEquals(BlockedStep.NONE, b.step(t0 + 20_000))
        // a new block starts with the first ask again
        b.blocked(t0 + 30_000)
        assertEquals(BlockedStep.ASK, b.step(t0 + 30_000))
        assertEquals(1, b.asksDone)
    }

    @Test
    fun movingOnClearsAStuckBlockedFlag() {
        val b = BlockedWatch()
        b.blocked(t0)
        b.step(t0)
        assertFalse(b.moved(t0 + 2_000, 0.3))
        assertTrue(b.isBlocked)
        assertTrue(b.moved(t0 + 4_000, 0.7))
        assertFalse(b.isBlocked)
        assertEquals(BlockedStep.NONE, b.step(t0 + 30_000))
    }

    @Test
    fun manyShortBlocksStillEndTheDrive() {
        val b = BlockedWatch()
        var t = t0
        // blocked 6 s, free 1 s, again and again: more than 25 s blocked in total ends it
        val steps = ArrayList<BlockedStep>()
        repeat(6) {
            b.blocked(t)
            b.step(t)
            t += 6_000
            steps += b.step(t)
            b.clear(t)
            t += 1_000
        }
        assertEquals(BlockedStep.GIVE_UP, steps[4])
        assertTrue(steps.take(4).none { it == BlockedStep.GIVE_UP })
    }

    // the detour steps after a blocked way: DetoursTest
}
