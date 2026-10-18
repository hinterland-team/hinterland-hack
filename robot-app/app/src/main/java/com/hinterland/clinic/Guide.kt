package com.hinterland.clinic

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.math.abs

// someone asked otto directly (GUIDE_TO_PLACE, CHECK_IN_FOR, the self check-in screen): the person is
// right here, so there is no pickup. paceChosen = they picked the pace on the screen already.
// checkIn = it was a check-in (otto says "you're checked in" with the ticket)
data class OwnRequest(val paceChosen: Boolean = false, val sample: String? = null, val checkIn: Boolean = false)

enum class GuideOutcome { ARRIVED, NOBODY_CAME, LOST, FAILED, CANCELLED, STOPPED }

// what the demo (Demo.kt, docs/demo-mode.md) changes in a walk: the script's parts instead of the walk's own
// lines (no llm at all), its walk as a fixed routine (field test b16: nothing guided by where the radar thinks
// the patient is), the operator's hold and Next
interface DemoWalk {
    // the patient's side on the demo walk (the script's walkSide, not walk.walkSide)
    val side: WalkSide

    // firstCheck: the turn towards the walk side and back, 0 = no turn
    val glanceDeg: Double

    // when firstCheck and fellBack are due (driving time), and which ones were played
    val plan: DemoWalkPlan

    // plays a part to its end (otto's lines, the patient's waits, wait_close)
    suspend fun play(part: String)

    // a part said while the walk goes on (obstacle)
    fun playLater(part: String)

    // the operator holds the demo: otto stands until resume
    fun held(): Boolean

    // the operator pressed Next during the walk: a walk part waits to be played
    fun nextWanted(): Boolean

    // takes that part, null = none
    fun takeNext(): String?
}

// one `guide` command (docs/journeys.md 4 and 6): pickup, pace, walking with check-backs, arrival.
// the decisions (check-back schedule, the walk-beside watch, short stops, where the patient is, the
// talk window, what to do per distance, blocked ways and detours, timelines, speeds) are plain kotlin in
// Walk.kt, Stops.kt, PatientTrack.kt, Detours.kt, Lidar.kt, Pickup.kt and Pace.kt; this class does the
// robot, the backend and the words. the walk's lines are made once per walk (WalkLines.kt).
// legs and events go to PATCH /api/journeys/:id with the ticket only, never a name.
// demo = the demo's walk (DemoWalk): its lines, its fixed routine, no llm; without a journey it is a local walk
class Guide private constructor(val cmd: RobotCommand, private val own: OwnRequest?, private val demo: DemoWalk?) {

    // the live settings as they were when this walk started (docs/robot-setup.md 3): one walk, one set
    private val s: Settings = Config.current

    val ticket: String = cmd.ticket.orEmpty()
    val journeyId: String = cmd.journeyId.orEmpty()
    private val legId: String = cmd.legId.orEmpty()
    private val to: Stop = cmd.to ?: Stop("", "")
    private val fromName: String = cmd.from?.name.orEmpty()
    val destination: String = to.label

    // the phone companion (QR on the pickup screen); null when the server sent no walk code
    private val walkUrl: String? = WalkLink.url(cmd.walkUrl, cmd.walkCode, BuildConfig.WALK_BASE_URL.ifBlank { Servers.current })

    // signals from the actions, the screen and the phone (any thread)
    private val confirmed = CompletableDeferred<String>()
    private val paceAnswer = CompletableDeferred<Pace>()
    @Volatile private var wantedPace: Pace? = null
    @Volatile private var pauseWanted = false
    @Volatile private var resumeWanted = false
    @Volatile private var allGood = false
    @Volatile private var presenceAt = 0L
    @Volatile private var feedback: String? = null
    @Volatile private var cancelled = false
    @Volatile private var sample: String? = own?.sample

    @Volatile var pace: Pace = PaceRules.start(cmd.pace ?: Pace.NORMAL, cmd.wheelchair)
        private set
    @Volatile var phase: GuidePhase = GuidePhase.PICKUP
        private set

    // the map spot otto drives to right now (live map), null while standing
    @Volatile var targetSpot: String? = null
        private set

    // map coordinates for progress, "how far is it?" and "near <spot>"
    @Volatile private var mapPlaces: List<MapPlace> = emptyList()
    @Volatile private var start: Point? = null
    @Volatile private var here: Point? = null
    @Volatile private var dest: Point? = null
    @Volatile private var progress: Float? = null
    private val milestones = mutableSetOf<Milestone>()
    // the patient as the camera saw them at the last check-back (map frame) and when
    @Volatile private var cameraFix: Pair<Point, Long>? = null
    @Volatile private var talkIds = 0L

    // v3.3 (docs/journeys.md 6.7): the side the patient walks on (the demo: its script's), the walk's ready
    // lines, where the patient is
    private val side = demo?.side ?: s.walkSide
    private val lines = WalkLines(LineContext(ticket, destination, side, arrival(), own != null, own?.checkIn == true))
    private val track = PatientTrack()
    // the ticket was said with the pace question (asked at the robot): the start line does not say it again
    @Volatile private var ticketTold = false
    @Volatile private var runScope: CoroutineScope? = null
    @Volatile private var linesJob: Job? = null
    @Volatile private var linesAsked = false
    @Volatile private var linesFromWords = false
    @Volatile private var linesRedone = false
    // the check-backs of this walk
    private var checks = 0
    private var lastCheckDistance: Double? = null
    private var fellBack = false
    @Volatile private var sideLost = false
    @Volatile private var cameraLogged = false
    // meters driven since the last check: a patient a few meters back after otto stood at an obstacle did not
    // fall behind. legDriven = meters driven in this leg (the blocked count starts again after a few)
    @Volatile private var drivenSinceCheck = 0.0
    @Volatile private var legDriven = 0.0
    @Volatile private var drivenFrom: Point? = null
    // a blocked way (Detours.kt): the count of this leg across restarts, the detour steps, spots tried
    private val legBlock = LegBlock(limitMs = s.blockedS * 1000L)
    private val detours = DetourSteps(maxWaypoints = s.maxWaypoints, backOff = s.backOff)
    private val tried = mutableSetOf<String>()
    private var helpCalled = false
    private var blockedSince = 0L
    // the demo: its detour part was said in this blocked stretch
    @Volatile private var demoWaySaid = false
    // legDriven at the last detour step: once otto drove on a few meters, the steps start again
    @Volatile private var jamDriven: Double? = null

    private fun now() = System.currentTimeMillis()

    // the patient's language: their own words once they spoke, else the call languages (voice.callLanguages)
    private val callTags: List<String> get() = s.callLanguages

    private val lang: Lang
        get() = sample?.let { Lang(null, it) } ?: Lang(callTags.firstOrNull())

    // for lines other parts say during this walk (walk_control)
    val speechLang: Lang get() = lang

    private val callLang: Lang
        get() = if (sample != null || callTags.isEmpty()) lang else Lang(null, tags = callTags)

    // a line of this walk still fits: the walk runs and was not ended (SpeechTurns drops it otherwise)
    private fun live(): Boolean = current === this && !cancelled

    private fun liveIn(vararg phases: GuidePhase): () -> Boolean = { live() && phase in phases }

    // ---- signals ----

    fun heard(text: String) {
        if (text.isBlank()) return
        sample = text
        // the walk's lines were written before the patient said a word: once more, in their language
        if (linesAsked && !linesFromWords && !linesRedone) {
            linesRedone = true
            requestLines("the patient's own words")
        }
    }

