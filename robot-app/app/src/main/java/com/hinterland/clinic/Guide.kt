package com.hinterland.clinic

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

// someone asked nova directly (GUIDE_TO_PLACE, CHECK_IN_FOR, the self check-in screen): the person is
// right here, so there is no pickup. paceChosen = they picked the pace on the screen already.
data class OwnRequest(val paceChosen: Boolean = false, val sample: String? = null)

enum class GuideOutcome { ARRIVED, NOBODY_CAME, LOST, FAILED, CANCELLED, STOPPED }

// one `guide` command (docs/journeys.md 4): pickup, pace, walking with check-backs, arrival.
// the decisions (when to check back, what to do per distance, timelines, speeds) are plain kotlin in
// Walk.kt, Pickup.kt and Pace.kt; this class does the robot, the backend and the words.
// legs and events go to PATCH /api/journeys/:id with the ticket only, never a name.
class Guide private constructor(val cmd: RobotCommand, private val own: OwnRequest?) {

    val ticket: String = cmd.ticket.orEmpty()
    val journeyId: String = cmd.journeyId.orEmpty()
    private val legId: String = cmd.legId.orEmpty()
    private val to: Stop = cmd.to ?: Stop("", "")
    private val fromName: String = cmd.from?.name.orEmpty()
    val destination: String = to.label

    // signals from the actions and the screen (any thread)
    private val confirmed = CompletableDeferred<String>()
    private val paceAnswer = CompletableDeferred<Pace>()
    @Volatile private var wantedPace: Pace? = null
    @Volatile private var pauseWanted = false
    @Volatile private var resumeWanted = false
    @Volatile private var presenceAt = 0L
    @Volatile private var feedback: String? = null
    @Volatile private var cancelled = false
    @Volatile private var sample: String? = own?.sample

    @Volatile var pace: Pace = PaceRules.start(cmd.pace ?: Pace.NORMAL, cmd.wheelchair)
        private set
    @Volatile var phase: GuidePhase = GuidePhase.PICKUP
        private set

    // map coordinates for progress, "how far is it?" and "near <spot>"
    @Volatile private var mapPlaces: List<MapPlace> = emptyList()
    @Volatile private var start: Point? = null
    @Volatile private var here: Point? = null
    @Volatile private var dest: Point? = null
    @Volatile private var progress: Float? = null
    private val milestones = mutableSetOf<Milestone>()
    @Volatile private var lastObstacleTalk = 0L

    private fun now() = System.currentTimeMillis()

    // the patient's language: their own words once they spoke, else the call languages (robot.callLanguages)
    private val lang: Lang
        get() = sample?.let { Lang(null, it) } ?: Lang(callTags.firstOrNull())

    private val callLang: Lang
        get() = if (sample != null || callTags.isEmpty()) lang else Lang(null, tags = callTags)

    // ---- signals ----

    fun heard(text: String) {
        if (text.isNotBlank()) sample = text
    }

    // true when the ticket was the one nova calls
    fun confirm(spokenTicket: String?, how: String): Boolean {
        if (spokenTicket != null && !Tickets.same(spokenTicket, ticket)) return false
        return confirmed.complete(how) || confirmed.isCompleted
    }

    val waitingForPickup get() = phase == GuidePhase.PICKUP || phase == GuidePhase.TO_PICKUP

    fun choosePace(p: Pace) {
        when {
            phase == GuidePhase.PACE -> paceAnswer.complete(p)
            phase == GuidePhase.PAUSED -> {
                if (p != pace) wantedPace = p
                resumeWanted = true
            }
            else -> if (p != pace) wantedPace = p
        }
    }

    fun pause(): Boolean {
        if (phase != GuidePhase.WALKING && phase != GuidePhase.CHECKING) return false
        pauseWanted = true
        return true
    }

    fun goOn() {
        resumeWanted = true
    }

    fun present() {
        presenceAt = now()
    }

    fun rate(up: Boolean) {
        if (feedback == null) feedback = if (up) "up" else "down"
    }

