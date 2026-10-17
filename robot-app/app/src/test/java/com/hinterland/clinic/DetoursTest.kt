package com.hinterland.clinic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// a blocked way (field test 17.10, b10: 12 "please step aside" in 5 minutes, never another way):
// the blocked count per leg across restarts, the detour steps and the automatic waypoint choice.
// spot names and layouts are test data only.
class DetoursTest {

    private val t0 = 10_000_000_000L

    // the b10 walk: the corridor stays blocked; every ~15 s a check-back stops the drive for 8 s and a new
    // drive starts (blocked again at once). returns the give-up time (ms after the start) and the asks
    private fun blockedCorridor(newCountEachDrive: Boolean, minutes: Int = 5): Pair<Long?, Int> {
        val leg = LegBlock()
        var way: WayBlock = if (newCountEachDrive) BlockedWatch() else leg
        var asks = 0
        var t = 0L
        val end = minutes * 60_000L
        while (t < end) {
            // one drive: blocked from its start, until the check-back after 15 s
            val driveEnd = t + 15_000
            way.blocked(t0 + t)
            while (t < driveEnd) {
                when (way.step(t0 + t)) {
                    BlockedStep.ASK -> asks++
                    BlockedStep.GIVE_UP -> return t to asks
                    BlockedStep.NONE -> Unit
                }
                t += 250
            }
            way.stop(t0 + t)
            // the check-back
            t += 8_000
            if (newCountEachDrive) way = BlockedWatch()
        }
        return null to asks
    }

    @Test
    fun theOldCountPerDriveLoopedForever() {
        val (gaveUp, asks) = blockedCorridor(newCountEachDrive = true)
        assertNull(gaveUp)
        // like the field test: a dozen asks and no other way
        assertTrue("asks $asks", asks >= 12)
    }

    @Test
    fun theLegCountGivesUpAfterTwoAsksAcrossRestarts() {
        val (gaveUp, asks) = blockedCorridor(newCountEachDrive = false)
        assertNotNull(gaveUp)
        assertEquals(2, asks)
        // asked at 0 s and 10 s; the grace after the 2nd ask ran out while the check-back stood: at the next drive
        assertEquals(23_000L, gaveUp)
    }

    @Test
    fun blockedTimeAddsUpOverManyShortJams() {
        // blocked 4 s, free 1 s, again and again, each in its own short drive: 25 s in total ends it
        val leg = LegBlock(maxAsks = 99)
        var t = t0
        var result: BlockedStep = BlockedStep.NONE
        var drives = 0
        while (result != BlockedStep.GIVE_UP && drives < 20) {
            leg.blocked(t)
            repeat(16) {
                if (result != BlockedStep.GIVE_UP) result = leg.step(t)
                t += 250
            }
            leg.clear(t)
            leg.stop(t)
            t += 1_000
            drives++
        }
        assertEquals(BlockedStep.GIVE_UP, result)
        assertTrue("drives $drives", drives in 7..8)
        assertTrue(leg.totalMs(t) in 25_000L..29_000L)
    }

    @Test
    fun eachDetourStepGetsItsOwnShortBudget() {
        val leg = LegBlock()
        leg.blocked(t0)
        assertEquals(BlockedStep.ASK, leg.step(t0))
        assertEquals(BlockedStep.GIVE_UP, leg.step(t0 + 26_000))
        // step A: one ask, 15 s
        leg.nextAttempt(t0 + 26_000, limitMs = 15_000, asks = 1)
        assertEquals(1, leg.attempts)
        assertEquals(BlockedStep.NONE, leg.step(t0 + 26_000))
        leg.blocked(t0 + 30_000)
        assertEquals(BlockedStep.ASK, leg.step(t0 + 30_000))
        assertEquals(BlockedStep.NONE, leg.step(t0 + 37_900))
        assertEquals(BlockedStep.GIVE_UP, leg.step(t0 + 38_000))
        // the leg's total keeps everything
        assertEquals(34_000L, leg.totalMs(t0 + 38_000))
        assertEquals(8_000L, leg.usedMs(t0 + 38_000))
    }

    @Test
    fun gettingPastTheJamStartsTheCountAgain() {
        val leg = LegBlock()
        // 12 m to go when the way gets blocked
        assertFalse(leg.progress(t0, 12.0))
        leg.blocked(t0)
        assertEquals(BlockedStep.ASK, leg.step(t0))
        leg.clear(t0 + 6_000)
        // inching forward 1 m is not past it
        assertFalse(leg.progress(t0 + 7_000, 11.0))
        // 1.5 m closer than where it got stuck: past it
        assertTrue(leg.progress(t0 + 9_000, 10.4))
        assertEquals(0, leg.asksDone)
        assertEquals(0L, leg.usedMs(t0 + 9_000))
        leg.blocked(t0 + 20_000)
        assertEquals(BlockedStep.ASK, leg.step(t0 + 20_000))
    }

