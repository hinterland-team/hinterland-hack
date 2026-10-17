package com.hinterland.clinic

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

// a blocked way (v3.3, docs/journeys.md 6.7). field test 17.10 (b10): the corridor to the exit was blocked,
// otto asked people to step aside 12 times in 5 minutes and never tried another way, because every
// check-back started a new drive and with it a new blocked count. OrionStar's navigation does not plan
// around a blocked corridor, it waits. so now the blocked time counts per leg, across restarts, and after
// ~2 asks / ~25 s otto works through the detour steps:
//   A  back off a little (only when nobody is behind him), turn a little, try the same way once more
//   B  a waypoint from the map spots (the place's viaSpot first), then on to the target; at most 2
//   C  tell the patient, call the team, stay with the patient and try the way again now and then
// plain kotlin, unit tested.

// what a drive reports to its blocked count (Robot.goTo): one per drive, or one per leg (LegBlock)
interface WayBlock {
    val isBlocked: Boolean
    val asksDone: Int
    fun blocked(now: Long)
    fun clear(now: Long)
    // otto drove on while "blocked" (the end-of-obstacle status got lost). true when that cleared it
    fun moved(now: Long, movedM: Double): Boolean
    fun step(now: Long): BlockedStep
    // the drive stopped (arrived, check-back, new pace): an open blocked time is closed
    fun stop(now: Long)
}

// the blocked count of one leg. each attempt (the way as it is, then every detour step) has a budget:
// ask people to step aside up to `asks` times, give up graceMs after the last ask or when the attempt was
// blocked longer than limitMs in total. a stop in between (check-back, pace change) keeps the count.
// limitMs = way.blockedS (a live setting): blocked this long on one try, then the next detour step
class LegBlock(
    private val askEveryMs: Long = BlockedWatch.ASK_EVERY_MS,
    private val graceMs: Long = BlockedWatch.GRACE_AFTER_LAST_ASK_MS,
    private val limitMs: Long = BlockedWatch.TOTAL_LIMIT_MS,
    private val maxAsks: Int = BlockedWatch.MAX_ASKS,
) : WayBlock {
    private val clock = BlockedClock()
    private var asks = 0
    private var lastAskAt = 0L
    // blocked ms of this leg before the current attempt
    private var budgetFrom = 0L
    private var attemptLimitMs = limitMs
    private var attemptAsks = maxAsks
    // how far it was to where otto drives when this attempt was first blocked, and how far he had driven
    private var refRemaining: Double? = null
    private var refDriven: Double? = null

    // detour steps started in this leg (for the log)
    var attempts = 0
        private set

    override val isBlocked: Boolean get() = clock.isBlocked()
    override val asksDone: Int get() = asks

    override fun blocked(now: Long) = clock.blocked(now)

    // the obstacle status ended: the asks stay, a short gap in a crowd does not start the count again
    override fun clear(now: Long) = clock.clear(now)

    override fun stop(now: Long) = clock.clear(now)

    override fun moved(now: Long, movedM: Double): Boolean {
        if (!clock.isBlocked() || movedM < BlockedWatch.MOVED_CLEARS_M) return false
        clock.clear(now)
        return true
    }

    override fun step(now: Long): BlockedStep {
        if (usedMs(now) > attemptLimitMs) return BlockedStep.GIVE_UP
        if (!clock.isBlocked()) return BlockedStep.NONE
        if (asks < attemptAsks && (asks == 0 || now - lastAskAt >= askEveryMs)) {
            asks++
            lastAskAt = now
            return BlockedStep.ASK
        }
        if (asks >= attemptAsks && now - lastAskAt >= graceMs) return BlockedStep.GIVE_UP
        return BlockedStep.NONE
    }

    // blocked ms of the whole leg
    fun totalMs(now: Long): Long = clock.total(now)

    // blocked ms of this attempt
    fun usedMs(now: Long): Long = clock.total(now) - budgetFrom

    // the next try (a detour step) gets its own budget
    fun nextAttempt(now: Long, limitMs: Long, asks: Int) {
        fresh(now, limitMs, asks)
        attempts++
    }

    private fun fresh(now: Long, limitMs: Long, asks: Int) {
        clock.clear(now)
        budgetFrom = clock.total(now)
        attemptLimitMs = limitMs
        attemptAsks = asks
        this.asks = 0
        lastAskAt = 0L
        refRemaining = null
        refDriven = null
    }

    // remainingM = how far it still is to where otto drives, drivenM = meters driven in this leg so far.
    // once otto got PROGRESS_M closer than where he was first blocked in this attempt, or drove DRIVEN_M on
    // (a way round a corner does not get closer at first), the jam is behind him: a fresh budget.
    // true when that happened
    fun progress(now: Long, remainingM: Double?, drivenM: Double = 0.0): Boolean {
        val blockedYet = asks > 0 || usedMs(now) > 0
        if (!blockedYet || refDriven == null) {
            refRemaining = remainingM
            refDriven = drivenM
            return false
        }
        val ref = refRemaining
        val closer = ref != null && remainingM != null && ref - remainingM >= PROGRESS_M
        val drove = drivenM - (refDriven ?: drivenM) >= DRIVEN_M
        if (!closer && !drove) return false
        fresh(now, limitMs, maxAsks)
        refRemaining = remainingM
        refDriven = drivenM
        return true
    }

    companion object {
        const val PROGRESS_M = 1.5
        const val DRIVEN_M = 4.0
    }
}