    val walking get() = phase !in setOf(GuidePhase.PICKUP, GuidePhase.TO_PICKUP, GuidePhase.PACE, GuidePhase.ARRIVED)

    // WALK_STATUS: plain facts for the llm
    fun statusFacts(): String {
        val h = here
        val d = dest
        val remaining = if (h != null && d != null) h.distanceTo(d) else null
        return WalkMath.statusFacts(remaining, pace, WalkMath.nearestSpot(h, mapPlaces), destination)
    }

    // ---- the flow ----

    private suspend fun run(): GuideOutcome = coroutineScope {
        val watcher = launch { watchJourney() }
        try {
            Clinic.showGuide(
                GuideUi(
                    ticket = ticket,
                    destination = destination,
                    destinationNote = to.room?.let { to.name.takeIf { n -> !Names.same(n, it) } },
                    exit = isExit(),
                    from = fromName,
                    phase = GuidePhase.PICKUP,
                    pace = pace,
                ),
            )
            if (!pickup()) return@coroutineScope outcome ?: GuideOutcome.NOBODY_CAME
            askPace()
            walk(this)
        } finally {
            watcher.cancel()
        }
    }

    private var outcome: GuideOutcome? = null

    private suspend fun pickup(): Boolean {
        val at = fromName.ifBlank { "the pickup point" }
        NovaBrain.activity(RobotState.FETCHING, "Picking up ${ticketOr()} at $at")
        if (own != null) {
            patch(LegStatus.PICKUP, JourneyEvent.PICKUP_STARTED, "Patient asked Nova directly")
            patch(null, JourneyEvent.PATIENT_CONFIRMED, "Patient is with Nova")
            return true
        }
        val fromSpot = cmd.from?.spot?.trim().orEmpty()
        val there = fromSpot.isEmpty() || Names.same(Robot.position, fromSpot) || Robot.isAt(fromSpot, 1.0) == true
        patch(
            LegStatus.PICKUP, JourneyEvent.PICKUP_STARTED,
            if (there) "Nova is waiting at $at" else "Nova is on the way to $at",
        )
        if (!there) {
            setPhase(GuidePhase.TO_PICKUP)
            val o = drive(fromSpot, null)
            if (cancelled) {
                cancelledNow()
                return false
            }
            if (o is NavOutcome.Failed) {
                if (NovaBrain.helpActive()) return end(GuideOutcome.STOPPED)
                return failNav(o.why, "the pickup point")
            }
        }
        setPhase(GuidePhase.PICKUP)
        NovaBrain.activity(RobotState.FETCHING, "Waiting for ${ticketOr()} at $at")
        callTicket(first = true)
        val timeline = PickupTimeline()
        val startedAt = now()
        var how: String? = null
        while (how == null) {
            if (cancelled) {
                cancelledNow()
                return false
            }
            val elapsed = now() - startedAt
            when (timeline.next(elapsed)) {
                PickupStep.REMIND -> callTicket(first = false)
                PickupStep.TIMEOUT -> {
                    patch(LegStatus.FAILED, JourneyEvent.FAILED, "Nobody came")
                    Clinic.feed.novaDid(now(), "Nobody came for ticket $ticket")
                    setPhase(GuidePhase.NOBODY_CAME)
                    Clinic.flash(Mood.SORRY, 4_000)
                    delay(4_000)
                    return end(GuideOutcome.NOBODY_CAME)
                }
                PickupStep.WAIT -> Unit
            }
            how = withTimeoutOrNull(timeline.msUntilNext(now() - startedAt).coerceIn(200, 1_000)) { confirmed.await() }
        }
        patch(
            null, JourneyEvent.PATIENT_CONFIRMED,
            if (how == HOW_TAP) "Patient tapped That's me" else "Patient said the ticket number",
        )
        Clinic.flash(Mood.EXCITED)
        return true
    }

