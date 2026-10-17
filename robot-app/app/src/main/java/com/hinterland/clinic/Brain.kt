package com.hinterland.clinic

// what otto does next, as a plain function of what he knows right now (Brain.decide).
// no android and no sdk in here, so the priorities are unit tested on the jvm. OttoBrain runs it.

// wire = value for the heartbeat (docs/api.md), label = on-screen english
enum class RobotState(val wire: String, val label: String) {
    IDLE("idle", "Idle"),
    GREETING("greeting", "Greeting"),
    TALKING("talking", "Talking"),
    FETCHING("fetching", "Fetching"),
    WALKING("walking", "Walking"),
    RETURNING("returning", "Returning"),
    CHARGING("charging", "Charging"),
    ERROR("error", "Error"),
}

// NONE = quiet, LISTENING = only the agent status says "listening", ACTIVE = someone really talks
enum class TalkLevel { NONE, LISTENING, ACTIVE }

// a person the robot sees right now (from PersonApi). id >= 0 means the face is complete.
data class Visitor(val id: Int, val distance: Double, val withFace: Boolean = true)

// what the screen and the heartbeat show
data class BrainStatus(
    val goal: String = "Starting up",
    val state: RobotState = RobotState.IDLE,
    val battery: Int? = null,
    val charging: Boolean = false,
    val position: String? = null,
    val detail: String? = null,
)

// everything the decision needs, at one moment. times are epoch ms, 0 = never.
data class Snapshot(
    val now: Long,
    val robotReady: Boolean = true,
    val homeSpot: String = "",
    val helpActive: Boolean = false,
    val talk: TalkLevel = TalkLevel.NONE,
    // pending commands from the backend, oldest first
    val commands: List<RobotCommand> = emptyList(),
    // commands the person in front of otto asked for (GUIDE_TO_PLACE, self check-in): no waiting, no excuse
    val ownCommandIds: Set<String> = emptySet(),
    // since when otto waits for the conversation to end before the next command
    val waitingSince: Long? = null,
    val visitor: Visitor? = null,
    val lastGreetAt: Long = 0,
    val lastTalkAt: Long = 0,
    // person id -> last time otto greeted or talked with them
    val engaged: Map<Int, Long> = emptyMap(),
    val battery: Int? = null,
    // the battery says it is charging
    val charging: Boolean = false,
    // otto drove onto the charger himself
    val dockedByOtto: Boolean = false,
    // null = not known yet
    val atHome: Boolean? = null,
    // spot the team sent otto to with go_to; he stays there
    val parkedAt: String? = null,
    // last time something happened (talk or a task)
    val quietSince: Long = 0,
    // a walk just ended, go back without waiting
    val returnHomeNow: Boolean = false,
    val returnFailedAt: Long = 0,
    val chargeFailedAt: Long = 0,
    val leaveFailedAt: Long = 0,
)

sealed interface Plan {
    // urgent help was called: stay put and keep talking
    data object StayWithPerson : Plan
    // a command is waiting until the person talking to otto is done (at most QUIET_WAIT_MS)
    data class WaitForQuiet(val command: RobotCommand) : Plan
    // excuse = say sorry to the person talking first, otto has to go
    data class RunCommand(val command: RobotCommand, val excuse: Boolean) : Plan
    data object Talk : Plan
    data class Greet(val visitorId: Int) : Plan
    data object LeaveCharger : Plan
    data object Charge : Plan
    data object GoCharge : Plan
    data object ReturnHome : Plan
    data object Wait : Plan
}

object Brain {

    const val QUIET_WAIT_MS = 20_000L
    const val RECENT_TALK_MS = 8_000L
    // a status that never got reset does not count forever
    const val LISTENING_MAX_MS = 20_000L
    const val THINKING_MAX_MS = 60_000L

    const val GREET_RANGE_M = 1.5
    const val GREET_SAME_PERSON_MS = 2 * 60_000L
    const val GREET_COOLDOWN_MS = 30_000L

    const val LOW_BATTERY = 20
    const val CHARGED_BATTERY = 30
    const val AWAY_IDLE_MS = 60_000L

    const val RETRY_RETURN_MS = 3 * 60_000L
    const val RETRY_CHARGE_MS = 5 * 60_000L
    const val RETRY_LEAVE_MS = 2 * 60_000L

