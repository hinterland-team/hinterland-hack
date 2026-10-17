package com.hinterland.clinic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// "is the patient still behind me?" on simulated scans (ScanSim): a corridor, legs, a wheelchair,
// furniture. robot frame: x forward, y left; "behind" = negative x.
class LidarTest {

    private val t0 = 10_000_000_000L

    // ---- leg detection ----

    @Test
    fun aPersonOneMeterBehindInACorridorIsAPairOfLegs() {
        val pts = ScanSim().corridor().person(-1.0, 0.1).scan()
        val c = LegDetector.candidates(pts, Sector.REAR)
        val pair = c.first { it.kind == LegKind.PAIR }
        assertEquals(1.0, pair.distance, 0.12)
        assertTrue(pair.at.x < 0)
        // the walls are long shapes, never people
        assertEquals(1, c.count { it.kind != LegKind.LEG })
    }

    @Test
    fun anEmptyCorridorHasNobodyBehind() {
        val pts = ScanSim().corridor().scan()
        assertTrue(LegDetector.candidates(pts, Sector.REAR).isEmpty())
        assertNull(LegDetector.nearest(pts, Sector.REAR))
    }

    @Test
    fun aWheelchairBehindIsOneWideShape() {
        // 0.6 m wide, 1.6 m behind
        val pts = ScanSim().corridor(halfWidth = 1.5).box(-1.9, 0.0, w = 0.6, h = 0.6).scan()
        val c = LegDetector.nearest(pts, Sector.REAR)
        assertNotNull(c)
        assertEquals(LegKind.BLOB, c!!.kind)
        assertEquals(1.6, c.distance, 0.15)
    }

    @Test
    fun aFlatPieceOfWallIsNotALeg() {
        // a 15 cm wall stub facing the robot, 1.2 m behind
        val pts = ScanSim(noiseM = 0.0).wall(-1.2, -0.075, -1.2, 0.075).scan()
        assertTrue(LegDetector.candidates(pts, Sector.REAR).none { it.kind == LegKind.LEG || it.kind == LegKind.PAIR })
    }

    @Test
    fun peopleFurtherThanTwoAndAHalfMetersBehindDoNotCount() {
        val pts = ScanSim().corridor().person(-3.2, 0.0).scan()
        assertTrue(LegDetector.candidates(pts, Sector.REAR).isEmpty())
    }

    @Test
    fun afterTheTurnThePatientIsInFront() {
        val pts = ScanSim().corridor().person(1.4, -0.2).scan()
        assertNull(LegDetector.nearest(pts, Sector.REAR))
        val ahead = LegDetector.nearest(pts, Sector.FRONT, maxRange = 6.0)
        assertNotNull(ahead)
        assertEquals(1.4, ahead!!.distance, 0.15)
        // someone 4.5 m ahead still counts in front (the far case of a check-back)
        val far = ScanSim().corridor().person(4.5, 0.0).scan()
        assertEquals(4.5, LegDetector.nearest(far, Sector.FRONT, 6.0)!!.distance, 0.2)
    }

    @Test
    fun sparseScansStillFindLegs() {
        // a cheap scanner: 1.5 deg steps, more noise
        val pts = ScanSim(stepDeg = 1.5, noiseM = 0.006).corridor().person(-0.9, 0.0).scan()
        assertNotNull(LegDetector.nearest(pts, Sector.REAR))
    }

    @Test
    fun theRearSectorWrapsAroundOneEighty() {
        // straight behind: angles near +180 and -180 belong to one shape
        val pts = ScanSim(stepDeg = 0.25).leg(-1.0, 0.0, r = 0.08).scan()
        val clusters = LegDetector.clusters(pts, Sector.REAR)
        assertEquals(1, clusters.size)
    }

    // ---- coverage and frames ----

    @Test
    fun coverageTellsIfTheBackIsVisible() {
        val front = Coverage().apply { add(ScanSim(fovDeg = 270.0).corridor().wall(-6.0, -1.2, -6.0, 1.2).wall(6.0, -1.2, 6.0, 1.2).scan()) }
        assertFalse(front.rearVisible())
        // 270 deg of view fill 28 bins of 10 deg (the edge bins count once a point fell in)
        assertEquals(280, front.coveredDeg())
        assertEquals("280 deg, from -140 to +140", front.describe())
        val all = Coverage().apply { add(ScanSim().corridor().wall(-6.0, -1.2, -6.0, 1.2).wall(6.0, -1.2, 6.0, 1.2).scan()) }
        assertTrue(all.rearVisible())
        assertEquals("360 deg, all around", all.describe())
        assertEquals("no points yet", Coverage().describe())
    }

    @Test
    fun framesAreFoundFromTheData() {
        val pose = RobotPose(8.0, 4.0, 0.7)
        val sim = ScanSim().corridor()
        assertEquals(ScanFrame.MAP, ScanMath.guessFrame(sim.scanInMap(pose), pose))
        assertEquals(ScanFrame.ROBOT, ScanMath.guessFrame(sim.scan(), pose))
        // robot close to the map origin: can't tell
        assertNull(ScanMath.guessFrame(sim.scan(), RobotPose(0.5, 0.3, 0.0)))
        assertNull(ScanMath.guessFrame(sim.scan(), null))
    }

