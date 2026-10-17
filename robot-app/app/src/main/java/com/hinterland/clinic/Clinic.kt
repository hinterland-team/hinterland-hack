package com.hinterland.clinic

import com.ainirobot.agent.AgentCore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

// the screen state, what the actions and the screen buttons do, and the team's small commands.
// a guide itself runs in Guide; what nova does on her own is decided by NovaBrain.
// network on Dispatchers.IO, never on the main thread. no patient names anywhere: tickets only.
object Clinic {

    private const val OBSTACLE_TALK_EVERY_MS = 10_000L
    private const val STAFF_REFRESH_MS = 1_000L
    // the staff menu closes by itself when nobody touches it
    private const val STAFF_IDLE_MS = 2 * 60_000L
    private const val NET_LOG_EVERY_MS = 60_000L
    // the self check-in screen closes by itself when nobody touches it
    private const val CHECK_IN_IDLE_MS = 90_000L
    private const val GUIDE_CARD_KEEP_MS = 4_000L

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    // lines for the dashboard's live feed, sent by NovaBrain
    val feed = FeedBuffer()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val started = AtomicBoolean(false)

    @Volatile private var lastObstacleTalk = 0L
    @Volatile private var lastUserText: String? = null
    @Volatile private var lastTouchAt = 0L
    @Volatile private var speakingJob: Job? = null
    @Volatile private var flashJob: Job? = null
    @Volatile private var guideCardJob: Job? = null
    @Volatile private var staffJob: Job? = null
    @Volatile private var staffTouchedAt = 0L

