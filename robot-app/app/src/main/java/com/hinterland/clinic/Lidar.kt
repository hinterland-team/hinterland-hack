package com.hinterland.clinic

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

// "is the patient still behind me?" from the robot's radar data (RobotAPI "Radar Data Listener",
// Definition.STATUS_LINE_DATA = a list of Pose points). plain kotlin without android, unit tested.
// robot frame: x forward, y left, meters. angles in degrees: 0 = straight ahead, +90 = left, ±180 = behind.

// where the robot is on the map. theta in radians.
data class RobotPose(val x: Double, val y: Double, val theta: Double) {
    val point: Point get() = Point(x, y)
}

// the frame the radar points come in. the docs do not say, so Otto finds out (ScanMath.guessFrame).
enum class ScanFrame { MAP, ROBOT }

object ScanMath {

    // map point -> robot frame
    fun toRobot(p: Point, pose: RobotPose): Point {
        val dx = p.x - pose.x
        val dy = p.y - pose.y
        val c = cos(pose.theta)
        val s = sin(pose.theta)
        return Point(dx * c + dy * s, -dx * s + dy * c)
    }

    // robot frame -> map point
    fun toMap(p: Point, pose: RobotPose): Point {
        val c = cos(pose.theta)
        val s = sin(pose.theta)
        return Point(pose.x + p.x * c - p.y * s, pose.y + p.x * s + p.y * c)
    }

    fun angleDeg(p: Point): Double = Math.toDegrees(atan2(p.y, p.x))

    fun range(p: Point): Double = hypot(p.x, p.y)

    // any angle in radians -> -pi..pi
    fun wrap(rad: Double): Double {
        var a = rad % (2 * Math.PI)
        if (a > Math.PI) a -= 2 * Math.PI
        if (a <= -Math.PI) a += 2 * Math.PI
        return a
    }

    // theta from the robot: radians, but a value far outside -2pi..2pi can only be degrees
    fun theta(raw: Double): Double = if (abs(raw) > 2 * Math.PI + 0.2) Math.toRadians(raw) else raw

    // radar points sit within a few meters of the sensor. near the robot's map position = map frame,
    // near 0/0 = robot frame. null when it can't tell (robot close to the map origin, odd data).
    fun guessFrame(points: List<Point>, pose: RobotPose?): ScanFrame? {
        if (pose == null || points.size < MIN_POINTS_FOR_GUESS) return null
        if (hypot(pose.x, pose.y) < AMBIGUOUS_ORIGIN_M) return null
        val toOrigin = median(points.map { hypot(it.x, it.y) })
        val toRobot = median(points.map { hypot(it.x - pose.x, it.y - pose.y) })
        return when {
            toRobot < toOrigin * CLEAR_RATIO -> ScanFrame.MAP
            toOrigin < toRobot * CLEAR_RATIO -> ScanFrame.ROBOT
            else -> null
        }
    }

    // radar points -> robot frame, whatever frame they came in
    fun robotFrame(points: List<Point>, frame: ScanFrame, pose: RobotPose?): List<Point>? = when (frame) {
        ScanFrame.ROBOT -> points
        ScanFrame.MAP -> pose?.let { p -> points.map { toRobot(it, p) } }
    }

    // radar points -> map frame (for the live map); null without a pose
    fun mapFrame(points: List<Point>, frame: ScanFrame, pose: RobotPose?): List<Point>? = when (frame) {
        ScanFrame.MAP -> points
        ScanFrame.ROBOT -> pose?.let { p -> points.map { toMap(it, p) } }
    }

    // at most max points, evenly spread over the scan (keeps its shape and order)
    fun downsample(points: List<Point>, max: Int): List<Point> {
        if (max <= 0) return emptyList()
        if (points.size <= max) return points
        val step = points.size.toDouble() / max
        return (0 until max).map { points[(it * step).toInt()] }
    }

    private fun median(v: List<Double>): Double {
        val s = v.sorted()
        return if (s.isEmpty()) 0.0 else s[s.size / 2]
    }

    private const val MIN_POINTS_FOR_GUESS = 20
    private const val AMBIGUOUS_ORIGIN_M = 2.0
    private const val CLEAR_RATIO = 0.6
}

// decides the radar frame from a few frames (ScanMath.guessFrame) and keeps it. fixed = set in
// local.properties (robot.scanFrame). no clear answer after giveUpAfter frames -> map frame.
class FrameVote(private val fixed: ScanFrame? = null, private val need: Int = 3, private val giveUpAfter: Int = 15) {
    private var map = 0
    private var robot = 0
    private var frames = 0

