package com.hinterland.clinic

import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

// the walk planner: when nova checks back, what she does for each distance, how long she waits for
// a patient who fell behind, milestones and progress. plain kotlin without android, unit tested.
// times are epoch ms (or ms since a start), distances meters.

// event types the robot sends (docs/journeys.md section 2)
object JourneyEvent {
    const val PICKUP_STARTED = "pickup_started"
    const val PATIENT_CONFIRMED = "patient_confirmed"
    const val WALKING = "walking"
    const val CHECK_BACK = "check_back"
    const val SLOWED_DOWN = "slowed_down"
    const val WAITING_FOR_PATIENT = "waiting_for_patient"
    const val LOST_PATIENT = "lost_patient"
    const val ARRIVED = "arrived"
    const val FAILED = "failed"
    const val FEEDBACK = "feedback"
}

// leg statuses the robot sets
object LegStatus {
    const val PICKUP = "pickup"
    const val WALKING = "walking"
    const val ARRIVED = "arrived"
    const val FAILED = "failed"
}

// counts driving time between check-backs. blocked time and stops (check-back, pause) do not count.
// right after nova had to wait for an obstacle a check-back is due at once.
class CheckBackClock(
    private val everyMs: Long = EVERY_MS,
    private val obstacleMinMs: Long = OBSTACLE_MIN_MS,
) {
    private var driven = 0L
    private var lastTick: Long? = null
    private var blockedSince: Long? = null
    private var obstacleWaited = false

    // called about every quarter second while a navigation runs
    fun tick(now: Long, blocked: Boolean) {
        val last = lastTick
        lastTick = now
        if (blocked) {
            if (blockedSince == null) blockedSince = now
            return
        }
        blockedSince?.let { if (now - it >= obstacleMinMs) obstacleWaited = true }
        blockedSince = null
        // a long gap (paused thread, clock jump) never counts as minutes of driving
        if (last != null) driven += (now - last).coerceIn(0, MAX_STEP_MS)
    }

    // navigation stopped: the next tick starts a new stretch
    fun pause() {
        lastTick = null
        blockedSince = null
    }

    fun due(): Boolean = driven >= everyMs || obstacleWaited

    fun obstacleWasReason(): Boolean = obstacleWaited

    fun drivenMs(): Long = driven

    fun reset() {
        driven = 0
        obstacleWaited = false
        lastTick = null
        blockedSince = null
    }

    companion object {
        const val EVERY_MS = 25_000L
        const val OBSTACLE_MIN_MS = 3_000L
        private const val MAX_STEP_MS = 2_000L
    }
}

// who is behind nova when she turns around
enum class Proximity { CLOSE, FAR, NOBODY, UNKNOWN }

object CheckBack {

    const val CLOSE_M = 3.0
    const val FAR_M = 6.0
    // near the destination the arrival itself is the check
    const val SKIP_FROM_PROGRESS = 0.92f

    // nearest person over a few looks. null = the vision api gave nothing at all (not "nobody")
    fun nearest(samples: List<List<Double>?>): Double? =
        samples.filterNotNull().flatten().filter { it > 0.0 }.minOrNull()

    fun visionWorked(samples: List<List<Double>?>): Boolean = samples.any { it != null }

    fun classify(samples: List<List<Double>?>): Proximity {
        if (!visionWorked(samples)) return Proximity.UNKNOWN
        val d = nearest(samples) ?: return Proximity.NOBODY
        return when {
            d <= CLOSE_M -> Proximity.CLOSE
            d <= FAR_M -> Proximity.FAR
            else -> Proximity.NOBODY
        }
    }

    // events for each outcome (docs/journeys.md 4.3). far: slowed_down only when there is a slower pace
    fun events(p: Proximity, pace: Pace): List<String> = when (p) {
        Proximity.CLOSE, Proximity.UNKNOWN -> listOf(JourneyEvent.CHECK_BACK)
        Proximity.FAR ->
            if (pace == Pace.VERY_SLOW) listOf(JourneyEvent.WAITING_FOR_PATIENT)
            else listOf(JourneyEvent.WAITING_FOR_PATIENT, JourneyEvent.SLOWED_DOWN)
        Proximity.NOBODY -> listOf(JourneyEvent.LOST_PATIENT)
    }

    fun shouldSkip(progress: Float?): Boolean = progress != null && progress >= SKIP_FROM_PROGRESS
}

enum class FarStep { WAIT, CAME, STILL_FAR, GONE }

