package com.hinterland.clinic

// v3.3 short stops and the walk-beside watch (docs/journeys.md 6.7). plain kotlin, unit tested.
// field test 17.10 (b8): every check-back took 38-44 s (turn 180 deg, an llm question, waiting for the
// answer, an llm reply, turn back) and the radar only sees -110..+120 deg, so a follower right behind
// flickered seen / not seen every 1-2 s. now the patient walks beside otto, where the radar sees him
// or her, and a stop takes a few seconds.

// what one check-back does
enum class StopKind {
    // the radar sees the patient right beside: no stop, a short line while walking on
    ROLLING,
    // stop, turn towards the patient, look, a 2-3 s line, go on. no listening
    QUICK,
    // stop, turn, a yes/no question and a short listening window (at most 5 s), go on
    ASK,
}

data class StopInput(
    val reason: CheckReason,
    // the number of this check in the walk, 1 = the first
    val checkNo: Int,
    // the patient's distance beside the robot on the radar right now, null = not seen just now
    val besideM: Double?,
    // how far the patient was at the last check, null = unknown
    val lastM: Double?,
    // the patient fell back since the last check (the side watch fired, otto waited or called them)
    val fellBack: Boolean,
)

object StopRules {
    // beside = the radar's follower is this close
    const val BESIDE_M = 2.0
    // the patient was further than this at the last check: ask, they may want a slower pace
    const val ASK_FROM_M = 2.0

    // a real question only every Nth check (walk.askEvery, 2 by default; 0 = never on the schedule), or when
    // the patient was further away or fell back. the side watch fired (nobody beside for a while): they mostly
    // just walk behind otto, where the radar is blind, so a stop with a short reminder to walk beside (the
    // question waits for the next check). rolling = false: every check is a stop (sensors.radarWatch off)
    fun kind(i: StopInput, askEvery: Int = 2, rolling: Boolean = true): StopKind {
        if (i.reason == CheckReason.FOLLOWER_LOST && !i.fellBack) return StopKind.QUICK
        val ask = i.fellBack ||
            (i.lastM ?: 0.0) > ASK_FROM_M ||
            (askEvery > 0 && i.checkNo % askEvery == 0)
        if (ask) return StopKind.ASK
        val beside = rolling && i.besideM != null && i.besideM > 0.0 && i.besideM <= BESIDE_M
        return if (beside) StopKind.ROLLING else StopKind.QUICK
    }
}

// the listening after the yes/no question: at most 5 s, over as soon as the patient answered (a tap,
// or words followed by a short pause). the agent answers spoken words by itself; the walk does not
// wait for that answer. askedAt = when the question started (words from then on count, also over
// the question), startedAt = when the question was over (the 5 s count from here).
class ListenWindow(
    private val askedAt: Long,
    private val startedAt: Long,
    private val maxMs: Long = MAX_MS,
    private val quietMs: Long = QUIET_MS,
) {
    private var spokeAt: Long? = null

    // true once the patient spoke since the question started
    val answeredByVoice get() = spokeAt != null

    // userSpokeAt = last time the mic heard the patient; decided = a tap, a pace change or "wait"
    fun done(now: Long, userSpokeAt: Long, decided: Boolean): Boolean {
        if (userSpokeAt >= askedAt) spokeAt = maxOf(spokeAt ?: 0L, userSpokeAt)
        if (decided) return true
        val spoke = spokeAt
        if (spoke != null && now - spoke >= quietMs && now >= startedAt) return true
        return now - startedAt >= maxMs
    }

    // 0..1 of the listening time, for the ring on the screen
    fun listened(now: Long): Float = ((now - startedAt).toFloat() / maxMs).coerceIn(0f, 1f)

    companion object {
        const val MAX_MS = 5_000L
        const val QUIET_MS = 900L
    }
}

// walk beside: the patient walks next to otto (a little behind), where the radar sees them.
// the watch fires once when nobody was beside for thresholdMs while otto drives. real data
// 17.10: a follower flickered seen / not seen with gaps of up to ~3.5 s while following, so 5 s.
// after a stop the patient needs a moment to get back beside: until they are seen again, START_GRACE_MS
// more. each false alarm (the camera found them close) adds 2 s; after 3 the schedule alone decides.
class SideWatch(
    private val lostMs: Long = LOST_MS,
    private val startGraceMs: Long = START_GRACE_MS,
    private val stepMs: Long = FALSE_ALARM_STEP_MS,
    private val maxFalseAlarms: Int = MAX_FALSE_ALARMS,
) {
    private var since = 0L
    private var fired = false

    // otto stands (blocked): nothing fires until the next start
    var holding = false
        private set
    var falseAlarms = 0
        private set
    val trusted get() = falseAlarms < maxFalseAlarms
    val thresholdMs get() = lostMs + falseAlarms * stepMs

    // otto drives (again): count from now
    fun start(now: Long) {
        since = now
        fired = false
        holding = false
    }

    fun hold() {
        holding = true
    }

    // true = check back now (once until the next start). everSeen = the radar saw the patient beside
    // in this walk (before that it can't tell this person from nobody); lastSeenAt = the last time it did
    fun check(now: Long, everSeen: Boolean, lastSeenAt: Long?): Boolean {
        if (fired || holding || !trusted || !everSeen) return false
        val seenSinceStart = lastSeenAt != null && lastSeenAt >= since
        val from = if (seenSinceStart) lastSeenAt!! else since + startGraceMs
        if (now - from < thresholdMs) return false
        fired = true
        return true
    }

    // the camera found the patient close after the watch fired
    fun falseAlarm() {
        falseAlarms++
    }

    companion object {
        const val LOST_MS = 5_000L
        const val START_GRACE_MS = 3_000L
        const val FALSE_ALARM_STEP_MS = 2_000L
        const val MAX_FALSE_ALARMS = 3
    }
}