    var decided: ScanFrame? = fixed
        private set
    // true when the frame was assumed, not measured
    var assumed = false
        private set

    fun add(guess: ScanFrame?): ScanFrame? {
        if (decided != null) return decided
        frames++
        when (guess) {
            ScanFrame.MAP -> map++
            ScanFrame.ROBOT -> robot++
            null -> Unit
        }
        decided = when {
            map >= need && map >= robot * 3 -> ScanFrame.MAP
            robot >= need && robot >= map * 3 -> ScanFrame.ROBOT
            frames >= giveUpAfter -> ScanFrame.MAP.also { assumed = true }
            else -> null
        }
        return decided
    }
}

// which directions the scan covers, summed over all frames of one walk (bins in the robot frame).
// a bin counts as covered when any point fell into it, so it needs some walls around to learn.
class Coverage(private val binDeg: Int = 10) {
    private val bins = BooleanArray(360 / binDeg)
    var frames = 0
        private set

    fun add(robotPoints: List<Point>) {
        frames++
        for (p in robotPoints) {
            val r = ScanMath.range(p)
            if (r < MIN_M || r > MAX_M) continue
            bins[index(ScanMath.angleDeg(p))] = true
        }
    }

    fun coveredDeg(): Int = bins.count { it } * binDeg

    // the back (|angle| >= 135 deg) is visible when most of its bins had points
    fun rearVisible(): Boolean {
        val rear = bins.indices.filter { abs(center(it)) >= REAR_FROM_DEG }
        return rear.count { bins[it] } >= (rear.size * 2 + 2) / 3
    }

    // "270 deg, from -135 to 135" for the log
    fun describe(): String {
        val covered = coveredDeg()
        if (covered == 0) return "no points yet"
        if (covered >= 360) return "360 deg, all around"
        // runs of covered bins on the circle, written from the first uncovered bin on
        val n = bins.size
        val firstGap = bins.indexOfFirst { !it }
        val runs = ArrayList<Pair<Int, Int>>()
        var i = 0
        while (i < n) {
            val k = (firstGap + 1 + i) % n
            if (bins[k]) {
                var len = 0
                while (len < n && bins[(k + len) % n]) len++
                runs += k to len
                i += len
            } else {
                i++
            }
        }
        val parts = runs.sortedByDescending { it.second }.map { (start, len) ->
            val from = edge(start)
            val to = edge(start + len)
            "${fmt(from)} to ${fmt(to)}"
        }
        return "$covered deg, from " + parts.joinToString(" and ")
    }

    private fun index(deg: Double): Int {
        val a = ((deg + 180.0) % 360.0 + 360.0) % 360.0
        return (a / binDeg).toInt().coerceIn(0, bins.size - 1)
    }

    private fun center(i: Int): Double = -180.0 + (i + 0.5) * binDeg

    private fun edge(i: Int): Int {
        val deg = -180 + (i % bins.size) * binDeg
        return if (deg == -180 && i > 0) 180 else deg
    }

    private fun fmt(d: Int) = if (d > 0) "+$d" else "$d"

    companion object {
        const val MIN_M = 0.25
        const val MAX_M = 12.0
        const val REAR_FROM_DEG = 135.0
    }
}

// where to look for legs: behind otto while he drives, in front after he turned around
enum class Sector(val fromDeg: Double, val toDeg: Double) {
    REAR(100.0, 180.0),
    FRONT(0.0, 60.0),
    ;

    fun contains(deg: Double): Boolean = abs(deg) in fromDeg..toDeg
}

enum class LegKind { PAIR, BLOB, LEG }

// something leg-like: a pair of legs, one wide shape (legs together, a coat, a wheelchair) or one leg
data class LegCandidate(val at: Point, val distance: Double, val kind: LegKind, val width: Double)

object LegDetector {

    const val MIN_RANGE_M = 0.3
    const val MAX_RANGE_M = 2.5
    // points further apart than this start a new shape
    const val GAP_M = 0.09
    const val LEG_MIN_M = 0.03
    const val LEG_MAX_M = 0.25
    const val BLOB_MAX_M = 0.75
    // two legs of one person stand this far apart (centre to centre)
    const val PAIR_MIN_M = 0.08
    const val PAIR_MAX_M = 0.55
    // a leg is round: its middle points bulge towards the sensor
    const val LEG_BULGE_M = 0.006

