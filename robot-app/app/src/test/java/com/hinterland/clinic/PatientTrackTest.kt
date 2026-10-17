package com.hinterland.clinic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// same person without knowing who it is: only where the patient was, the turn towards that, and
// picking the person nearest the predicted spot. no faces, no ids.
class PatientTrackTest {

    private val t0 = 10_000_000_000L

    // ---- the turn at a check-back ----

    @Test
    fun theTurnGoesTowardsTheLastBearingNotABlindHalfTurn() {
        // beside on the left, a little behind (radar): turn left 110 deg
        assertEquals(Turn(110f, left = true), TurnPlan.toward(110.0, WalkSide.LEFT))
        // walking on the right after all: turn right
        assertEquals(Turn(100f, left = false), TurnPlan.toward(-100.0, WalkSide.LEFT))
        // nothing known: towards the walk side, beside and a little behind
        assertEquals(Turn(135f, left = true), TurnPlan.toward(null, WalkSide.LEFT))
        assertEquals(Turn(135f, left = false), TurnPlan.toward(null, WalkSide.RIGHT))
        // straight behind is the only half turn
        assertEquals(180f, TurnPlan.toward(180.0, WalkSide.LEFT)!!.degrees, 1e-6f)
        // almost in front: no turn at all
        assertNull(TurnPlan.toward(12.0, WalkSide.LEFT))
        // angles beyond +-180 are wrapped first
        assertEquals(Turn(90f, left = false), TurnPlan.toward(270.0, WalkSide.LEFT))
    }

    @Test
    fun aFreshRadarFixKeepsItsPlaceNextToOtto() {
        val track = PatientTrack()
        val pose = RobotPose(4.0, 1.0, 0.0)
        // beside on the left, a little behind: robot frame (-0.3, 0.9)
        val fix = Point(-0.3, 0.9)
        track.noteRadar(fix, ScanMath.toMap(fix, pose), t0)
        // 2 s later otto drove on 1.2 m: the patient walks with him, the bearing stays
        val p = track.predict(t0 + 2_000, RobotPose(5.2, 1.0, 0.0))!!
        assertTrue(p.relative)
        assertEquals(108.4, p.bearingDeg, 0.5)
        assertEquals(0.95, p.distance!!, 0.01)
        val turn = TurnPlan.toward(p.bearingDeg, WalkSide.LEFT)!!
        assertTrue(turn.left)
        assertEquals(108.4f, turn.degrees, 0.1f)
    }

    @Test
    fun anOldFixPointsToWhereThePatientWasLastSeen() {
        val track = PatientTrack()
        val pose = RobotPose(0.0, 0.0, 0.0)
        val fix = Point(-0.3, 0.9)
        track.noteRadar(fix, ScanMath.toMap(fix, pose), t0)
        // 10 s later otto is 5 m further on: the patient may have stopped there, behind on the left
        val p = track.predict(t0 + 10_000, RobotPose(5.0, 0.0, 0.0))!!
        assertFalse(p.relative)
        assertEquals(170.36, p.bearingDeg, 0.05)
        assertEquals(5.38, p.distance!!, 0.01)
        val turn = TurnPlan.toward(p.bearingDeg, WalkSide.LEFT)!!
        assertTrue(turn.left)
        assertEquals(170.36f, turn.degrees, 0.05f)
        // too far for a distance: only the direction counts
        val far = track.predict(t0 + 20_000, RobotPose(12.0, 0.0, 0.0))!!
        assertNull(far.distance)
    }

    @Test
    fun theNewestFixWins() {
        val track = PatientTrack()
        val pose = RobotPose(0.0, 0.0, 0.0)
        track.noteStart(Point(0.7, 0.0), pose, t0)
        assertEquals(FixSource.START, track.last!!.source)
        track.noteRadar(Point(-0.2, 0.8), Point(-0.2, 0.8), t0 + 5_000)
        assertEquals(FixSource.RADAR, track.last!!.source)
        track.noteCamera(Point(1.1, 0.0), Point(1.1, 0.0), t0 + 9_000)
        assertEquals(FixSource.CAMERA, track.last!!.source)
        // dropped with the walk
        track.clear()
        assertNull(track.last)
        assertNull(track.predict(t0 + 10_000, pose))
    }