    private fun now() = System.currentTimeMillis()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        // goal, battery and robot state on the screen
        scope.launch {
            NovaBrain.status.collect { b -> _ui.update { it.copy(brain = b) } }
        }
        // keep the planner in sync with what the screen shows
        scope.launch {
            ui.map { it.screenInfo() }.distinctUntilChanged().collect { info ->
                runCatching { AgentCore.uploadInterfaceInfo(info) }
            }
        }
    }

    fun refreshScreenInfo() {
        runCatching { AgentCore.uploadInterfaceInfo(_ui.value.screenInfo()) }
    }

    // ---- listeners (worker threads) ----

    fun onUserSpeech(text: String, final: Boolean) {
        if (text.isBlank()) return
        NovaBrain.heardUser()
        if (final) {
            lastUserText = text
            Guide.current?.heard(text)
            // the raw words leave the robot only with the dashboard's demo switch on
            feed.patientSaid(now(), text)
        }
        // the patient's own words are never shown (they could say their name): only that nova listens
        _ui.update { it.copy(subtitle = "", subtitleFromUser = true) }
    }

    fun onRobotSpeech(text: String, final: Boolean) {
        if (text.isBlank()) return
        NovaBrain.heardRobot()
        if (final) feed.novaSaid(now(), text)
        _ui.update { it.copy(subtitle = text, subtitleFromUser = false, speaking = true) }
        speakingJob?.cancel()
        speakingJob = scope.launch {
            delay(if (final) 600 else 4_000)
            _ui.update { it.copy(speaking = false) }
        }
    }

    fun onAgentStatus(status: String, message: String?) {
        val mode = when (status) {
            "listening" -> AgentMode.LISTENING
            "thinking", "processing" -> AgentMode.THINKING
            else -> AgentMode.IDLE
        }
        NovaBrain.agentStatus(mode)
        val note = if (status == "processing") message?.trim()?.takeIf { it.isNotEmpty() } else null
        _ui.update { it.copy(agent = mode, agentNote = note) }
    }

    fun onRobotHealth(ok: Boolean, note: String?) {
        _ui.update { it.copy(robot = if (ok) Health.OK else Health.DOWN, robotNote = note) }
    }

    fun reportError(e: Throwable) {
        RobotLog.e(LogSource.VOICE, "Action failed: ${e.javaClass.simpleName} ${e.message.orEmpty().take(120)}")
        _ui.update { it.copy(banner = "Something went wrong: ${e.message ?: e.javaClass.simpleName}") }
    }

    // ---- chassis (out-of-service screen, docs/journeys.md 6.6) ----

    // called by the brain every second
    fun setChassis(issue: ChassisIssue?) {
        val before = _ui.value.chassis
        if (before == issue) return
        if (issue != null) RobotLog.e(LogSource.NAV, "Out of service: ${issue.detail}")
        else if (before != null) RobotLog.i(LogSource.NAV, "Chassis ready again")
        _ui.update { it.copy(chassis = issue) }
    }

    // ---- staff menu (long-press the Nova logo 3 s) ----

    fun openStaff() {
        staffTouchedAt = now()
        RobotLog.i(LogSource.APP, "Staff menu opened")
        _ui.update { it.copy(staff = staffSnapshot()) }
        staffJob?.cancel()
        staffJob = scope.launch {
            while (true) {
                delay(STAFF_REFRESH_MS)
                if (now() - staffTouchedAt > STAFF_IDLE_MS) {
                    _ui.update { it.copy(staff = null) }
                    break
                }
                _ui.update { s -> if (s.staff != null) s.copy(staff = staffSnapshot()) else s }
            }
        }
    }

    fun closeStaff() {
        staffJob?.cancel()
        _ui.update { it.copy(staff = null) }
    }

    fun staffTouched() {
        staffTouchedAt = now()
    }

    fun staffRecheckServers() {
        staffTouched()
        scope.launch {
            Servers.checkAll()
            _ui.update { s -> if (s.staff != null) s.copy(staff = staffSnapshot()) else s }
        }
    }

    private fun staffSnapshot(): StaffUi {
        val b = _ui.value.brain
        val issue = Robot.chassisIssue()
        return StaffUi(
            robotName = Backend.robot,
            version = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            system = Robot.systemVersion,
            servers = Servers.states.value,
            current = Servers.current,
            chassis = when {
                issue != null -> issue.detail
                !Robot.connected -> "Connecting to the robot"
                else -> "Ready" + (Robot.position?.let { ", at $it" } ?: "")
            },
            chassisOk = issue == null && Robot.ready,
            emergency = Robot.emergency,
            battery = Robot.battery,
            charging = Robot.chargingNow(),
            radar = Radar.summary,
            brain = "${b.state.label}: ${b.goal}",
            logs = RobotLog.recent.value,
            logsPending = RobotLog.pending(),
        )
    }

    // ---- places (GET /api/places) ----

    suspend fun refreshPlaces(): List<Place>? {
        val list = net(quiet = true) { Backend.places() }.getOrNull() ?: return null
        _ui.update { it.copy(places = list) }
        Robot.homeFromPlaces = Places.homeSpot(list, BuildConfig.HOME_SPOT).ifBlank { null }
        return list
    }

    private suspend fun loadPlaces(): List<Place> = refreshPlaces() ?: _ui.value.places

    fun placeForSpot(spot: String?): Place? {
        val s = spot?.trim().orEmpty()
        if (s.isEmpty()) return null
        return _ui.value.places.firstOrNull { Names.key(it.spot) == Names.key(s) }
    }

    // ---- guide screen (Guide) ----

    fun guideStarted() {
        guideCardJob?.cancel()
        // the patient walks behind nova: the mic has to hear them without facing the robot.
        // barge-in stays on (speaking over nova stops her tts, AgentOS default).
        setWakeFree(false)
        _ui.update { it.copy(checkIn = null) }
    }

    fun guideEnded() {
        setWakeFree(true)
        // the last card (arrived, nobody came, failed) stays a moment, then the welcome screen
        guideCardJob?.cancel()
        guideCardJob = scope.launch {
            delay(GUIDE_CARD_KEEP_MS)
            if (Guide.current == null) _ui.update { it.copy(guide = null) }
        }
    }

    fun showGuide(g: GuideUi?) {
        guideCardJob?.cancel()
        _ui.update { it.copy(guide = g) }
    }

    fun updateGuide(f: (GuideUi) -> GuideUi) {
        _ui.update { s -> s.guide?.let { s.copy(guide = f(it)) } ?: s }
    }

    private fun setWakeFree(on: Boolean) {
        runCatching { AgentCore.isEnableWakeFree = on }.onFailure { RobotLog.w(LogSource.VOICE, "Wake-free not set: ${it.message}") }
    }

    // ---- actions (ClinicActions). sample = what the person said, for the language ----

    // CONFIRM_TICKET: the person says the ticket nova calls
    suspend fun confirmTicket(ticket: String?, sample: String?): Boolean {
        val t = Tickets.normalize(ticket)
        val g = Guide.current
        val lang = Lang(null, sample)
        feed.patientIntent(now(), if (t != null) "Said ticket $t" else "Said it is them")
        if (g != null && g.waitingForPickup) {
            if (t == null) {
                speak("Ask them kindly to say their ticket number or tap That's me on the screen.", lang, "Please tap \"That's me\".")
                return true
            }
            g.heard(sample.orEmpty())
            if (g.confirm(t, Guide.HOW_VOICE)) return true
            speak(
                "Tell them kindly that you are calling ticket ${g.ticket} right now, not $t, and that the team will send you for them soon.",
                lang,
                "I'm calling ${g.ticket} right now.",
            )
            return true
        }
        if (t != null) return waitingStatus(t, sample)
        speak("Ask them kindly for their ticket number: one letter and two digits.", lang, "What is your ticket number?")
        return true
    }

    // SET_PACE: walk slower / faster, or go on after a pause
    suspend fun setPace(pace: Pace, sample: String?): Boolean {
        feed.patientIntent(now(), "Asked to walk at ${pace.label.lowercase()}")
        val g = Guide.current
        val lang = Lang(null, sample)
        if (g != null && g.phase != GuidePhase.ARRIVED) {
            g.heard(sample.orEmpty())
            val asked = g.phase == GuidePhase.PACE
            g.choosePace(pace)
            // during the pace question the guide's next line already answers
            if (!asked) speak("Confirm in a few warm words that you walk at ${pace.label.lowercase()} now.", lang, pace.label)
            return true
        }
        speak("Say in a few words that you will walk at that pace when you walk together.", lang, pace.label)
        return true
    }

    // PAUSE_WALK
    suspend fun pauseWalk(sample: String?): Boolean {
        feed.patientIntent(now(), "Asked Nova to wait")
        val lang = Lang(null, sample)
        val g = Guide.current
        if (g != null && g.pause()) {
            g.heard(sample.orEmpty())
            speak("Say: of course, you wait right here, they should just tell you when they are ready. Short.", lang, "Of course, I'll wait.")
            return true
        }
        speak("Say kindly that you are not walking right now.", lang, "I'm not walking right now.")
        return true
    }

    // WALK_STATUS: how far, how long, where are we
    suspend fun walkStatus(sample: String?): Boolean {
        feed.patientIntent(now(), "Asked how far it is")
        val lang = Lang(null, sample)
        val g = Guide.current
        if (g != null && g.walking) {
            speak(
                "Answer the patient's question about the walk in one or two short sentences, using only these facts. ${g.statusFacts()}",
                lang,
                g.destination,
            )
            return true
        }
        speak("Say kindly that you are not walking with anyone right now.", lang, "We're not walking right now.")
        return true
    }

    // GUIDE_TO_PLACE: public places only, rooms are the reception desk's job
    suspend fun guideToPlace(place: String?, sample: String?): Boolean {
        val lang = Lang(null, sample)
        if (busyWalking(lang)) return false
        val places = loadPlaces()
        val public = Places.public(places)
        val match = Places.find(place, public)
        if (match == null) {
            val known = Places.find(place, places)
            speak(
                buildString {
                    if (known != null) append("Explain kindly that the reception desk assigns treatment rooms, so you cannot take them to ${known.label} yourself. ")
                    else append("Say kindly that you do not know this place. ")
                    if (public.isNotEmpty()) append("Offer to take them to one of these places: ${Places.spoken(public)}. ")
                    append("Short.")
                },
                lang,
                "The reception desk assigns rooms.",
            )
            return true
        }
        return startOwnJourney(match, null, null, null, OwnRequest(paceChosen = false, sample = sample), "Asked to be taken to ${match.label}", lang)
    }

    // CHECK_IN_FOR: self check-in by voice for a room marked selfCheckIn
    suspend fun checkInFor(place: String?, sample: String?): Boolean {
        val lang = Lang(null, sample)
        if (busyWalking(lang)) return false
        val places = loadPlaces()
        val rooms = Places.selfCheckIn(places)
        val reception = Places.reception(places)
        val room = Places.find(place, rooms) ?: Places.find(place, listOfNotNull(reception))
        if (room == null) {
            speak(
                if (rooms.isEmpty()) "Say kindly that the team at the reception desk will check them in."
                else "Ask kindly which of these they are here for: ${Places.spoken(rooms)}. If they are not sure, the team at the reception desk helps.",
                lang,
                "Who are you here to see?",
            )
            return true
        }
        if (room.kind == PlaceKind.RECEPTION) return notSure(lang)
        val target = Places.checkInTarget(room, places)
        return startOwnJourney(target.to, room.id, null, null, OwnRequest(paceChosen = false, sample = sample), "Checked in for ${room.label}", lang)
    }

    // "I'm not sure": at the reception the desk helps, elsewhere nova walks them there
    private suspend fun notSure(lang: Lang): Boolean {
        val reception = Places.reception(_ui.value.places)
        if (reception == null || Robot.atHome == true) {
            speak("Say kindly that the team at the reception desk will help them, right here.", lang, "The team at the desk will help you.")
            return true
        }
        return startOwnJourney(reception, null, null, null, OwnRequest(), "Not sure who they are here for", lang)
    }

    private suspend fun busyWalking(lang: Lang): Boolean {
        if (Robot.chassisIssue() != null) {
            speak(
                "Say kindly that you cannot walk right now because your driving is switched off, and that the team at the reception desk will help.",
                lang,
                "I can't walk right now. The desk will help you.",
            )
            return true
        }
        if (Guide.current == null && !NovaBrain.helpActive()) return false
        speak(
            "Say kindly that you are walking with someone right now and the team at the reception desk can help.",
            lang,
            "I'm busy right now. The desk can help you.",
        )
        return true
    }

    // POST /api/journeys for the person in front of nova; the guide starts at once (no pickup)
    private suspend fun startOwnJourney(
        to: Place,
        forPlaceId: String?,
        pace: Pace?,
        wheelchair: Boolean?,
        own: OwnRequest,
        intent: String,
        lang: Lang,
    ): Boolean {
        feed.patientIntent(now(), intent)
        setTask("Getting your ticket")
        val r = net { Backend.createJourney(to.id, forPlaceId, pace, wheelchair) }
        setTask(null)
        val created = r.getOrElse { e ->
            val forbidden = (e as? BackendException)?.code == 403
            flash(Mood.SORRY)
            speak(
                if (forbidden) "Explain kindly that the reception desk has to send you there."
                else "Apologize: the practice system is not reachable right now, the team at the reception desk will help.",
                lang,
                "Sorry, that didn't work. The desk will help you.",
            )
            return false
        }
        created.journey?.id?.let { NovaBrain.expectOwn(it, own) }
        created.command?.let { NovaBrain.addOwnCommand(it, own) }
        return true
    }

    // WAITING_STATUS by ticket
    suspend fun waitingStatus(ticket: String?, sample: String?): Boolean {
        val lang = Lang(null, sample)
        val t = Tickets.normalize(ticket)
        if (t == null) {
            speak("Ask kindly for their ticket number: one letter and two digits, it is on the robot screen or the staff gave it to them.", lang, "What is your ticket number?")
            return true
        }
        feed.patientIntent(now(), "Asked about the wait for ticket $t")
        setTask("Looking up ticket $t")
        val r = net { Backend.journeyByTicket(t) }
        setTask(null)
        val j = r.getOrElse {
            speak("Apologize: you cannot look it up right now. The team at the reception desk can help.", lang, "Sorry, I can't look that up right now.")
            return false
        }
        if (j == null) {
            speak("Say you could not find ticket $t for today and suggest asking at the reception desk.", lang, "I couldn't find ticket $t.")
            return true
        }
        speak(
            "Tell the person where their visit stands in one or two short sentences, using only these facts. ${journeyFacts(j)}",
            lang,
            "Ticket $t",
        )
        return true
    }

    private fun journeyFacts(j: Journey): String = buildString {
        val where = j.leg?.toName ?: "their room"
        append("Ticket ${j.ticket}. ")
        append(
            when (j.status) {
                JourneyStatus.WAITING_PICKUP -> "Nova will pick them up for $where soon. "
                JourneyStatus.WALKING -> "Nova is walking them to $where right now. "
                JourneyStatus.ARRIVED -> "They are at $where and the team knows they are there. "
                JourneyStatus.WITH_STAFF -> "They are with the team at $where. "
                JourneyStatus.DONE -> "The visit is finished. "
                JourneyStatus.CANCELLED -> "This ticket was cancelled, the reception desk can help. "
                else -> ""
            },
        )
        when {
            j.queuePosition == 1 -> append("Next in line. ")
            j.queuePosition != null -> append("${j.queuePosition - 1} ahead of them in line. ")
        }
        j.estimatedWaitMinutes?.let { append(if (it > 0) "Expected wait about $it minutes. " else "Expected wait: almost none. ") }
    }

    // CALL_STAFF: no health details in the summary
    suspend fun callStaff(summary: String, urgency: String, sample: String?): Boolean {
        val urgent = urgency == "urgent"
        // stay with the person from now on, even before the message is out
        if (urgent) {
            NovaBrain.helpStarted()
            _ui.update { it.copy(help = HelpUi(urgent = true, sent = null)) }
        }
        feed.patientIntent(now(), if (urgent) "Needs urgent help" else "Asked for a team member")
        val lang = Lang(null, sample)
        val g = Guide.current
        setTask(if (urgent) "Alerting the practice team" else "Calling the practice team")
        val result = net { Backend.help(summary, if (urgent) "urgent" else "normal", g?.journeyId, g?.ticket) }
        if (urgent) {
            NovaBrain.helpSent(result.getOrNull())
            _ui.update { it.copy(help = HelpUi(urgent = true, sent = result.isSuccess)) }
        }
        val sent = result.isSuccess
        setTask(null)
        if (sent) {
            // no happy face in an emergency: she stays concerned with the person
            if (!urgent) flash(Mood.RELIEVED)
            speak(
                "Tell the person the practice team has been informed and someone will come to them shortly." +
                    if (urgent) " Because it is urgent, also tell them clearly to call the emergency number 112 right away, and that you stay with them." else "",
                lang,
                if (urgent) "The team is informed. Please call 112 now." else "The team is informed and will come to you.",
            )
        } else {
            flash(Mood.SORRY)
            speak(
                "Apologize: you could not reach the practice team right now." +
                    if (urgent) " Tell them clearly to call the emergency number 112 right away." else " Ask them to wait a moment.",
                lang,
                if (urgent) "Please call 112 now." else "Sorry, I couldn't reach the team.",
            )
        }
        return sent
    }

    // urgent help is over (closed on the board or timed out)
    fun helpEnded() {
        _ui.update { if (it.help != null) it.copy(help = null) else it }
    }

    // ---- screen buttons (main thread) ----

    fun tapThatsMe() {
        touched()
        val g = Guide.current ?: return
        if (g.confirm(null, Guide.HOW_TAP)) feed.patientIntent(now(), "Tapped That's me for ticket ${g.ticket}")
    }

    fun tapPace(pace: Pace) {
        touched()
        Guide.current?.choosePace(pace)
        feed.patientIntent(now(), "Chose ${pace.label.lowercase()} on the screen")
    }

    fun tapImHere() {
        touched()
        Guide.current?.present()
        feed.patientIntent(now(), "Tapped I'm here")
    }

    fun tapGoOn() {
        touched()
        Guide.current?.goOn()
        feed.patientIntent(now(), "Ready to go on")
    }

    // the talk at a check-back: "All good", "Slower", "Wait"
    fun tapAllGood() {
        touched()
        Guide.current?.allGood()
        feed.patientIntent(now(), "Tapped all good")
    }

    fun tapTalkSlower() {
        touched()
        val g = Guide.current ?: return
        g.choosePace(g.pace.slower())
        feed.patientIntent(now(), "Tapped slower")
    }

    fun tapTalkWait() {
        touched()
        Guide.current?.pause()
        feed.patientIntent(now(), "Tapped wait")
    }

    fun tapFeedback(up: Boolean) {
        touched()
        val g = Guide.current ?: return
        g.rate(up)
        updateGuide { it.copy(feedback = if (up) "up" else "down") }
        flash(if (up) Mood.LOVE else Mood.SORRY, 3_000)
    }

    // self check-in: who -> pace -> confirm
    fun tapCheckIn() {
        touched()
        if (Guide.current != null) return
        val rooms = Places.selfCheckIn(_ui.value.places)
        _ui.update { it.copy(checkIn = CheckInUi(step = CheckInStep.WHO, rooms = rooms)) }
        flash(Mood.SURPRISED, 900)
        feed.patientIntent(now(), "Started the self check-in")
        scope.launch {
            val fresh = refreshPlaces() ?: return@launch
            _ui.update { s -> s.checkIn?.let { c -> s.copy(checkIn = c.copy(rooms = Places.selfCheckIn(fresh))) } ?: s }
        }
    }

    // null = "I'm not sure"
    fun tapRoom(room: Place?) {
        touched()
        val reception = Places.reception(_ui.value.places)
        _ui.update { s ->
            val c = s.checkIn ?: return@update s
            when {
                room != null -> s.copy(checkIn = c.copy(step = CheckInStep.PACE, room = room))
                reception == null || Robot.atHome == true -> s.copy(checkIn = c.copy(step = CheckInStep.ASK_DESK, room = null))
                else -> s.copy(checkIn = c.copy(step = CheckInStep.PACE, room = reception))
            }
        }
    }

    fun tapCheckInPace(pace: Pace, wheelchair: Boolean) {
        touched()
        _ui.update { s -> s.checkIn?.let { c -> s.copy(checkIn = c.copy(step = CheckInStep.CONFIRM, pace = pace, wheelchair = wheelchair)) } ?: s }
    }

    fun tapCheckInBack() {
        touched()
        _ui.update { s ->
            val c = s.checkIn ?: return@update s
            when (c.step) {
                CheckInStep.PACE, CheckInStep.ASK_DESK -> s.copy(checkIn = c.copy(step = CheckInStep.WHO, room = null))
                CheckInStep.CONFIRM, CheckInStep.FAILED -> s.copy(checkIn = c.copy(step = CheckInStep.PACE, error = null))
                else -> s.copy(checkIn = null)
            }
        }
    }

    fun tapCheckInClose() {
        touched()
        _ui.update { it.copy(checkIn = null) }
    }

    fun tapCheckInConfirm() {
        touched()
        val c = _ui.value.checkIn ?: return
        val room = c.room ?: return
        _ui.update { s -> s.checkIn?.let { s.copy(checkIn = it.copy(step = CheckInStep.SENDING)) } ?: s }
        scope.launch {
            val places = _ui.value.places
            val target = if (room.kind == PlaceKind.RECEPTION) CheckInTarget(room, room) else Places.checkInTarget(room, places)
            val forId = room.id.takeIf { room.kind != PlaceKind.RECEPTION }
            feed.patientIntent(now(), "Checked in for ${room.label} on the screen")
            val r = net { Backend.createJourney(target.to.id, forId, c.pace, c.wheelchair) }
            r.onSuccess { created ->
                val own = OwnRequest(paceChosen = true, sample = lastUserText)
                created.journey?.id?.let { NovaBrain.expectOwn(it, own) }
                // the guide screen takes over as soon as the guide starts (guideStarted closes this one)
                created.command?.let { NovaBrain.addOwnCommand(it, own) }
            }.onFailure { e ->
                _ui.update { s ->
                    s.checkIn?.let { s.copy(checkIn = it.copy(step = CheckInStep.FAILED, error = Backend.friendly(e))) } ?: s
                }
                flash(Mood.SORRY)
            }
        }
    }

    private fun touched() {
        lastTouchAt = now()
        NovaBrain.heardUser()
    }

    // called by the brain every second
    fun expireCheckIn(now: Long) {
        val c = _ui.value.checkIn ?: return
        if (c.step == CheckInStep.SENDING) return
        if (now - lastTouchAt > CHECK_IN_IDLE_MS) _ui.update { it.copy(checkIn = null) }
    }

    // ---- commands from the team (run by NovaBrain, holding the body lock) ----

    suspend fun pollCommand(): RobotCommand? = net(quiet = true) { Backend.nextCommand() }.getOrNull()

    suspend fun commandDone(id: String) {
        withContext(NonCancellable) { net(quiet = true) { Backend.commandDone(id) } }
    }

    // go_to: drive to the spot and stay there. true when she got there.
    suspend fun goToSpot(cmd: RobotCommand, excuse: Boolean): Boolean {
        try {
            val spot = cmd.spot?.trim().orEmpty()
            if (spot.isEmpty()) return false
            if (excuse) excuseMe()
            val home = Robot.isHome(spot)
            NovaBrain.activity(
                if (home) RobotState.RETURNING else RobotState.WALKING,
                if (home) "Driving back to $spot" else "Going to $spot",
            )
            setWalking(true)
            val outcome = try {
                Robot.goTo(spot, stopWhen = { NovaBrain.helpActive() }, onEvent = { onNavEvent(it) })
            } finally {
                setWalking(false)
            }
            if (outcome is NavOutcome.Failed) NovaBrain.failed("Could not reach $spot: ${outcome.why.screen}")
            return outcome == NavOutcome.Arrived
        } finally {
            commandDone(cmd.id)
        }
    }

    // say: the team typed it, nova says it as it is
    suspend fun sayFromTeam(cmd: RobotCommand) {
        try {
            val text = cmd.text?.trim().orEmpty()
            if (text.isEmpty()) return
            _ui.update { it.copy(subtitle = text, subtitleFromUser = false) }
            Voice.say(text)
        } finally {
            commandDone(cmd.id)
        }
    }

    // ask: the team's text goes to AgentOS like something a person said, so nova's ai decides (query)
    suspend fun askFromTeam(cmd: RobotCommand) {
        try {
            val text = cmd.text?.trim().orEmpty()
            if (text.isEmpty()) return
            feed.novaDid(now(), "Message from the team: $text")
            Voice.ask(text)
        } finally {
            commandDone(cmd.id)
        }
    }

    // a command type this app does not know: close it so it does not hang on the board
    suspend fun skipCommand(cmd: RobotCommand) {
        RobotLog.w(LogSource.BRAIN, "Unknown command type ${cmd.type}")
        commandDone(cmd.id)
    }

    // walk_control from the patient's phone or the dashboard (docs/journeys.md 6.4). runs at once,
    // also while nova walks; closed on every path
    suspend fun walkControl(cmd: RobotCommand) {
        try {
            val c = WalkControl.of(cmd.action)
            val g = Guide.current
            if (c == null || g == null || (cmd.journeyId != null && cmd.journeyId != g.journeyId)) {
                RobotLog.i(LogSource.BRAIN, "Walk control ${cmd.action ?: "?"} ignored: no matching walk")
                return
            }
            val applied = g.control(c)
            RobotLog.i(LogSource.BRAIN, "Walk control ${c.wire} for ${g.ticket.ifBlank { "a patient" }}" + if (applied) "" else " (nothing to change)")
            feed.patientIntent(
                now(),
                when (c) {
                    WalkControl.WAIT -> "Asked Nova to wait (phone)"
                    WalkControl.RESUME -> "Ready to go on (phone)"
                    WalkControl.SLOWER -> "Asked to walk slower (phone)"
                    WalkControl.FASTER -> "Asked to walk faster (phone)"
                },
            )
            if (applied) {
                speakLater(
                    when (c) {
                        WalkControl.WAIT -> "Say: of course, you wait right here until they are ready. Short."
                        WalkControl.RESUME -> "Say briefly and warmly that you walk on now."
                        WalkControl.SLOWER -> "Confirm in a few warm words that you walk a bit slower now."
                        WalkControl.FASTER -> "Confirm in a few warm words that you walk a bit faster now."
                    },
                    g.speechLang,
                )
            }
        } finally {
            commandDone(cmd.id)
        }
    }

    // ask with a journey = the patient's phone ("Talk"): their words, so they only go to the dashboard
    // feed with the demo switch on and never to the log. nova's ai answers right away, also mid-walk
    suspend fun askDuringWalk(cmd: RobotCommand) {
        try {
            val text = cmd.text?.trim().orEmpty()
            if (text.isEmpty()) return
            // with a journey it comes from the walk page on the patient's phone
            if (cmd.journeyId != null) {
                Guide.current?.takeIf { it.journeyId == cmd.journeyId }?.heard(text)
                feed.patientIntent(now(), "Talked to Nova through the phone")
                feed.patientSaid(now(), text)
                RobotLog.i(LogSource.VOICE, "Patient talked through the phone")
            } else {
                feed.novaDid(now(), "Message from the team: $text")
            }
            NovaBrain.heardUser()
            Voice.ask(text)
        } finally {
            commandDone(cmd.id)
        }
    }

    // nova has to go while someone talks to her (after waiting up to 20 s)
    private suspend fun excuseMe() {
        speak(
            "Politely tell the person you are talking with that you have to go now because the practice team needs you, " +
                "and that you will be back in a moment.",
            Lang(null, lastUserText),
            "Sorry, I have to go. I'll be right back.",
        )
    }

    // ---- things nova says on her own (brain tasks) ----

    suspend fun greet() {
        speak(
            "Greet a visitor who just walked up to you at the reception of a doctor's practice. " +
                "In one or two short sentences offer to check them in or show them the way.",
            Lang(null),
            "Hello! I can check you in or show you the way.",
        )
    }

    suspend fun sayGoingToCharge() {
        Voice.speak("Tell the people around you in one short sentence that your battery is low and you are going to your charger now.", Lang(null))
    }

    fun setWalking(on: Boolean) {
        _ui.update { it.copy(walking = on) }
    }

    // blocked: ask people to let nova pass, at most every OBSTACLE_TALK_EVERY_MS
    fun onNavEvent(ev: NavEvent, lang: Lang = Lang(null)) {
        when (ev) {
            NavEvent.BLOCKED -> {
                setTask("Something is in my way")
                val now = now()
                if (now - lastObstacleTalk >= OBSTACLE_TALK_EVERY_MS) {
                    lastObstacleTalk = now
                    scope.launch { Voice.speak("Politely ask the people in your way to let you pass.", lang) }
                }
            }
            NavEvent.CLEAR -> setTask(null)
        }
    }

    // ---- idle ----

    fun hasContent(): Boolean = _ui.value.let { it.subtitle.isNotBlank() || it.guide != null || it.checkIn != null }

    // back to the welcome screen; the next patient starts with a fresh llm context
    fun idleReset() {
        if (Guide.current != null) return
        _ui.update { it.copy(subtitle = "", task = null, checkIn = null, guide = null) }
        lastUserText = null
        runCatching { AgentCore.clearContext() }
    }

    // ---- helpers ----

    // english instruction -> a spoken line in the person's language; the screen text if the llm fails
    suspend fun speak(task: String, lang: Lang, screen: String) {
        val ok = working { Voice.speak(task, lang) }
        if (!ok) {
            RobotLog.w(LogSource.VOICE, "Speech failed, showing it on the screen")
            _ui.update { it.copy(subtitle = screen, subtitleFromUser = false) }
        }
    }

    // the same, without waiting (nova turns or drives meanwhile)
    fun speakLater(task: String, lang: Lang) {
        scope.launch { Voice.speak(task, lang) }
    }

    private suspend fun <T> working(block: suspend () -> T): T {
        _ui.update { it.copy(working = it.working + 1) }
        try {
            return block()
        } finally {
            _ui.update { it.copy(working = (it.working - 1).coerceAtLeast(0)) }
        }
    }

    // network call on IO; quiet = no "thinking" face (polling, status patches)
    suspend fun <T> net(quiet: Boolean = false, block: () -> T): Result<T> {
        val call: suspend () -> Result<T> = {
            withContext(Dispatchers.IO) { runCatching(block) }
                .onSuccess { _ui.update { it.copy(backend = Health.OK, banner = null) } }
                .onFailure { e ->
                    val why = Backend.friendly(e)
                    netLog(why)
                    // a 404/403 is an answer, not an outage
                    if ((e as? BackendException)?.code !in setOf(403, 404, 409)) {
                        _ui.update { it.copy(backend = Health.DOWN, banner = "Practice server: $why") }
                    }
                }
        }
        return if (quiet) call() else working { call() }
    }

    // the backend may be down for a while: one log line per reason and minute is enough
    @Volatile private var netLogged: Pair<String, Long>? = null

    private fun netLog(why: String) {
        val last = netLogged
        val now = now()
        if (last != null && last.first == why && now - last.second < NET_LOG_EVERY_MS) return
        netLogged = why to now
        RobotLog.w(LogSource.NET, "Practice server: $why")
    }

    private fun setTask(task: String?) {
        _ui.update { it.copy(task = task) }
    }

    fun flash(mood: Mood, ms: Long = 2_500) {
        flashJob?.cancel()
        _ui.update { it.copy(flash = mood) }
        flashJob = scope.launch {
            delay(ms)
            _ui.update { s -> if (s.flash == mood) s.copy(flash = null) else s }
        }
    }
}
