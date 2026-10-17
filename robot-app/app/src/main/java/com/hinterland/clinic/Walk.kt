package com.hinterland.clinic

import kotlin.math.abs
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
    // driving time until the next check-back; the adaptive schedule changes it on the way
    var everyMs: Long = EVERY_MS,
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

    // camera first (it is what the turn is for); the radar in front of nova backs it up when the camera
    // sees nobody, for example in a dim corridor. lidarAhead = nearest leg-like shape after the turn.
    fun decide(samples: List<List<Double>?>, lidarAhead: Double?): Proximity {
        val camera = classify(samples)
        if (camera == Proximity.CLOSE || camera == Proximity.FAR) return camera
        val d = lidarAhead?.takeIf { it > 0.0 } ?: return camera
        return when {
            d <= CLOSE_M -> Proximity.CLOSE
            d <= FAR_M -> Proximity.FAR
            else -> camera
        }
    }

    // the nearest distance from both sensors, null when neither saw anyone
    fun nearestOf(camera: Double?, lidar: Double?): Double? =
        listOfNotNull(camera?.takeIf { it > 0.0 }, lidar?.takeIf { it > 0.0 }).minOrNull()
}

// why nova turns around now (docs/journeys.md 6.3)
enum class CheckReason(val detail: String) {
    FIRST("first check after the start"),
    REGULAR("regular check"),
    TURN("after a turn"),
    OBSTACLE("after waiting for an obstacle"),
    FOLLOWER_LOST("nobody behind on the radar"),
}

