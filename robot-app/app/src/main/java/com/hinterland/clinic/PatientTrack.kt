package com.hinterland.clinic

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

// same person, without knowing who it is (v3.3, docs/journeys.md 6.7): only WHERE the patient is. at
// "That's me" (or when a walk starts at the robot) otto notes the patient's position from the radar and
// the camera, keeps it up to date from the radar beside him and the camera at the stops, and at a check-back
// turns towards where the patient was last seen and takes the person nearest that spot. no faces, no ids,
// no pictures: a few positions in memory, dropped with the walk. plain kotlin, unit tested.
// robot frame: x ahead, y left, meters. bearings in degrees, + = left, -180..180.

// someone the camera sees right now: distance and angle in view (PersonApi Person.getDistance and
// getAngleInView, live only). the docs do not say which side a positive angle is, so matching tries both
data class SeenPerson(val distance: Double, val angleDeg: Double = 0.0)

enum class FixSource(val wire: String) { START("start"), RADAR("radar"), CAMERA("camera") }

// where the patient was: robot frame at that moment, plus the map point when the pose was known
data class PatientFix(val robot: Point, val map: Point?, val at: Long, val source: FixSource)

// where to look now
data class Predicted(
    val bearingDeg: Double,
    // null = only the direction can be trusted
    val distance: Double?,
    val source: FixSource,
    val ageMs: Long,
    // the map point it came from, to place it again after the turn
    val map: Point? = null,
    // true = the patient keeps the place next to otto (a fresh radar fix), false = where they were last
    val relative: Boolean = false,
)

object Bearing {
    fun of(p: Point): Double = ScanMath.angleDeg(p)

    // any angle in degrees -> -180..180
    fun wrap(deg: Double): Double {
        var a = deg % 360.0
        if (a > 180.0) a -= 360.0
        if (a <= -180.0) a += 360.0
        return a
    }

    fun point(distance: Double, bearingDeg: Double): Point {
        val a = Math.toRadians(bearingDeg)
        return Point(distance * cos(a), distance * sin(a))
    }
}

class PatientTrack(private val relativeKeepMs: Long = RELATIVE_KEEP_MS) {
    @Volatile private var start: PatientFix? = null
    @Volatile private var radar: PatientFix? = null
    @Volatile private var camera: PatientFix? = null

    // the newest fix
    val last: PatientFix? get() = listOfNotNull(radar, camera, start).maxByOrNull { it.at }

    fun noteStart(robot: Point, pose: RobotPose?, at: Long) {
        start = PatientFix(robot, pose?.let { ScanMath.toMap(robot, it) }, at, FixSource.START)
    }

    // the radar's follower beside otto (FollowerFix has the map point already)
    fun noteRadar(robot: Point, map: Point?, at: Long) {
        radar = PatientFix(robot, map, at, FixSource.RADAR)
    }

    // the person picked at a check-back: robot frame at that look, and its map point (pose at that look)
    fun noteCamera(robot: Point, map: Point?, at: Long) {
        camera = PatientFix(robot, map, at, FixSource.CAMERA)
    }

    fun clear() {
        start = null
        radar = null
        camera = null
    }

    // where the patient should be now. pose = otto now, null = unknown
    fun predict(now: Long, pose: RobotPose?): Predicted? {
        val f = last ?: return null
        val age = (now - f.at).coerceAtLeast(0)
        // seen beside a moment ago: they keep their place next to otto
        if (f.source == FixSource.RADAR && age <= relativeKeepMs) {
            return Predicted(Bearing.of(f.robot), ScanMath.range(f.robot), f.source, age, f.map, relative = true)
        }
        // older: where they were last seen (they may have stopped there), seen from where otto is now
        if (f.map != null && pose != null) {
            val r = ScanMath.toRobot(f.map, pose)
            val d = ScanMath.range(r)
            return Predicted(Bearing.of(r), d.takeIf { it <= MAX_DISTANCE_M }, f.source, age, f.map)
        }
        // no pose: the bearing as it was, the distance is a guess
        return Predicted(Bearing.of(f.robot), null, f.source, age, f.map)
    }

    companion object {
        // a radar fix this fresh counts as "still in the same place next to otto"
        const val RELATIVE_KEEP_MS = 4_000L
        // a predicted spot further than this is only a direction
        const val MAX_DISTANCE_M = CheckBack.FAR_M
    }
}

// where the patient should be after the turn, robot frame. distance null = only the direction is known
data class Target(val bearingDeg: Double, val distance: Double?)

data class Turn(val degrees: Float, val left: Boolean) {
    // + = left
    val signedDeg: Double get() = if (left) degrees.toDouble() else -degrees.toDouble()
}