    @Test
    fun frameVoteDecidesAndKeepsIt() {
        val v = FrameVote()
        assertNull(v.add(ScanFrame.MAP))
        assertNull(v.add(null))
        assertNull(v.add(ScanFrame.MAP))
        assertEquals(ScanFrame.MAP, v.add(ScanFrame.MAP))
        assertEquals(ScanFrame.MAP, v.add(ScanFrame.ROBOT))
        assertFalse(v.assumed)
        // never clear: map frame assumed after 15 frames
        val unclear = FrameVote()
        repeat(14) { assertNull(unclear.add(null)) }
        assertEquals(ScanFrame.MAP, unclear.add(null))
        assertTrue(unclear.assumed)
        assertEquals(ScanFrame.ROBOT, FrameVote(fixed = ScanFrame.ROBOT).add(ScanFrame.MAP))
    }

    @Test
    fun mapAndRobotFrameRoundTrip() {
        val pose = RobotPose(3.0, -2.0, Math.toRadians(90.0))
        // 1 m ahead of a robot facing +y is +1 in y on the map
        val ahead = ScanMath.toMap(Point(1.0, 0.0), pose)
        assertEquals(3.0, ahead.x, 1e-9)
        assertEquals(-1.0, ahead.y, 1e-9)
        val back = ScanMath.toRobot(ahead, pose)
        assertEquals(1.0, back.x, 1e-9)
        assertEquals(0.0, back.y, 1e-9)
        assertEquals(Math.toRadians(-170.0), ScanMath.wrap(Math.toRadians(190.0)), 1e-9)
        // degrees from a robot that reports them
        assertEquals(Math.toRadians(90.0), ScanMath.theta(90.0), 1e-9)
        assertEquals(1.2, ScanMath.theta(1.2), 1e-9)
    }

    @Test
    fun downsampleKeepsTheShape() {
        val pts = ScanSim(stepDeg = 0.25).corridor().scan()
        val d = ScanMath.downsample(pts, 240)
        assertEquals(240, d.size)
        assertEquals(pts.first(), d.first())
        assertEquals(pts.take(10), ScanMath.downsample(pts.take(10), 240))
    }

    // ---- following over time ----

    // the robot drives along +x at 0.5 m/s, 5 frames a second
    private fun drive(seconds: Double, patient: Boolean, chairAtMapX: Double?, tracker: FollowerTracker): List<FollowerFix?> {
        val out = ArrayList<FollowerFix?>()
        var t = 0.0
        while (t <= seconds) {
            val pose = RobotPose(2.0 + 0.5 * t, 0.0, 0.0)
            val sim = ScanSim(seed = (t * 10).toInt()).corridor(from = -20.0, to = 40.0)
            if (patient) sim.person(-1.1, 0.05)
            // a chair that stands on the map: in the robot frame it falls back as otto drives on
            chairAtMapX?.let { sim.box(it - pose.x, 0.6, w = 0.4, h = 0.4) }
            val c = LegDetector.candidates(sim.scan(), Sector.REAR)
            out += tracker.update(t0 + (t * 1000).toLong(), c, pose)
            t += 0.2
        }
        return out
    }

    @Test
    fun aPatientWhoFollowsIsTrackedAllTheWay() {
        val tr = FollowerTracker()
        val fixes = drive(4.0, patient = true, chairAtMapX = null, tracker = tr)
        assertTrue(tr.everSeen)
        // confirmed after two frames, then every frame
        assertTrue(fixes.drop(2).all { it != null })
        assertEquals(1.1, fixes.last()!!.distance, 0.15)
        assertTrue(kotlin.math.abs(t0 + 4_000 - tr.lastSeenAt!!) <= 250)
    }

    @Test
    fun furnitureOttoDrivesPastIsNotAFollower() {
        val tr = FollowerTracker()
        // the chair is 0.8 m behind at the start, nobody follows
        val fixes = drive(4.0, patient = false, chairAtMapX = 1.2, tracker = tr)
        // it may look like someone for a moment, but once otto drove 0.6 m away from it, it is dropped
        assertTrue(fixes.takeLast(5).all { it == null })
    }

    @Test
    fun followerWatchFiresOnceAfterFourSeconds() {
        val w = FollowerWatch()
        w.start(t0)
        // never saw the follower in this walk: the radar may just not see this person
        assertFalse(w.check(t0 + 10_000, rearVisible = true, everSeen = false, lastSeenAt = null))
        // back not visible: the schedule does the job
        assertFalse(w.check(t0 + 10_000, rearVisible = false, everSeen = true, lastSeenAt = t0))
        assertFalse(w.check(t0 + 3_900, rearVisible = true, everSeen = true, lastSeenAt = t0))
        assertTrue(w.check(t0 + 4_000, rearVisible = true, everSeen = true, lastSeenAt = t0))
        assertFalse(w.check(t0 + 6_000, rearVisible = true, everSeen = true, lastSeenAt = t0))
        // after the check-back it counts again from the restart
        w.start(t0 + 20_000)
        assertFalse(w.check(t0 + 23_000, rearVisible = true, everSeen = true, lastSeenAt = t0))
        assertTrue(w.check(t0 + 24_100, rearVisible = true, everSeen = true, lastSeenAt = t0))
    }

    @Test
    fun followerWatchStopsTrustingItselfAfterTwoFalseAlarms() {
        val w = FollowerWatch()
        w.falseAlarm()
        assertTrue(w.trusted)
        w.falseAlarm()
        assertFalse(w.trusted)
        w.start(t0)
        assertFalse(w.check(t0 + 60_000, rearVisible = true, everSeen = true, lastSeenAt = t0))
    }
}