// the adaptive check-back schedule (docs/journeys.md 6.3): the first check 10 s after the start, then
// 10-30 s depending on how close the patient was last, plus one check right after a turn of more than
// 45 deg (not the turn on the spot every drive starts with) and one right after an obstacle wait.
// the radar watch can ask for one at any time.
// field test 17.10: a 34 s walk had no check at all (25 s of "driving" never added up). so the time
// counts all navigation time (a blocked flag that never clears can't stop it), and nova builds trust
// step by step: the second check comes at most 15 s after the first, then 20 s, 25 s, 30 s.
class CheckSchedule(
    private val firstMs: Long = FIRST_MS,
    private val turnDeg: Double = TURN_DEG,
    private val minGapMs: Long = MIN_GAP_MS,
) {
    // only for "nova had to wait for an obstacle"; its own time count is not used
    private val obstacles = CheckBackClock(Long.MAX_VALUE)
    private var elapsed = 0L
    private var lastTick: Long? = null
    private var stretchStart = 0L
    private var checks = 0
    private var lastDistance: Double? = null
    private var refHeading: Double? = null
    private var turnPending = false
    private var turnedDeg = 0.0
    private var settleHeading = 0.0
    private var settleSince = 0L
    private var lostWanted = false

    // navigation time from the last check (or the start) until the next one
    var intervalMs: Long = firstMs
        private set
    val checksDone: Int get() = checks
    val elapsedMs: Long get() = elapsed
    // how much the last turn was, for the log
    val lastTurnDeg: Double get() = turnedDeg

    fun nextInMs(): Long = (intervalMs - elapsed).coerceAtLeast(0)

    // every quarter second while navigating. heading = robot theta in radians (null when unknown)
    fun tick(now: Long, blocked: Boolean, heading: Double?) {
        obstacles.tick(now, blocked)
        val last = lastTick
        if (last == null) stretchStart = now else elapsed += (now - last).coerceIn(0, MAX_STEP_MS)
        lastTick = now
        if (heading == null) return
        val ref = refHeading
        // every drive starts by turning on the spot towards the path: that is no corner
        if (ref == null || (!turnPending && now - stretchStart < TURN_ARM_MS)) {
            refHeading = heading
            return
        }
        val fromRef = Math.toDegrees(abs(ScanMath.wrap(heading - ref)))
        if (!turnPending && fromRef > turnDeg) {
            turnPending = true
            settleHeading = heading
            settleSince = now
        } else if (turnPending && Math.toDegrees(abs(ScanMath.wrap(heading - settleHeading))) > SETTLE_DEG) {
            // still turning
            settleHeading = heading
            settleSince = now
        }
        if (turnPending) turnedDeg = fromRef
    }

    // nova stopped driving (check-back, pause, new pace): the next tick starts a new stretch
    fun pause() {
        obstacles.pause()
        lastTick = null
    }

    // the radar lost the follower
    fun followerLost() {
        lostWanted = true
    }

    // the latest patient distance from the radar: closer = longer between checks.
    // true when the next check moved by 5 s or more (worth a log line)
    fun noteDistance(d: Double?): Boolean {
        if (d == null || d <= 0.0) return false
        lastDistance = d
        if (checks == 0) return false
        val next = intervalAfter(checks, d)
        val moved = abs(next - intervalMs) >= LOG_CHANGE_MS
        intervalMs = next
        return moved
    }

    fun due(now: Long): CheckReason? {
        if (lostWanted) return CheckReason.FOLLOWER_LOST
        val gapOk = checks == 0 || elapsed >= minGapMs
        if (obstacles.obstacleWasReason() && gapOk) return CheckReason.OBSTACLE
        if (turnPending && now - settleSince >= TURN_SETTLE_MS && gapOk) return CheckReason.TURN
        if (elapsed >= intervalMs) return if (checks == 0) CheckReason.FIRST else CheckReason.REGULAR
        return null
    }

    // a check-back happened; distance = how far the patient was (null = unknown)
    fun done(distance: Double?, heading: Double?) {
        checks++
        distance?.takeIf { it > 0.0 }?.let { lastDistance = it }
        intervalMs = intervalAfter(checks, lastDistance)
        restart(heading)
    }

    // near the destination: no turning around, just start the count again
    fun skip(heading: Double?) = restart(heading)

    private fun restart(heading: Double?) {
        elapsed = 0
        lastTick = null
        obstacles.reset()
        lostWanted = false
        turnPending = false
        turnedDeg = 0.0
        refHeading = heading ?: refHeading
    }

    companion object {
        const val FIRST_MS = 10_000L
        const val MIN_MS = 10_000L
        const val MAX_MS = 30_000L
        const val UNKNOWN_MS = 20_000L
        const val NEAR_M = 1.5
        const val FAR_M = 3.0
        const val TURN_DEG = 45.0
        // the turn is over when the heading stays within this for TURN_SETTLE_MS
        const val SETTLE_DEG = 8.0
        const val TURN_SETTLE_MS = 1_500L
        // turns in the first seconds of each drive are the robot facing its path
        const val TURN_ARM_MS = 4_000L
        // a turn or obstacle check needs this much navigation time since the last check
        const val MIN_GAP_MS = 6_000L
        // trust grows per check: at most 15 s after the first, +5 s per check, up to MAX_MS
        const val RAMP_FIRST_MS = 15_000L
        const val RAMP_STEP_MS = 5_000L
        // a paused thread or a clock jump never counts as minutes
        private const val MAX_STEP_MS = 2_000L
        private const val LOG_CHANGE_MS = 5_000L

        // 1.5 m or closer -> 30 s, 3 m or further -> 10 s, in between linear; unknown -> 20 s
        fun intervalFor(distance: Double?): Long {
            val d = distance?.takeIf { it > 0.0 } ?: return UNKNOWN_MS
            if (d <= NEAR_M) return MAX_MS
            if (d >= FAR_M) return MIN_MS
            val f = (d - NEAR_M) / (FAR_M - NEAR_M)
            return (MAX_MS - f * (MAX_MS - MIN_MS)).toLong()
        }

        // the interval after the n-th check: by distance, but never more than the trust ramp allows
        fun intervalAfter(checks: Int, distance: Double?): Long {
            val ramp = RAMP_FIRST_MS + RAMP_STEP_MS * (checks - 1).coerceAtLeast(0)
            return minOf(intervalFor(distance), ramp, MAX_MS)
        }
    }
}

// no turning around in the last meters: the arrival itself is the check. the remaining straight-line
// distance, not the progress share (a route round a corner reached 92 % far too early).
object NearGoal {
    const val SKIP_WITHIN_M = 2.0

    fun skipCheck(here: Point?, dest: Point?): Boolean =
        here != null && dest != null && here.distanceTo(dest) <= SKIP_WITHIN_M
}

enum class TalkStep { LISTEN, REPLY, DONE }