    // highest priority first, see the list in the comments
    fun decide(s: Snapshot): Plan {
        // 1. someone needs urgent help: stay, keep talking, start nothing else
        if (s.helpActive) return Plan.StayWithPerson

        // 2. the person in front asked otto to walk them somewhere: go now
        s.commands.firstOrNull { it.id in s.ownCommandIds }?.let { return Plan.RunCommand(it, excuse = false) }

        // 3. the team wants something. a patient talking right now gets up to 20 s first
        nextCommand(s.commands)?.let { cmd ->
            val talking = s.talk != TalkLevel.NONE
            if (talking && s.now - (s.waitingSince ?: s.now) < QUIET_WAIT_MS) return Plan.WaitForQuiet(cmd)
            return Plan.RunCommand(cmd, excuse = talking && moves(cmd))
        }

        // never cut into a conversation. a bare "listening" status may just be a face in front
        // of the mic, so a visitor who walks up still gets greeted
        val visitor = greetable(s)
        if (s.talk == TalkLevel.ACTIVE || (s.talk == TalkLevel.LISTENING && visitor == null)) return Plan.Talk

        // 4. someone walked up
        if (visitor != null) return Plan.Greet(visitor.id)

        // 5. battery
        if (s.charging || s.dockedByOtto) {
            val full = s.battery != null && s.battery >= CHARGED_BATTERY
            val retry = s.now - s.leaveFailedAt >= RETRY_LEAVE_MS
            // only leave a charger otto drove to himself; if the team docked him, he stays
            return if (s.dockedByOtto && full && s.robotReady && retry) Plan.LeaveCharger else Plan.Charge
        }
        if (needsCharge(s)) return Plan.GoCharge

        // 6. back to the home spot (reception)
        if (shouldReturn(s)) return Plan.ReturnHome

        // 7. nothing to do
        return Plan.Wait
    }

    // guide first, then go_to, then say / ask; oldest first within a type
    fun nextCommand(commands: List<RobotCommand>): RobotCommand? =
        commands.withIndex()
            .minWithOrNull(compareBy<IndexedValue<RobotCommand>>({ rank(it.value.type) }, { it.index }))
            ?.value

    private fun rank(type: String) = when (type) {
        RobotCommand.GUIDE -> 0
        RobotCommand.GO_TO -> 1
        RobotCommand.SAY, RobotCommand.ASK -> 2
        else -> 3
    }

    private fun moves(cmd: RobotCommand) = cmd.type == RobotCommand.GUIDE || cmd.type == RobotCommand.GO_TO

    fun greetable(s: Snapshot): Visitor? {
        val v = s.visitor ?: return null
        if (v.id < 0 || !v.withFace || v.distance <= 0.0 || v.distance > GREET_RANGE_M) return null
        // not at a room's door after a walk, only at home or where the team parked him
        if (s.atHome == false && s.parkedAt == null) return null
        if (s.now - maxOf(s.lastGreetAt, s.lastTalkAt) < GREET_COOLDOWN_MS) return null
        val seen = s.engaged[v.id] ?: return v
        return v.takeIf { s.now - seen >= GREET_SAME_PERSON_MS }
    }

    fun needsCharge(s: Snapshot): Boolean =
        s.robotReady && s.battery != null && s.battery <= LOW_BATTERY &&
            s.now - s.chargeFailedAt >= RETRY_CHARGE_MS

    fun shouldReturn(s: Snapshot): Boolean =
        s.robotReady && s.homeSpot.isNotBlank() && s.atHome == false && s.parkedAt == null &&
            (s.returnHomeNow || s.now - s.quietSince >= AWAY_IDLE_MS) &&
            s.now - s.returnFailedAt >= RETRY_RETURN_MS

    // a running ReturnHome or GoCharge gives way to these (a guide, the person's own request, go_to, urgent help)
    fun shouldPreempt(s: Snapshot): Boolean =
        s.helpActive || s.commands.any { moves(it) }

