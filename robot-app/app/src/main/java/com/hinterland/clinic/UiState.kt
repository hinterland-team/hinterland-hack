package com.hinterland.clinic

// what the robot screen shows. plain data, no android: the mirror for the dashboard and the
// screen info for the llm are unit tested.

enum class Health { UNKNOWN, OK, DOWN }

// what the agentos planner is doing (from OnAgentStatusChangedListener)
enum class AgentMode { IDLE, LISTENING, THINKING }

// how otto feels. talking is separate (UiState.speaking): he can talk happily, sorrily, sleepily...
enum class Mood {
    IDLE, LISTENING, THINKING, WALKING, HAPPY, SORRY,
    // a big moment: arrived, "that's me"
    EXCITED,
    // thumbs up from the patient
    LOVE,
    SURPRISED,
    // urgent help, a patient out of sight
    CONCERNED,
    // the robot itself is in trouble
    CONFUSED,
    // charging
    SLEEPY,
    // low battery
    TIRED,
    // a patient found again, the team informed
    RELIEVED,
    // otto greets someone walking up
    GREETING,
}

// below this battery level otto looks tired (unless charging)
const val LOW_BATTERY = 15

// step = on-screen english for the current step, view = heartbeat screen.view
enum class GuidePhase(val view: String) {
    TO_PICKUP("pickup"),
    PICKUP("pickup"),
    PACE("pickup"),
    WALKING("walking"),
    CHECKING("check_back"),
    WAITING("waiting"),
    PAUSED("waiting"),
    LOOKING("waiting"),
    LOST("help"),
    // the way stays blocked after the detours: the team is called, otto waits with the patient (6.7)
    BLOCKED("help"),
    ARRIVED("arrived"),
    NOBODY_CAME("waiting"),
    FAILED("help"),
    CANCELLED("idle"),
}

data class GuideUi(
    val ticket: String,
    // "Room B204" or the place name
    val destination: String,
    // the place name under a room number, e.g. "Dr. Blue"
    val destinationNote: String? = null,
    val exit: Boolean = false,
    val from: String = "",
    val phase: GuidePhase = GuidePhase.PICKUP,
    val pace: Pace = Pace.NORMAL,
    // 0..1 along the way, null when otto can't tell
    val progress: Float? = null,
    // why it failed, in short english
    val note: String? = null,
    // "up" / "down" once the patient rated the walk
    val feedback: String? = null,
    // the destination is a waiting room (place kind waiting)
    val waiting: Boolean = false,
    // the phone companion link for the QR code on the pickup screen (6.4)
    val walkUrl: String? = null,
    // the face-to-face talk at a check-back (6.4), null when there is none
    val talk: TalkUi? = null,
    // otto takes the place's waypoint because the way was blocked (6.6)
    val detour: Boolean = false,
    // the side the patient walks on, next to otto (6.7)
    val side: WalkSide = WalkSide.LEFT,
) {
    fun step(): String = when (phase) {
        GuidePhase.TO_PICKUP -> "Coming to pick you up"
        GuidePhase.PICKUP -> "Please come to me"
        GuidePhase.PACE -> "How shall I walk?"
        GuidePhase.WALKING -> if (detour) "Taking another way" else "Walking with you"
        GuidePhase.CHECKING -> if (talk != null) "All good?" else "Checking on you"
        GuidePhase.WAITING -> "I'll wait for you"
        GuidePhase.PAUSED -> "Waiting until you're ready"
        GuidePhase.LOOKING -> "Looking for you"
        GuidePhase.LOST -> "The team is on the way"
        GuidePhase.BLOCKED -> "The way is blocked"
        GuidePhase.ARRIVED -> if (exit) "Goodbye and get well soon" else "We're here"
        GuidePhase.NOBODY_CAME -> "Nobody came"
        GuidePhase.FAILED -> "I can't get there now"
        GuidePhase.CANCELLED -> "The team ended this walk"
    }

    // the screen during a guide, with the patient behind or in front of otto
    val walkingNow get() = phase == GuidePhase.WALKING
    val needsPresence get() = phase in setOf(GuidePhase.CHECKING, GuidePhase.WAITING, GuidePhase.LOOKING, GuidePhase.LOST) && talk == null
}