// patient 3-6 m behind: nova waits up to 20 s for them to come close
class FarWait(
    private val startedAt: Long,
    private val maxMs: Long = MAX_MS,
    private val goneAfterMs: Long = GONE_AFTER_MS,
) {
    private var lastSeen = startedAt

    // nearest = closest person right now, null when nobody (or no vision)
    fun next(now: Long, nearest: Double?): FarStep {
        if (nearest != null && nearest > 0.0 && nearest <= CheckBack.CLOSE_M) return FarStep.CAME
        if (nearest != null && nearest > 0.0 && nearest <= CheckBack.FAR_M) lastSeen = now
        if (now - startedAt < maxMs) return FarStep.WAIT
        return if (now - lastSeen <= goneAfterMs) FarStep.STILL_FAR else FarStep.GONE
    }

    companion object {
        const val MAX_MS = 20_000L
        const val GONE_AFTER_MS = 6_000L
    }
}

enum class LostStep { CALL, REPORT, GIVE_UP, WATCH }

// nobody behind nova: call out twice, after 30 s tell the team, after 60 s more give up
class LostTimeline(
    private val callsAtMs: List<Long> = listOf(0L, SECOND_CALL_MS),
    private val reportAtMs: Long = REPORT_MS,
    private val giveUpAtMs: Long = REPORT_MS + GIVE_UP_AFTER_REPORT_MS,
) {
    private var calls = 0
    private var reported = false

    val callsDone get() = calls
    val hasReported get() = reported

    // elapsed = ms since nova found nobody. each step comes once, in order.
    fun next(elapsed: Long): LostStep {
        if (elapsed >= giveUpAtMs && reported) return LostStep.GIVE_UP
        if (elapsed >= reportAtMs && !reported) {
            reported = true
            return LostStep.REPORT
        }
        if (calls < callsAtMs.size && elapsed >= callsAtMs[calls] && !reported) {
            calls++
            return LostStep.CALL
        }
        return LostStep.WATCH
    }

    companion object {
        const val SECOND_CALL_MS = 12_000L
        const val REPORT_MS = 30_000L
        const val GIVE_UP_AFTER_REPORT_MS = 60_000L
    }
}

enum class Milestone { START, HALF_WAY, ALMOST_THERE, ARRIVAL }

object Milestones {
    const val HALF = 0.5f
    const val ALMOST = 0.8f

    // the one milestone to say now, if any. passing both at once only says "almost there".
    fun due(progress: Float?, said: Set<Milestone>): Milestone? {
        if (progress == null) return null
        if (progress >= ALMOST && Milestone.ALMOST_THERE !in said) return Milestone.ALMOST_THERE
        if (progress >= HALF && progress < ALMOST && Milestone.HALF_WAY !in said && Milestone.ALMOST_THERE !in said) {
            return Milestone.HALF_WAY
        }
        return null
    }
}

data class Point(val x: Double, val y: Double) {
    fun distanceTo(o: Point): Double = hypot(x - o.x, y - o.y)
}

// a spot on the robot map with its coordinates (RobotApi getPlaceList)
data class MapPlace(val name: String, val point: Point)

object WalkMath {

    // 0..1 along the straight line from start to destination, never going back. null when unknown.
    fun progress(start: Point?, here: Point?, dest: Point?, previous: Float?): Float? {
        if (start == null || here == null || dest == null) return previous
        val total = start.distanceTo(dest)
        if (total < MIN_TOTAL_M) return previous
        val p = (1.0 - here.distanceTo(dest) / total).coerceIn(0.0, 1.0).toFloat()
        return max(previous ?: 0f, p)
    }

    fun nearestSpot(here: Point?, places: List<MapPlace>): String? {
        if (here == null) return null
        return places.minByOrNull { it.point.distanceTo(here) }?.name
    }

    fun find(name: String?, places: List<MapPlace>): MapPlace? {
        val k = Names.key(name)
        if (k.isEmpty()) return null
        return places.firstOrNull { Names.key(it.name) == k }
    }

    // "how far is it?" in plain facts for the llm. straight line on the map, so it says "about".
    fun statusFacts(remainingM: Double?, pace: Pace, near: String?, destination: String): String = buildString {
        append("Destination: $destination. ")
        if (remainingM == null) {
            append("The remaining distance is not known right now. ")
        } else {
            val m = roundMeters(remainingM)
            val seconds = remainingM / PaceTable.speed(pace).linear
            if (m <= 5) append("Only a few meters to go. ") else append("About $m meters to go in a straight line. ")
            append("That is ${duration(seconds)} at the ${pace.label.lowercase()}. ")
        }
        near?.let { append("Nova is near the spot called $it. ") }
    }

    fun roundMeters(m: Double): Int = if (m < 5) ceil(m).toInt() else ((m / 5.0).roundToInt() * 5)

    fun duration(seconds: Double): String = when {
        seconds < 20 -> "less than half a minute"
        seconds < 45 -> "about half a minute"
        seconds < 90 -> "about one minute"
        else -> "about ${(seconds / 60.0).roundToInt()} minutes"
    }

    private const val MIN_TOTAL_M = 0.5
}