    @Test
    fun afterTheTurnThePredictedSpotIsAhead() {
        val track = PatientTrack()
        val fix = Point(-0.3, 0.9)
        track.noteRadar(fix, null, t0)
        val p = track.predict(t0 + 1_000, null)!!
        val turn = TurnPlan.toward(p.bearingDeg, WalkSide.LEFT)
        val t = TurnPlan.target(p, turn, null, WalkSide.LEFT)
        assertEquals(0.0, t.bearingDeg, 0.6)
        assertEquals(0.95, t.distance!!, 0.01)
        // a map point is placed again with the pose after the turn
        val old = Predicted(170.36, 5.38, FixSource.RADAR, 10_000, map = Point(-0.3, 0.9), relative = false)
        val after = RobotPose(5.0, 0.0, Math.toRadians(170.36))
        val placed = TurnPlan.target(old, TurnPlan.toward(old.bearingDeg, WalkSide.LEFT), after, WalkSide.LEFT)
        assertEquals(0.0, placed.bearingDeg, 0.1)
        assertEquals(5.38, placed.distance!!, 0.01)
        // looking 35 deg to the left moves the spot 35 deg to the right
        assertEquals(-35.0, TurnPlan.shifted(Target(0.0, 1.0), 35.0).bearingDeg, 1e-9)
    }

    // ---- picking the patient among several people ----

    @Test
    fun theCameraPicksThePersonNearestThePredictedSpotNotTheNearestOne() {
        // after the turn the patient should be straight ahead, 1.5 m away. a passer-by stands closer, at the side
        val target = Target(0.0, 1.5)
        val people = listOf(SeenPerson(1.0, 40.0), SeenPerson(1.6, 5.0))
        val m = PatientMatch.best(people, emptyList(), target)!!
        assertEquals(1.6, m.distance, 1e-9)
        assertEquals(FixSource.CAMERA, m.source)
        assertTrue(m.cost < PatientMatch.GOOD_M)
        // before v3.3 the nearest one won
        assertEquals(1.0, PatientMatch.best(people, emptyList(), null)!!.distance, 1e-9)
    }

    @Test
    fun theCameraAngleSignDoesNotMatter() {
        // the docs do not say which side a positive angle in view is: both sides are tried
        val target = Target(-20.0, 2.0)
        val a = PatientMatch.best(listOf(SeenPerson(2.0, 20.0)), emptyList(), target)!!
        val b = PatientMatch.best(listOf(SeenPerson(2.0, -20.0)), emptyList(), target)!!
        assertEquals(a.cost, b.cost, 1e-9)
        assertEquals(-20.0, Bearing.of(a.point), 1e-6)
    }

    @Test
    fun theRadarPicksThePairOfLegsAtTheSpot() {
        val target = Target(0.0, 1.4)
        val legs = listOf(Point(0.9, 0.5), Point(1.45, -0.1))
        val m = PatientMatch.best(emptyList(), legs, target)!!
        assertEquals(FixSource.RADAR, m.source)
        assertEquals(1.45, m.point.x, 1e-9)
    }

    @Test
    fun nobodyNearTheSpotMeansLookAround() {
        // the patient should be 1.2 m ahead; the only person is 3 m away to the side
        val target = Target(0.0, 1.2)
        assertNull(PatientMatch.best(listOf(SeenPerson(3.0, 30.0)), emptyList(), target))
        // only the direction known (an old fix): anyone close to that line fits, also far away
        val line = Target(0.0, null)
        assertNotNull(PatientMatch.best(listOf(SeenPerson(4.5, 3.0)), emptyList(), line))
        assertNull(PatientMatch.best(listOf(SeenPerson(4.5, 35.0)), emptyList(), line))
    }

    @Test
    fun atThatsMeThePersonInFrontIsNoted() {
        // the radar first: it knows the side
        val m = PatientMatch.atStart(listOf(SeenPerson(0.6, 3.0)), listOf(Point(0.65, 0.1), Point(1.9, -0.8)))!!
        assertEquals(FixSource.RADAR, m.source)
        assertEquals(0.66, m.distance, 0.01)
        // no radar shape: the camera's nearest person within 2 m
        val c = PatientMatch.atStart(listOf(SeenPerson(2.4, 0.0), SeenPerson(0.7, -4.0)), emptyList())!!
        assertEquals(FixSource.CAMERA, c.source)
        assertEquals(0.7, c.distance, 1e-9)
        // nobody close enough
        assertNull(PatientMatch.atStart(listOf(SeenPerson(2.5, 0.0)), listOf(Point(3.0, 0.0))))
    }
}