    private suspend fun callTicket(first: Boolean) {
        Clinic.speak(
            if (first) {
                "Call out ticket $ticket so the patient in the waiting area hears it, and ask them to please come to Nova. " +
                    "Just that, very short."
            } else {
                "Call ticket $ticket once more, friendly and short: they should please come to Nova."
            },
            callLang,
            "Ticket $ticket, please come to me.",
        )
    }

    private suspend fun askPace() {
        val staff = cmd.pace ?: Pace.NORMAL
        if (!PaceRules.shouldAsk(staff, cmd.wheelchair, own?.paceChosen == true)) return
        setPhase(GuidePhase.PACE)
        Clinic.speak(
            "Ask the patient in one short, friendly question whether you should walk normally or a bit slower.",
            lang,
            "Shall I walk normally or a bit slower?",
        )
        withTimeoutOrNull(PACE_ANSWER_MS) { paceAnswer.await() }?.let { setPace(it, announce = false) }
    }

    private suspend fun walk(scope: CoroutineScope): GuideOutcome {
        patch(LegStatus.WALKING, JourneyEvent.WALKING, "Walking at ${pace.label.lowercase()}")
        setPhase(GuidePhase.WALKING)
        NovaBrain.activity(RobotState.WALKING, Brain.guideGoal(ticket, destination))
        Clinic.feed.novaDid(now(), "Walking ticket $ticket to $destination")

        mapPlaces = Robot.mapPlaces()
        dest = WalkMath.find(to.spot, mapPlaces)?.point
        start = Robot.pose()
        here = start
        val tracker = scope.launch { track() }

        milestones += Milestone.START
        Clinic.speak(
            buildString {
                if (own != null && ticket.isNotBlank()) append("Tell them their ticket is $ticket. ")
                append("Ask the patient to please follow you to $destination. ")
                append("Say you walk at their pace and turn around now and then to check on them. Two short sentences at most.")
            },
            lang,
            "Please follow me to $destination.",
        )

        val clock = CheckBackClock()
        try {
            while (true) {
                if (cancelled) return cancelledNow()
                val o = Robot.goTo(
                    spot = to.spot,
                    speed = PaceTable.speed(pace),
                    stopWhen = { NovaBrain.helpActive() || cancelled },
                    interrupt = { pauseWanted || wantedPace != null || (clock.due() && !CheckBack.shouldSkip(progress)) },
                    onTick = { blocked ->
                        clock.tick(now(), blocked)
                        // close to the door: no turning around any more
                        if (clock.due() && CheckBack.shouldSkip(progress)) clock.reset()
                    },
                    onEvent = ::onNavEvent,
                )
                clock.pause()
                when (o) {
                    NavOutcome.Arrived -> return arrive()
                    is NavOutcome.Failed -> return when {
                        cancelled -> cancelledNow()
                        NovaBrain.helpActive() -> {
                            patch(LegStatus.FAILED, JourneyEvent.FAILED, "Stopped: urgent help was called")
                            result(GuideOutcome.STOPPED)
                        }
                        else -> {
                            failNav(o.why, destination)
                            GuideOutcome.FAILED
                        }
                    }
                    NavOutcome.Interrupted -> {
                        val newPace = wantedPace
                        when {
                            newPace != null -> {
                                wantedPace = null
                                setPace(newPace, announce = true)
                            }
                            pauseWanted -> pauseHere()?.let { return it }
                            clock.due() -> {
                                val result = checkBack(clock.obstacleWasReason())
                                clock.reset()
                                result?.let { return it }
                            }
                        }
                    }
                }
            }
        } finally {
            tracker.cancel()
        }
    }

    // position every POSE_EVERY_MS: progress line on the screen and the milestone lines
    private suspend fun track() = coroutineScope {
        while (isActive) {
            delay(POSE_EVERY_MS)
            val p = Robot.pose() ?: continue
            here = p
            val next = WalkMath.progress(start, p, dest, progress)
            if (next != progress) {
                progress = next
                Clinic.updateGuide { it.copy(progress = next) }
            }
            if (phase == GuidePhase.WALKING) {
                Milestones.due(progress, milestones)?.let { m ->
                    milestones += m
                    if (m == Milestone.ALMOST_THERE) milestones += Milestone.HALF_WAY
                    launch { sayMilestone(m) }
                }
            }
        }
    }