    // shapes in the sector, in angle order. each shape is a list of points (robot frame)
    fun clusters(points: List<Point>, sector: Sector, minRange: Double = MIN_RANGE_M, maxRange: Double = MAX_RANGE_M): List<List<Point>> {
        val inside = points.filter {
            val r = ScanMath.range(it)
            r in minRange..maxRange && sector.contains(ScanMath.angleDeg(it))
        }
        // the rear sector goes over +-180: sort on 0..360 there so it stays one sweep
        val sorted = inside.sortedBy {
            val a = ScanMath.angleDeg(it)
            if (sector == Sector.REAR) (a + 360.0) % 360.0 else a
        }
        val out = ArrayList<List<Point>>()
        var current = ArrayList<Point>()
        for (p in sorted) {
            val last = current.lastOrNull()
            if (last != null && last.distanceTo(p) > GAP_M) {
                out += current
                current = ArrayList()
            }
            current += p
        }
        if (current.isNotEmpty()) out += current
        return out
    }

    // leg-like shapes in the sector, nearest first
    fun candidates(points: List<Point>, sector: Sector = Sector.REAR, maxRange: Double = MAX_RANGE_M): List<LegCandidate> {
        val legs = ArrayList<Pair<Point, Double>>()
        val out = ArrayList<LegCandidate>()
        for (c in clusters(points, sector, maxRange = maxRange)) {
            if (c.size < 2) continue
            val width = c.first().distanceTo(c.last())
            val center = centroid(c)
            when {
                width in LEG_MIN_M..LEG_MAX_M && round(c) -> legs += center to width
                width > LEG_MAX_M && width <= BLOB_MAX_M -> out += LegCandidate(center, ScanMath.range(center), LegKind.BLOB, width)
            }
        }
        // pair up legs, closest pairs first; each leg once
        val used = BooleanArray(legs.size)
        val pairs = ArrayList<Triple<Int, Int, Double>>()
        for (i in legs.indices) for (j in i + 1 until legs.size) {
            val d = legs[i].first.distanceTo(legs[j].first)
            if (d in PAIR_MIN_M..PAIR_MAX_M) pairs += Triple(i, j, d)
        }
        for ((i, j, _) in pairs.sortedBy { it.third }) {
            if (used[i] || used[j]) continue
            used[i] = true
            used[j] = true
            val mid = Point((legs[i].first.x + legs[j].first.x) / 2, (legs[i].first.y + legs[j].first.y) / 2)
            out += LegCandidate(mid, ScanMath.range(mid), LegKind.PAIR, legs[i].first.distanceTo(legs[j].first))
        }
        for (i in legs.indices) if (!used[i]) {
            out += LegCandidate(legs[i].first, ScanMath.range(legs[i].first), LegKind.LEG, legs[i].second)
        }
        return out.sortedBy { it.distance }
    }

    // nearest person-like shape (pair or blob first, a single leg only if nothing better)
    fun nearest(points: List<Point>, sector: Sector, maxRange: Double = MAX_RANGE_M): LegCandidate? {
        val all = candidates(points, sector, maxRange)
        return all.firstOrNull { it.kind != LegKind.LEG } ?: all.firstOrNull()
    }

    // a flat piece of wall is not a leg. needs a few points to tell; sparse shapes pass.
    private fun round(c: List<Point>): Boolean {
        if (c.size < 4) return true
        val a = c.first()
        val b = c.last()
        val len = a.distanceTo(b)
        if (len < 1e-6) return true
        // signed distance from the chord a-b; the sensor (0/0) side counts positive
        fun side(p: Point) = ((b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)) / len
        val sensor = if (side(Point(0.0, 0.0)) >= 0) 1.0 else -1.0
        val bulge = c.subList(1, c.size - 1).maxOf { side(it) * sensor }
        return bulge >= LEG_BULGE_M
    }

    private fun centroid(c: List<Point>): Point = Point(c.sumOf { it.x } / c.size, c.sumOf { it.y } / c.size)
}

// the follower estimate otto keeps while he walks
data class FollowerFix(val robot: Point, val map: Point?, val distance: Double, val at: Long)

