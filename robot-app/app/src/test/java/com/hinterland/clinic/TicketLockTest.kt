package com.hinterland.clinic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// the ticket lock (docs/demo-mode.md): once locked, only a candidate near the patient's predicted spot next to
// otto counts; a shape that keeps its map spot while otto drives on is never the patient. positions only
class TicketLockTest {

    private val t0 = 10_000_000_000L

    private fun leg(x: Double, y: Double) = LegCandidate(Point(x, y), Math.hypot(x, y), LegKind.PAIR, 0.3)

    // otto drives along x at 0.5 m/s, a frame every 200 ms: the pose at frame i
    private fun pose(i: Int) = RobotPose(i * 0.1, 0.0, 0.0)

    @Test
    fun onceLockedOnlyTheSpotNextToOttoCounts() {
        val t = FollowerTracker()
        // the patient walks with otto 1 m to his right: locked after two frames
        assertNull(t.update(t0, listOf(leg(-0.3, -1.0)), pose(0)))
        assertNotNull(t.update(t0 + 200, listOf(leg(-0.3, -1.0)), pose(1)))
        assertTrue(t.locked)
        // someone else on the left, and the patient gone: nobody is taken
        assertNull(t.update(t0 + 2_000, listOf(leg(-0.3, 1.0)), pose(10)))
        assertNull(t.update(t0 + 2_200, listOf(leg(-0.3, 1.0)), pose(11)))
        assertEquals(1, t.ignoredOther)
        // someone 0.9 m off the patient's spot: still not the patient
        assertNull(t.update(t0 + 2_400, listOf(leg(-0.3, -1.9)), pose(12)))
        // the patient back at their spot (within 0.7 m): found again
        t.update(t0 + 2_600, listOf(leg(-0.2, -0.8), leg(-0.3, 1.0)), pose(13))
        val back = t.update(t0 + 2_800, listOf(leg(-0.2, -0.8), leg(-0.3, 1.0)), pose(14))
        assertNotNull(back)
        assertEquals(-0.8, back!!.robot.y, 1e-9)
        assertEquals(1, t.ignoredOther)
    }

    @Test
    fun thePredictedSpotTurnsWithOtto() {
        val t = FollowerTracker()
        t.update(t0, listOf(leg(0.0, -1.0)), RobotPose(0.0, 0.0, 0.0))
        t.update(t0 + 200, listOf(leg(0.0, -1.0)), RobotPose(0.0, 0.0, 0.0))
        // otto turns 90 deg to the right on the spot: the patient (on his right) is now in front of him
        val p = t.predicted(RobotPose(0.0, 0.0, -Math.PI / 2))!!
        assertEquals(1.0, p.x, 1e-9)
        assertEquals(0.0, p.y, 1e-9)
        // a camera anchor at a check-back moves the lock there
        t.anchor(t0 + 400, Point(1.2, 0.0), Point(0.0, -1.2), RobotPose(0.0, 0.0, -Math.PI / 2))
        assertEquals(1.2, t.predicted(RobotPose(0.0, 0.0, -Math.PI / 2))!!.x, 1e-9)
    }

    @Test
    fun aShapeThatKeepsItsMapSpotWhileOttoDrivesOnIsNeverThePatient() {
        val t = FollowerTracker()
        // a pillar at map (1.0, -1.0) while otto drives past it, 0.5 m/s
        var i = 0
        var picked = 0
        while (i <= 10) {
            val p = pose(i)
            val pillar = ScanMath.toRobot(Point(1.0, -1.0), p)
            if (t.update(t0 + i * 200L, listOf(LegCandidate(pillar, ScanMath.range(pillar), LegKind.BLOB, 0.3)), p) != null) picked++
            i++
        }
        // after a second of driving it is known as static: left out from then on, also later in the walk
        assertTrue(t.ignoredStatic >= 1)
        val later = pose(30)
        val again = ScanMath.toRobot(Point(1.05, -1.0), later)
        t.update(t0 + 6_000, listOf(LegCandidate(again, ScanMath.range(again), LegKind.BLOB, 0.3)), later)
        assertEquals(1, t.ignoredStatic)
        assertNull(t.update(t0 + 6_200, listOf(LegCandidate(again, ScanMath.range(again), LegKind.BLOB, 0.3)), later))
        // the patient walking along with otto is never static
        val w = FollowerTracker()
        var seen: FollowerFix? = null
        for (k in 0..15) seen = w.update(t0 + k * 200L, listOf(leg(-0.3, -1.0)), pose(k))
        assertNotNull(seen)
        assertEquals(0, w.ignoredStatic)
    }

    @Test
    fun theDemosFellBackIsTimedOnly() {
        // b20: the lock does not trigger fellBack in the demo, only fellBackAfterS of driving after firstCheck does
        val plan = DemoWalkPlan(DemoScripts.default, firstCheckMs = 10_000)
        plan.played(DemoPart.FIRST_CHECK)
        assertNull(plan.due())
        plan.tick(t0, blocked = false)
        plan.tick(t0 + 1_500, blocked = false)
        plan.tick(t0 + 3_000, blocked = false)
        plan.tick(t0 + 4_500, blocked = false)
        assertNull(plan.due())
        plan.tick(t0 + 6_000, blocked = false)
        assertEquals(DemoPart.FELL_BACK, plan.due())
    }

    @Test
    fun theLockStartsFromThePatientsSpotAtTheWalkStart() {
        val t = FollowerTracker()
        // the patient stood at map (0.0, -1.0) when the walk started
        t.lockStart(Point(0.0, -1.0))
        // someone 2 m away on the left is not taken, the one near the start spot is
        assertNull(t.update(t0, listOf(leg(0.0, 2.0), leg(-0.1, -1.1)), pose(0)))
        val fix = t.update(t0 + 200, listOf(leg(0.0, 2.0), leg(-0.2, -1.1)), pose(1))
        assertNotNull(fix)
        assertEquals(-1.1, fix!!.robot.y, 1e-9)
        // nobody within 1 m of the start spot: no lock
        val far = FollowerTracker()
        far.lockStart(Point(0.0, -1.0))
        assertNull(far.update(t0, listOf(leg(0.0, 1.5)), pose(0)))
        assertNull(far.update(t0 + 200, listOf(leg(0.0, 1.5)), pose(0)))
    }
}
