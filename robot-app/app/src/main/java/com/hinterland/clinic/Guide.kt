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
data class OwnRequest(val paceChosen: Boolean = false, val sample: String? = null)

enum class GuideOutcome { ARRIVED, NOBODY_CAME, LOST, FAILED, CANCELLED, STOPPED }

// one `guide` command (docs/journeys.md 4 and 6): pickup, pace, walking with check-backs, arrival.
// the decisions (check-back schedule, the walk-beside watch, short stops, where the patient is, the
// talk window, what to do per distance, blocked ways and detours, timelines, speeds) are plain kotlin in
// Walk.kt, Stops.kt, PatientTrack.kt, Detours.kt, Lidar.kt, Pickup.kt and Pace.kt; this class does the
// robot, the backend and the words. the walk's lines are made once per walk (WalkLines.kt).
// legs and events go to PATCH /api/journeys/:id with the ticket only, never a name.
class Guide private constructor(val cmd: RobotCommand, private val own: OwnRequest?) {

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

    // v3.3 (docs/journeys.md 6.7): the side the patient walks on, the walk's ready lines, where the patient is
    private val side = WalkSide.of(BuildConfig.WALK_SIDE)
    private val lines = WalkLines(LineContext(ticket, destination, side, arrival(), own != null))
    private val track = PatientTrack()
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
    private val legBlock = LegBlock()
    private val detours = DetourSteps()
    private val tried = mutableSetOf<String>()
    private var helpCalled = false
    private var blockedSince = 0L
    // legDriven at the last detour step: once otto drove on a few meters, the steps start again
    @Volatile private var jamDriven: Double? = null

    private fun now() = System.currentTimeMillis()

    // the patient's language: their own words once they spoke, else the call languages (robot.callLanguages)
    private val lang: Lang
        get() = sample?.let { Lang(null, it) } ?: Lang(callTags.firstOrNull())

    // for lines other parts say during this walk (walk_control)
    val speechLang: Lang get() = lang