    private suspend fun sayMilestone(m: Milestone) {
        val task = when (m) {
            Milestone.HALF_WAY -> "Tell the patient behind you, short and cheerful, that you are half way to $destination."
            Milestone.ALMOST_THERE -> "Tell the patient behind you, short and warm, that you are almost at $destination."
            else -> return
        }
        Clinic.speak(task, lang, if (m == Milestone.HALF_WAY) "Half way there." else "Almost there.")
    }

    private suspend fun pauseHere(): GuideOutcome? {
        pauseWanted = false
        resumeWanted = false
        setPhase(GuidePhase.PAUSED)
        patch(null, JourneyEvent.WAITING_FOR_PATIENT, "Patient asked Nova to wait")
        Clinic.feed.novaDid(now(), "Waiting until the patient is ready")
        val until = now() + PAUSE_MAX_MS
        while (!resumeWanted && now() < until) {
            if (cancelled) return cancelledNow()
            delay(250)
        }
        val timedOut = !resumeWanted
        resumeWanted = false
        wantedPace?.let {
            wantedPace = null
            setPace(it, announce = true)
        }
        if (timedOut) return checkBack(obstacle = false)
        setPhase(GuidePhase.WALKING)
        return null
    }

    // turn around, look, react (docs/journeys.md 4.3). null = walk on
    private suspend fun checkBack(obstacle: Boolean): GuideOutcome? {
        setPhase(GuidePhase.CHECKING)
        NovaBrain.activity(RobotState.WALKING, "Checking on ${ticketOr()}")
        Clinic.feed.novaDid(now(), if (obstacle) "Checking on the patient after an obstacle" else "Turning around to check on the patient")
        delay(Robot.NAV_RELEASE_MS)
        // the chassis may still be held right after stopNavigation: one more try
        val turned = Robot.turn(HALF_TURN, left = true) || run {
            delay(TURN_RETRY_MS)
            Robot.turn(HALF_TURN, left = true)
        }
        val samples = look()
        // not turned = nova looks the wrong way: ask out loud instead of raising a false alarm
        val seen = when {
            now() - presenceAt < PRESENCE_MS -> Proximity.CLOSE
            !turned -> Proximity.UNKNOWN
            else -> CheckBack.classify(samples)
        }
        val events = CheckBack.events(seen, pace)
        val result: GuideOutcome? = when (seen) {
            Proximity.CLOSE, Proximity.UNKNOWN -> {
                Clinic.speak(
                    if (CheckBack.shouldSkip(progress) || (progress ?: 0f) >= Milestones.ALMOST) {
                        "Ask the patient behind you in one short, warm question if all is good, and say you are almost there."
                    } else {
                        "Ask the patient behind you in one short, warm question: is all good, shall you slow down?"
                    },
                    lang,
                    "All good? Shall I slow down?",
                )
                patch(
                    null, events.first(),
                    if (seen == Proximity.UNKNOWN) "Nova could not see behind, asked out loud" else "Patient close, all fine",
                )
                answerWindow()
                null
            }
            Proximity.FAR -> waitForFar(events)
            Proximity.NOBODY -> lost()
        }
        if (result != null) return result
        if (cancelled) return cancelledNow()
        if (turned) Robot.turn(HALF_TURN, left = false)
        wantedPace?.let {
            wantedPace = null
            setPace(it, announce = false)
        }
        if (pauseWanted) return pauseHere()
        setPhase(GuidePhase.WALKING)
        NovaBrain.activity(RobotState.WALKING, Brain.guideGoal(ticket, destination))
        return null
    }

