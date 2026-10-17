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
// a guide itself runs in Guide; what otto does on his own is decided by OttoBrain.
// everything otto says goes through Speech (SpeechTurns: one line at a time, questions wait for the answer).
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
    // the mouth stops this long after a ready line is over
    private const val SAID_LINGER_MS = 400L
    private const val LOG_TASK_CHARS = 40
    // the robot system's own voice while otto is silent goes to the log at most this often
    private const val SYSTEM_VOICE_LOG_MS = 5_000L

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    // lines for the dashboard's live feed, sent by OttoBrain
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
    @Volatile private var systemVoiceAt = 0L

    private fun now() = System.currentTimeMillis()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        Speech.start()
        // goal, battery and robot state on the screen
        scope.launch {
            OttoBrain.status.collect { b -> _ui.update { it.copy(brain = b) } }
        }
        // keep the planner in sync with what the screen shows
        scope.launch {
            ui.map { it.screenInfo() }.distinctUntilChanged().collect { info ->
                runCatching { AgentCore.uploadInterfaceInfo(info) }
            }
        }
        // the screen settings (screen.privacyLine, screen.talkHints) and the mode
        scope.launch {
            Config.flow.collect { s -> _ui.update { it.copy(screen = ScreenPrefs(s.privacyLine, s.talkHints)) } }
        }
        scope.launch {
            Modes.flow.collect { m -> _ui.update { it.copy(mode = m) } }
        }
    }

    fun refreshScreenInfo() {
        runCatching { AgentCore.uploadInterfaceInfo(_ui.value.screenInfo()) }
    }

    // ---- listeners (worker threads) ----

    // the last time the mic heard the patient (taps do not count, unlike OttoBrain.heardUser)
    @Volatile private var voiceAt = 0L

    fun lastVoiceAt(): Long = voiceAt

    fun onUserSpeech(text: String, final: Boolean) {
        if (text.isBlank()) return
        // show / calm: the mic is off; anything that still comes in gets no reaction
        if (Modes.current.silent) return
        voiceAt = now()
        Speech.userSpeech(final)
        OttoBrain.heardUser()
        if (final) {
            lastUserText = text
            Guide.current?.heard(text)
            // the raw words leave the robot only with the dashboard's demo switch on
            feed.patientSaid(now(), text)
        }
        // the patient's own words are never shown (they could say their name): only that otto listens
        _ui.update { it.copy(subtitle = "", subtitleFromUser = true) }
    }

    fun onRobotSpeech(text: String, final: Boolean) {
        if (text.isBlank()) return
        val ours = Speech.robotSpeech(final)
        if (Modes.current.silent) {
            // not from this app: the robot system talks by itself (the calm / show mode field test wants to know)
            if (!ours && now() - systemVoiceAt >= SYSTEM_VOICE_LOG_MS) {
                systemVoiceAt = now()
                RobotLog.w(LogSource.VOICE, "Robot system voice in ${Modes.current.label} mode, not from Otto's app: \"${text.trim().take(80)}\"")
            }
            return
        }
        OttoBrain.heardRobot()
        Voice.heardRobot(now())
        if (final) feed.ottoSaid(now(), text)
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
        OttoBrain.agentStatus(mode)
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

    // ---- modes (RobotModes.kt) ----

    // show / calm: the face only, nothing to tap, nothing said. normal again: everything as before
    fun modeChanged(old: RobotMode, new: RobotMode) {
        if (new.silent) {
            speakingJob?.cancel()
            _ui.update { it.copy(checkIn = null, subtitle = "", subtitleFromUser = false, speaking = false, task = null, mode = new) }
            feed.ottoDid(now(), "${new.label} mode: Otto is silent")
        } else {
            _ui.update { it.copy(mode = new) }
            feed.ottoDid(now(), "Normal mode again")
            if (old.silent) refreshScreenInfo()
        }
        refreshStaff()
    }

    // ---- staff menu (long-press the Nova Clinic logo 3 s) ----

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
                refreshStaff()
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
            Config.requestNow("staff menu")
            MapSync.resend()
            refreshStaff()
        }
    }

    // the mode switch at the top of the staff menu
    fun staffSetMode(mode: RobotMode) {
        staffTouched()
        val r = Modes.request(mode, "staff menu")
        RobotLog.i(LogSource.APP, "Staff menu: ${r.detail}")
        refreshStaff()
    }

    private fun refreshStaff() {
        _ui.update { s -> if (s.staff != null) s.copy(staff = staffSnapshot()) else s }
    }

    private fun staffSnapshot(): StaffUi {
        val b = _ui.value.brain
        val issue = Robot.chassisIssue()
        val perf = PerfMonitor.live()
        val map = MapSync.current
        val loc = MapSync.localised
        val floor = map?.let { m -> MapSync.maps.firstOrNull { Floors.same(it.name, m) }?.floor }
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
            logs = RobotLog.recent(),
            logsPending = RobotLog.pending(),
            perf = perf?.short() ?: "Screen: measuring, the first numbers come after a few seconds",
            perfOk = perf?.smooth ?: true,
            mode = Modes.current,
            modePending = Modes.pending,
            settings = Config.summary(),
            settingsOk = Config.ok,
            map = when {
                map == null -> "Map: not known yet"
                else -> "Map $map" + (floor?.let { ", floor $it" } ?: "") + when (loc) {
                    true -> ", localised"
                    false -> ", NOT localised"
                    null -> ""
                } + if (MapSync.maps.size > 1) " · ${MapSync.maps.size} maps" else ""
            },
            mapOk = map != null && loc != false,
        )
    }

    // ---- places (GET /api/places) ----

    suspend fun refreshPlaces(): List<Place>? {
        val list = net(quiet = true) { Backend.places() }.getOrNull() ?: return null
        _ui.update { it.copy(places = list) }
        Robot.homeFromPlaces = Places.homeSpot(placesHere(list), BuildConfig.HOME_SPOT).ifBlank { null }
        return list
    }

    // only the places on otto's floor (docs/robot-setup.md 2): he cannot take the elevator
    fun placesHere(all: List<Place> = _ui.value.places): List<Place> = Places.onMap(all, MapSync.current)

    private suspend fun loadPlaces(): List<Place> = placesHere(refreshPlaces() ?: _ui.value.places)

    // MapSync: otto is on another map now
    fun mapChanged() {
        _ui.update { it.copy(mapName = MapSync.current) }
        Robot.homeFromPlaces = Places.homeSpot(placesHere(), BuildConfig.HOME_SPOT).ifBlank { null }
        refreshStaff()
    }

    fun placeForSpot(spot: String?): Place? {
        val s = spot?.trim().orEmpty()
        if (s.isEmpty()) return null
        return _ui.value.places.firstOrNull { Names.key(it.spot) == Names.key(s) }
    }

    // ---- guide screen (Guide) ----

    fun guideStarted() {
        guideCardJob?.cancel()
        // the patient walks beside otto: the mic has to hear them without facing the robot (voice.openMicOnWalks).
        // barge-in stays on (speaking over otto stops his tts, AgentOS default).
        setWakeFree(!Config.current.openMicOnWalks)
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
        // a mode asked for during the walk starts now
        Modes.guideEnded()
    }

    // voice.openMicOnWalks changed: a walk under way gets it at once
    fun walkMicChanged() {
        if (Guide.current != null) setWakeFree(!Config.current.openMicOnWalks)
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

    // a walk this mode does not take: its leg fails with the reason, so the dashboard shows it
    suspend fun refuseGuide(cmd: RobotCommand, why: String) {
        val journey = cmd.journeyId?.takeIf { it.isNotBlank() } ?: return
        net(quiet = true) { Backend.patchJourney(journey, cmd.legId, LegStatus.FAILED, JourneyEvent.FAILED, why) }
            .onFailure { RobotLog.w(LogSource.NET, "Journey update failed: ${Backend.friendly(it)}") }
        feed.ottoDid(now(), "Walk for ticket ${cmd.ticket ?: "?"} refused: $why")
    }

    // ---- actions (ClinicActions). sample = what the person said, for the language ----

    // CONFIRM_TICKET: the person says the ticket otto calls
    suspend fun confirmTicket(ticket: String?, sample: String?): Boolean {
        val t = Tickets.normalize(ticket)
        val g = Guide.current
        val lang = Lang(null, sample)
        feed.patientIntent(now(), if (t != null) "Said ticket $t" else "Said it is them")
        if (g != null && g.waitingForPickup) {
            if (t == null) {
                speak("Ask them kindly to say their ticket number or tap That's me on the screen.", lang, "Please tap \"That's me\".", question = true)
                return true
            }
            g.heard(sample.orEmpty())
            // the guide's next line (the pace question or the start) is the answer: nothing else said here
            if (g.confirm(t, Guide.HOW_VOICE)) return true
            speak(
                "Tell them kindly that you are calling ticket ${g.ticket} right now, not $t, and that the team will send you for them soon.",
                lang,
                "I'm calling ${g.ticket} right now.",
            )
            return true
        }
        if (t != null) return waitingStatus(t, sample)
        speak("Ask them kindly for their ticket number: one letter and two digits.", lang, "What is your ticket number?", question = true)
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
            // the walk's ready line (6.7): said at once, no llm round trip while walking
            val line = g.paceLine(pace)
            g.choosePace(pace)
            // during the pace question the guide's next line already answers
            if (!asked) sayLine(line, SpeechKind.PACE, fits = { Guide.current === g })
            return true
        }
        speak("Say in a few words that you will walk at that pace when you walk together.", lang, pace.label)
        return true
    }

    // PAUSE_WALK
    suspend fun pauseWalk(sample: String?): Boolean {
        feed.patientIntent(now(), "Asked Otto to wait")
        val lang = Lang(null, sample)
        val g = Guide.current
        if (g != null && g.pause()) {
            g.heard(sample.orEmpty())
            sayLine(g.line(LineKey.PAUSE_OK), SpeechKind.PACE, fits = { Guide.current === g })
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
                fits = { Guide.current === g },
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
            val known = Places.find(place, places) ?: Places.find(place, _ui.value.places)
            val elsewhere = known != null && !Floors.onMap(known.mapName, MapSync.current)
            speak(
                buildString {
                    when {
                        elsewhere -> append("Explain kindly that ${known!!.label} is on another floor and you cannot take the elevator, so the team at the reception desk helps. ")
                        known != null -> append("Explain kindly that the reception desk assigns treatment rooms, so you cannot take them to ${known.label} yourself. ")
                        else -> append("Say kindly that you do not know this place. ")
                    }
                    if (public.isNotEmpty()) append("Offer to take them to one of these places: ${Places.spoken(public)}. ")
                    append("Short.")
                },
                lang,
                if (elsewhere) "That is on another floor. The desk will help you." else "The reception desk assigns rooms.",
            )
            return true
        }
        return startOwnJourney(match, null, null, null, OwnRequest(paceChosen = false, sample = sample), "Asked to be taken to ${match.label}", lang)
    }

    // CHECK_IN_FOR: self check-in by voice for a room marked selfCheckIn
    suspend fun checkInFor(place: String?, sample: String?): Boolean {
        val lang = Lang(null, sample)
        if (busyWalking(lang)) return false
        if (!ModeRules.selfCheckIn(Modes.current)) return false
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
                question = rooms.isNotEmpty(),
            )
            return true
        }
        if (room.kind == PlaceKind.RECEPTION) return notSure(lang)
        val target = Places.checkInTarget(room, places)
        // the check-in itself is said by the guide, together with the ticket and its first question (SpeechTurns)
        return startOwnJourney(target.to, room.id, null, null, OwnRequest(paceChosen = false, sample = sample, checkIn = true), "Checked in for ${room.label}", lang)
    }

    // "I'm not sure": at the reception the desk helps, elsewhere otto walks them there
    private suspend fun notSure(lang: Lang): Boolean {
        val reception = Places.reception(placesHere())
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
        if (Guide.current == null && !OttoBrain.helpActive()) return false
        speak(
            "Say kindly that you are walking with someone right now and the team at the reception desk can help.",
            lang,
            "I'm busy right now. The desk can help you.",
        )
        return true
    }

    // POST /api/journeys for the person in front of otto; the guide starts at once (no pickup)
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
        created.journey?.id?.let { OttoBrain.expectOwn(it, own) }
        created.command?.let { OttoBrain.addOwnCommand(it, own) }
        return true
    }

    // WAITING_STATUS by ticket
    suspend fun waitingStatus(ticket: String?, sample: String?): Boolean {
        val lang = Lang(null, sample)
        val t = Tickets.normalize(ticket)
        if (t == null) {
            speak("Ask kindly for their ticket number: one letter and two digits, it is on the robot screen or the staff gave it to them.", lang, "What is your ticket number?", question = true)
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
                JourneyStatus.WAITING_PICKUP -> "Otto will pick them up for $where soon. "
                JourneyStatus.WALKING -> "Otto is walking them to $where right now. "
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
            OttoBrain.helpStarted()
            _ui.update { it.copy(help = HelpUi(urgent = true, sent = null)) }
        }
        feed.patientIntent(now(), if (urgent) "Needs urgent help" else "Asked for a team member")
        val lang = Lang(null, sample)
        val g = Guide.current
        setTask(if (urgent) "Alerting the practice team" else "Calling the practice team")
        val result = net { Backend.help(summary, if (urgent) "urgent" else "normal", g?.journeyId, g?.ticket) }
        if (urgent) {
            OttoBrain.helpSent(result.getOrNull())
            _ui.update { it.copy(help = HelpUi(urgent = true, sent = result.isSuccess)) }
        }
        val sent = result.isSuccess
        setTask(null)
        val kind = if (urgent) SpeechKind.ALERT else SpeechKind.REPLY
        if (sent) {
            // no happy face in an emergency: he stays concerned with the person
            if (!urgent) flash(Mood.RELIEVED)
            speak(
                "Tell the person the practice team has been informed and someone will come to them shortly." +
                    if (urgent) " Because it is urgent, also tell them clearly to call the emergency number 112 right away, and that you stay with them." else "",
                lang,
                if (urgent) "The team is informed. Please call 112 now." else "The team is informed and will come to you.",
                kind = kind, urgent = urgent,
            )
        } else {
            flash(Mood.SORRY)
            speak(
                "Apologize: you could not reach the practice team right now." +
                    if (urgent) " Tell them clearly to call the emergency number 112 right away." else " Ask them to wait a moment.",
                lang,
                if (urgent) "Please call 112 now." else "Sorry, I couldn't reach the team.",
                kind = kind, urgent = urgent,
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
        Speech.answered("tapped ${pace.label.lowercase()}")
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
        Speech.answered("tapped go on")
        Guide.current?.goOn()
        feed.patientIntent(now(), "Ready to go on")
    }

    // the talk at a check-back: "All good", "Slower", "Wait"
    fun tapAllGood() {
        touched()
        Speech.answered("tapped all good")
        Guide.current?.allGood()
        feed.patientIntent(now(), "Tapped all good")
    }

    fun tapTalkSlower() {
        touched()
        Speech.answered("tapped slower")
        val g = Guide.current ?: return
        g.choosePace(g.pace.slower())
        feed.patientIntent(now(), "Tapped slower")
    }

    fun tapTalkWait() {
        touched()
        Speech.answered("tapped wait")
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
        if (Guide.current != null || !ModeRules.selfCheckIn(Modes.current)) return
        val rooms = Places.selfCheckIn(placesHere())
        _ui.update { it.copy(checkIn = CheckInUi(step = CheckInStep.WHO, rooms = rooms)) }
        flash(Mood.SURPRISED, 900)
        feed.patientIntent(now(), "Started the self check-in")
        scope.launch {
            val fresh = refreshPlaces() ?: return@launch
            _ui.update { s -> s.checkIn?.let { c -> s.copy(checkIn = c.copy(rooms = Places.selfCheckIn(placesHere(fresh)))) } ?: s }
        }
    }

    // null = "I'm not sure"
    fun tapRoom(room: Place?) {
        touched()
        Speech.answered("tapped a room")
        val reception = Places.reception(placesHere())
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
                val own = OwnRequest(paceChosen = true, sample = lastUserText, checkIn = room.kind != PlaceKind.RECEPTION)
                created.journey?.id?.let { OttoBrain.expectOwn(it, own) }
                // the guide screen takes over as soon as the guide starts (guideStarted closes this one)
                created.command?.let { OttoBrain.addOwnCommand(it, own) }
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
        OttoBrain.heardUser()
    }

    // called by the brain every second
    fun expireCheckIn(now: Long) {
        val c = _ui.value.checkIn ?: return
        if (c.step == CheckInStep.SENDING) return
        if (now - lastTouchAt > CHECK_IN_IDLE_MS) _ui.update { it.copy(checkIn = null) }
    }

    // ---- commands from the team (run by OttoBrain, holding the body lock) ----

    suspend fun pollCommand(): RobotCommand? = net(quiet = true) { Backend.nextCommand() }.getOrNull()

    suspend fun commandDone(id: String, result: CommandResult? = null) {
        withContext(NonCancellable) { net(quiet = true) { Backend.commandDone(id, result) } }
    }

    // go_to: drive to the spot and stay there. true when he got there.
    suspend fun goToSpot(cmd: RobotCommand, excuse: Boolean): Boolean {
        try {
            val spot = cmd.spot?.trim().orEmpty()
            if (spot.isEmpty()) return false
            if (excuse) excuseMe()
            val home = Robot.isHome(spot)
            OttoBrain.activity(
                if (home) RobotState.RETURNING else RobotState.WALKING,
                if (home) "Driving back to $spot" else "Going to $spot",
            )
            setWalking(true)
            val outcome = try {
                Robot.goTo(spot, stopWhen = { OttoBrain.helpActive() }, onEvent = { onNavEvent(it) })
            } finally {
                setWalking(false)
            }
            if (outcome is NavOutcome.Failed) OttoBrain.failed("Could not reach $spot: ${outcome.why.screen}")
            return outcome == NavOutcome.Arrived
        } finally {
            commandDone(cmd.id)
        }
    }

    // say: the team typed it, otto says it as it is
    suspend fun sayFromTeam(cmd: RobotCommand) {
        try {
            val text = cmd.text?.trim().orEmpty()
            if (text.isEmpty()) return
            Speech.say(SpeechKind.TEAM, text)
        } finally {
            commandDone(cmd.id)
        }
    }

    // ask: the team's text goes to AgentOS like something a person said, so otto's ai decides (query)
    suspend fun askFromTeam(cmd: RobotCommand) {
        try {
            val text = cmd.text?.trim().orEmpty()
            if (text.isEmpty() || Modes.current.silent) return
            feed.ottoDid(now(), "Message from the team: $text")
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
    // also while otto walks; closed on every path
    suspend fun walkControl(cmd: RobotCommand) {
        try {
            val c = WalkControl.of(cmd.action)
            val g = Guide.current
            if (c == null || g == null || (cmd.journeyId != null && cmd.journeyId != g.journeyId)) {
                RobotLog.i(LogSource.BRAIN, "Walk control ${cmd.action ?: "?"} ignored: no matching walk")
                return
            }
            // the line before the change: slower / faster is measured from the pace now
            val line = g.line(
                when (c) {
                    WalkControl.WAIT -> LineKey.PAUSE_OK
                    WalkControl.RESUME -> LineKey.GO_ON
                    WalkControl.SLOWER -> LineKey.SLOWER_OK
                    WalkControl.FASTER -> LineKey.FASTER_OK
                },
            )
            val applied = g.control(c)
            RobotLog.i(LogSource.BRAIN, "Walk control ${c.wire} for ${g.ticket.ifBlank { "a patient" }}" + if (applied) "" else " (nothing to change)")
            feed.patientIntent(
                now(),
                when (c) {
                    WalkControl.WAIT -> "Asked Otto to wait (phone)"
                    WalkControl.RESUME -> "Ready to go on (phone)"
                    WalkControl.SLOWER -> "Asked to walk slower (phone)"
                    WalkControl.FASTER -> "Asked to walk faster (phone)"
                },
            )
            if (applied) Speech.sayLater(SpeechKind.PACE, line) { Guide.current === g }
        } finally {
            commandDone(cmd.id)
        }
    }

    // ask with a journey = the patient's phone ("Talk"): their words, so they only go to the dashboard
    // feed with the demo switch on and never to the log. otto's ai answers right away, also mid-walk
    suspend fun askDuringWalk(cmd: RobotCommand) {
        try {
            val text = cmd.text?.trim().orEmpty()
            if (text.isEmpty() || Modes.current.silent) return
            // with a journey it comes from the walk page on the patient's phone
            if (cmd.journeyId != null) {
                Guide.current?.takeIf { it.journeyId == cmd.journeyId }?.heard(text)
                feed.patientIntent(now(), "Talked to Otto through the phone")
                feed.patientSaid(now(), text)
                RobotLog.i(LogSource.VOICE, "Patient talked through the phone")
            } else {
                feed.ottoDid(now(), "Message from the team: $text")
            }
            OttoBrain.heardUser()
            Voice.ask(text)
        } finally {
            commandDone(cmd.id)
        }
    }

    // otto has to go while someone talks to him (after waiting up to 20 s)
    private suspend fun excuseMe() {
        speak(
            "Politely tell the person you are talking with that you have to go now because the practice team needs you, " +
                "and that you will be back in a moment.",
            Lang(null, lastUserText),
            "Sorry, I have to go. I'll be right back.",
            kind = SpeechKind.INFO,
        )
    }

    // ---- things otto says on his own (brain tasks) ----

    suspend fun greet() {
        val since = now()
        speak(
            "Greet a visitor who just walked up to you at the reception of a doctor's practice. " +
                "In one or two short sentences offer to check them in or show them the way.",
            Lang(null),
            "Hello! I can check you in or show you the way.",
            kind = SpeechKind.GREETING,
            // the person already started talking, or a walk began: no greeting any more
            fits = { lastVoiceAt() < since && Guide.current == null },
        )
    }

    suspend fun sayGoingToCharge() {
        Speech.speak(
            SpeechKind.INFO,
            "Tell the people around you in one short sentence that your battery is low and you are going to your charger now.",
            Lang(null),
            "My battery is low. I'm going to my charger.",
        )
    }

    fun setWalking(on: Boolean) {
        _ui.update { it.copy(walking = on) }
    }

    // blocked: ask people to let otto pass, at most every OBSTACLE_TALK_EVERY_MS. ready = a walk's own
    // line for it (6.7), said at once; without one the llm writes it. dropped when otto drives on before it is said
    fun onNavEvent(ev: NavEvent, lang: Lang = Lang(null), ready: String? = null) {
        when (ev) {
            NavEvent.BLOCKED -> {
                setTask("Something is in my way")
                val now = now()
                if (now - lastObstacleTalk >= OBSTACLE_TALK_EVERY_MS) {
                    lastObstacleTalk = now
                    val stillThere = { Robot.driving != null }
                    if (ready != null) Speech.sayLater(SpeechKind.WAY, ready, stillThere)
                    else scope.launch { Speech.speak(SpeechKind.WAY, "Politely ask the people in your way to let you pass.", lang, "Excuse me, may I pass, please?", fits = stillThere) }
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
        Speech.clear("back to the welcome screen")
        runCatching { AgentCore.clearContext() }
    }

    // ---- speech (everything goes through Speech, SpeechTurns) ----

    // english instruction -> a spoken line in the person's language, when it is its turn. when the llm does not
    // deliver, the ready line is said instead (a walk's line in the patient's language, else the english screen
    // text), so there is no silent gap (6.7). true when it was said
    suspend fun speak(
        task: String,
        lang: Lang,
        screen: String,
        ready: String? = null,
        kind: SpeechKind = SpeechKind.REPLY,
        question: Boolean = false,
        urgent: Boolean = false,
        fits: () -> Boolean = { true },
    ): Boolean = Speech.speak(kind, task, lang, ready ?: screen, question, urgent, fits) == TurnOutcome.SAID

    // a ready line (the walk's line set, a fallback): text-to-speech only, no llm, when it is its turn.
    // false when it was not said (talked over, dropped, silent mode)
    suspend fun sayLine(
        text: String,
        kind: SpeechKind = SpeechKind.WALK,
        question: Boolean = false,
        topic: String? = null,
        fits: () -> Boolean = { true },
    ): Boolean = Speech.say(kind, text, question = question, topic = topic, fits = fits) == TurnOutcome.SAID

    fun sayLineLater(text: String, kind: SpeechKind = SpeechKind.WALK, fits: () -> Boolean = { true }) {
        Speech.sayLater(kind, text, fits)
    }

    // Speech plays a ready line: on the screen like otto's other words, then text-to-speech
    suspend fun playText(text: String): TurnOutcome {
        val t = text.trim()
        if (t.isEmpty()) return TurnOutcome.SAID
        val startedAt = now()
        _ui.update { it.copy(subtitle = t, subtitleFromUser = false, speaking = true) }
        speakingJob?.cancel()
        feed.ottoSaid(now(), t)
        val ok = Voice.say(t)
        speakingJob = scope.launch {
            delay(SAID_LINGER_MS)
            _ui.update { it.copy(speaking = false) }
        }
        return when {
            ok -> TurnOutcome.SAID
            lastVoiceAt() >= startedAt -> TurnOutcome.INTERRUPTED
            else -> TurnOutcome.FAILED
        }
    }

    // Speech plays an llm line: written and spoken by AgentOS in the person's language. every llm line logs how
    // long it took; a line the patient talked over is not said again
    suspend fun playTask(task: String, lang: Lang, fallback: String): TurnOutcome {
        val r = working { Voice.speakTimed(task, lang) }
        val what = task.take(LOG_TASK_CHARS).trimEnd()
        return when {
            r.ok -> {
                RobotLog.i(
                    LogSource.VOICE,
                    "Llm line (\"$what...\"): " + (r.firstWordsMs?.let { "first words after ${secs(it)}, " } ?: "") +
                        "done after ${secs(r.totalMs)}" + if (r.tries > 1) ", 2nd try" else "",
                )
                TurnOutcome.SAID
            }
            r.interrupted -> {
                RobotLog.i(LogSource.VOICE, "Llm line (\"$what...\") stopped after ${secs(r.totalMs)}: the patient spoke, the agent answers")
                TurnOutcome.INTERRUPTED
            }
            else -> {
                RobotLog.w(LogSource.VOICE, "Speech failed after ${secs(r.totalMs)} (${r.tries} tries), said the ready line instead")
                playText(fallback)
            }
        }
    }

    private fun secs(ms: Long) = String.format(java.util.Locale.ROOT, "%.1f s", ms / 1000.0)

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