enum class DetourStep { BACK_OFF, WAYPOINT, HELP }

// which detour step comes next in this leg: A once, then up to maxWaypoints waypoints, then help.
// backOff = way.backOff, maxWaypoints = way.maxWaypoints (live settings)
class DetourSteps(private val maxWaypoints: Int = MAX_WAYPOINTS, private val backOff: Boolean = true) {
    var backedOff = false
        private set
    var waypointsTried = 0
        private set

    // waypointsLeft = candidates not tried yet
    fun next(waypointsLeft: Int): DetourStep = when {
        backOff && !backedOff -> {
            backedOff = true
            DetourStep.BACK_OFF
        }
        waypointsTried < maxWaypoints && waypointsLeft > 0 -> {
            waypointsTried++
            DetourStep.WAYPOINT
        }
        else -> DetourStep.HELP
    }

    // otto got past the jam: a later jam may use every step again
    fun reset() {
        backedOff = false
        waypointsTried = 0
    }

    companion object {
        const val MAX_WAYPOINTS = 2
    }
}

// a map spot to drive over, and why it was picked (for the log)
data class Waypoint(val name: String, val offDeg: Double?, val ratio: Double?, val explicit: Boolean = false) {
    fun why(): String = if (explicit) "the place's waypoint" else
        String.format(java.util.Locale.ROOT, "%.0f deg off the blocked way, %.1fx the direct way", offDeg ?: 0.0, ratio ?: 0.0)
}

object DetourPlan {
    // spots this close to where otto got stuck are in the same jam
    const val NEAR_JAM_M = 1.5
    // nor one otto stands at already
    const val NEAR_HERE_M = 0.8
    // otto over the spot to the target may be at most this much longer than straight to the target
    const val MAX_RATIO = 2.0
    // spots off the blocked direction by more than this come first, closer than MIN_OFF_DEG never
    const val PREFER_OFF_DEG = 45.0
    const val MIN_OFF_DEG = 20.0
    // where the jam is: this far ahead of otto, the way he faced when he got stuck
    const val JAM_AHEAD_M = 1.0

    // places = map spots with coordinates (getPlaceList), here / heading = otto when he got stuck (heading in
    // radians, null = unknown), target = the destination's point. exclude = the target and spots tried
    // already. best first; empty when nothing fits
    fun waypoints(
        places: List<MapPlace>,
        here: Point,
        heading: Double?,
        target: Point,
        exclude: Collection<String>,
        max: Int = DetourSteps.MAX_WAYPOINTS,
    ): List<Waypoint> {
        val direct = here.distanceTo(target)
        if (direct < NEAR_HERE_M) return emptyList()
        // the blocked direction: where otto faced, else straight towards the target
        val dir = heading ?: atan2(target.y - here.y, target.x - here.x)
        val jam = Point(here.x + JAM_AHEAD_M * cos(dir), here.y + JAM_AHEAD_M * sin(dir))
        val skip = exclude.map { Names.key(it) }.toSet()
        val fits = places.mapNotNull { p ->
            if (Names.key(p.name) in skip) return@mapNotNull null
            val at = p.point
            if (at.distanceTo(jam) < NEAR_JAM_M || at.distanceTo(here) < NEAR_HERE_M || at.distanceTo(target) < NEAR_HERE_M) return@mapNotNull null
            val ratio = (here.distanceTo(at) + at.distanceTo(target)) / direct
            if (ratio > MAX_RATIO) return@mapNotNull null
            val off = Math.toDegrees(abs(ScanMath.wrap(atan2(at.y - here.y, at.x - here.x) - dir)))
            if (off < MIN_OFF_DEG) return@mapNotNull null
            // from the spot on, the way must not lead back through the jam
            if (segmentDistance(jam, at, target) < NEAR_JAM_M) return@mapNotNull null
            Waypoint(p.name, off, ratio)
        }
        return fits.sortedWith(compareBy<Waypoint>({ (it.offDeg ?: 0.0) <= PREFER_OFF_DEG }, { it.ratio })).take(max)
    }

    // how far p is from the straight line a-b
    fun segmentDistance(p: Point, a: Point, b: Point): Double {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        if (len2 < 1e-12) return p.distanceTo(a)
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0.0, 1.0)
        return p.distanceTo(Point(a.x + t * dx, a.y + t * dy))
    }
}

// the patient is "behind" only when otto really drove away from them: standing at an obstacle while the
// patient waits a few meters back is no reason to walk slower (b10: the pace fell 0.6 -> 0.4 -> 0.25 m/s)
object FellBehind {
    const val MIN_DRIVEN_M = 2.0

    fun forReal(drivenSinceCheckM: Double): Boolean = drivenSinceCheckM >= MIN_DRIVEN_M
}