enum class TalkStage { ASKING, LISTENING, REPLYING }

// the check-back talk on the screen: the question, and since when otto listens (for the ring)
data class TalkUi(
    val question: String,
    val stage: TalkStage = TalkStage.ASKING,
    val listenMs: Long = ListenWindow.MAX_MS,
    // 0..1 of the listening time, set when the screen should show a fixed ring (previews)
    val listened: Float? = null,
    // changes with every new talk, so the ring starts again
    val id: Long = 0,
)

// the staff menu (long-press the Nova Clinic logo 3 s): what staff need to see on the robot itself
data class StaffUi(
    val robotName: String = "",
    val version: String = "",
    val system: String? = null,
    val servers: List<ServerState> = emptyList(),
    val current: String = "",
    // "Ready" or why not
    val chassis: String = "",
    val chassisOk: Boolean = true,
    val emergency: Boolean? = null,
    val battery: Int? = null,
    val charging: Boolean = false,
    val radar: String = "",
    val brain: String = "",
    val logs: List<LogLine> = emptyList(),
    val logsPending: Int = 0,
    // how smoothly the app runs (6.7): one line, and whether it looks fine
    val perf: String = "",
    val perfOk: Boolean = true,
    // the mode switch at the top (RobotModes.kt); pending = asked for during a walk
    val mode: RobotMode = RobotMode.NORMAL,
    val modePending: RobotMode? = null,
    // docs/robot-setup.md: "Settings v4, applied 18:42" and "Map Hinterland 2, floor 1, localised"
    val settings: String = "",
    val settingsOk: Boolean = true,
    val map: String = "",
    val mapOk: Boolean = true,
)

// screen settings from the dashboard (screen.privacyLine, screen.talkHints)
data class ScreenPrefs(
    // empty = no privacy line
    val privacyLine: String = Settings.PRIVACY_LINE,
    val talkHints: Boolean = true,
)

enum class CheckInStep { WHO, ASK_DESK, PACE, CONFIRM, SENDING, FAILED }

// self check-in on the touch screen: who -> pace -> confirm. no typing, no name.
data class CheckInUi(
    val step: CheckInStep = CheckInStep.WHO,
    val rooms: List<Place> = emptyList(),
    val room: Place? = null,
    val pace: Pace = Pace.NORMAL,
    val wheelchair: Boolean = false,
    val error: String? = null,
) {
    fun stepLabel(): String = when (step) {
        CheckInStep.WHO -> "Who are you here to see?"
        CheckInStep.ASK_DESK -> "The team at the desk will help you"
        CheckInStep.PACE -> "How shall I walk?"
        CheckInStep.CONFIRM -> "Ready to go?"
        CheckInStep.SENDING -> "Getting your ticket"
        CheckInStep.FAILED -> "That didn't work"
    }

    // 1-3 for the step dots
    fun stepNumber(): Int = when (step) {
        CheckInStep.WHO, CheckInStep.ASK_DESK -> 1
        CheckInStep.PACE -> 2
        else -> 3
    }
}

// urgent help was called: otto stays and shows this
data class HelpUi(val urgent: Boolean, val sent: Boolean?)

// what the screen shows next to otto's face. a new key = something new happened, the face moves aside
// and the info comes up. holds = the screen waits for a tap, so the info stays until it doesn't.
data class Focus(val key: String, val holds: Boolean)