    // true when the ticket was the one otto calls
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
        allGood = true
    }

    // "All good" on the talk screen
    fun allGood() {
        allGood = true
    }

    fun present() {
        presenceAt = now()
    }

    // "I'm here" was tapped since t (the demo's wait_close)
    fun presentSince(t: Long): Boolean = presenceAt >= t

    fun rate(up: Boolean) {
        if (feedback == null) feedback = if (up) "up" else "down"
    }

    // walk_control from the phone page or the dashboard (6.4). true when it applied to this walk
    fun control(c: WalkControl): Boolean = when (c) {
        WalkControl.WAIT -> pause()
        WalkControl.RESUME -> {
            goOn()
            phase == GuidePhase.PAUSED || phase == GuidePhase.CHECKING
        }
        WalkControl.SLOWER -> pace.slower().let { p -> if (p != pace) choosePace(p); p != pace }
        WalkControl.FASTER -> pace.faster().let { p -> if (p != pace) choosePace(p); p != pace }
    }

    // the walk's ready line in the patient's language (english until the llm's lines are there)
    fun line(key: LineKey): String = lines.text(key)

    // the answer to "slower" / "faster" / "go on", from the pace now
    fun paceLine(p: Pace): String = line(
        when {
            p.isSlowerThan(pace) -> LineKey.SLOWER_OK
            pace.isSlowerThan(p) -> LineKey.FASTER_OK
            else -> LineKey.GO_ON
        },
    )

    val walking get() = phase !in setOf(GuidePhase.PICKUP, GuidePhase.TO_PICKUP, GuidePhase.PACE, GuidePhase.ARRIVED)

    // WALK_STATUS: plain facts for the llm
    fun statusFacts(): String {
        val h = here
        val d = dest
        val remaining = if (h != null && d != null) h.distanceTo(d) else null
        return WalkMath.statusFacts(remaining, pace, WalkMath.nearestSpot(h, mapPlaces), destination)
    }

    // the patient's estimated position for the live map: radar now, else the camera at the last check-back
    fun followerForMap(): Pair<Point, String>? {
        val now = now()
        Radar.follower?.takeIf { now - it.at <= FOLLOWER_FRESH_MS }?.map?.let { return it to "lidar" }
        return cameraFix?.takeIf { now - it.second <= CAMERA_FIX_KEEP_MS }?.first?.let { it to "camera" }
    }

    // ---- the flow ----

    private suspend fun run(): GuideOutcome = coroutineScope {
        runScope = this
        val watcher = launch { watchJourney() }
        // the demo has no llm lines: nobody waits for them (its own lines come from the script)
        if (demo != null) lines.failed()
        try {
            Clinic.showGuide(
                GuideUi(
                    ticket = ticket,
                    destination = destination,
                    destinationNote = to.room?.let { to.name.takeIf { n -> !Names.same(n, it) } },
                    exit = isExit(),
                    waiting = isWaitingRoom(),
                    from = fromName,
                    phase = GuidePhase.PICKUP,
                    pace = pace,
                    walkUrl = walkUrl,
                    side = side,
                    checkedIn = demo != null && own?.checkIn == true,
                ),
            )
            RobotLog.i(LogSource.BRAIN, "Guide ${ticketOr()} to $destination" + if (own != null) " (asked at the robot)" else "")
            // the person is right here: their lines now
            if (own != null && demo == null) requestLines("asked at the robot")
            if (!pickup()) return@coroutineScope outcome ?: GuideOutcome.NOBODY_CAME
            askPace()
            walk(this)
        } finally {
            watcher.cancel()
            linesJob?.cancel()
            Radar.stop()
            // where the patient was is only kept for this walk
            track.clear()
            runScope = null
        }
    }

    private var outcome: GuideOutcome? = null

    private suspend fun pickup(): Boolean {
        val at = fromName.ifBlank { "the pickup point" }
        OttoBrain.activity(RobotState.FETCHING, "Picking up ${ticketOr()} at $at")
        if (own != null) {
            patch(LegStatus.PICKUP, JourneyEvent.PICKUP_STARTED, "Patient asked Otto directly")
            patch(null, JourneyEvent.PATIENT_CONFIRMED, "Patient is with Otto")
            rememberStart("asked at the robot")
            return true
        }
        val fromSpot = cmd.from?.spot?.trim().orEmpty()
        val there = fromSpot.isEmpty() || Names.same(Robot.position, fromSpot) || Robot.isAt(fromSpot, 1.0) == true
        patch(
            LegStatus.PICKUP, JourneyEvent.PICKUP_STARTED,
            if (there) "Otto is waiting at $at" else "Otto is on the way to $at",
        )
        if (!there) {
            setPhase(GuidePhase.TO_PICKUP)
            val o = drive(fromSpot, null)
            if (cancelled) {
                cancelledNow()
                return false
            }
            if (o is NavOutcome.Failed) {
                if (OttoBrain.helpActive()) return end(GuideOutcome.STOPPED)
                return failNav(o.why, "the pickup point")
            }
        }
        setPhase(GuidePhase.PICKUP)
        OttoBrain.activity(RobotState.FETCHING, "Waiting for ${ticketOr()} at $at")
        val timeline = PickupTimeline(remindEveryMs = s.pickupRemindS * 1000L, timeoutMs = s.pickupTimeoutS * 1000L)
        // the call runs on its own: a tap or the ticket said meanwhile ends the waiting below at once.
        // after it, while the patient comes over, the walk's lines are written (one llm call)
        runScope?.launch {
            callTicket(first = true)
            requestLines("pickup")
        }
        val startedAt = now()
        var how: String? = null
        while (how == null) {
            if (cancelled) {
                cancelledNow()
                return false
            }
            val elapsed = now() - startedAt
            when (timeline.next(elapsed)) {
                PickupStep.REMIND -> runScope?.launch { callTicket(first = false) }
                PickupStep.TIMEOUT -> {
                    patch(LegStatus.FAILED, JourneyEvent.FAILED, "Nobody came")
                    Clinic.feed.ottoDid(now(), "Nobody came for ticket $ticket")
                    RobotLog.i(LogSource.BRAIN, "Nobody came for ${ticketOr()} at $at")
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
        rememberStart(if (how == HOW_TAP) "tapped That's me" else "said the ticket")
        return true
    }

    // dropped before it is said when the patient confirmed meanwhile (no "please come" to someone who came)
    private suspend fun callTicket(first: Boolean) {
        Clinic.speak(
            if (first) {
                "Call out ticket $ticket so the patient in the waiting area hears it, and ask them to please come to Otto. " +
                    "Just that, very short."
            } else {
                "Call ticket $ticket once more, friendly and short: they should please come to Otto."
            },
            callLang,
            "Ticket $ticket, please come to me.",
            kind = SpeechKind.CALL,
            fits = { live() && waitingForPickup && !confirmed.isCompleted },
        )
    }

    // the walk's lines in one llm call (6.7), in the background. a second call only when the patient
    // speaks for the first time after the first one was made in the default language
    private fun requestLines(why: String) {
        val scope = runScope ?: return
        val l = lang
        val fromWords = sample != null
        linesAsked = true
        linesFromWords = fromWords
        linesJob?.cancel()
        linesJob = scope.launch {
            val t0 = now()
            val raw = Voice.write(LineSet.system(s.styleNote), LineSet.prompt(lines.context, l), LineSet.MAX_TOKENS, LINES_TIMEOUT_MS)
            val made = LineSet.parse(raw)
            val took = (now() - t0) / 1000.0
            if (made.size >= LineSet.MIN_LINES) {
                lines.accept(made, fromWords)
                RobotLog.i(
                    LogSource.VOICE,
                    "Walk lines ready after %.1f s (%s): %d of %d in the patient's language%s".format(
                        Locale.ROOT, took, why, made.size, LineKey.entries.size, if (fromWords) ", from their own words" else "",
                    ),
                )
            } else {
                lines.failed()
                RobotLog.w(
                    LogSource.VOICE,
                    "Walk lines not made after %.1f s (%s, %d usable): ".format(Locale.ROOT, took, why, made.size) +
                        if (lines.ready) "the earlier ones stay" else "english lines for this walk",
                )
            }
        }
    }

    // a line of the walk: the ready one in the patient's language, when it is its turn (SpeechTurns). several
    // keys are said as one line (the ticket with the next question). at the start of a walk the lines may still
    // be in the making: up to waitMs for them, then the llm for this one line (llmTask), with the english line
    // as its fallback
    private suspend fun say(
        keys: List<LineKey>,
        kind: SpeechKind,
        waitMs: Long = 0,
        llmTask: String? = null,
        question: Boolean = false,
        fits: () -> Boolean = { live() },
    ) {
        if (!lines.ready && waitMs > 0) lines.await(waitMs)
        if (lines.ready || llmTask == null) {
            keys.filter { !lines.localized(it) }.takeIf { it.isNotEmpty() }?.let { k ->
                RobotLog.d(LogSource.VOICE, "Ready line ${k.joinToString { it.name.lowercase(Locale.ROOT) }} in english: the walk's lines are not there")
            }
            Clinic.sayLine(keys.joinToString(" ") { lines.text(it) }, kind, question = question, fits = fits)
        } else {
            Clinic.speak(llmTask, lang, keys.joinToString(" ") { LineSet.english(it, lines.context) }, kind = kind, question = question, fits = fits)
        }
    }

    private suspend fun say(key: LineKey, kind: SpeechKind, fits: () -> Boolean = { live() }) = say(listOf(key), kind, fits = fits)

    // the same while otto turns or drives on
    private fun sayLater(key: LineKey, kind: SpeechKind = SpeechKind.WALK, fits: () -> Boolean = { live() }) {
        Clinic.sayLineLater(lines.text(key), kind, fits)
    }

    // at "That's me" (or when the walk starts at the robot): where the patient stands, from the radar in
    // front and the camera. only a position, in memory for this walk
    private suspend fun rememberStart(how: String) {
        Radar.start()
        val since = now()
        withTimeoutOrNull(START_LOOK_MS) { while (!Radar.scanSince(since)) delay(100) }
        val m = PatientMatch.atStart(Robot.people().orEmpty(), Radar.aheadPoints())
        if (m == null) {
            RobotLog.i(LogSource.VISION, "Patient at the start ($how): not seen, the first look goes to otto's ${side.wire} side")
            return
        }
        val pose = Robot.latestPose()
        track.noteStart(m.point, pose, now())
        // the ticket lock starts from this spot (positions only, in memory)
        Radar.lockStart(pose?.let { ScanMath.toMap(m.point, it) })
        RobotLog.i(
            LogSource.VISION,
            "Patient at the start ($how): %.1f m, %+.0f deg (%s)".format(Locale.ROOT, m.distance, Bearing.of(m.point), m.source.wire),
        )
    }

    // asked at the robot: the ticket (and "you're checked in") is said in one line with the question, then otto
    // waits for the answer (voice.answerWaitS); a spoken answer gets a moment more for the agent's SET_PACE
    private suspend fun askPace() {
        val staff = cmd.pace ?: Pace.NORMAL
        if (!PaceRules.shouldAsk(staff, cmd.wheelchair, own?.paceChosen == true)) return
        setPhase(GuidePhase.PACE)
        val withTicket = own != null
        val askedAt = now()
        say(
            if (withTicket) listOf(LineKey.TICKET_IS, LineKey.PACE_QUESTION) else listOf(LineKey.PACE_QUESTION),
            SpeechKind.QUESTION, waitMs = FIRST_LINE_WAIT_MS,
            llmTask = (if (withTicket) LineSet.task(LineKey.TICKET_IS, lines.context) + " Then: " else "") +
                "Ask the patient in one short, friendly question whether you should walk normally or a bit slower.",
            question = true,
            fits = liveIn(GuidePhase.PACE),
        )
        if (withTicket) ticketTold = true
        val answer = withTimeoutOrNull(Settings.ms(s.answerWaitS)) { paceAnswer.await() }
            ?: if (Clinic.lastVoiceAt() >= askedAt) withTimeoutOrNull(ACTION_GRACE_MS) { paceAnswer.await() } else null
        answer?.let { setPace(it, announce = false) }
        Speech.answered(if (answer != null) "pace ${answer.wire}" else "no answer, the walk starts")
    }

    private fun startTask(): String = buildString {
        if (own != null && !ticketTold) append(LineSet.task(LineKey.TICKET_IS, lines.context)).append(" ")
        append("Ask the patient to walk next to you on your ${side.wire} side, a little behind you, so you can see them. ")
        append("Say you are going to $destination together. Two short sentences at most.")
    }

    private suspend fun walk(scope: CoroutineScope): GuideOutcome {
        patch(LegStatus.WALKING, JourneyEvent.WALKING, "Walking at ${pace.label.lowercase()}")
        setPhase(GuidePhase.WALKING)
        OttoBrain.activity(RobotState.WALKING, Brain.guideGoal(ticket, destination))
        Clinic.feed.ottoDid(now(), "Walking ticket $ticket to $destination")

        mapPlaces = Robot.mapPlaces()
        dest = WalkMath.find(to.spot, mapPlaces)?.point
        start = Robot.pose()
        here = start
        drivenFrom = start
        Radar.start()
        val tracker = scope.launch { track() }

        milestones += Milestone.START
        // walk beside otto (6.7): the radar sees there, the back is blind. asked at the robot and no pace question:
        // the ticket comes in the same line
        val startKeys = if (own != null && !ticketTold) listOf(LineKey.TICKET_IS, LineKey.START) else listOf(LineKey.START)
        ticketTold = true
        // the demo said its start in the reception part
        if (demo == null) say(startKeys, SpeechKind.WALK, waitMs = FIRST_LINE_WAIT_MS, llmTask = startTask(), fits = liveIn(GuidePhase.WALKING))

        val schedule = CheckSchedule(firstMs = s.firstCheckS * 1000L, minMs = s.checkMinS * 1000L, maxMs = s.checkMaxS * 1000L)
        val watch = SideWatch(lostMs = s.besideLostS * 1000L)
        watch.start(now())
        RobotLog.i(
            LogSource.BRAIN,
            "Walk with ${ticketOr()} to $destination started, the patient walks on otto's ${side.wire}, " +
                "first check-back in ${schedule.nextInMs() / 1000} s",
        )
        demo?.let { d ->
            RobotLog.i(
                LogSource.APP,
                "Demo walk: a fixed routine, firstCheck after ${s.firstCheckS} s of driving (glance %.0f deg to the %s), fellBack %.0f s of driving after it"
                    .format(Locale.ROOT, d.glanceDeg, side.wire, d.plan.fellBackAfterMs / 1000.0),
            )
        }
        // the way: straight to the destination, or over a waypoint when it is blocked (detour steps)
        var target = to.spot
        try {
            while (true) {
                if (cancelled) return cancelledNow()
                targetSpot = target
                val targetPoint = WalkMath.find(target, mapPlaces)?.point
                val o = Robot.goTo(
                    spot = target,
                    speed = PaceTable.speed(pace, s),
                    stopWhen = { OttoBrain.helpActive() || cancelled },
                    // no check-back while otto stands at an obstacle with the patient: it comes once he drives on.
                    // the demo: no check-backs of the schedule, its own walk parts, the operator's hold and Next
                    interrupt = {
                        val d = demo
                        pauseWanted || wantedPace != null || if (d == null) {
                            schedule.due(now()) != null && !legBlock.isBlocked
                        } else {
                            d.held() || d.nextWanted() || (d.plan.due() != null && !legBlock.isBlocked) || (legBlock.isBlocked && atTheDoor())
                        }
                    },
                    onTick = { blocked -> tick(blocked, schedule, watch, scope, targetPoint) },
                    onEvent = ::onNavEvent,
                    way = legBlock,
                )
                targetSpot = null
                schedule.pause()
                demo?.plan?.pause()
                when (o) {
                    NavOutcome.Arrived -> {
                        if (target == to.spot) return arrive()
                        // at the waypoint: on to the destination, with a fresh (short) blocked budget
                        RobotLog.i(LogSource.NAV, "Waypoint $target reached, on to ${to.spot}")
                        target = to.spot
                        legBlock.nextAttempt(now(), DETOUR_LIMIT_MS, DETOUR_ASKS)
                        Clinic.updateGuide { it.copy(detour = false) }
                    }
                    is NavOutcome.Failed -> when {
                        cancelled -> return cancelledNow()
                        OttoBrain.helpActive() -> {
                            patch(LegStatus.FAILED, JourneyEvent.FAILED, "Stopped: urgent help was called")
                            return result(GuideOutcome.STOPPED)
                        }
                        // the demo: blocked right at the destination (people at the doctor's door) is arrived
                        demo != null && (o.why == NavFailure.BLOCKED || o.why == NavFailure.NO_PATH) && atTheDoor() -> {
                            RobotLog.i(LogSource.APP, "Demo: the way is blocked within ${DEMO_DOOR_M} m of $destination, that counts as arrived")
                            return arrive()
                        }
                        o.why == NavFailure.BLOCKED || o.why == NavFailure.NO_PATH -> {
                            val next = nextWay(target, o.why)
                            if (next != null) {
                                target = next
                            } else {
                                // every detour step was tried: the team is called, otto waits and tries again
                                waitForWay()?.let { return it }
                                target = to.spot
                            }
                        }
                        else -> {
                            failNav(o.why, destination)
                            return GuideOutcome.FAILED
                        }
                    }
                    NavOutcome.Interrupted -> {
                        val d = demo
                        if (d != null) {
                            demoInterrupted(d, scope)?.let { return it }
                            watch.start(now())
                            continue
                        }
                        val newPace = wantedPace
                        val reason = schedule.due(now())
                        when {
                            newPace != null -> {
                                wantedPace = null
                                setPace(newPace, announce = true)
                            }
                            pauseWanted -> pauseHere(scope)?.let { return it }
                            reason != null -> {
                                RobotLog.i(
                                    LogSource.BRAIN,
                                    "Check-back now: ${reason.detail}" +
                                        (if (reason == CheckReason.TURN) " (%.0f deg)".format(Locale.ROOT, schedule.lastTurnDeg) else "") +
                                        ", ${schedule.elapsedMs / 1000} s since the last one",
                                )
                                val (result, distance) = checkBack(reason, watch, scope)
                                // the navigation turns otto back to the path: no corner check for that
                                schedule.done(distance, Robot.heading(), turnedAround = true)
                                watch.start(now())
                                result?.let { return it }
                                RobotLog.i(
                                    LogSource.BRAIN,
                                    "Next check-back in ${schedule.nextInMs() / 1000} s " +
                                        (distance?.let { "(patient was %.1f m away, check %d)".format(Locale.ROOT, it, schedule.checksDone) }
                                            ?: "(distance unknown, check ${schedule.checksDone})"),
                                )
                            }
                        }
                    }
                }
            }
        } finally {
            targetSpot = null
            tracker.cancel()
            Radar.stop()
        }
    }

    // every quarter second while driving: the schedule, the walk-beside watch, the patient's position,
    // the blocked count, and the check-backs that need no stop (the radar sees the patient beside).
    // the demo: its own driving time; the watch and the schedule only go to the log
    private fun tick(blocked: Boolean, schedule: CheckSchedule, watch: SideWatch, scope: CoroutineScope, targetPoint: Point?) {
        val t = now()
        schedule.tick(t, blocked, Robot.heading())
        demo?.plan?.tick(t, blocked)
        // otto stands at an obstacle: the patient may step behind him, that is no reason for an alarm
        if (blocked) watch.hold() else if (watch.holding) watch.start(t)
        val beside = Radar.besideFix(t)
        if (beside != null) track.noteRadar(beside.robot, beside.map, beside.at)
        if (demo == null && beside != null && schedule.noteDistance(beside.distance)) {
            RobotLog.i(LogSource.BRAIN, "Next check-back moved to ${schedule.nextInMs() / 1000} s (radar: patient %.1f m beside)".format(Locale.ROOT, beside.distance))
        }
        // sensors.radarWatch off: the radar does not call check-backs, the schedule alone decides
        if (s.radarWatch && watch.check(t, Radar.everSeen(), Radar.lastSeenAt())) {
            if (demo != null) {
                // the lock lost the patient while driving: after the firstCheck part that is fellBack
                if (demo.plan.wasPlayed(DemoPart.FIRST_CHECK)) {
                    demo.plan.lockLost()
                    RobotLog.i(LogSource.APP, "Demo: the lock lost the patient for ${watch.thresholdMs / 1000} s while driving, fellBack now")
                } else {
                    RobotLog.i(LogSource.LIDAR, "Nobody at the patient's spot for ${watch.thresholdMs / 1000} s (demo: before firstCheck, no stop)")
                }
            } else {
                sideLost = true
                RobotLog.i(LogSource.LIDAR, "Nobody beside on the radar for ${watch.thresholdMs / 1000} s, checking back now")
                schedule.followerLost()
            }
        }
        // past the jam: the blocked count of this leg starts again
        if (legBlock.progress(t, targetPoint?.let { p -> here?.distanceTo(p) }, legDriven)) {
            RobotLog.i(LogSource.NAV, "Past the blocked spot, the blocked count starts again")
            demoWaySaid = false
        }
        // a few meters past the last jam: a later one gets every detour step again, also its own team call
        val jam = jamDriven
        if (jam != null && legDriven - jam >= LegBlock.DRIVEN_M) {
            jamDriven = null
            detours.reset()
            helpCalled = false
            RobotLog.i(LogSource.NAV, "Drove on past the jam, the detour steps are ready again")
            demoWaySaid = false
        }
        val due = schedule.due(t) ?: return
        // the demo's other check-backs: no stop, no turn, a log line
        if (demo != null) {
            RobotLog.i(LogSource.APP, "Demo: check-back (${due.detail}) not made, the demo walk is a fixed routine")
            schedule.done(null, Robot.heading())
            return
        }
        if (due == CheckReason.FOLLOWER_LOST) return
        // the last meters: no turning around, the arrival itself is the check
        if (NearGoal.skipCheck(here, dest)) {
            RobotLog.i(LogSource.BRAIN, "Check-back (${due.detail}) skipped: almost at $destination")
            schedule.skip(Robot.heading())
            return
        }
        if (beside == null || blocked) return
        val kind = StopRules.kind(StopInput(due, checks + 1, beside.distance, lastCheckDistance, fellBack), s.askEvery, rolling = s.radarWatch)
        if (kind == StopKind.ROLLING) rollingCheck(due, beside, schedule, scope)
    }

    // a check-back without a stop: the radar sees the patient right beside otto (6.7)
    private fun rollingCheck(reason: CheckReason, fix: FollowerFix, schedule: CheckSchedule, scope: CoroutineScope) {
        checks++
        lastCheckDistance = fix.distance
        fellBack = false
        drivenSinceCheck = 0.0
        schedule.done(fix.distance, Robot.heading())
        RobotLog.i(
            LogSource.BRAIN,
            "Check-back %d (%s): patient beside on the radar, %.1f m at %+.0f deg, no stop. Next in %d s".format(
                Locale.ROOT, checks, reason.detail, fix.distance, Bearing.of(fix.robot), schedule.nextInMs() / 1000,
            ),
        )
        Clinic.feed.ottoDid(now(), "The patient walks beside Otto, all fine")
        sayLater(LineKey.CHECK_OK, SpeechKind.WALK, liveIn(GuidePhase.WALKING, GuidePhase.CHECKING))
        scope.launch { patch(null, JourneyEvent.CHECK_BACK, "Patient beside, all fine (radar)") }
    }

    // ---- a blocked way (Detours.kt) ----

    // the way to `current` is blocked or has no path: the next detour step. the spot to drive to next, or
    // null when the steps are used up (then the team is called)
    private suspend fun nextWay(current: String, why: NavFailure): String? {
        val pose = Robot.latestPose() ?: Robot.robotPose()
        val at = pose?.point ?: here
        val near = WalkMath.nearestSpot(at, mapPlaces) ?: "an unknown spot"
        val left = waypointsLeft(at, pose?.theta, current)
        // way.helpAfterS: blocked this long on the leg in total, the team is called also with detours left
        val blockedLong = legBlock.totalMs(now()) >= s.helpAfterS * 1000L
        val step = if (blockedLong) DetourStep.HELP else detours.next(left.size)
        if (blockedLong) RobotLog.i(LogSource.NAV, "Blocked ${legBlock.totalMs(now()) / 1000} s on this leg (help after ${s.helpAfterS} s): calling the team")
        jamDriven = legDriven
        RobotLog.i(
            LogSource.NAV,
            "Way to $current ${if (why == NavFailure.NO_PATH) "has no path" else "is blocked"} near $near, " +
                "${legBlock.totalMs(now()) / 1000} s blocked on this leg: detour step ${step.name.lowercase(Locale.ROOT).replace('_', ' ')}",
        )
        return when (step) {
            DetourStep.BACK_OFF -> {
                backOff()
                legBlock.nextAttempt(now(), DETOUR_LIMIT_MS, DETOUR_ASKS)
                current
            }
            DetourStep.WAYPOINT -> {
                val w = left.first()
                tried += Names.key(w.name)
                RobotLog.i(
                    LogSource.NAV,
                    "Detour over '${w.name}' (${w.why()})" + (left.getOrNull(1)?.let { ", next would be '${it.name}'" } ?: ", no other spot fits"),
                )
                takeDetour()
                legBlock.nextAttempt(now(), DETOUR_LIMIT_MS, DETOUR_ASKS)
                w.name
            }
            DetourStep.HELP -> null
        }
    }

    // the place's own waypoint first, then map spots that lead around the jam (DetourPlan; way.autoWaypoints)
    private fun waypointsLeft(at: Point?, heading: Double?, current: String): List<Waypoint> {
        val out = ArrayList<Waypoint>()
        val via = viaSpot()
        if (via != null && Names.key(via) !in tried && !Names.same(via, current)) out += Waypoint(via, null, null, explicit = true)
        val d = dest
        if (s.autoWaypoints && at != null && d != null) {
            out += DetourPlan.waypoints(mapPlaces, at, heading, d, tried + listOfNotNull(to.spot, via, current))
        }
        return out
    }

    // detour step A: back off a little (only when the radar sees nothing close behind and the patient
    // walks beside, not behind), turn a little towards the freer side, then the same way once more
    private suspend fun backOff() {
        Clinic.feed.ottoDid(now(), "The way is blocked, stepping back to try again")
        patch(null, JourneyEvent.WALKING, "The way was blocked, Otto steps back and tries again")
        delay(Robot.NAV_RELEASE_MS)
        val p = track.predict(now(), Robot.latestPose())
        val patientBehind = p == null || (abs(p.bearingDeg) >= PATIENT_BEHIND_DEG && (p.distance ?: 0.0) < BACK_PATIENT_M)
        val clear = Radar.clearBehind(BACK_CLEAR_M)
        val backed = if (clear && !patientBehind) {
            val d = demo
            if (d != null) demoWayLine(d) else say(LineKey.BACK_UP, SpeechKind.WAY)
            Robot.back(BACK_OFF_M)
        } else {
            false
        }
        val left = Radar.roomLeft() ?: (side == WalkSide.RIGHT)
        val turned = turnOnSpot(if (left) DETOUR_TURN_DEG.toDouble() else -DETOUR_TURN_DEG.toDouble(), "detour step A, more room there") != null
        RobotLog.i(
            LogSource.NAV,
            "Detour step A: " + when {
                backed -> "backed off %.1f m".format(Locale.ROOT, BACK_OFF_M)
                patientBehind -> "no backing off, the patient may be behind"
                !clear -> "no backing off, something is close behind"
                else -> "backing off did not work"
            } + if (turned) ", turned %.0f deg %s (more room there), trying the way again".format(Locale.ROOT, DETOUR_TURN_DEG, if (left) "left" else "right")
            else ", trying the way again",
        )
    }

    private suspend fun takeDetour() {
        patch(null, JourneyEvent.WALKING, "The way was blocked, Otto takes another way")
        Clinic.feed.ottoDid(now(), "The way is blocked, taking another way")
        Clinic.updateGuide { it.copy(detour = true) }
        val d = demo
        if (d != null) demoWayLine(d) else say(LineKey.DETOUR, SpeechKind.WAY)
    }

    // detour step C: the way stays blocked. the patient hears it, the team gets a call (once), otto waits
    // with the patient and tries the way again every BLOCKED_RETRY_MS; after BLOCKED_WAIT_MS he gives up.
    // null = try the way again now, else the end of the guide
    private suspend fun waitForWay(): GuideOutcome? {
        if (!helpCalled) {
            helpCalled = true
            blockedSince = now()
            val near = WalkMath.nearestSpot(here, mapPlaces) ?: fromName.ifBlank { null } ?: to.name
            RobotLog.w(LogSource.NAV, "Way to ${to.spot} still blocked near $near after the detours (${legBlock.totalMs(now()) / 1000} s blocked on this leg): calling the team")
            patch(null, JourneyEvent.WALKING, "The way is blocked near $near, Otto called the team")
            Clinic.net(quiet = true) { Backend.help("Way to ${to.name} blocked near $near, Otto needs a hand", "normal", journeyId, ticket) }
            Clinic.feed.ottoDid(now(), "The way is blocked, asked the team for help")
            setPhase(GuidePhase.BLOCKED)
            Clinic.flash(Mood.SORRY, 3_000)
            val d = demo
            if (d != null) demoWayLine(d) else say(LineKey.BLOCKED_HELP, SpeechKind.ALERT)
        }
        if (now() - blockedSince >= BLOCKED_WAIT_MS) {
            RobotLog.w(LogSource.NAV, "Way to ${to.spot} blocked for ${(now() - blockedSince) / 1000} s after the team call: this leg ends")
            patch(LegStatus.FAILED, JourneyEvent.FAILED, "The way stayed blocked")
            say(LineKey.GOING_BACK, SpeechKind.ALERT)
            return result(GuideOutcome.FAILED)
        }
        setPhase(GuidePhase.BLOCKED)
        val until = now() + BLOCKED_RETRY_MS
        while (now() < until) {
            if (cancelled) return cancelledNow()
            if (OttoBrain.helpActive()) {
                patch(LegStatus.FAILED, JourneyEvent.FAILED, "Stopped: urgent help was called")
                return result(GuideOutcome.STOPPED)
            }
            delay(500)
        }
        legBlock.nextAttempt(now(), RETRY_LIMIT_MS, 1)
        RobotLog.i(LogSource.NAV, "Trying the way to ${to.spot} again (team called ${(now() - blockedSince) / 1000} s ago)")
        setPhase(GuidePhase.WALKING)
        return null
    }

    // position every POSE_EVERY_MS: progress line on the screen, the milestone lines, meters driven
    private suspend fun track() = coroutineScope {
        while (isActive) {
            delay(POSE_EVERY_MS)
            Radar.checkSilent()
            val p = Robot.pose() ?: continue
            here = p
            val from = drivenFrom
            if (from == null) {
                drivenFrom = p
            } else if (from.distanceTo(p) >= DRIVEN_STEP_M) {
                val step = from.distanceTo(p)
                drivenSinceCheck += step
                legDriven += step
                drivenFrom = p
            }
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

    // dropped when otto is no longer walking by the time it is its turn (a stop, the arrival)
    private suspend fun sayMilestone(m: Milestone) {
        val key = when (m) {
            Milestone.HALF_WAY -> LineKey.HALF_WAY
            Milestone.ALMOST_THERE -> LineKey.ALMOST_THERE
            else -> return
        }
        say(key, SpeechKind.MILESTONE, liveIn(GuidePhase.WALKING))
    }

    private suspend fun pauseHere(scope: CoroutineScope): GuideOutcome? {
        pauseWanted = false
        resumeWanted = false
        setPhase(GuidePhase.PAUSED)
        patch(null, JourneyEvent.WAITING_FOR_PATIENT, "Patient asked Otto to wait")
        Clinic.feed.ottoDid(now(), "Waiting until the patient is ready")
        RobotLog.i(LogSource.BRAIN, "Paused for ${ticketOr()}")
        val until = now() + PAUSE_MAX_MS
        while (!resumeWanted && now() < until) {
            if (cancelled) return cancelledNow()
            delay(250)
        }
        val timedOut = !resumeWanted
        resumeWanted = false
        allGood = false
        wantedPace?.let {
            wantedPace = null
            setPace(it, announce = true)
        }
        // the demo walk does not look for the patient: it simply goes on
        if (timedOut && demo == null) return checkBack(CheckReason.REGULAR, null, scope).first
        setPhase(GuidePhase.WALKING)
        return null
    }

    // the demo's walk stopped on purpose: the operator's hold, Next, a walk part that is due, a pace change
    private suspend fun demoInterrupted(d: DemoWalk, scope: CoroutineScope): GuideOutcome? {
        if (legBlock.isBlocked && atTheDoor()) {
            RobotLog.i(LogSource.APP, "Demo: blocked within ${DEMO_DOOR_M} m of $destination, that counts as arrived")
            return arrive()
        }
        if (d.held()) return holdForDemo()
        val byHand = d.takeNext()
        val part = byHand ?: d.plan.due()
        val newPace = wantedPace
        return when {
            part != null -> demoStop(d, part, byHand != null, scope)
            newPace != null -> {
                wantedPace = null
                setPace(newPace, announce = true)
                null
            }
            pauseWanted -> pauseHere(scope)
            else -> null
        }
    }

    // the demo's walk parts with the caring check-back (docs/demo-mode.md flow 4, ticket lock): otto turns towards
    // where the lock says the patient is (turn rule), the camera takes the person nearest that spot (within
    // DEMO_GATE_M, everyone else is ignored) and anchors the lock there, the part plays, otto turns back the same
    // angle and drives on. no idea at all: glanceDeg towards the walk side (firstCheck), behind on the walk side
    // (fellBack). fellBack is skipped in the last meters
    private suspend fun demoStop(d: DemoWalk, part: String, byHand: Boolean, scope: CoroutineScope): GuideOutcome? {
        val fell = part == DemoPart.FELL_BACK
        if (fell && NearGoal.skipCheck(here, dest)) {
            RobotLog.i(LogSource.APP, "Demo: fellBack skipped, Otto is within ${NearGoal.SKIP_WITHIN_M.toInt()} m of $destination")
            d.plan.played(part)
            return null
        }
        RobotLog.i(
            LogSource.APP,
            "Demo: $part" + if (byHand) " (Next)" else " after %.0f s of driving".format(Locale.ROOT, d.plan.drivenMs() / 1000.0),
        )
        delay(Robot.NAV_RELEASE_MS)
        setPhase(if (fell) GuidePhase.WAITING else GuidePhase.CHECKING)
        OttoBrain.activity(RobotState.WALKING, if (fell) "Waiting for ${ticketOr()}" else "Checking on ${ticketOr()}")
        Clinic.feed.ottoDid(now(), if (fell) "Waiting for the patient to catch up" else "Turning to check on the patient")
        if (fell) patch(null, JourneyEvent.WAITING_FOR_PATIENT, "Patient fell behind, Otto waits")
        val startHeading = Robot.heading()
        val predicted = track.predict(now(), Robot.latestPose())
        val turn = when {
            predicted != null -> TurnPlan.toward(predicted.bearingDeg, side, s.turnSide)
            fell -> TurnPlan.toward(null, side, s.turnSide)
            d.glanceDeg > 0.0 -> TurnPlan.choose(side.sign * d.glanceDeg, s.turnSide)
            else -> null
        }
        val turned = turn != null && (turnOnSpot(turn, "to the patient") || run {
            delay(TURN_RETRY_MS)
            turnOnSpot(turn, "to the patient, once more")
        })
        val turnedFirst = TurnPlan.target(predicted, if (turned) turn else null, Robot.latestPose(), side)
        // face the patient: corrected by their fresh bearing, at most twice
        val faced = faceThePatient(turnedFirst)
        val counted = (if (turned) turn?.signedDeg ?: 0.0 else 0.0) + faced
        val target = TurnPlan.shifted(turnedFirst, faced)
        val look = look(target, DEMO_GATE_M)
        val m = look.match
        if (m != null) {
            track.noteCamera(m.point, look.matchMap, now())
            look.matchMap?.let { cameraFix = it to now() }
            Radar.anchor(m.point, look.matchMap)
        }
        RobotLog.i(
            LogSource.APP,
            "Demo: $part, " + (turn?.let { "turned %s %.0f deg".format(Locale.ROOT, if (it.left) "left" else "right", it.degrees) + if (turned) "" else " (not taken)" } ?: "no turn") +
                (predicted?.let { " towards the %s fix".format(Locale.ROOT, it.source.wire) } ?: " (no idea where the patient is)") +
                (m?.let { ", the patient %.1f m away".format(Locale.ROOT, it.distance) } ?: ", nobody at that spot"),
        )
        d.play(part)
        // back by the total turned, the shorter way, then on
        turnBack(startHeading, counted)
        patch(null, JourneyEvent.CHECK_BACK, if (fell) "Patient is back with Otto" else "Patient close, all fine")
        d.plan.played(part)
        checks++
        if (cancelled) return cancelledNow()
        wantedPace?.let {
            wantedPace = null
            setPace(it, announce = false)
        }
        if (pauseWanted) return pauseHere(scope)
        setPhase(GuidePhase.WALKING)
        OttoBrain.activity(RobotState.WALKING, Brain.guideGoal(ticket, destination))
        return null
    }

    // the demo: otto is this close to the destination (blocked here = arrived)
    private fun atTheDoor(): Boolean {
        val p = Robot.latestPose()?.point ?: here
        val d = dest
        return p != null && d != null && p.distanceTo(d) <= DEMO_DOOR_M
    }

    // the demo: the walk's way and alert lines while the way is blocked are no script lines, so the script's
    // detour part is said instead, once per blocked stretch (otto never stands there in silence)
    private suspend fun demoWayLine(d: DemoWalk) {
        if (demoWaySaid) return
        demoWaySaid = true
        RobotLog.i(LogSource.APP, "Demo: the way is blocked, the detour part")
        d.play(DemoPart.DETOUR)
    }

    // the demo operator holds: otto stands until resume (no journey event, it is the team's own pause)
    private suspend fun holdForDemo(): GuideOutcome? {
        val before = phase
        setPhase(GuidePhase.PAUSED)
        RobotLog.i(LogSource.APP, "Demo: walk held by the operator, Otto stands")
        while (demo?.held() == true) {
            if (cancelled) return cancelledNow()
            delay(250)
        }
        RobotLog.i(LogSource.APP, "Demo: walk goes on")
        setPhase(if (before == GuidePhase.PAUSED) GuidePhase.WALKING else before)
        return null
    }

    // every turn on the spot in a walk goes here: the one rule (TurnPlan.choose with walk.turnSide: more than
    // 90 deg via his right side by default, smaller turns the shorter way), logged. the turn made, null = none
    // needed or the robot did not take it
    private suspend fun turnOnSpot(signedDeg: Double, why: String): Turn? {
        val t = TurnPlan.choose(signedDeg, s.turnSide) ?: return null
        return if (turnOnSpot(t, why)) t else null
    }

    // after a check-back turn otto faces the patient (Facing, field test b16: still 79 deg off while he talked):
    // their fresh bearing, more than 15 deg off = a correction the shorter way and a new look, at most twice.
    // the degrees turned (+ = left)
    private suspend fun faceThePatient(target: Target?): Double {
        var turned = 0.0
        var done = 0
        var t = target
        while (true) {
            delay(FACE_LOOK_MS)
            val b = patientBearing(t)
            val c = Facing.correction(b, done)
            if (c == null || b == null) {
                RobotLog.i(
                    LogSource.VISION,
                    when {
                        b == null -> "Facing: patient not seen after the turn"
                        abs(b) <= Facing.BAND_DEG -> "Facing: patient straight ahead (%.0f deg)".format(Locale.ROOT, abs(b))
                        else -> "Facing: patient still at %+.0f deg after %d corrections".format(Locale.ROOT, b, done)
                    },
                )
                return turned
            }
            done++
            RobotLog.i(
                LogSource.VISION,
                "Facing: patient at %+.0f deg after the turn, turning %s %.0f deg (correction %d)".format(Locale.ROOT, b, if (c.left) "left" else "right", c.degrees, done),
            )
            if (!turnOnSpot(c, "facing the patient, correction $done")) return turned
            turned += c.signedDeg
            t = t?.let { TurnPlan.shifted(it, c.signedDeg) }
        }
    }

    // the patient's bearing now (+ = left): the ticket lock's radar fix when fresh, else the camera person nearest
    // the spot (within FACE_CAMERA_M). the camera's side is not documented: with the spot straight ahead a person
    // off to one side gives no correction
    private fun patientBearing(target: Target?): Double? {
        val now = now()
        Radar.follower?.takeIf { now - it.at < FACE_RADAR_FRESH_MS }?.let { return Bearing.of(it.robot) }
        val t = target ?: return null
        val m = PatientMatch.best(Robot.people().orEmpty(), emptyList(), t, FACE_CAMERA_M) ?: return null
        val b = Bearing.of(m.point)
        if (abs(b) > Facing.BAND_DEG && abs(t.bearingDeg) < Facing.BAND_DEG) return null
        return b
    }

    // driving on after a stop: back by the total turned (the shorter way). startHeading = where otto faced when he
    // stopped; without a pose, the turns counted at the stop
    private suspend fun turnBack(startHeading: Double?, counted: Double) {
        val h = Robot.heading()
        val total = if (startHeading != null && h != null) Math.toDegrees(ScanMath.wrap(h - startHeading)) else counted
        val back = Facing.back(total) ?: return
        turnOnSpot(back, "back to the way, %.0f deg turned in all".format(Locale.ROOT, total))
    }

    private suspend fun turnOnSpot(t: Turn, why: String): Boolean {
        RobotLog.d(
            LogSource.NAV,
            "Turn %s %.0f deg (%s, walk.turnSide %s)".format(Locale.ROOT, if (t.left) "left" else "right", t.degrees, why, s.turnSide.wire),
        )
        return Robot.turn(t.degrees, t.left)
    }

    // ---- check-backs (docs/journeys.md 4.3, 6.3, 6.7) ----

    // what the looks after a turn saw. match = the person nearest the predicted spot (null = nobody near it),
    // samples / ahead = everyone the camera and the radar in front saw, for the fallback
    private class Look(
        val match: Match?,
        val matchMap: Point?,
        val samples: List<List<Double>?>,
        val ahead: Double?,
        val people: List<SeenPerson>,
    ) {
        fun plus(o: Look): Look {
            val best = listOfNotNull(this, o).filter { it.match != null }.minByOrNull { it.match!!.cost }
            return Look(best?.match, best?.matchMap, samples + o.samples, CheckBack.nearestOf(ahead, o.ahead), people + o.people)
        }
    }

    // a stop: turn towards where the patient was last seen (not a blind half turn), take the person nearest
    // that spot, look a bit to each side once when nobody is there, then a short line (QUICK) or a yes/no
    // question with at most 5 s of listening (ASK). no turn back: the navigation turns otto to the path.
    // first = null to walk on, else the end of the guide; second = how far the patient was. not in the demo walk
    // (its own routine: demoStop)
    private suspend fun checkBack(reason: CheckReason, watch: SideWatch?, scope: CoroutineScope): Pair<GuideOutcome?, Double?> {
        val stopAt = now()
        val no = checks + 1
        val beside = Radar.besideFix(stopAt)
        val kind = StopRules.kind(StopInput(reason, no, beside?.distance, lastCheckDistance, fellBack), s.askEvery, rolling = s.radarWatch)
            .let { if (it == StopKind.ROLLING) StopKind.QUICK else it }
        setPhase(GuidePhase.CHECKING)
        OttoBrain.activity(RobotState.WALKING, "Checking on ${ticketOr()}")
        Clinic.feed.ottoDid(
            now(),
            when (reason) {
                CheckReason.OBSTACLE -> "Checking on the patient after an obstacle"
                CheckReason.TURN -> "Checking on the patient after a turn"
                CheckReason.FOLLOWER_LOST -> "Nobody beside on the radar, turning to the patient"
                else -> "Turning to check on the patient"
            },
        )
        delay(Robot.NAV_RELEASE_MS)
        // where otto faces now: he turns back by the total when he drives on
        val startHeading = Robot.heading()
        val predicted = track.predict(now(), Robot.latestPose())
        val turn = TurnPlan.toward(predicted?.bearingDeg, side, s.turnSide)
        // the radar saw them beside just now: the question already while otto turns to them
        val question = if (kind == StopKind.ASK) questionKey() else null
        var askedAt = 0L
        var asking: Job? = null
        if (question != null && beside != null) {
            askedAt = now()
            showTalk(question)
            asking = scope.launch { say(listOf(question), SpeechKind.QUESTION, question = true, fits = liveIn(GuidePhase.CHECKING)) }
        }
        // the chassis may still be held right after stopNavigation: one more try
        val towards = if (reason == CheckReason.FOLLOWER_LOST) "to where the patient was" else "to the patient"
        val turned = turn == null || turnOnSpot(turn, towards) || run {
            delay(TURN_RETRY_MS)
            turnOnSpot(turn, "$towards, once more")
        }
        val turnedFirst = TurnPlan.target(predicted, if (turned) turn else null, Robot.latestPose(), side)
        // face the patient: corrected by their fresh bearing, at most twice
        val faced = faceThePatient(turnedFirst)
        val counted = (if (turned) turn?.signedDeg ?: 0.0 else 0.0) + faced
        val target = TurnPlan.shifted(turnedFirst, faced)
        var look = look(target)
        if (turned && look.match == null && now() - presenceAt >= PRESENCE_MS) {
            // nobody near the spot: a little to each side once before deciding
            look = look.plus(lookAround(target))
        }
        val m = look.match
        val seen = when {
            now() - presenceAt < PRESENCE_MS -> Proximity.CLOSE
            // not turned = otto may look the wrong way: ask out loud instead of raising a false alarm
            !turned -> Proximity.UNKNOWN
            m != null -> when {
                m.distance <= CheckBack.CLOSE_M -> Proximity.CLOSE
                m.distance <= CheckBack.FAR_M -> Proximity.FAR
                else -> Proximity.NOBODY
            }
            // nobody near the spot, even to the sides: anyone close counts (as before v3.3)
            else -> CheckBack.decide(look.samples, look.ahead)
        }
        val distance = m?.distance ?: if (turned) CheckBack.nearestOf(CheckBack.nearest(look.samples), look.ahead) else Radar.followerDistance()
        if (m != null) {
            track.noteCamera(m.point, look.matchMap, now())
            look.matchMap?.let { cameraFix = it to now() }
            // the ticket lock is anchored on the person the camera picked
            Radar.anchor(m.point, look.matchMap)
        }
        if (!cameraLogged && look.people.isNotEmpty()) {
            cameraLogged = true
            // learns how the robot reports the camera angle (the docs do not say which side is +)
            RobotLog.i(
                LogSource.VISION,
                "Camera after the turn: " + look.people.take(4).joinToString { "%.1f m at %+.0f deg".format(Locale.ROOT, it.distance, it.angleDeg) },
            )
        }
        RobotLog.i(
            LogSource.VISION,
            "Check-back $no (${reason.detail}, ${kind.name.lowercase(Locale.ROOT)}): ${seen.name.lowercase(Locale.ROOT)}" +
                (turn?.let { ", turned %s %.0f deg".format(Locale.ROOT, if (it.left) "left" else "right", it.degrees) } ?: ", no turn needed") +
                (if (!turned) " (the turn did not work)" else "") +
                (predicted?.let { " towards the %s fix (%.0f s old)".format(Locale.ROOT, it.source.wire, it.ageMs / 1000.0) } ?: " towards the walk side") +
                (m?.let { ", picked %.1f m (%s) %.1f m from the spot".format(Locale.ROOT, it.distance, it.source.wire, it.cost) } ?: ", nobody near the spot") +
                (look.ahead?.let { ", radar ahead %.1f m".format(Locale.ROOT, it) } ?: ""),
        )
        if (reason == CheckReason.FOLLOWER_LOST && seen == Proximity.CLOSE && watch != null) {
            watch.falseAlarm()
            RobotLog.i(
                LogSource.LIDAR,
                if (watch.trusted) "Side watch: the patient was close after all, it now waits ${watch.thresholdMs / 1000} s"
                else "Side watch had ${watch.falseAlarms} false alarms, schedule only for this walk",
            )
        }
        val events = CheckBack.events(seen, pace)
        val result: GuideOutcome? = when (seen) {
            Proximity.CLOSE, Proximity.UNKNOWN -> {
                if (kind == StopKind.ASK || seen == Proximity.UNKNOWN) {
                    val answered = askAndListen(question ?: questionKey(), asking, askedAt)
                    patch(
                        null, events.first(),
                        buildString {
                            append(if (seen == Proximity.UNKNOWN) "Otto could not see the patient, asked out loud" else "Patient close, all fine")
                            if (answered) append(", they answered")
                        },
                    )
                } else {
                    // a 2-3 s line while the navigation turns otto back to the path
                    sayLater(if (reason == CheckReason.FOLLOWER_LOST || sideLost) LineKey.SIDE_REMINDER else LineKey.CHECK_OK)
                    patch(null, events.first(), "Patient close, all fine")
                }
                null
            }
            // the question may have started while turning: the screen shows the waiting instead
            Proximity.FAR -> {
                Clinic.updateGuide { it.copy(talk = null) }
                waitForFar(events)
            }
            Proximity.NOBODY -> {
                Clinic.updateGuide { it.copy(talk = null) }
                lost()
            }
        }
        checks = no
        distance?.let { lastCheckDistance = it }
        fellBack = seen == Proximity.FAR || seen == Proximity.NOBODY
        sideLost = false
        drivenSinceCheck = 0.0
        RobotLog.i(LogSource.BRAIN, "Stop %d took %.1f s".format(Locale.ROOT, no, (now() - stopAt) / 1000.0))
        if (result != null) return result to distance
        if (cancelled) return cancelledNow() to distance
        // driving on: back towards the way by the total turned
        turnBack(startHeading, counted)
        wantedPace?.let {
            wantedPace = null
            setPace(it, announce = false)
        }
        if (pauseWanted) return pauseHere(scope) to distance
        setPhase(GuidePhase.WALKING)
        OttoBrain.activity(RobotState.WALKING, Brain.guideGoal(ticket, destination))
        return null to distance
    }

    private fun questionKey(): LineKey = if ((progress ?: 0f) >= Milestones.ALMOST) LineKey.ASK_ALMOST else LineKey.ASK

    // the question on the screen (in english, like the rest of the screen)
    private fun showTalk(key: LineKey) {
        val id = ++talkIds
        Clinic.updateGuide { it.copy(talk = TalkUi(LineSet.english(key, lines.context), TalkStage.ASKING, listenMs = s.listenS * 1000L, id = id)) }
    }

    // a few looks after the turn (the camera needs a moment), over early when someone is right at the spot
    // gate = how far from the predicted spot someone may be (the demo: DEMO_GATE_M, everyone else is ignored)
    private suspend fun look(target: Target, gate: Double = PatientMatch.GATE_M): Look {
        val samples = ArrayList<List<Double>?>()
        val people = ArrayList<SeenPerson>()
        var best: Match? = null
        var bestMap: Point? = null
        for (i in 0 until LOOKS) {
            delay(if (i == 0) FIRST_LOOK_MS else LOOK_EVERY_MS)
            val seen = Robot.people()
            samples += seen?.map { it.distance }
            seen?.let { people += it }
            val m = PatientMatch.best(seen.orEmpty(), Radar.aheadPoints(), target, gate)
            if (m != null && (best == null || m.cost < best.cost)) {
                best = m
                bestMap = Robot.latestPose()?.let { ScanMath.toMap(m.point, it) }
            }
            if (best != null && best.cost <= PatientMatch.GOOD_M) break
        }
        return Look(best, bestMap, samples, Radar.nearestAhead(), people)
    }

    // nobody near the spot: a little to one side (the spot's side first), then the other, once
    private suspend fun lookAround(target: Target): Look {
        val firstLeft = if (target.bearingDeg == 0.0) side == WalkSide.LEFT else target.bearingDeg > 0
        val first = if (firstLeft) TurnPlan.ASIDE_DEG else -TurnPlan.ASIDE_DEG
        var turnedDeg = 0.0
        turnOnSpot(first, "a look to one side")?.let { turnedDeg += it.signedDeg }
        var acc = look(TurnPlan.shifted(target, turnedDeg))
        if (acc.match != null) return acc.also { RobotLog.i(LogSource.VISION, "Found someone at the spot after looking ${if (firstLeft) "left" else "right"}") }
        turnOnSpot(-2.0 * first, "a look to the other side")?.let { turnedDeg += it.signedDeg }
        val other = look(TurnPlan.shifted(target, turnedDeg))
        acc = acc.plus(other)
        if (other.match != null) return acc.also { RobotLog.i(LogSource.VISION, "Found someone at the spot after looking ${if (firstLeft) "right" else "left"}") }
        // back towards the spot for what comes next (waiting, calling out)
        turnOnSpot(first, "back towards the spot")
        RobotLog.i(LogSource.VISION, "Nobody at the spot, also not to the sides")
        return acc
    }

    // the yes/no question face to face, then at most 5 s of listening (ListenWindow). spoken answers go to
    // otto's agent as always (SET_PACE, PAUSE_WALK, a few words back); the walk does not wait for its
    // answer. asking = the question already under way (said while turning). true = they answered by voice
    private suspend fun askAndListen(key: LineKey, asking: Job?, askedAt: Long): Boolean {
        allGood = false
        val since = if (asking != null) askedAt else now()
        if (asking != null) {
            asking.join()
        } else {
            showTalk(key)
            say(listOf(key), SpeechKind.QUESTION, question = true, fits = liveIn(GuidePhase.CHECKING))
        }
        // walk.listenS: the stop's own listening window (the speech manager's answer wait ends with it)
        val window = ListenWindow(askedAt = since, startedAt = now(), maxMs = s.listenS * 1000L)
        Clinic.updateGuide { g -> g.talk?.let { g.copy(talk = it.copy(stage = TalkStage.LISTENING)) } ?: g }
        val listenFrom = now()
        while (!cancelled) {
            val decided = allGood || wantedPace != null || pauseWanted
            if (window.done(now(), Clinic.lastVoiceAt(), decided)) break
            delay(TALK_TICK_MS)
        }
        Clinic.updateGuide { it.copy(talk = null) }
        val byVoice = window.answeredByVoice
        RobotLog.i(
            LogSource.VOICE,
            "Question: " + when {
                wantedPace != null -> "asked for another pace"
                pauseWanted -> "asked to wait"
                allGood -> "tapped all good"
                byVoice -> "answered by voice, the agent replies"
                else -> "no answer"
            } + ", listened %.1f s".format(Locale.ROOT, (now() - listenFrom) / 1000.0),
        )
        // the stop's listening is over: the speech manager stops waiting for an answer too
        Speech.answered(if (byVoice) "the patient answered at the stop" else "the stop's listening is over")
        // otto's ai answers spoken replies itself; otherwise a short "let's go on" while he turns to the path
        if (!byVoice && wantedPace == null && !pauseWanted) sayLater(LineKey.GO_ON)
        allGood = false
        return byVoice
    }

    private suspend fun waitForFar(events: List<String>): GuideOutcome? {
        setPhase(GuidePhase.WAITING)
        OttoBrain.activity(RobotState.WALKING, "Waiting for ${ticketOr()}")
        Clinic.feed.ottoDid(now(), "Waiting for the patient to catch up")
        say(LineKey.WAIT_FOR_YOU, SpeechKind.WALK, liveIn(GuidePhase.WAITING))
        patch(null, JourneyEvent.WAITING_FOR_PATIENT, "Patient 3-6 m behind, Otto waits")
        val wait = FarWait(now(), maxMs = s.farWaitS * 1000L)
        var step = FarStep.WAIT
        while (step == FarStep.WAIT) {
            if (cancelled) return cancelledNow()
            delay(FAR_LOOK_MS)
            step = if (now() - presenceAt < PRESENCE_MS) FarStep.CAME else wait.next(now(), nearestNow())
        }
        if (step == FarStep.GONE) return lost()
        // slower only when otto really walked away from the patient, not when he stood at an obstacle
        val slower = JourneyEvent.SLOWED_DOWN in events && FellBehind.forReal(drivenSinceCheck)
        if (slower) {
            setPace(pace.slower(), announce = false, detail = "Patient fell behind, pace lowered")
        } else if (JourneyEvent.SLOWED_DOWN in events) {
            RobotLog.i(LogSource.BRAIN, "Patient waited a few meters back, otto drove only %.1f m since the last check: the pace stays".format(Locale.ROOT, drivenSinceCheck))
        }
        say(if (slower) LineKey.THANKS_SLOWER else LineKey.GO_ON, SpeechKind.WALK)
        return null
    }

    // camera and the radar in front, whichever sees someone closer
    private fun nearestNow(): Double? = CheckBack.nearestOf(CheckBack.nearest(listOf(Robot.peopleDistances())), Radar.nearestAhead())

    // nobody near: call out twice, tell the team after 30 s, give up 60 s later (LostTimeline)
    private suspend fun lost(): GuideOutcome? {
        setPhase(GuidePhase.LOOKING)
        OttoBrain.activity(RobotState.WALKING, "Looking for ${ticketOr()}")
        Clinic.feed.ottoDid(now(), "Nobody near Otto, calling the patient")
        RobotLog.w(LogSource.VISION, "Nobody near Otto, calling ${ticketOr()}")
        // walk.lostCallAgainS / lostReportS / lostGiveUpS (live settings)
        val timeline = LostTimeline(
            callsAtMs = listOf(0L, s.lostCallAgainS * 1000L),
            reportAtMs = s.lostReportS * 1000L,
            giveUpAtMs = (s.lostReportS + s.lostGiveUpS) * 1000L,
        )
        val startedAt = now()
        while (true) {
            if (cancelled) return cancelledNow()
            when (timeline.next(now() - startedAt)) {
                // the call runs on its own: the patient may come back while otto calls
                LostStep.CALL -> sayLater(LineKey.CALL_OUT, SpeechKind.CALL, liveIn(GuidePhase.LOOKING, GuidePhase.LOST))
                LostStep.REPORT -> {
                    val near = WalkMath.nearestSpot(here, mapPlaces) ?: fromName.ifBlank { null } ?: to.name
                    patch(null, JourneyEvent.LOST_PATIENT, "No one near Otto after calling twice")
                    Clinic.net(quiet = true) {
                        Backend.help("Lost the patient with ticket $ticket near $near", "normal", journeyId, ticket)
                    }
                    Clinic.feed.ottoDid(now(), "Lost sight of ticket $ticket, asked the team for help")
                    RobotLog.w(LogSource.BRAIN, "Lost ${ticketOr()} near $near, asked the team for help")
                    setPhase(GuidePhase.LOST)
                    Clinic.flash(Mood.SORRY, 3_000)
                }
                LostStep.GIVE_UP -> {
                    patch(LegStatus.FAILED, JourneyEvent.FAILED, "Lost the patient")
                    say(LineKey.GOING_BACK, SpeechKind.ALERT)
                    return result(GuideOutcome.LOST)
                }
                LostStep.WATCH -> Unit
            }
            delay(FAR_LOOK_MS)
            val near = nearestNow()
            val found = now() - presenceAt < PRESENCE_MS || (near != null && near <= CheckBack.FAR_M)
            if (found) {
                patch(null, JourneyEvent.CHECK_BACK, if (timeline.hasReported) "Patient found again after the help call" else "Patient came back after Otto called")
                Clinic.feed.ottoDid(now(), "Found the patient again")
                RobotLog.i(LogSource.VISION, "Found ${ticketOr()} again")
                val slower = FellBehind.forReal(drivenSinceCheck)
                if (slower) setPace(pace.slower(), announce = false, detail = "Patient fell behind, pace lowered")
                Clinic.flash(Mood.RELIEVED, 3_000)
                say(if (slower) LineKey.FOUND_AGAIN else LineKey.SIDE_REMINDER, SpeechKind.WALK)
                return null
            }
        }
    }

    private suspend fun arrive(): GuideOutcome {
        patch(LegStatus.ARRIVED, JourneyEvent.ARRIVED, "Arrived at $destination")
        setPhase(GuidePhase.ARRIVED)
        Clinic.updateGuide { it.copy(progress = 1f, detour = false) }
        OttoBrain.activity(RobotState.WALKING, "Arrived with ${ticketOr()} at $destination")
        Clinic.feed.ottoDid(now(), "Arrived at $destination")
        Clinic.flash(Mood.EXCITED, 3_000)
        // the demo: its arrive part, then it drives back (no wait for the thumbs)
        val d = demo
        if (d != null) {
            d.play(DemoPart.ARRIVE)
            delay(DEMO_ARRIVED_MS)
            return GuideOutcome.ARRIVED
        }
        // the arrival line was written with the others (room, waiting area or exit)
        say(LineKey.ARRIVED, SpeechKind.ARRIVAL, liveIn(GuidePhase.ARRIVED))
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
        val detail = "Otto could not reach $where: ${why.screen}"
        patch(LegStatus.FAILED, JourneyEvent.FAILED, detail)
        Clinic.net(quiet = true) {
            Backend.help("Otto could not walk ticket $ticket to $where (${why.screen}). Please help.", "normal", journeyId, ticket)
        }
        RobotLog.e(LogSource.NAV, "$detail, the team was called")
        OttoBrain.failed(detail)
        Clinic.updateGuide { it.copy(phase = GuidePhase.FAILED, note = why.screen, talk = null) }
        phase = GuidePhase.FAILED
        Clinic.flash(Mood.SORRY, 4_000)
        Clinic.speak(
            "Apologize: you cannot get to $where right now because ${why.spoken}. A team member will come to help. Two short sentences.",
            lang,
            "${why.screen}. A team member will help you.",
            kind = SpeechKind.ALERT,
        )
        delay(3_000)
        return end(GuideOutcome.FAILED)
    }

    private suspend fun cancelledNow(): GuideOutcome {
        setPhase(GuidePhase.CANCELLED)
        RobotLog.i(LogSource.BRAIN, "The team ended the walk of ${ticketOr()}")
        Clinic.speak("Say briefly that the team has ended this walk and a team member will help.", lang, "The team ended this walk.", kind = SpeechKind.ALERT)
        delay(2_000)
        return result(GuideOutcome.CANCELLED)
    }

    // staff may cancel or close the journey while otto is on it (a local demo walk has none)
    private suspend fun watchJourney() {
        if (journeyId.isEmpty()) return
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

    private fun placeOfDestination(): Place? = Clinic.placeForSpot(to.spot)

    private fun isExit(): Boolean = (to.kind ?: placeOfDestination()?.kind) == PlaceKind.EXIT

    private fun isWaitingRoom(): Boolean = (to.kind ?: placeOfDestination()?.kind) == PlaceKind.WAITING

    private fun arrival(): Arrival = when {
        isExit() -> Arrival.EXIT
        isWaitingRoom() -> Arrival.WAITING
        else -> Arrival.ROOM
    }

    // the waypoint for a blocked way: from the command, else from the place list (dashboard)
    private fun viaSpot(): String? =
        (to.viaSpot ?: placeOfDestination()?.viaSpot)?.trim()?.takeIf { it.isNotEmpty() && !Names.same(it, to.spot) }

    private fun setPhase(p: GuidePhase) {
        phase = p
        Clinic.updateGuide { it.copy(phase = p, pace = pace) }
    }

    private suspend fun setPace(p: Pace, announce: Boolean, detail: String? = null) {
        val old = pace
        if (p == old) return
        pace = p
        Clinic.updateGuide { it.copy(pace = p) }
        RobotLog.i(LogSource.BRAIN, "Pace for ${ticketOr()}: ${p.wire}")
        if (p.isSlowerThan(old)) {
            patch(null, JourneyEvent.SLOWED_DOWN, detail ?: "Pace set to ${p.label.lowercase()}")
        } else {
            patch(null, JourneyEvent.WALKING, "Pace set to ${p.label.lowercase()}")
        }
        if (announce) Clinic.feed.ottoDid(now(), "Walking at ${p.label.lowercase()} now")
    }

    private suspend fun drive(spot: String, speed: Speed?): NavOutcome {
        Clinic.setWalking(true)
        targetSpot = spot
        try {
            return Robot.goTo(spot, speed, stopWhen = { OttoBrain.helpActive() || cancelled }, onEvent = ::onNavEvent)
        } finally {
            targetSpot = null
            Clinic.setWalking(false)
        }
    }

    // blocked: ask people in front to let otto pass (the leg's count decides when), with the walk's ready line.
    // the demo: its obstacle part
    private fun onNavEvent(ev: NavEvent) {
        val d = demo
        if (d != null) Clinic.onNavEvent(ev, own = { d.playLater(DemoPart.OBSTACLE) })
        else Clinic.onNavEvent(ev, lang, ready = lines.text(LineKey.EXCUSE_ME))
    }

    private suspend fun patch(status: String?, event: String?, detail: String?) {
        if (journeyId.isEmpty()) return
        Clinic.net(quiet = true) {
            Backend.patchJourney(journeyId, legId.ifEmpty { null }, status, event, detail)
        }.onFailure { RobotLog.w(LogSource.NET, "Journey update failed: ${Backend.friendly(it)}") }
    }

    companion object {
        const val HOW_TAP = "tap"
        const val HOW_VOICE = "voice"
        private const val TURN_RETRY_MS = 800L
        // the looks after a turn: the camera needs a moment, then every 0.3 s, at most 4
        private const val LOOKS = 4
        private const val FIRST_LOOK_MS = 350L
        private const val LOOK_EVERY_MS = 300L
        private const val FAR_LOOK_MS = 500L
        private const val PRESENCE_MS = 15_000L
        // the patient answered the pace question by voice: the agent's SET_PACE gets this long more to come
        private const val ACTION_GRACE_MS = 4_000L
        private const val TALK_TICK_MS = 200L
        private const val PAUSE_MAX_MS = 2 * 60_000L
        private const val FEEDBACK_MS = 15_000L
        // also keeps Robot.latestPose fresh (getPosition) when STATUS_POSE does not report
        private const val POSE_EVERY_MS = 1_000L
        private const val JOURNEY_CHECK_MS = 10_000L
        private const val FOLLOWER_FRESH_MS = 1_500L
        private const val CAMERA_FIX_KEEP_MS = 10_000L
        // the walk's lines: the llm call may take this long; the first lines wait this long for them
        private const val LINES_TIMEOUT_MS = 12_000L
        private const val FIRST_LINE_WAIT_MS = 3_000L
        // at "That's me": waiting for a fresh radar frame
        private const val START_LOOK_MS = 700L
        // pose noise while standing does not count as driving
        private const val DRIVEN_STEP_M = 0.08
        // a blocked way: each detour step gets this budget (blocked time, asks)
        private const val DETOUR_LIMIT_MS = 15_000L
        private const val DETOUR_ASKS = 1
        // step A: back off only when nothing is this close behind and the patient is not behind within BACK_PATIENT_M
        private const val BACK_OFF_M = 0.5f
        private const val BACK_CLEAR_M = 0.9
        private const val BACK_PATIENT_M = 2.0
        private const val PATIENT_BEHIND_DEG = 135.0
        private const val DETOUR_TURN_DEG = 30f
        // step C: try the way again this often while the team comes, give up after BLOCKED_WAIT_MS
        private const val BLOCKED_RETRY_MS = 40_000L
        private const val BLOCKED_WAIT_MS = 3 * 60_000L
        private const val RETRY_LIMIT_MS = 10_000L
        // the demo: a moment at the door after its arrive part
        private const val DEMO_ARRIVED_MS = 1_500L
        // the demo's check-back: the camera takes only someone this close to the predicted spot (ticket lock)
        private const val DEMO_GATE_M = 0.8
        // facing the patient after a turn: a fresh look, the radar fix this fresh, the camera this close to the spot
        private const val FACE_LOOK_MS = 400L
        private const val FACE_RADAR_FRESH_MS = 500L
        private const val FACE_CAMERA_M = 1.5
        // the demo: blocked this close to the destination counts as arrived (people at the doctor's door)
        private const val DEMO_DOOR_M = 1.5

        // the guide that runs right now, for the actions and the screen
        @Volatile var current: Guide? = null
            private set

        // runs one guide command to its end. the command is closed (done) on every path. demo = the demo's walk;
        // it may be local (no journey: the check-in did not reach the server), then nothing goes to the server
        suspend fun run(cmd: RobotCommand, own: OwnRequest?, demo: DemoWalk? = null): GuideOutcome {
            val local = demo != null && cmd.journeyId.isNullOrBlank()
            if (cmd.to == null || (!local && (cmd.journeyId.isNullOrBlank() || cmd.legId.isNullOrBlank()))) {
                RobotLog.w(LogSource.BRAIN, "Guide command without journey, leg or destination")
                if (!local) withContext(NonCancellable) { Clinic.net(quiet = true) { Backend.commandDone(cmd.id) } }
                return GuideOutcome.FAILED
            }
            val journeyId = cmd.journeyId.orEmpty()
            // it may have waited in otto's queue: skip it when the team closed the journey meanwhile
            if (own == null) {
                val j = Clinic.net(quiet = true) { Backend.journey(journeyId) }.getOrNull()
                if (j != null && JourneyStatus.closed(j.status)) {
                    RobotLog.i(LogSource.BRAIN, "Guide for ${cmd.ticket ?: "a patient"} skipped: the journey is ${j.status}")
                    withContext(NonCancellable) { Clinic.net(quiet = true) { Backend.commandDone(cmd.id) } }
                    return GuideOutcome.CANCELLED
                }
            }
            // docs/robot-setup.md 2: a place on another floor is refused, otto cannot take the elevator
            val placeMap = cmd.to.mapName ?: Clinic.placeForSpot(cmd.to.spot)?.mapName
            if (!Floors.onMap(placeMap, MapSync.current)) {
                val why = "${cmd.to.label} is on the map $placeMap and Otto is on ${MapSync.current}: he cannot take the elevator"
                RobotLog.w(LogSource.BRAIN, "Guide for ${cmd.ticket ?: "a patient"} refused: $why")
                if (!local) {
                    withContext(NonCancellable) {
                        Clinic.net(quiet = true) { Backend.patchJourney(journeyId, cmd.legId, LegStatus.FAILED, JourneyEvent.FAILED, why) }
                        Clinic.commandDone(cmd.id, CommandResult(false, why))
                    }
                }
                return GuideOutcome.FAILED
            }
            val g = Guide(cmd, own, demo)
            current = g
            Clinic.guideStarted()
            return try {
                g.run()
            } finally {
                current = null
                withContext(NonCancellable) {
                    if (!local) Clinic.net(quiet = true) { Backend.commandDone(cmd.id) }
                    Clinic.guideEnded()
                }
            }
        }
    }
}