    // a few looks over about 1.5 s: the vision needs a moment after the turn
    private suspend fun look(): List<List<Double>?> {
        val out = ArrayList<List<Double>?>()
        repeat(LOOK_SAMPLES) {
            delay(LOOK_EVERY_MS)
            out += Robot.peopleDistances()
        }
        return out
    }

    // after "all good?": time for an answer (SET_PACE, PAUSE_WALK or just talk), shorter when nothing comes
    private suspend fun answerWindow() {
        val until = now() + ANSWER_WINDOW_MS
        while (now() < until && wantedPace == null && !pauseWanted) delay(200)
        // someone is still answering: give the planner its time
        val extra = now() + ANSWER_EXTRA_MS
        while (now() < extra && wantedPace == null && !pauseWanted && NovaBrain.conversationActive()) delay(200)
    }

    private suspend fun waitForFar(events: List<String>): GuideOutcome? {
        setPhase(GuidePhase.WAITING)
        NovaBrain.activity(RobotState.WALKING, "Waiting for ${ticketOr()}")
        Clinic.feed.novaDid(now(), "Waiting for the patient to catch up")
        Clinic.speak("Tell the patient behind you kindly that you will wait for them. One short sentence.", lang, "I'll wait for you.")
        patch(null, JourneyEvent.WAITING_FOR_PATIENT, "Patient 3-6 m behind, Nova waits")
        val wait = FarWait(now())
        var step = FarStep.WAIT
        while (step == FarStep.WAIT) {
            if (cancelled) return cancelledNow()
            delay(FAR_LOOK_MS)
            step = if (now() - presenceAt < PRESENCE_MS) FarStep.CAME else wait.next(now(), CheckBack.nearest(listOf(Robot.peopleDistances())))
        }
        if (step == FarStep.GONE) return lost()
        if (JourneyEvent.SLOWED_DOWN in events) {
            setPace(pace.slower(), announce = false, detail = "Patient fell behind, pace lowered")
        }
        Clinic.speak(
            "Thank the patient warmly and say you will walk a little slower now. One short sentence.",
            lang,
            "Thank you! I'll walk a little slower.",
        )
        return null
    }

    // nobody behind: call out twice, tell the team after 30 s, give up 60 s later (LostTimeline)
    private suspend fun lost(): GuideOutcome? {
        setPhase(GuidePhase.LOOKING)
        NovaBrain.activity(RobotState.WALKING, "Looking for ${ticketOr()}")
        Clinic.feed.novaDid(now(), "Nobody behind Nova, calling the patient")
        val timeline = LostTimeline()
        val startedAt = now()
        while (true) {
            if (cancelled) return cancelledNow()
            when (timeline.next(now() - startedAt)) {
                LostStep.CALL -> Clinic.speak(
                    "Call out for the patient with ticket $ticket: you are right here and they should please come to you. One short sentence.",
                    callLang,
                    "Ticket $ticket, I'm over here.",
                )
                LostStep.REPORT -> {
                    val near = WalkMath.nearestSpot(here, mapPlaces) ?: fromName.ifBlank { null } ?: to.name
                    patch(null, JourneyEvent.LOST_PATIENT, "No one behind Nova after calling twice")
                    Clinic.net(quiet = true) {
                        Backend.help("Lost the patient with ticket $ticket near $near", "normal", journeyId, ticket)
                    }
                    Clinic.feed.novaDid(now(), "Lost sight of ticket $ticket, asked the team for help")
                    setPhase(GuidePhase.LOST)
                    Clinic.flash(Mood.SORRY, 3_000)
                }
                LostStep.GIVE_UP -> {
                    patch(LegStatus.FAILED, JourneyEvent.FAILED, "Lost the patient")
                    Clinic.speak(
                        "Say briefly that you will go back to the reception now and that the team will help.",
                        lang,
                        "I'm going back to the reception. The team will help.",
                    )
                    return result(GuideOutcome.LOST)
                }
                LostStep.WATCH -> Unit
            }
            delay(FAR_LOOK_MS)
            val near = CheckBack.nearest(listOf(Robot.peopleDistances()))
            val found = now() - presenceAt < PRESENCE_MS || (near != null && near <= CheckBack.FAR_M)
            if (found) {
                patch(null, JourneyEvent.CHECK_BACK, if (timeline.hasReported) "Patient found again after the help call" else "Patient came back after Nova called")
                Clinic.feed.novaDid(now(), "Found the patient again")
                setPace(pace.slower(), announce = false, detail = "Patient fell behind, pace lowered")
                Clinic.flash(Mood.RELIEVED, 3_000)
                Clinic.speak(
                    "Say you are glad to see them again and that you will walk a little slower now. One short sentence.",
                    lang,
                    "There you are! I'll walk a little slower.",
                )
                return null
            }
        }
    }

