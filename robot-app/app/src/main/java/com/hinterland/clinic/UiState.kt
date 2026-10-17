package com.hinterland.clinic

// what the robot screen shows. plain data, no android: the mirror for the dashboard and the
// screen info for the llm are unit tested.

enum class Health { UNKNOWN, OK, DOWN }

// what the agentos planner is doing (from OnAgentStatusChangedListener)
enum class AgentMode { IDLE, LISTENING, THINKING }

enum class Mood { IDLE, LISTENING, THINKING, SPEAKING, WALKING, HAPPY, SORRY }

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
    // 0..1 along the way, null when nova can't tell
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
    // nova takes the place's waypoint because the way was blocked (6.6)
    val detour: Boolean = false,
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
        GuidePhase.ARRIVED -> if (exit) "Goodbye and get well soon" else "We're here"
        GuidePhase.NOBODY_CAME -> "Nobody came"
        GuidePhase.FAILED -> "I can't get there now"
        GuidePhase.CANCELLED -> "The team ended this walk"
    }

    // the screen during a guide, with the patient behind or in front of nova
    val walkingNow get() = phase == GuidePhase.WALKING
    val needsPresence get() = phase in setOf(GuidePhase.CHECKING, GuidePhase.WAITING, GuidePhase.LOOKING, GuidePhase.LOST) && talk == null
}

enum class TalkStage { ASKING, LISTENING, REPLYING }

// the check-back talk on the screen: the question, and since when nova listens (for the ring)
data class TalkUi(
    val question: String,
    val stage: TalkStage = TalkStage.ASKING,
    val listenMs: Long = TalkWindow.LISTEN_MS,
    // 0..1 of the listening time, set when the screen should show a fixed ring (previews)
    val listened: Float? = null,
    // changes with every new talk, so the ring starts again
    val id: Long = 0,
)

// the staff menu (long-press the Nova logo 3 s): what staff need to see on the robot itself
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

// urgent help was called: nova stays and shows this
data class HelpUi(val urgent: Boolean, val sent: Boolean?)

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
    // nova can't drive (out-of-service screen, 6.6)
    val chassis: ChassisIssue? = null,
    // the staff menu is open
    val staff: StaffUi? = null,
) {
    // the face follows the walk: attentive while checking, concerned when someone is missing
    fun mood(): Mood = when {
        flash != null -> flash
        speaking -> Mood.SPEAKING
        help != null -> Mood.LISTENING
        guide?.phase in setOf(GuidePhase.LOOKING, GuidePhase.LOST, GuidePhase.FAILED, GuidePhase.NOBODY_CAME) -> Mood.SORRY
        guide?.talk != null -> Mood.LISTENING
        guide?.phase in setOf(GuidePhase.CHECKING, GuidePhase.WAITING, GuidePhase.PICKUP) -> Mood.LISTENING
        guide?.phase == GuidePhase.ARRIVED -> Mood.HAPPY
        walking -> Mood.WALKING
        working > 0 || agent == AgentMode.THINKING -> Mood.THINKING
        agent == AgentMode.LISTENING -> Mood.LISTENING
        else -> Mood.IDLE
    }

    val canSelfCheckIn get() = chassis == null && Places.selfCheckIn(places).isNotEmpty()

    // heartbeat `screen` for the dashboard's live mirror. ticket, never a name.
    fun mirror(): ScreenMirror {
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
        return ScreenMirror(view = "idle", title = "Hello, I'm Nova", step = brain.goal)
    }

    // what the planner should know about the screen (uploadInterfaceInfo replaces it every time)
    fun screenInfo(): String = buildString {
        appendLine("Nova Clinic robot screen. Nova right now: ${brain.goal}.")
        val public = Places.public(places)
        val rooms = Places.selfCheckIn(places)
        when {
            help != null -> appendLine(
                if (help.urgent) "Urgent help was called. Nova stays with the person; the team is on the way."
                else "Nova called a team member.",
            )
            guide != null -> {
                appendLine("Guide for ticket ${guide.ticket} to ${guide.destination}${guide.destinationNote?.let { " ($it)" }.orEmpty()}.")
                appendLine("Step: ${guide.step()}. Current pace: ${guide.pace.wire}.")
                guide.progress?.let { appendLine("About ${(it * 100).toInt()} % of the way done.") }
                when (guide.phase) {
                    GuidePhase.PICKUP -> appendLine("Nova is calling ticket ${guide.ticket}. The person with that ticket confirms with CONFIRM_TICKET.")
                    GuidePhase.PACE -> appendLine("Nova asked how fast to walk. The answer goes to SET_PACE.")
                    GuidePhase.PAUSED -> appendLine("The walk is paused. When the person is ready, use SET_PACE with the pace they want.")
                    GuidePhase.CHECKING -> if (guide.talk != null) {
                        appendLine(
                            "Nova turned around and asked the patient if all is good and whether to slow down. " +
                                "If they say yes or all good, answer in a few warm words. Slower: SET_PACE. Wait or a break: PAUSE_WALK.",
                        )
                    }
                    GuidePhase.ARRIVED -> appendLine("Arrived. The screen asks how the walk was (thumbs up or down).")
                    else -> Unit
                }
            }
            chassis != null -> appendLine(
                "Nova cannot drive right now (${chassis.detail}). She can still talk, but she cannot walk anyone " +
                    "anywhere; the reception desk helps.",
            )
            checkIn != null -> {
                appendLine("Self check-in on screen, step: ${checkIn.stepLabel()}.")
                checkIn.room?.let { appendLine("Chosen: ${it.label} (${it.name}).") }
            }
            else -> appendLine(
                "Welcome screen: Nova can check visitors in for a room, walk them to public places, " +
                    "tell them about their wait by ticket and call a team member.",
            )
        }
        if (rooms.isNotEmpty()) appendLine("Rooms for self check-in (CHECK_IN_FOR): ${Places.spoken(rooms)}.")
        if (public.isNotEmpty()) appendLine("Public places (GUIDE_TO_PLACE): ${Places.spoken(public)}.")
        append("Other treatment rooms are assigned by the reception desk.")
    }
}
