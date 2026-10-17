package com.hinterland.clinic

import java.util.Locale
import kotlin.random.Random

// otto's modes (the user's spec 17.10), switched in the staff menu, by the dashboard (command set_mode) or by
// settings.mode in GET /api/robot/config. the heartbeat reports the mode. plain kotlin, unit tested.
//  normal = the receptionist as before
//  show   = lively face, small turns now and then, silent: no speech, no greeting, the mic is off, no walks
//  calm   = quiet and passive: no speech, no greeting, no automatic driving, he does not mind being pushed
//  demo   = the rehearsed demo (docs/demo-mode.md, Demo.kt): the mic is off, otto only says the script's lines
enum class RobotMode(val wire: String, val label: String) {
    NORMAL("normal", "Normal"),
    SHOW("show", "Show"),
    CALM("calm", "Calm"),
    DEMO("demo", "Demo"),
    ;

    // nothing is said at all (show and calm)
    val silent: Boolean get() = this == SHOW || this == CALM

    // the mic is off and kept off (show, calm and the demo)
    val micOff: Boolean get() = this != NORMAL

    companion object {
        fun of(raw: String?): RobotMode? = entries.firstOrNull { it.wire == raw?.trim()?.lowercase(Locale.ROOT) }
    }
}

// what each mode allows. the brain, the poll and the speech manager ask here
object ModeRules {

    // a command otto does not take in this mode: the answer for the dashboard, null = he takes it.
    // go_to stays possible (the team sends him somewhere on purpose); words are silent in show and calm.
    // the demo runs its own walk and has no ai: walks and ask wait until it is over, say still works
    fun refuses(mode: RobotMode, type: String): String? = when {
        mode == RobotMode.NORMAL -> null
        type == RobotCommand.GUIDE -> "Otto is in ${mode.label} mode"
        mode == RobotMode.DEMO && type == RobotCommand.ASK -> "Otto is in ${mode.label} mode"
        mode == RobotMode.DEMO && type == RobotCommand.SWITCH_MAP -> "Otto is in Demo mode. Switch the map after the demo."
        mode.silent && (type == RobotCommand.SAY || type == RobotCommand.ASK) -> "Otto is in ${mode.label} mode and does not talk"
        else -> null
    }

    fun greets(mode: RobotMode) = mode == RobotMode.NORMAL

    fun selfCheckIn(mode: RobotMode) = mode == RobotMode.NORMAL

    // back to the reception after a while away
    fun drivesHome(mode: RobotMode) = mode == RobotMode.NORMAL

    // calm: no automatic driving at all, low battery is only reported. the demo drives only its own run
    fun drivesToCharger(mode: RobotMode) = mode != RobotMode.CALM && mode != RobotMode.DEMO

    // off the charger again when full: only in normal mode (show and calm stay where they are)
    fun leavesCharger(mode: RobotMode) = mode == RobotMode.NORMAL

    // the small turns of the show
    fun idleTurns(mode: RobotMode) = mode == RobotMode.SHOW

    // one line for the staff menu
    fun describe(mode: RobotMode): String = when (mode) {
        RobotMode.NORMAL -> "Receptionist: talks, greets, walks patients."
        RobotMode.SHOW -> "Lively face and small turns, silent. Walks are refused."
        RobotMode.CALM -> "Quiet and passive, no driving on his own. Walks are refused."
        RobotMode.DEMO -> "Demo script: the mic is off, Otto only says the script's lines."
    }
}

// a small turn on the spot in show mode
data class ShowTurn(val degrees: Double, val left: Boolean)

// the show's small turns: now and then 10-15 deg, gentle, only when nobody is very close and not on the
// charger. otto stays within maxOffsetDeg of where he started, so he turns back towards the middle when he
// is far out. the random source is passed in, so the tests choose it
class ShowMoves(
    private val random: Random = Random.Default,
    private val minDeg: Double = MIN_DEG,
    private val maxDeg: Double = MAX_DEG,
    private val maxOffsetDeg: Double = MAX_OFFSET_DEG,
    private val minGapMs: Long = MIN_GAP_MS,
    private val maxGapMs: Long = MAX_GAP_MS,
    private val closeM: Double = CLOSE_M,
) {
    // + = left of where the show started
    var offsetDeg = 0.0
        private set
    private var nextAt = 0L

    // nearestM = the closest person the camera sees (null = the camera gives nothing: no turn, to be safe;
    // an empty list = nobody there, use Double.MAX_VALUE). the turn to make now, or null
    fun due(now: Long, nearestM: Double?, charging: Boolean): ShowTurn? {
        if (nextAt == 0L) nextAt = now + gap()
        if (now < nextAt) return null
        if (charging || nearestM == null || nearestM < closeM) {
            // try again a little later
            nextAt = now + RETRY_MS
            return null
        }
        val deg = Math.round((minDeg + random.nextDouble() * (maxDeg - minDeg)) * 10) / 10.0
        val left = when {
            offsetDeg + deg > maxOffsetDeg -> false
            offsetDeg - deg < -maxOffsetDeg -> true
            else -> random.nextBoolean()
        }
        return ShowTurn(deg, left)
    }

    // the turn is over (ok = the robot took it)
    fun turned(t: ShowTurn, ok: Boolean, now: Long) {
        if (ok) offsetDeg += if (t.left) t.degrees else -t.degrees
        nextAt = now + gap()
    }

    // a new show starts where otto stands now
    fun reset() {
        offsetDeg = 0.0
        nextAt = 0L
    }

    private fun gap(): Long = minGapMs + (random.nextDouble() * (maxGapMs - minGapMs)).toLong()

    companion object {
        const val MIN_DEG = 10.0
        const val MAX_DEG = 15.0
        const val MAX_OFFSET_DEG = 25.0
        const val MIN_GAP_MS = 18_000L
        const val MAX_GAP_MS = 40_000L
        const val RETRY_MS = 5_000L
        // nobody closer than this
        const val CLOSE_M = 1.2
        // gentle: degrees per second (the robot allows up to 50)
        const val TURN_SPEED = 12f
    }
}
