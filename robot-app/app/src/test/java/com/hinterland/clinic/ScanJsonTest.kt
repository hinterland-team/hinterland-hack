package com.hinterland.clinic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

// the radar frame read without org.json: the same points as Json.scan, much less work. the timing test
// prints what one frame costs on this pc (the robot's cpu is slower, the ratio is what counts)
class ScanJsonTest {

    // a frame as the robot sends it (STATUS_LINE_DATA, a Gson list of Pose): ~370 points, robot frame
    private fun robotFrame(points: Int = 369, seed: Int = 1): String {
        val pts = ScanSim(fovDeg = 230.0, startDeg = -110.0, stepDeg = 230.0 / points, seed = seed).corridor(halfWidth = 1.4).person(-0.3, 0.9).scan()
        return pts.joinToString(",", "[", "]") { p ->
            String.format(Locale.ROOT, "{\"px\":%.4f,\"py\":%.4f,\"theta\":0.0,\"time\":%d,\"status\":0,\"distance\":0.0}", p.x, p.y, 1_729_170_000_000L)
        }
    }

    @Test
    fun theSamePointsAsJsonScan() {
        val data = robotFrame()
        val fast = ScanJson.parse(data)!!
        val slow = Json.scan(data)!!
        assertTrue(fast.size > 300)
        assertEquals(slow, fast)
    }

    @Test
    fun otherShapesGoTheSlowWay() {
        assertEquals(listOf(Point(2.0, 3.0)), ScanJson.parse("""[[2,3],[1],["a","b"]]"""))
        assertEquals(1, ScanJson.parse("""{"points":[{"x":1,"y":2}]}""")!!.size)
        assertTrue(ScanJson.parse("[]")!!.isEmpty())
        assertTrue(ScanJson.parse("  [ ]  ")!!.isEmpty())
        assertNull(ScanJson.parse("garbage"))
        assertNull(ScanJson.parse(null))
        // x / y keys, a name with braces and escaped quotes, nested values, extra spaces
        assertEquals(
            listOf(Point(1.5, -2.0), Point(0.25, 0.5)),
            ScanJson.parse("""[ {"name":"a{b}\"c","x": 1.5, "y" : -2.0, "extra":{"px":9,"py":9}}, {"px":2.5E-1,"py":5e-1,"list":[1,2]} ]"""),
        )
        // numbers as text: the slow way reads those
        assertEquals(listOf(Point(1.0, 2.0)), ScanJson.parse("""[{"px":"1","py":"2"}]"""))
        // a cut-off frame is not trusted
        assertEquals(Json.scan("""[{"px":1,"py":2},{"px":3"""), ScanJson.parse("""[{"px":1,"py":2},{"px":3"""))
    }

    @Test
    fun oneRadarFrameCostsLessThanBefore() {
        val frames = (1..20).map { robotFrame(seed = it) }
        val pose = RobotPose(4.0, 2.0, 0.3)
        // warm up the jit
        repeat(300) { i -> Json.scan(frames[i % frames.size]); ScanJson.parse(frames[i % frames.size]) }
        val n = 1_000
        fun time(block: (String) -> Unit): Double {
            val t = System.nanoTime()
            for (i in 0 until n) block(frames[i % frames.size])
            return (System.nanoTime() - t) / 1e3 / n
        }
        val tracker = FollowerTracker()
        var now = 0L
        // the whole frame as the radar thread does it: before (org.json, rear sector) and now
        val before = time { d ->
            val pts = Json.scan(d)!!
            Coverage().add(pts)
            ScanMath.mapFrame(pts, ScanFrame.ROBOT, pose)
            tracker.update(now++, LegDetector.candidates(pts, Sector.REAR), pose)
        }
        val after = time { d ->
            val pts = ScanJson.parse(d)!!
            Coverage().add(pts)
            tracker.update(now++, LegDetector.candidates(pts, Sector.SIDES), pose)
        }
        val parseBefore = time { Json.scan(it) }
        val parseAfter = time { ScanJson.parse(it) }
        println(
            String.format(
                Locale.ROOT,
                "RADAR-BENCH frame %.0f us -> %.0f us (parse %.0f us -> %.0f us), %d points, this pc",
                before, after, parseBefore, parseAfter, ScanJson.parse(frames[0])!!.size,
            ),
        )
        assertTrue("parse $parseBefore -> $parseAfter us", parseAfter < parseBefore)
    }
}