    @Test
    fun drivingOnRoundACornerAlsoCountsAsPastTheJam() {
        // the way first leads away from the target (round a corner): not closer, but 4 m driven
        val leg = LegBlock()
        leg.progress(t0, 8.0, drivenM = 10.0)
        leg.blocked(t0)
        leg.step(t0)
        leg.clear(t0 + 3_000)
        assertFalse(leg.progress(t0 + 6_000, 8.6, drivenM = 12.0))
        assertTrue(leg.progress(t0 + 9_000, 9.1, drivenM = 14.2))
        assertEquals(0, leg.asksDone)
    }

    @Test
    fun theStepsComeInOrder() {
        val d = DetourSteps()
        assertEquals(DetourStep.BACK_OFF, d.next(waypointsLeft = 3))
        assertEquals(DetourStep.WAYPOINT, d.next(3))
        assertEquals(DetourStep.WAYPOINT, d.next(2))
        assertEquals(DetourStep.HELP, d.next(1))
        assertEquals(DetourStep.HELP, d.next(1))
        // without waypoints: from backing off straight to calling the team
        val none = DetourSteps()
        assertEquals(DetourStep.BACK_OFF, none.next(0))
        assertEquals(DetourStep.HELP, none.next(0))
        // past the jam, a later jam may use every step again
        none.reset()
        assertEquals(DetourStep.BACK_OFF, none.next(0))
    }

    // ---- the waypoint ----

    private fun spot(name: String, x: Double, y: Double) = MapPlace(name, Point(x, y))

    @Test
    fun aHallWithTwoWaysAroundTakesTheOneOffTheBlockedDirectionFirst() {
        // otto at the hall's west end heading east to Room 7, blocked right ahead
        val places = listOf(
            spot("Hall North", 5.0, 3.0), spot("Hall South", 3.0, -4.0), spot("Kiosk", 1.5, 0.3),
            spot("Far Wing", 5.0, 12.0), spot("Back Door", -3.0, 0.0), spot("Room 7", 10.0, 0.0),
        )
        val w = DetourPlan.waypoints(places, Point(0.0, 0.0), 0.0, Point(10.0, 0.0), exclude = listOf("Room 7"))
        assertEquals(listOf("Hall South", "Hall North"), w.map { it.name })
        // 53 deg off the blocked way first, then 31 deg; both much shorter than twice the direct way
        assertEquals(53.1, w[0].offDeg!!, 0.5)
        assertTrue(w[0].ratio!! < 1.4 && w[1].ratio!! < 1.2)
        // Kiosk is in the jam, Far Wing too long, Back Door leads through the jam again
        assertTrue(w[0].why().contains("deg off the blocked way"))
    }

    @Test
    fun aLobbyWithAPillar() {
        // heading north to the lifts, blocked in front of the pillar
        val places = listOf(spot("Info Desk", 2.5, 4.0), spot("Lift Lobby", 4.0, 1.0), spot("Lifts", 0.0, 8.0))
        val w = DetourPlan.waypoints(places, Point(0.0, 0.0), Math.PI / 2, Point(0.0, 8.0), exclude = listOf("Lifts"))
        // 76 deg off the blocked way comes first, although its way is longer than the 32 deg one
        assertEquals(listOf("Lift Lobby", "Info Desk"), w.map { it.name })
        assertTrue(w[0].ratio!! > w[1].ratio!!)
    }

    @Test
    fun aRingWhereTheWayRoundIsTooLongMeansCallingTheTeam() {
        // the ring corridor: the other way round is more than twice as long, nothing to try
        val places = listOf(
            spot("Reception Point", 0.0, 0.0), spot("Dr. Red", 10.0, 6.0), spot("Elevator", 5.0, 6.0),
            spot("Stairs Down", 0.0, 6.0), spot("Home", -2.0, 3.0), spot("Dr. Blue", 10.0, 0.0),
        )
        val w = DetourPlan.waypoints(places, Point(4.0, 0.0), 0.0, Point(10.0, 0.0), exclude = listOf("Dr. Blue"))
        assertTrue(w.toString(), w.isEmpty())
    }

    @Test
    fun triedSpotsTheTargetAndUnknownHeadingsAreHandled() {
        val places = listOf(spot("Hall North", 5.0, 3.0), spot("Hall South", 3.0, -4.0), spot("Room 7", 10.0, 0.0))
        val w = DetourPlan.waypoints(places, Point(0.0, 0.0), null, Point(10.0, 0.0), exclude = listOf("room 7", "Hall South"))
        // no heading: the blocked direction is straight towards the target
        assertEquals(listOf("Hall North"), w.map { it.name })
        // standing at the target already: nothing to do
        assertTrue(DetourPlan.waypoints(places, Point(10.0, 0.2), 0.0, Point(10.0, 0.0), emptyList()).isEmpty())
        assertEquals(1.0, DetourPlan.segmentDistance(Point(1.0, 1.0), Point(0.0, 0.0), Point(5.0, 0.0)), 1e-9)
        assertEquals("the place's waypoint", Waypoint("Hall Corner", null, null, explicit = true).why())
    }

    @Test
    fun waitingAtAnObstacleIsNotFallingBehind() {
        // b10: the pace fell 0.6 -> 0.4 -> 0.25 m/s while otto stood in a blocked corridor
        assertFalse(FellBehind.forReal(0.4))
        assertFalse(FellBehind.forReal(1.9))
        assertTrue(FellBehind.forReal(2.0))
    }
}