data class UiState(
    val agent: AgentMode = AgentMode.IDLE,
    val agentNote: String? = null,
    // own network / llm work in flight - the face looks "thinking" while > 0
    val working: Int = 0,
    val task: String? = null,
    val speaking: Boolean = false,
    val walking: Boolean = false,
    val flash: Mood? = null,
    val subtitle: String = "",
    val subtitleFromUser: Boolean = false,
    val banner: String? = null,
    val backend: Health = Health.UNKNOWN,
    val robot: Health = Health.UNKNOWN,
    val robotNote: String? = null,
    val brain: BrainStatus = BrainStatus(),
    val guide: GuideUi? = null,
    val checkIn: CheckInUi? = null,
    val help: HelpUi? = null,
    // from GET /api/places
    val places: List<Place> = emptyList(),
    // otto can't drive (out-of-service screen, 6.6)
    val chassis: ChassisIssue? = null,
    // the staff menu is open
    val staff: StaffUi? = null,
    // normal / show / calm (RobotModes.kt): show and calm are the face only, silent
    val mode: RobotMode = RobotMode.NORMAL,
    // the map otto is on: only places of this floor are offered (docs/robot-setup.md 2)
    val mapName: String? = null,
    val screen: ScreenPrefs = ScreenPrefs(),
) {
    // the places on otto's floor
    val placesHere: List<Place> get() = Places.onMap(places, mapName)

    // the face follows the walk: attentive while checking, worried when someone is missing.
    // talking doesn't change the mood, the mouth moves on top of it.
    fun mood(): Mood = when {
        flash != null -> flash
        help != null -> Mood.CONCERNED
        guide?.phase in setOf(GuidePhase.LOOKING, GuidePhase.LOST) -> Mood.CONCERNED
        guide?.phase in setOf(GuidePhase.FAILED, GuidePhase.NOBODY_CAME, GuidePhase.BLOCKED) -> Mood.SORRY
        guide?.talk != null -> Mood.LISTENING
        guide?.phase in setOf(GuidePhase.CHECKING, GuidePhase.WAITING, GuidePhase.PICKUP) -> Mood.LISTENING
        guide?.phase == GuidePhase.ARRIVED -> Mood.HAPPY
        walking -> Mood.WALKING
        working > 0 || agent == AgentMode.THINKING -> Mood.THINKING
        agent == AgentMode.LISTENING -> Mood.LISTENING
        brain.state == RobotState.GREETING -> Mood.GREETING
        brain.state == RobotState.ERROR -> Mood.CONFUSED
        brain.state == RobotState.CHARGING && !speaking -> Mood.SLEEPY
        !brain.charging && (brain.battery ?: 100) <= LOW_BATTERY -> Mood.TIRED
        else -> Mood.IDLE
    }

    val canSelfCheckIn get() = chassis == null && ModeRules.selfCheckIn(mode) && Places.selfCheckIn(placesHere).isNotEmpty()

    // null = nothing going on, the face fills the screen. show and calm: always the face
    fun focus(): Focus? {
        if (mode.silent) return null
        if (help != null) return Focus("help", holds = true)
        guide?.let { g ->
            // the check-back question ("all good?") waits for an answer or a tap on Slower / Wait
            g.talk?.let { return Focus("guide:${g.phase}:talk${it.id}", holds = true) }
            val tap = when (g.phase) {
                GuidePhase.PICKUP, GuidePhase.PACE, GuidePhase.PAUSED -> true
                GuidePhase.ARRIVED -> g.feedback == null
                else -> false
            }
            return Focus("guide:${g.phase}", holds = tap)
        }
        // out of service: shown when it starts, then the face again (a tap shows it again)
        chassis?.let { return Focus("chassis:${it.name}", holds = false) }
        checkIn?.let { return Focus("checkin:${it.step}", holds = it.step != CheckInStep.SENDING) }
        return when (brain.state) {
            RobotState.RETURNING, RobotState.CHARGING, RobotState.ERROR -> Focus("brain:${brain.state.wire}", holds = false)
            else -> null
        }
    }

    // heartbeat `screen` for the dashboard's live mirror. ticket, never a name.
    fun mirror(): ScreenMirror {
        if (mode.silent) return ScreenMirror(view = "idle", title = "${mode.label} mode", step = ModeRules.describe(mode))
        help?.let {
            return ScreenMirror(
                view = "help",
                title = if (it.urgent) "Help is on the way" else "Calling the team",
                step = if (it.urgent) "Staying with the person until the team arrives" else null,
            )
        }
        guide?.let { g ->
            val title = if (g.ticket.isBlank()) "To ${g.destination}" else "${g.ticket} to ${g.destination}"
            return ScreenMirror(
                view = g.phase.view,
                title = title,
                ticket = g.ticket.ifBlank { null },
                step = g.step(),
                progress = g.progress?.coerceIn(0f, 1f),
            )
        }
        chassis?.let { c ->
            return ScreenMirror(view = "help", title = "Out of service", step = c.hint)
        }
        checkIn?.let { c ->
            return ScreenMirror(view = "checkin", title = "Self check-in", step = c.stepLabel())
        }
        return ScreenMirror(view = "idle", title = "Hello, I'm Otto", step = brain.goal)
    }

    // what the planner should know about the screen (uploadInterfaceInfo replaces it every time)
    fun screenInfo(): String = buildString {
        appendLine("Nova Clinic robot screen. Otto right now: ${brain.goal}.")
        // only the places on otto's floor: he cannot take the elevator
        val public = Places.public(placesHere)
        val rooms = if (ModeRules.selfCheckIn(mode)) Places.selfCheckIn(placesHere) else emptyList()
        when {
            help != null -> appendLine(
                if (help.urgent) "Urgent help was called. Otto stays with the person; the team is on the way."
                else "Otto called a team member.",
            )
            guide != null -> {
                appendLine("Guide for ticket ${guide.ticket} to ${guide.destination}${guide.destinationNote?.let { " ($it)" }.orEmpty()}.")
                appendLine("Step: ${guide.step()}. Current pace: ${guide.pace.wire}.")
                guide.progress?.let { appendLine("About ${(it * 100).toInt()} % of the way done.") }
                when (guide.phase) {
                    GuidePhase.PICKUP -> appendLine("Otto is calling ticket ${guide.ticket}. The person with that ticket confirms with CONFIRM_TICKET.")
                    GuidePhase.PACE -> appendLine("Otto asked how fast to walk. The answer goes to SET_PACE.")
                    GuidePhase.PAUSED -> appendLine("The walk is paused. When the person is ready, use SET_PACE with the pace they want.")
                    GuidePhase.CHECKING -> if (guide.talk != null) {
                        appendLine(
                            "Otto turned to the patient and asked if all is good and whether to slow down. " +
                                "If they say yes or all good, answer in a few warm words. Slower: SET_PACE. Wait or a break: PAUSE_WALK.",
                        )
                    }
                    GuidePhase.ARRIVED -> appendLine("Arrived. The screen asks how the walk was (thumbs up or down).")
                    GuidePhase.BLOCKED -> appendLine(
                        "The way is blocked. Otto asked the team for help, waits here with the patient and tries the way again now and then.",
                    )
                    else -> Unit
                }
                if (guide.walkingNow || guide.phase == GuidePhase.CHECKING) {
                    appendLine("The patient walks next to Otto, on his ${guide.side.wire} side, so he can see them.")
                }
            }
            chassis != null -> appendLine(
                "Otto cannot drive right now (${chassis.detail}). He can still talk, but he cannot walk anyone " +
                    "anywhere; the reception desk helps.",
            )
            checkIn != null -> {
                appendLine("Self check-in on screen, step: ${checkIn.stepLabel()}.")
                checkIn.room?.let { appendLine("Chosen: ${it.label} (${it.name}).") }
            }
            else -> appendLine(
                "Welcome screen: Otto can check visitors in for a room, walk them to public places, " +
                    "tell them about their wait by ticket and call a team member.",
            )
        }
        if (rooms.isNotEmpty()) appendLine("Rooms for self check-in (CHECK_IN_FOR): ${Places.spoken(rooms)}.")
        if (public.isNotEmpty()) appendLine("Public places (GUIDE_TO_PLACE): ${Places.spoken(public)}.")
        append("Other treatment rooms are assigned by the reception desk.")
    }
}