// follows one leg-like shape behind otto from frame to frame. a shape that stays put on the map while
// otto drives on is furniture, not a patient: it is ignored for a while.
class FollowerTracker(
    private val gateM: Double = 0.8,
    private val keepMs: Long = 1_500,
    private val confirmHits: Int = 2,
) {
    private class Sample(val t: Long, val cand: Point, val robot: Point)

    private var track: FollowerFix? = null
    private var hits = 0
    private val history = ArrayDeque<Sample>()
    private val ignored = ArrayList<Pair<Point, Long>>()

    // last time the follower was seen for sure (confirmed track), null = not yet
    var lastSeenAt: Long? = null
        private set
    var everSeen = false
        private set

    val current: FollowerFix? get() = track?.takeIf { hits >= confirmHits }

    fun reset() {
        track = null
        hits = 0
        history.clear()
        ignored.clear()
        lastSeenAt = null
        everSeen = false
    }

    // one radar frame: leg candidates in the robot frame (REAR sector) and the pose, if known
    fun update(now: Long, candidates: List<LegCandidate>, pose: RobotPose?): FollowerFix? {
        ignored.removeAll { now - it.second > IGNORE_MS }
        val usable = candidates.filter { c ->
            val m = pose?.let { ScanMath.toMap(c.at, it) }
            m == null || ignored.none { it.first.distanceTo(m) < IGNORE_RADIUS_M }
        }
        val last = track?.takeIf { now - it.at <= keepMs }
        val pick = if (last != null) {
            usable.minByOrNull { distanceToTrack(it, last, pose) }?.takeIf { distanceToTrack(it, last, pose) <= gateM }
        } else {
            usable.minWithOrNull(compareBy<LegCandidate>({ it.kind.ordinal }, { it.distance }))
        }
        if (pick == null) {
            if (last == null) {
                track = null
                hits = 0
                history.clear()
            }
            return current
        }
        if (last == null) {
            hits = 0
            history.clear()
        }
        val map = pose?.let { ScanMath.toMap(pick.at, it) }
        track = FollowerFix(pick.at, map, pick.distance, now)
        hits++
        if (map != null && pose != null) {
            history.addLast(Sample(now, map, pose.point))
            while (history.isNotEmpty() && now - history.first().t > HISTORY_MS) history.removeFirst()
            if (isStatic(now)) {
                ignored += map to now
                track = null
                hits = 0
                history.clear()
                return null
            }
        }
        if (hits >= confirmHits) {
            lastSeenAt = now
            everSeen = true
        }
        return current
    }

    private fun distanceToTrack(c: LegCandidate, last: FollowerFix, pose: RobotPose?): Double {
        val lm = last.map
        return if (lm != null && pose != null) ScanMath.toMap(c.at, pose).distanceTo(lm) else c.at.distanceTo(last.robot)
    }

    // the robot drove on, the shape did not move with it
    private fun isStatic(now: Long): Boolean {
        val then = history.firstOrNull { now - it.t >= STATIC_LOOK_BACK_MS } ?: return false
        val nowS = history.last()
        val robotMoved = nowS.robot.distanceTo(then.robot)
        if (robotMoved < MIN_ROBOT_MOVE_M) return false
        return nowS.cand.distanceTo(then.cand) < robotMoved * STATIC_RATIO
    }

    companion object {
        const val HISTORY_MS = 3_500L
        const val STATIC_LOOK_BACK_MS = 2_000L
        const val MIN_ROBOT_MOVE_M = 0.6
        const val STATIC_RATIO = 0.25
        const val IGNORE_MS = 15_000L
        const val IGNORE_RADIUS_M = 0.35
    }
}

// continuous watch while the back is visible: nobody behind for 4 s -> check back at once.
// it only fires once the follower was seen in this walk (so it works for this person), and it stops
// trusting itself after two false alarms (the camera found the patient close each time).
class FollowerWatch(private val lostMs: Long = LOST_MS, private val maxFalseAlarms: Int = 2) {
    private var since = 0L
    private var fired = false
    var falseAlarms = 0
        private set
    val trusted get() = falseAlarms < maxFalseAlarms

    // the walk (re)starts: count from now
    fun start(now: Long) {
        since = now
        fired = false
    }

    // true = check back now (once until the next start)
    fun check(now: Long, rearVisible: Boolean, everSeen: Boolean, lastSeenAt: Long?): Boolean {
        if (fired || !trusted || !rearVisible || !everSeen) return false
        val from = maxOf(since, lastSeenAt ?: 0L)
        if (now - from < lostMs) return false
        fired = true
        return true
    }

    // the camera found the patient close after the watch fired
    fun falseAlarm() {
        falseAlarms++
    }

    companion object {
        const val LOST_MS = 4_000L
    }
}