    fun talkLevel(now: Long, agent: AgentMode, agentAt: Long, userAt: Long, robotAt: Long, actions: Int): TalkLevel =
        when {
            actions > 0 -> TalkLevel.ACTIVE
            now - userAt < RECENT_TALK_MS || now - robotAt < RECENT_TALK_MS -> TalkLevel.ACTIVE
            agent == AgentMode.THINKING && now - agentAt < THINKING_MAX_MS -> TalkLevel.ACTIVE
            agent == AgentMode.LISTENING && now - agentAt < LISTENING_MAX_MS -> TalkLevel.LISTENING
            else -> TalkLevel.NONE
        }

    // nearest complete face in range
    fun pickVisitor(people: List<Visitor>, rangeM: Double = GREET_RANGE_M): Visitor? =
        people.filter { it.withFace && it.id >= 0 && it.distance > 0.0 && it.distance <= rangeM }
            .minByOrNull { it.distance }

    // ---- words for the screen and the board (english, not speech) ----

    fun goal(plan: Plan, s: Snapshot): String = when (plan) {
        Plan.StayWithPerson -> "Staying with someone who needs help"
        is Plan.WaitForQuiet -> "Finishing this talk, then " + commandGoal(plan.command, s.homeSpot).replaceFirstChar { it.lowercase() }
        is Plan.RunCommand -> commandGoal(plan.command, s.homeSpot)
        Plan.Talk -> "Talking with a visitor"
        is Plan.Greet -> "Greeting a visitor"
        Plan.LeaveCharger -> "Charged, leaving the charger"
        Plan.Charge -> s.battery?.let { "Charging ($it %)" } ?: "Charging"
        Plan.GoCharge -> "Driving to the charger"
        Plan.ReturnHome -> "Driving back to ${s.homeSpot}"
        Plan.Wait -> "Waiting for patients"
    }

    fun state(plan: Plan, homeSpot: String): RobotState = when (plan) {
        Plan.StayWithPerson, is Plan.WaitForQuiet, Plan.Talk -> RobotState.TALKING
        is Plan.RunCommand -> commandState(plan.command, homeSpot)
        is Plan.Greet -> RobotState.GREETING
        Plan.LeaveCharger, Plan.Charge, Plan.GoCharge -> RobotState.CHARGING
        Plan.ReturnHome -> RobotState.RETURNING
        Plan.Wait -> RobotState.IDLE
    }

    // ticket, never a name ("Guiding A17 to Room B204")
    fun commandGoal(c: RobotCommand, homeSpot: String): String = when (c.type) {
        RobotCommand.GUIDE -> guideGoal(c.ticket, c.to?.label)
        RobotCommand.GO_TO -> when {
            c.spot.isNullOrBlank() -> "Checking a task from the team"
            isHome(c.spot, homeSpot) -> "Driving back to ${c.spot}"
            else -> "Going to ${c.spot}"
        }
        RobotCommand.SAY -> "Passing on a message from the team"
        RobotCommand.ASK -> "Thinking about a message from the team"
        else -> "Checking a task from the team"
    }

    fun guideGoal(ticket: String?, destination: String?): String =
        "Guiding ${ticket?.ifBlank { null } ?: "a patient"}" + (destination?.ifBlank { null }?.let { " to $it" } ?: "")

    fun commandState(c: RobotCommand, homeSpot: String): RobotState = when (c.type) {
        RobotCommand.GUIDE -> RobotState.FETCHING
        RobotCommand.GO_TO -> if (isHome(c.spot, homeSpot)) RobotState.RETURNING else RobotState.WALKING
        else -> RobotState.TALKING
    }

    fun isHome(spot: String?, homeSpot: String): Boolean =
        homeSpot.isNotBlank() && spot != null && spot.trim().equals(homeSpot.trim(), ignoreCase = true)
}

// how long the robot has been stuck in front of obstacles during one drive, in total
class BlockedClock {
    private var since: Long? = null
    private var done = 0L

    @Synchronized
    fun blocked(at: Long) {
        if (since == null) since = at
    }

    @Synchronized
    fun clear(at: Long) {
        since?.let { done += (at - it).coerceAtLeast(0) }
        since = null
    }

    @Synchronized
    fun isBlocked(): Boolean = since != null

    @Synchronized
    fun total(now: Long): Long = done + (since?.let { (now - it).coerceAtLeast(0) } ?: 0)
}