    private val callLang: Lang
        get() = if (sample != null || callTags.isEmpty()) lang else Lang(null, tags = callTags)

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
                ),
            )
            RobotLog.i(LogSource.BRAIN, "Guide ${ticketOr()} to $destination" + if (own != null) " (asked at the robot)" else "")
            // the person is right here: their lines now
            if (own != null) requestLines("asked at the robot")
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
        callTicket(first = true)
        // while the patient comes over, the walk's lines are written (one llm call)
        requestLines("pickup")
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
            val raw = Voice.write(LineSet.SYSTEM, LineSet.prompt(lines.context, l), LineSet.MAX_TOKENS, LINES_TIMEOUT_MS)
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

    // a line of the walk: the ready one in the patient's language, at once. at the start of a walk the
    // lines may still be in the making: up to waitMs for them, then the llm for this one line (llmTask),
    // with the english line as its fallback
    private suspend fun say(key: LineKey, waitMs: Long = 0, llmTask: String? = null) {
        if (!lines.ready && waitMs > 0) lines.await(waitMs)
        if (lines.ready || llmTask == null) {
            if (!lines.localized(key)) RobotLog.d(LogSource.VOICE, "Ready line ${key.name.lowercase(Locale.ROOT)} in english: the walk's lines are not there")
            Clinic.sayLine(lines.text(key))
        } else {
            Clinic.speak(llmTask, lang, LineSet.english(key, lines.context))
        }
    }

    // the same while otto turns or drives on
    private fun sayLater(key: LineKey) {
        runScope?.launch { Clinic.sayLine(lines.text(key)) }
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
        track.noteStart(m.point, Robot.latestPose(), now())
        RobotLog.i(
            LogSource.VISION,
            "Patient at the start ($how): %.1f m, %+.0f deg (%s)".format(Locale.ROOT, m.distance, Bearing.of(m.point), m.source.wire),
        )
    }

    private suspend fun askPace() {
        val staff = cmd.pace ?: Pace.NORMAL
        if (!PaceRules.shouldAsk(staff, cmd.wheelchair, own?.paceChosen == true)) return
        setPhase(GuidePhase.PACE)
        say(
            LineKey.PACE_QUESTION, waitMs = FIRST_LINE_WAIT_MS,
            llmTask = "Ask the patient in one short, friendly question whether you should walk normally or a bit slower.",
        )
        withTimeoutOrNull(PACE_ANSWER_MS) { paceAnswer.await() }?.let { setPace(it, announce = false) }
    }

    private fun startTask(): String = buildString {
        if (own != null && ticket.isNotBlank()) append("Tell them their ticket is $ticket. ")
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
        // walk beside otto (6.7): the radar sees there, the back is blind
        say(LineKey.START, waitMs = FIRST_LINE_WAIT_MS, llmTask = startTask())

        val schedule = CheckSchedule()
        val watch = SideWatch()
        watch.start(now())
        RobotLog.i(
            LogSource.BRAIN,
            "Walk with ${ticketOr()} to $destination started, the patient walks on otto's ${side.wire}, " +
                "first check-back in ${schedule.nextInMs() / 1000} s",
        )
        // the way: straight to the destination, or over a waypoint when it is blocked (detour steps)
        var target = to.spot
        try {
            while (true) {
                if (cancelled) return cancelledNow()
                targetSpot = target
                val targetPoint = WalkMath.find(target, mapPlaces)?.point
                val o = Robot.goTo(
                    spot = target,
                    speed = PaceTable.speed(pace),
                    stopWhen = { OttoBrain.helpActive() || cancelled },
                    // no check-back while otto stands at an obstacle with the patient: it comes once he drives on
                    interrupt = { pauseWanted || wantedPace != null || (schedule.due(now()) != null && !legBlock.isBlocked) },
                    onTick = { blocked -> tick(blocked, schedule, watch, scope, targetPoint) },
                    onEvent = ::onNavEvent,
                    way = legBlock,
                )
                targetSpot = null
                schedule.pause()
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
    // the blocked count, and the check-backs that need no stop (the radar sees the patient beside)
    private fun tick(blocked: Boolean, schedule: CheckSchedule, watch: SideWatch, scope: CoroutineScope, targetPoint: Point?) {
        val t = now()
        schedule.tick(t, blocked, Robot.heading())
        // otto stands at an obstacle: the patient may step behind him, that is no reason for an alarm
        if (blocked) watch.hold() else if (watch.holding) watch.start(t)
        val beside = Radar.besideFix(t)
        if (beside != null) track.noteRadar(beside.robot, beside.map, beside.at)
        if (beside != null && schedule.noteDistance(beside.distance)) {
            RobotLog.i(LogSource.BRAIN, "Next check-back moved to ${schedule.nextInMs() / 1000} s (radar: patient %.1f m beside)".format(Locale.ROOT, beside.distance))
        }
        if (watch.check(t, Radar.everSeen(), Radar.lastSeenAt())) {
            sideLost = true
            RobotLog.i(LogSource.LIDAR, "Nobody beside on the radar for ${watch.thresholdMs / 1000} s, checking back now")
            schedule.followerLost()
        }
        // past the jam: the blocked count of this leg starts again
        if (legBlock.progress(t, targetPoint?.let { p -> here?.distanceTo(p) }, legDriven)) {
            RobotLog.i(LogSource.NAV, "Past the blocked spot, the blocked count starts again")
        }
        // a few meters past the last jam: a later one gets every detour step again, also its own team call
        val jam = jamDriven
        if (jam != null && legDriven - jam >= LegBlock.DRIVEN_M) {
            jamDriven = null
            detours.reset()
            helpCalled = false
            RobotLog.i(LogSource.NAV, "Drove on past the jam, the detour steps are ready again")
        }
        val due = schedule.due(t) ?: return
        if (due == CheckReason.FOLLOWER_LOST) return
        // the last meters: no turning around, the arrival itself is the check
        if (NearGoal.skipCheck(here, dest)) {
            RobotLog.i(LogSource.BRAIN, "Check-back (${due.detail}) skipped: almost at $destination")
            schedule.skip(Robot.heading())
            return
        }
        if (beside == null || blocked) return
        val kind = StopRules.kind(StopInput(due, checks + 1, beside.distance, lastCheckDistance, fellBack))
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
        scope.launch {
            Clinic.sayLine(lines.text(LineKey.CHECK_OK))
            patch(null, JourneyEvent.CHECK_BACK, "Patient beside, all fine (radar)")
        }
    }

    // ---- a blocked way (Detours.kt) ----

    // the way to `current` is blocked or has no path: the next detour step. the spot to drive to next, or
    // null when the steps are used up (then the team is called)
    private suspend fun nextWay(current: String, why: NavFailure): String? {
        val pose = Robot.latestPose() ?: Robot.robotPose()
        val at = pose?.point ?: here
        val near = WalkMath.nearestSpot(at, mapPlaces) ?: "an unknown spot"
        val left = waypointsLeft(at, pose?.theta, current)
        val step = detours.next(left.size)
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

    // the place's own waypoint first, then map spots that lead around the jam (DetourPlan)
    private fun waypointsLeft(at: Point?, heading: Double?, current: String): List<Waypoint> {
        val out = ArrayList<Waypoint>()
        val via = viaSpot()
        if (via != null && Names.key(via) !in tried && !Names.same(via, current)) out += Waypoint(via, null, null, explicit = true)
        val d = dest
        if (at != null && d != null) {
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
            Clinic.sayLine(lines.text(LineKey.BACK_UP))
            Robot.back(BACK_OFF_M)
        } else {
            false
        }
        val left = Radar.roomLeft() ?: (side == WalkSide.RIGHT)
        val turned = Robot.turn(DETOUR_TURN_DEG, left)
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
        Clinic.sayLine(lines.text(LineKey.DETOUR))
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
            Clinic.sayLine(lines.text(LineKey.BLOCKED_HELP))
        }
        if (now() - blockedSince >= BLOCKED_WAIT_MS) {
            RobotLog.w(LogSource.NAV, "Way to ${to.spot} blocked for ${(now() - blockedSince) / 1000} s after the team call: this leg ends")
            patch(LegStatus.FAILED, JourneyEvent.FAILED, "The way stayed blocked")
            Clinic.sayLine(lines.text(LineKey.GOING_BACK))
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

    private suspend fun sayMilestone(m: Milestone) {
        val key = when (m) {
            Milestone.HALF_WAY -> LineKey.HALF_WAY
            Milestone.ALMOST_THERE -> LineKey.ALMOST_THERE
            else -> return
        }
        Clinic.sayLine(lines.text(key))
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
        if (timedOut) return checkBack(CheckReason.REGULAR, null, scope).first
        setPhase(GuidePhase.WALKING)
        return null
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
    // first = null to walk on, else the end of the guide; second = how far the patient was
    private suspend fun checkBack(reason: CheckReason, watch: SideWatch?, scope: CoroutineScope): Pair<GuideOutcome?, Double?> {
        val stopAt = now()
        val no = checks + 1
        val beside = Radar.besideFix(stopAt)
        val kind = StopRules.kind(StopInput(reason, no, beside?.distance, lastCheckDistance, fellBack))
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
        val predicted = track.predict(now(), Robot.latestPose())
        val turn = TurnPlan.toward(predicted?.bearingDeg, side)
        // the radar saw them beside just now: the question already while otto turns to them
        val question = if (kind == StopKind.ASK) questionKey() else null
        var askedAt = 0L
        var asking: Job? = null
        if (question != null && beside != null) {
            askedAt = now()
            showTalk(question)
            asking = scope.launch { Clinic.sayLine(lines.text(question)) }
        }
        // the chassis may still be held right after stopNavigation: one more try
        val turned = turn == null || Robot.turn(turn.degrees, turn.left) || run {
            delay(TURN_RETRY_MS)
            Robot.turn(turn.degrees, turn.left)
        }
        val target = TurnPlan.target(predicted, if (turned) turn else null, Robot.latestPose(), side)
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
        Clinic.updateGuide { it.copy(talk = TalkUi(LineSet.english(key, lines.context), TalkStage.ASKING, id = id)) }
    }

    // a few looks after the turn (the camera needs a moment), over early when someone is right at the spot
    private suspend fun look(target: Target): Look {
        val samples = ArrayList<List<Double>?>()
        val people = ArrayList<SeenPerson>()
        var best: Match? = null
        var bestMap: Point? = null
        for (i in 0 until LOOKS) {
            delay(if (i == 0) FIRST_LOOK_MS else LOOK_EVERY_MS)
            val seen = Robot.people()
            samples += seen?.map { it.distance }
            seen?.let { people += it }
            val m = PatientMatch.best(seen.orEmpty(), Radar.aheadPoints(), target)
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
        val a = TurnPlan.ASIDE_DEG.toFloat()
        var turnedDeg = 0.0
        if (Robot.turn(a, left = firstLeft)) turnedDeg += if (firstLeft) a else -a
        var acc = look(TurnPlan.shifted(target, turnedDeg))
        if (acc.match != null) return acc.also { RobotLog.i(LogSource.VISION, "Found someone at the spot after looking ${if (firstLeft) "left" else "right"}") }
        if (Robot.turn(2 * a, left = !firstLeft)) turnedDeg += if (firstLeft) -2.0 * a else 2.0 * a
        val other = look(TurnPlan.shifted(target, turnedDeg))
        acc = acc.plus(other)
        if (other.match != null) return acc.also { RobotLog.i(LogSource.VISION, "Found someone at the spot after looking ${if (firstLeft) "right" else "left"}") }
        // back towards the spot for what comes next (waiting, calling out)
        Robot.turn(a, left = firstLeft)
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
            Clinic.sayLine(lines.text(key))
        }
        val window = ListenWindow(askedAt = since, startedAt = now())
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
        // otto's ai answers spoken replies itself; otherwise a short "let's go on" while he turns to the path
        if (!byVoice && wantedPace == null && !pauseWanted) sayLater(LineKey.GO_ON)
        allGood = false
        return byVoice
    }

    private suspend fun waitForFar(events: List<String>): GuideOutcome? {
        setPhase(GuidePhase.WAITING)
        OttoBrain.activity(RobotState.WALKING, "Waiting for ${ticketOr()}")
        Clinic.feed.ottoDid(now(), "Waiting for the patient to catch up")
        Clinic.sayLine(lines.text(LineKey.WAIT_FOR_YOU))
        patch(null, JourneyEvent.WAITING_FOR_PATIENT, "Patient 3-6 m behind, Otto waits")
        val wait = FarWait(now())
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
        Clinic.sayLine(lines.text(if (slower) LineKey.THANKS_SLOWER else LineKey.GO_ON))
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
        val timeline = LostTimeline()
        val startedAt = now()
        while (true) {
            if (cancelled) return cancelledNow()
            when (timeline.next(now() - startedAt)) {
                LostStep.CALL -> Clinic.sayLine(lines.text(LineKey.CALL_OUT))
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
                    Clinic.sayLine(lines.text(LineKey.GOING_BACK))
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
                Clinic.sayLine(lines.text(if (slower) LineKey.FOUND_AGAIN else LineKey.SIDE_REMINDER))
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
        // the arrival line was written with the others (room, waiting area or exit)
        Clinic.sayLine(lines.text(LineKey.ARRIVED))
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
        )
        delay(3_000)
        return end(GuideOutcome.FAILED)
    }

    private suspend fun cancelledNow(): GuideOutcome {
        setPhase(GuidePhase.CANCELLED)
        RobotLog.i(LogSource.BRAIN, "The team ended the walk of ${ticketOr()}")
        Clinic.speak("Say briefly that the team has ended this walk and a team member will help.", lang, "The team ended this walk.")
        delay(2_000)
        return result(GuideOutcome.CANCELLED)
    }

    // staff may cancel or close the journey while otto is on it
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

    // blocked: ask people in front to let otto pass (the leg's count decides when), with the walk's ready line
    private fun onNavEvent(ev: NavEvent) {
        Clinic.onNavEvent(ev, lang, ready = lines.text(LineKey.EXCUSE_ME))
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
        private const val PACE_ANSWER_MS = 12_000L
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

        // the tags the ticket call is said in (local.properties robot.callLanguages, default "de,en")
        val callTags: List<String> by lazy { Lang.tagList(BuildConfig.CALL_LANGUAGES) }

        // the guide that runs right now, for the actions and the screen
        @Volatile var current: Guide? = null
            private set

        // runs one guide command to its end. the command is closed (done) on every path.
        suspend fun run(cmd: RobotCommand, own: OwnRequest?): GuideOutcome {
            if (cmd.to == null || cmd.journeyId.isNullOrBlank() || cmd.legId.isNullOrBlank()) {
                RobotLog.w(LogSource.BRAIN, "Guide command without journey, leg or destination")
                withContext(NonCancellable) { Clinic.net(quiet = true) { Backend.commandDone(cmd.id) } }
                return GuideOutcome.FAILED
            }
            // it may have waited in otto's queue: skip it when the team closed the journey meanwhile
            if (own == null) {
                val j = Clinic.net(quiet = true) { Backend.journey(cmd.journeyId) }.getOrNull()
                if (j != null && JourneyStatus.closed(j.status)) {
                    RobotLog.i(LogSource.BRAIN, "Guide for ${cmd.ticket ?: "a patient"} skipped: the journey is ${j.status}")
                    withContext(NonCancellable) { Clinic.net(quiet = true) { Backend.commandDone(cmd.id) } }
                    return GuideOutcome.CANCELLED
                }
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
