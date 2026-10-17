package com.hinterland.clinic

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

// a small 2d laser scanner for the tests: rays from the robot (0/0, x forward, y left) against walls,
// round legs and boxes, with a fixed angular step, a field of view and a little range noise.
// gives the same kind of point lists the radar reports, without a robot.
class ScanSim(
    private val fovDeg: Double = 360.0,
    private val stepDeg: Double = 0.5,
    private val maxRange: Double = 10.0,
    private val noiseM: Double = 0.003,
    seed: Int = 7,
    // where the scan starts; null = centred on straight ahead (our robot: -110, fov 230)
    private val startDeg: Double? = null,
) {
    private val random = Random(seed)
    private val segments = ArrayList<Pair<Point, Point>>()
    private val circles = ArrayList<Pair<Point, Double>>()

    fun wall(x1: Double, y1: Double, x2: Double, y2: Double) = apply { segments += Point(x1, y1) to Point(x2, y2) }

    // a corridor along x, walls at y = +-halfWidth
    fun corridor(halfWidth: Double = 1.2, from: Double = -12.0, to: Double = 12.0) =
        wall(from, halfWidth, to, halfWidth).wall(from, -halfWidth, to, -halfWidth)

    fun leg(x: Double, y: Double, r: Double = 0.06) = apply { circles += Point(x, y) to r }

    // a person standing with the legs side by side (apart = centre to centre)
    fun person(x: Double, y: Double, apart: Double = 0.22) = leg(x, y + apart / 2).leg(x, y - apart / 2)

    // an axis-aligned box (a wheelchair seen from behind, a chair, a cabinet)
    fun box(cx: Double, cy: Double, w: Double, h: Double) = apply {
        val x1 = cx - h / 2
        val x2 = cx + h / 2
        val y1 = cy - w / 2
        val y2 = cy + w / 2
        wall(x1, y1, x1, y2).wall(x2, y1, x2, y2).wall(x1, y1, x2, y1).wall(x1, y2, x2, y2)
    }

    // the scan in the robot frame, in angle order from -fov/2
    fun scan(): List<Point> {
        val out = ArrayList<Point>()
        val n = (fovDeg / stepDeg).toInt()
        for (i in 0 until n) {
            val deg = (startDeg ?: -fovDeg / 2) + i * stepDeg
            val a = Math.toRadians(deg)
            val dx = cos(a)
            val dy = sin(a)
            var best = Double.MAX_VALUE
            for ((p, q) in segments) hitSegment(dx, dy, p, q)?.let { if (it < best) best = it }
            for ((c, r) in circles) hitCircle(dx, dy, c, r)?.let { if (it < best) best = it }
            if (best > maxRange) continue
            val d = best + (random.nextDouble() * 2 - 1) * noiseM
            out += Point(d * dx, d * dy)
        }
        return out
    }

    // the same scan moved into the map frame for a robot at pose
    fun scanInMap(pose: RobotPose): List<Point> = scan().map { ScanMath.toMap(it, pose) }

    private fun hitSegment(dx: Double, dy: Double, p: Point, q: Point): Double? {
        val ex = q.x - p.x
        val ey = q.y - p.y
        val den = dx * ey - dy * ex
        if (kotlin.math.abs(den) < 1e-12) return null
        val t = (p.x * ey - p.y * ex) / den
        val u = (p.x * dy - p.y * dx) / den
        return if (t > 0 && u in 0.0..1.0) t else null
    }

    private fun hitCircle(dx: Double, dy: Double, c: Point, r: Double): Double? {
        val b = dx * c.x + dy * c.y
        val disc = b * b - (c.x * c.x + c.y * c.y - r * r)
        if (disc < 0) return null
        val t = b - sqrt(disc)
        return if (t > 0) t else null
    }
}