// the short face-to-face talk at a check-back (docs/journeys.md 6.4): nova asked a yes/no question,
// listens about 6 s, gives nova's ai time to answer when the patient spoke, then nova walks on.
class TalkWindow(
    private val startedAt: Long,
    private val listenMs: Long = LISTEN_MS,
    private val replyMaxMs: Long = REPLY_MAX_MS,
) {
    private var spokeAt: Long? = null
    private var quietSince: Long? = null

    // true once the patient spoke in this window
    val answeredByVoice get() = spokeAt != null

    // userSpokeAt = last time the mic heard the patient; talking = nova's ai is busy (thinking, speaking,
    // an action); decided = the patient chose already (tap, pace change, wait)
    fun next(now: Long, userSpokeAt: Long, talking: Boolean, decided: Boolean): TalkStep {
        if (spokeAt == null && userSpokeAt >= startedAt) spokeAt = userSpokeAt
        val spoke = spokeAt
        if (spoke == null) {
            if (decided) return TalkStep.DONE
            return if (now - startedAt >= listenMs) TalkStep.DONE else TalkStep.LISTEN
        }
        // they spoke: wait for nova's answer, at most replyMaxMs after the last words
        val lastWords = maxOf(spoke, userSpokeAt)
        if (now - lastWords >= replyMaxMs) return TalkStep.DONE
        if (talking || now - lastWords < REPLY_START_MS) {
            quietSince = null
            return TalkStep.REPLY
        }
        val q = quietSince ?: now.also { quietSince = it }
        return if (now - q >= QUIET_END_MS) TalkStep.DONE else TalkStep.REPLY
    }

    // 0..1 of the listening time, for the ring on the screen
    fun listened(now: Long): Float = ((now - startedAt).toFloat() / listenMs).coerceIn(0f, 1f)

    companion object {
        const val LISTEN_MS = 6_000L
        const val REPLY_MAX_MS = 9_000L
        // the planner needs a moment before it shows that it works on an answer
        const val REPLY_START_MS = 1_500L
        const val QUIET_END_MS = 1_200L
    }
}

enum class BlockedStep { NONE, ASK, GIVE_UP }

// something is in nova's way (docs/journeys.md 6.6): ask people to step aside, ask once more after
// 10 s, give up on this way 8 s after the second ask (the guide then tries the place's viaSpot once,
// then calls the team). blocked longer than 25 s in total during one drive also ends it.
class BlockedWatch(
    private val askEveryMs: Long = ASK_EVERY_MS,
    private val maxAsks: Int = MAX_ASKS,
    private val graceMs: Long = GRACE_AFTER_LAST_ASK_MS,
    private val totalLimitMs: Long = TOTAL_LIMIT_MS,
) {
    private val clock = BlockedClock()
    private var asks = 0
    private var lastAskAt = 0L

    val isBlocked: Boolean get() = clock.isBlocked()
    val asksDone: Int get() = asks

    fun blocked(now: Long) = clock.blocked(now)

    fun clear(now: Long) {
        clock.clear(now)
        asks = 0
    }

    // the robot drove on while "blocked" (the end-of-obstacle status never came): it is not blocked.
    // movedM = meters since the blocked status started. true when that cleared it
    fun moved(now: Long, movedM: Double): Boolean {
        if (!clock.isBlocked() || movedM < MOVED_CLEARS_M) return false
        clear(now)
        return true
    }

    fun step(now: Long): BlockedStep {
        if (clock.total(now) > totalLimitMs) return BlockedStep.GIVE_UP
        if (!clock.isBlocked()) return BlockedStep.NONE
        if (asks == 0 || (asks < maxAsks && now - lastAskAt >= askEveryMs)) {
            asks++
            lastAskAt = now
            return BlockedStep.ASK
        }
        if (asks >= maxAsks && now - lastAskAt >= graceMs) return BlockedStep.GIVE_UP
        return BlockedStep.NONE
    }

    companion object {
        const val ASK_EVERY_MS = 10_000L
        const val MAX_ASKS = 2
        const val GRACE_AFTER_LAST_ASK_MS = 8_000L
        const val TOTAL_LIMIT_MS = 25_000L
        const val MOVED_CLEARS_M = 0.5
    }
}

// after a failed stretch: try the place's waypoint once when the way was blocked or had no path
object Detour {
    fun shouldTry(why: NavFailure, viaSpot: String?, tried: Boolean): Boolean =
        !tried && !viaSpot.isNullOrBlank() && why in setOf(NavFailure.BLOCKED, NavFailure.NO_PATH)
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