object TurnPlan {
    // closer to straight ahead than this: no turn needed
    const val MIN_DEG = 20.0
    // nothing known about the patient: beside and a little behind on the walk side
    const val DEFAULT_DEG = 135.0
    // nobody near the spot: look this far to each side once
    const val ASIDE_DEG = 35.0

    // the turn that faces a bearing (+ = left). no bearing = the walk side. null = already facing it
    fun toward(bearingDeg: Double?, side: WalkSide): Turn? {
        val b = Bearing.wrap(bearingDeg ?: (DEFAULT_DEG * side.sign))
        if (abs(b) < MIN_DEG) return null
        return Turn(abs(b).toFloat().coerceAtMost(180f), left = b > 0)
    }

    // the predicted spot in the robot frame after the turn. a map point is placed again with the new pose;
    // a fresh radar fix (the patient stops next to otto) and a missing pose only shift by the turn
    fun target(p: Predicted?, turned: Turn?, poseAfter: RobotPose?, side: WalkSide): Target {
        val turnedDeg = turned?.signedDeg ?: 0.0
        if (p == null) return Target(Bearing.wrap(DEFAULT_DEG * side.sign - turnedDeg), null)
        val map = p.map
        if (!p.relative && map != null && poseAfter != null) {
            val r = ScanMath.toRobot(map, poseAfter)
            val d = ScanMath.range(r)
            return Target(Bearing.of(r), d.takeIf { it <= PatientTrack.MAX_DISTANCE_M })
        }
        return Target(Bearing.wrap(p.bearingDeg - turnedDeg), p.distance)
    }

    // the same spot after otto turned by deg more (+ = left)
    fun shifted(t: Target, deg: Double): Target = t.copy(bearingDeg = Bearing.wrap(t.bearingDeg - deg))
}

// the person taken as the patient: how far, where (robot frame), how far off the predicted spot
data class Match(val distance: Double, val point: Point, val cost: Double, val source: FixSource)

object PatientMatch {
    // further than this from the predicted spot: not the patient
    const val GATE_M = 1.2
    // this close: no need to look again
    const val GOOD_M = 0.6
    // at "That's me" the patient stands in front, within this distance
    const val START_MAX_M = 2.0

    // meters between a seen point and the target spot. without a distance: how far it is off the
    // target's direction (sideways), so anyone along that line fits
    fun cost(p: Point, t: Target): Double {
        val d = t.distance
        if (d != null) return p.distanceTo(Bearing.point(d, t.bearingDeg))
        val off = Math.toRadians(abs(Bearing.wrap(Bearing.of(p) - t.bearingDeg)))
        val r = ScanMath.range(p)
        return if (off >= PI / 2) r else r * sin(off)
    }

    // the camera's person as a point: both sides tried, the one nearer the target wins
    fun cameraPoint(s: SeenPerson, t: Target?): Point {
        val a = Bearing.point(s.distance, s.angleDeg)
        val b = Bearing.point(s.distance, -s.angleDeg)
        if (t == null) return a
        return if (cost(a, t) <= cost(b, t)) a else b
    }

    // the person nearest the predicted spot, among the camera's people and the radar's leg shapes in
    // front. target null = no idea where: the nearest one wins (as before v3.3). null = nobody near it
    fun best(people: List<SeenPerson>, legs: List<Point>, target: Target?, gate: Double = GATE_M): Match? {
        val all = ArrayList<Match>()
        people.filter { it.distance > 0.0 }.forEach { s ->
            val p = cameraPoint(s, target)
            all += Match(s.distance, p, target?.let { cost(p, it) } ?: s.distance, FixSource.CAMERA)
        }
        legs.forEach { p ->
            val r = ScanMath.range(p)
            if (r > 0.0) all += Match(r, p, target?.let { cost(p, it) } ?: r, FixSource.RADAR)
        }
        val pick = all.minByOrNull { it.cost } ?: return null
        if (target == null) return pick
        return pick.takeIf { it.cost <= gate }
    }

    // at "That's me": the person in front within START_MAX_M, the radar first (it knows the side)
    fun atStart(people: List<SeenPerson>, legs: List<Point>): Match? {
        val leg = legs.filter { ScanMath.range(it) in 0.1..START_MAX_M }.minByOrNull { ScanMath.range(it) }
        if (leg != null) return Match(ScanMath.range(leg), leg, 0.0, FixSource.RADAR)
        val s = people.filter { it.distance in 0.1..START_MAX_M }.minByOrNull { it.distance } ?: return null
        return Match(s.distance, Bearing.point(s.distance, s.angleDeg), 0.0, FixSource.CAMERA)
    }
}