    private suspend fun arrive(): GuideOutcome {
        patch(LegStatus.ARRIVED, JourneyEvent.ARRIVED, "Arrived at $destination")
        setPhase(GuidePhase.ARRIVED)
        Clinic.updateGuide { it.copy(progress = 1f) }
        NovaBrain.activity(RobotState.WALKING, "Arrived with ${ticketOr()} at $destination")
        Clinic.feed.novaDid(now(), "Arrived at $destination")
        Clinic.flash(Mood.EXCITED, 3_000)
        if (isExit()) {
            Clinic.speak(
                "You arrived at the exit with the patient. Say goodbye warmly and wish them to get well soon. One or two short sentences.",
                lang,
                "Goodbye and get well soon!",
            )
        } else {
            Clinic.speak(
                "You arrived at $destination with the patient. Tell them: this is $destination, please take a seat, " +
                    "the team knows they are here. Two short sentences at most.",
                lang,
                "This is $destination. Please take a seat, the team knows you're here.",
            )
        }
        // optional thumbs up / down on the screen, only the anonymous count goes out
        val until = now() + FEEDBACK_MS
        while (feedback == null && now() < until && !cancelled) delay(250)
        feedback?.let { f ->
            Clinic.updateGuide { it.copy(feedback = f) }
            patch(null, JourneyEvent.FEEDBACK, f)
            delay(1_500)
        }
        return GuideOutcome.ARRIVED
    }

    private suspend fun failNav(why: NavFailure, where: String): Boolean {
        val detail = "Nova could not reach $where: ${why.screen}"
        patch(LegStatus.FAILED, JourneyEvent.FAILED, detail)
        Clinic.net(quiet = true) {
            Backend.help("Nova could not walk ticket $ticket to $where (${why.screen}). Please help.", "normal", journeyId, ticket)
        }
        NovaBrain.failed(detail)
        Clinic.updateGuide { it.copy(phase = GuidePhase.FAILED, note = why.screen) }
        phase = GuidePhase.FAILED
        Clinic.flash(Mood.SORRY, 4_000)
        Clinic.speak(
            "Apologize: you cannot get to $where right now because ${why.spoken}. A team member will come to help. Two short sentences.",
            lang,
            "${why.screen}. A team member will help you.",
        )
        delay(3_000)
        return end(GuideOutcome.FAILED)
    }

    private suspend fun cancelledNow(): GuideOutcome {
        setPhase(GuidePhase.CANCELLED)
        Clinic.speak("Say briefly that the team has ended this walk and a team member will help.", lang, "The team ended this walk.")
        delay(2_000)
        return result(GuideOutcome.CANCELLED)
    }

    // staff may cancel or close the journey while nova is on it
    private suspend fun watchJourney() {
        while (true) {
            delay(JOURNEY_CHECK_MS)
            val j = Clinic.net(quiet = true) { Backend.journey(journeyId) }.getOrNull() ?: continue
            if (JourneyStatus.closed(j.status)) cancelled = true
        }
    }

    // ---- helpers ----

    // for the steps that answer "go on?" with a boolean
    private fun end(o: GuideOutcome): Boolean {
        outcome = o
        return false
    }

    private fun result(o: GuideOutcome): GuideOutcome {
        outcome = o
        return o
    }

    private fun ticketOr() = ticket.ifBlank { "a patient" }

    private fun isExit(): Boolean =
        (to.kind ?: Clinic.placeForSpot(to.spot)?.kind) == PlaceKind.EXIT

    private fun setPhase(p: GuidePhase) {
        phase = p
        Clinic.updateGuide { it.copy(phase = p, pace = pace) }
    }

    private suspend fun setPace(p: Pace, announce: Boolean, detail: String? = null) {
        val old = pace
        if (p == old) return
        pace = p
        Clinic.updateGuide { it.copy(pace = p) }
        if (p.isSlowerThan(old)) {
            patch(null, JourneyEvent.SLOWED_DOWN, detail ?: "Pace set to ${p.label.lowercase()}")
        } else {
            patch(null, JourneyEvent.WALKING, "Pace set to ${p.label.lowercase()}")
        }
        if (announce) Clinic.feed.novaDid(now(), "Walking at ${p.label.lowercase()} now")
    }

    private suspend fun drive(spot: String, speed: Speed?): NavOutcome {
        Clinic.setWalking(true)
        try {
            return Robot.goTo(spot, speed, stopWhen = { NovaBrain.helpActive() || cancelled }, onEvent = ::onNavEvent)
        } finally {
            Clinic.setWalking(false)
        }
    }

    // blocked: ask people in front to let nova pass, at most every OBSTACLE_TALK_EVERY_MS
    private fun onNavEvent(ev: NavEvent) {
        Clinic.onNavEvent(ev, lang)
    }

    private suspend fun patch(status: String?, event: String?, detail: String?) {
        if (journeyId.isEmpty()) return
        Clinic.net(quiet = true) {
            Backend.patchJourney(journeyId, legId.ifEmpty { null }, status, event, detail)
        }.onFailure { Log.w(TAG, "patch failed: ${Backend.friendly(it)}") }
    }

    companion object {
        private const val TAG = "NovaGuide"
        const val HOW_TAP = "tap"
        const val HOW_VOICE = "voice"
        private const val HALF_TURN = 180f
        private const val TURN_RETRY_MS = 800L
        private const val LOOK_SAMPLES = 4
        private const val LOOK_EVERY_MS = 400L
        private const val FAR_LOOK_MS = 500L
        private const val PRESENCE_MS = 15_000L
        private const val PACE_ANSWER_MS = 12_000L
        private const val ANSWER_WINDOW_MS = 4_000L
        private const val ANSWER_EXTRA_MS = 6_000L
        private const val PAUSE_MAX_MS = 2 * 60_000L
        private const val FEEDBACK_MS = 15_000L
        private const val POSE_EVERY_MS = 1_500L
        private const val JOURNEY_CHECK_MS = 10_000L

        // the tags the ticket call is said in (local.properties robot.callLanguages, default "de,en")
        val callTags: List<String> by lazy { Lang.tagList(BuildConfig.CALL_LANGUAGES) }

        // the guide that runs right now, for the actions and the screen
        @Volatile var current: Guide? = null
            private set

        // runs one guide command to its end. the command is closed (done) on every path.
        suspend fun run(cmd: RobotCommand, own: OwnRequest?): GuideOutcome {
            if (cmd.to == null || cmd.journeyId.isNullOrBlank() || cmd.legId.isNullOrBlank()) {
                Log.w(TAG, "guide command without journey, leg or destination")
                withContext(NonCancellable) { Clinic.net(quiet = true) { Backend.commandDone(cmd.id) } }
                return GuideOutcome.FAILED
            }
            val g = Guide(cmd, own)
            current = g
            Clinic.guideStarted()
            return try {
                g.run()
            } finally {
                current = null
                withContext(NonCancellable) {
                    Clinic.net(quiet = true) { Backend.commandDone(cmd.id) }
                    Clinic.guideEnded()
                }
            }
        }
    }
}
