package com.hinterland.clinic

import android.util.Log
import com.ainirobot.agent.AgentCore
import kotlinx.coroutines.CompletableDeferred
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
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

// the receptionist: the visit flows behind the actions and the team's commands, and the screen state.
// what nova does on her own (greeting, charging, going home) is decided by NovaBrain; flows that
// move her run while holding the brain's body lock. network on Dispatchers.IO, never on the main thread.
object Clinic {

    private const val TAG = "NovaClinic"
    private const val FRESH_AFTER_MS = 20_000L
    private const val OBSTACLE_TALK_EVERY_MS = 10_000L
    // calling a patient: wait this long for someone to come close, call once more halfway
    private const val CALL_WAIT_MS = 30_000L
    private const val CALL_AGAIN_MS = 15_000L
    private const val CALL_NEAR_M = 1.5
    // without person data nova can't see who comes, so she just gives them a moment
    private const val CALL_BLIND_MS = 8_000L

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val started = AtomicBoolean(false)

    private data class Known(val visit: Visit, val spot: String?, val notified: Boolean?)

    private data class GuideTarget(
        val visitId: String?,
        val patient: String?,
        val doctor: String,
        val spot: String?,
        val lang: Lang,
        val prevStatus: String?,
    )

    // visits this robot saw today, by id
    private val visits = ConcurrentHashMap<String, Known>()
    @Volatile private var current: String? = null

    @Volatile private var lastFlowAt = 0L
    @Volatile private var lastObstacleTalk = 0L
    @Volatile private var lastUserText: String? = null
    @Volatile private var walkingPatient = false
    @Volatile private var speakingJob: Job? = null
    @Volatile private var flashJob: Job? = null

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
        if (final) lastUserText = text
        val now = System.currentTimeMillis()
        _ui.update { s ->
            val inConversation = s.steps.any { it.key == StepKey.LISTENING && it.state == StepState.ACTIVE }
            val fresh = !walkingPatient && (s.steps.isEmpty() || (!inConversation && now - lastFlowAt > FRESH_AFTER_MS))
            s.copy(
                subtitle = text,
                subtitleFromUser = true,
                steps = if (fresh) Timeline.fresh(Clock.now()) else s.steps,
                header = if (fresh) null else s.header,
                queue = if (fresh) null else s.queue,
            )
        }
    }

    fun onRobotSpeech(text: String, final: Boolean) {
        if (text.isBlank()) return
        NovaBrain.heardRobot()
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
        Log.w(TAG, "action failed", e)
        _ui.update { it.copy(banner = "Something went wrong: ${e.message ?: e.javaClass.simpleName}") }
    }

    // ---- CHECK_IN ----

    suspend fun checkIn(
        patient: String,
        doctor: String,
        time: String?,
        language: String?,
        reply: String?,
        sample: String?,
    ): Boolean {
        val now = Clock.now()
        val tag = language ?: Locale.getDefault().toLanguageTag()
        val lang = Lang(language, sample)

        // show the agent steps while it works: details are in, now telling the doctor
        var steps = _ui.value.steps.takeIf { s -> s.any { it.key == StepKey.LISTENING && it.state == StepState.ACTIVE } }
            ?: Timeline.fresh(now)
        steps = Timeline.named(steps, patient, doctor, time)
        steps = Timeline.gotDetails(steps, patient, doctor, time, now)
        steps = Timeline.set(steps, StepKey.NOTIFIED, StepState.ACTIVE, now)
        show(steps, Timeline.header(patient, doctor, time))
        setTask("Checking you in")

        val result = net { Backend.checkIn(patient, doctor, time, tag) }
        setTask(null)
        val r = result.getOrElse { e ->
            show(
                Timeline.set(
                    steps, StepKey.NOTIFIED, StepState.FAILED, Clock.now(),
                    detail = Backend.friendly(e), label = "Could not reach the practice system",
                ),
            )
            flash(Mood.SORRY)
            speakOrShow(
                "Apologize: you could not save the check-in because the practice system is not reachable right now. " +
                    "Ask them to take a seat; a team member will help them.",
                lang,
                "Sorry, I could not check you in. A team member will help you.",
            )
            return false
        }

        val k = keep(r.visit, r.spot, r.notified)
        var shown = Timeline.forVisit(k.visit, r.notified)
        if (!r.doctorKnown) {
            shown = Timeline.set(shown, StepKey.NOTIFIED, StepState.DONE, label = Timeline.notifiedLabel(null, false))
        }
        val v = k.visit
        showVisit(
            shown,
            Timeline.header(v.patientName.ifBlank { patient }, v.doctorName.ifBlank { doctor }, v.appointmentTime ?: time),
            v,
        )
        flash(Mood.HAPPY)

        if (r.doctorKnown) {
            // one sentence with the queue numbers; the planner's reply if the llm call fails
            val said = working {
                Voice.speak(
                    Queue.checkInTask(
                        v.patientName.ifBlank { patient }, v.doctorName.ifBlank { doctor },
                        v.queuePosition, v.estimatedWaitMinutes,
                    ),
                    lang,
                )
            }
            if (!said) sayOrShow(reply)
        } else {
            speakOrShow(
                "Tell $patient they are checked in, but you could not find a doctor called $doctor in this practice, " +
                    "so a team member will come and help them. Ask them to take a seat.",
                lang,
                "You are checked in. A team member will help you shortly.",
            )
        }
        return true
    }

    // ---- GUIDE_TO_DOCTOR ----

    // returns once the patient is at the door (or it failed); the way back runs on as the brain's task
    suspend fun guide(doctor: String, patient: String?, sample: String?): Boolean {
        val known = findKnown(doctor, patient)
        val name = known?.visit?.doctorName?.ifBlank { null } ?: doctor
        val who = known?.visit?.patientName?.ifBlank { null } ?: patient
        val lang = Lang(known?.visit?.language, sample)
        if (NovaBrain.helpActive()) {
            speakOrShow(
                "Tell them you will stay right here with them until the practice team arrives.",
                lang,
                "I'll stay here with you until the team arrives.",
            )
            return false
        }
        val delivered = CompletableDeferred<Boolean>()
        val job = NovaBrain.runBody(RobotState.WALKING, walkGoal(who, name)) {
            try {
                setTask("Finding $name's room")
                val spot = working { spotFor(doctor, known) }
                setTask(null)
                val target = GuideTarget(
                    visitId = known?.visit?.id,
                    patient = who,
                    doctor = name,
                    spot = spot,
                    lang = lang,
                    prevStatus = known?.visit?.status,
                )
                if (spot == null) {
                    delivered.complete(failWalk(target, baseSteps(target), NavFailure.NO_SPOT))
                } else {
                    escort(target) { delivered.complete(it) }
                }
            } finally {
                delivered.complete(false)
            }
        }
        if (job == null) {
            speakOrShow(
                "Tell the patient you are busy with another task right now and will be back at reception in a moment.",
                Lang(null, sample),
                "I'm busy right now. Back in a moment!",
            )
            return false
        }
        // the action must always end, even if the walk never ran
        job.invokeOnCompletion { delivered.complete(false) }
        return delivered.await()
    }

    // ---- CALL_STAFF ----

    suspend fun callStaff(summary: String, urgency: String, sample: String?): Boolean {
        val urgent = urgency == "urgent"
        // stay with the person from now on, even before the message is out
        if (urgent) NovaBrain.helpStarted()
        val lang = Lang(null, sample)
        setTask(if (urgent) "Alerting the practice team" else "Calling the practice team")
        val result = net { Backend.help(summary, if (urgent) "urgent" else "normal") }
        if (urgent) NovaBrain.helpSent(result.getOrNull())
        val sent = result.isSuccess
        setTask(null)
        if (sent) {
            flash(Mood.HAPPY)
            speakOrShow(
                "Tell the person the practice team has been informed and someone will come to them shortly." +
                    if (urgent) " Because it is urgent, also tell them clearly to call the emergency number 112 right away, and that you stay with them." else "",
                lang,
                if (urgent) "The team is informed. Please call 112 now." else "The team is informed and will come to you.",
            )
        } else {
            flash(Mood.SORRY)
            speakOrShow(
                "Apologize: you could not reach the practice team right now." +
                    if (urgent) " Tell them clearly to call the emergency number 112 right away." else " Ask them to wait a moment.",
                lang,
                if (urgent) "Please call 112 now." else "Sorry, I couldn't reach the team.",
            )
        }
        return sent
    }

    // ---- WAITING_STATUS ----

    suspend fun waitingStatus(patient: String, sample: String?): Boolean {
        setTask("Looking up your visit")
        var reachable = true
        val local = Names.match(patient, visits.values.toList()) { it.visit.patientName }
        // always the fresh visit from the backend: the queue moves
        val visit = if (local != null) {
            net { Backend.visit(local.visit.id) }.getOrElse { reachable = false; local.visit }
        } else {
            net { Backend.activeVisits() }
                .onFailure { reachable = false }
                .getOrNull()
                ?.let { list -> Names.match(patient, list) { it.patientName } }
        }
        setTask(null)

        if (visit == null) {
            if (!reachable) {
                speakOrShow(
                    "Apologize: you cannot look up the visit right now because the practice system is not reachable. " +
                        "Ask them to wait a moment; a team member can help.",
                    Lang(null, sample),
                    "Sorry, I can't reach the practice system right now.",
                )
                return false
            }
            speakOrShow(
                "Tell $patient you could not find a check-in under this name today and offer to check them in now.",
                Lang(null, sample),
                "I couldn't find a check-in for $patient.",
            )
            return true
        }

        val k = keep(visit, visits[visit.id]?.spot, visits[visit.id]?.notified)
        showVisit(
            Timeline.forVisit(k.visit, k.notified),
            Timeline.header(visit.patientName, visit.doctorName, visit.appointmentTime),
            visit,
        )
        val facts = buildString {
            append("Patient: ${visit.patientName}. Doctor: ${visit.doctorName}. ")
            visit.appointmentTime?.let { append("Appointment time: $it. ") }
            Clock.minutesSince(visit.createdAt)?.let { append("Checked in $it minutes ago. ") }
            append("Status: ${statusFact(visit.status, visit.doctorName)} ")
            if (VisitStatus.rank(visit.status) in 0..VisitStatus.rank(VisitStatus.NOTIFIED)) {
                append(Queue.facts(visit.doctorName, visit.queuePosition, visit.estimatedWaitMinutes))
            }
        }
        val lang = if (!sample.isNullOrBlank()) Lang(null, sample) else Lang(visit.language)
        speakOrShow(
            "Tell the patient their current status in one or two short sentences, using only these facts. Facts: $facts",
            lang,
            Timeline.header(visit.patientName, visit.doctorName, visit.appointmentTime),
        )
        return true
    }

    private fun statusFact(status: String, doctor: String): String = when (status) {
        VisitStatus.CHECKED_IN -> "checked in, the practice team will let the doctor know."
        VisitStatus.NOTIFIED -> "$doctor has been informed and will call them when ready."
        VisitStatus.CALLED -> "$doctor is ready now and Nova will take them there."
        VisitStatus.ESCORTING -> "Nova is taking them to $doctor right now."
        VisitStatus.WITH_DOCTOR -> "they are already with $doctor."
        VisitStatus.DONE -> "the visit is finished."
        VisitStatus.CANCELLED -> "the visit was cancelled, a team member can help."
        else -> "checked in."
    }

    // ---- commands from the team (run by NovaBrain, holding the body lock) ----

    suspend fun pollCommand(): RobotCommand? = net(quiet = true) { Backend.nextCommand() }.getOrNull()

    // call_patient: back to reception if needed, call the patient, wait for them, walk them over, come back
    suspend fun fetch(cmd: RobotCommand, excuse: Boolean) {
        try {
            if (excuse) excuseMe()
            val fetched = cmd.visitId?.let { id -> net { Backend.visit(id) }.getOrNull() }
            val known = fetched?.let { v -> keep(v, cmd.spot ?: visits[v.id]?.spot, visits[v.id]?.notified) }
            val patient = known?.visit?.patientName?.ifBlank { null } ?: cmd.patientName
            val doctor = known?.visit?.doctorName?.ifBlank { null } ?: cmd.doctorName.orEmpty()
            val lang = Lang(known?.visit?.language)
            val who = patient ?: "the patient"

            // patients wait at reception, so that is where nova calls them
            val home = Robot.home
            if (home.isNotEmpty() && Robot.ready && Robot.atHome != true) {
                NovaBrain.activity(RobotState.RETURNING, "Going to $home to fetch $who")
                drive(home, escort = false, lang = Lang(null))
            }

            val steps = known?.let { Timeline.forVisit(it.visit, it.notified) }
                ?: Timeline.set(
                    Timeline.named(Timeline.fresh(null), patient, doctor, null),
                    StepKey.WAITING, StepState.DONE, Clock.now(), label = Timeline.waitingLabel(doctor, true),
                )
            showVisit(steps, Timeline.header(patient, doctor, known?.visit?.appointmentTime), known?.visit)

            NovaBrain.activity(RobotState.FETCHING, "Calling $who for $doctor")
            speakOrShow(
                "Call the patient ${patient.orEmpty()} out loud in the waiting room: $doctor is ready for them now. " +
                    "Ask them to come to you.",
                lang,
                listOfNotNull(patient, "$doctor is ready now. Please come to me.").joinToString(", "),
            )
            NovaBrain.activity(RobotState.FETCHING, "Waiting for $who to come over")
            val came = awaitPatient(patient, doctor, lang)

            val spot = cmd.spot?.takeIf { it.isNotBlank() } ?: working { spotFor(doctor, known) }
            val target = GuideTarget(
                visitId = known?.visit?.id ?: cmd.visitId,
                patient = patient,
                doctor = doctor,
                spot = spot,
                lang = lang,
                prevStatus = VisitStatus.CALLED,
            )
            when {
                !came -> nobodyCame(target)
                spot == null -> failWalk(target, baseSteps(target), NavFailure.NO_SPOT)
                else -> escort(target)
            }
        } finally {
            // "done" means nova is back (robot_back on the board)
            withContext(NonCancellable) { net(quiet = true) { Backend.commandDone(cmd.id) } }
        }
    }

    // true when someone came within reach
    private suspend fun awaitPatient(patient: String?, doctor: String, lang: Lang): Boolean {
        val start = System.currentTimeMillis()
        var calledAgain = false
        while (true) {
            val near = Robot.peopleNear(CALL_NEAR_M)
            if (near == null) {
                delay(CALL_BLIND_MS)
                return true
            }
            if (near > 0) return true
            val waited = System.currentTimeMillis() - start
            if (waited >= CALL_WAIT_MS) return false
            if (!calledAgain && waited >= CALL_AGAIN_MS) {
                calledAgain = true
                Voice.speak(
                    "Call ${patient ?: "the patient"} once more, short and friendly: $doctor is ready and they should come to you.",
                    lang,
                )
            }
            delay(500)
        }
    }

    private suspend fun nobodyCame(t: GuideTarget) {
        val who = t.patient ?: "The patient"
        NovaBrain.activity(RobotState.FETCHING, "$who did not come, telling the team")
        show(Timeline.set(baseSteps(t), StepKey.WALKING, StepState.FAILED, Clock.now(), detail = "Nobody came"))
        flash(Mood.SORRY, 3_000)
        speakOrShow(
            "Say briefly that ${t.patient ?: "the patient"} did not come, and that you will let the practice team know.",
            t.lang,
            "$who did not come. I'll tell the team.",
        )
        t.visitId?.let { id ->
            net(quiet = true) { Backend.updateVisit(id, VisitStatus.CALLED, "patient did not come") }
                .onSuccess { setStatus(id, VisitStatus.CALLED) }
        }
        net(quiet = true) {
            Backend.help("$who did not come when Nova called them for ${t.doctor}.", "normal")
        }
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
            val outcome = drive(spot, escort = false, lang = Lang(null))
            if (outcome is NavOutcome.Failed) NovaBrain.failed("Could not reach $spot: ${outcome.why.screen}")
            return outcome == NavOutcome.Arrived
        } finally {
            withContext(NonCancellable) { net(quiet = true) { Backend.commandDone(cmd.id) } }
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
            withContext(NonCancellable) { net(quiet = true) { Backend.commandDone(cmd.id) } }
        }
    }

    // a command type this app does not know: close it so it does not hang on the board
    suspend fun skipCommand(cmd: RobotCommand) {
        Log.w(TAG, "unknown command type ${cmd.type}")
        net(quiet = true) { Backend.commandDone(cmd.id) }
    }

    // nova has to go while someone talks to her (after waiting up to 20 s)
    private suspend fun excuseMe() {
        speakOrShow(
            "Politely tell the person you are talking with that you have to go now because the practice team needs you, " +
                "and that you will be back in a moment.",
            Lang(null, lastUserText),
            "Sorry, I have to go. I'll be right back.",
        )
    }

    // ---- things nova says on her own (brain tasks) ----

    suspend fun greet() {
        _ui.update { it.copy(opener = "Nova greeted a visitor who walked up and asked if they have an appointment.") }
        speakOrShow(
            "Greet a visitor who just walked up to the reception of a doctor's practice and ask if they have an appointment. " +
                "One short sentence.",
            Lang(null),
            "Hello! Do you have an appointment?",
        )
    }

    suspend fun sayGoingToCharge() {
        Voice.speak("Tell the people around you in one short sentence that your battery is low and you are going to your charger now.", Lang(null))
    }

    // ---- walking ----

    private fun walkGoal(patient: String?, doctor: String) =
        "Walking ${patient ?: "a patient"} to $doctor"

    // walks the patient to the doctor, then back home (not during urgent help).
    // onDelivered runs at the door, so the action can finish before the way back.
    private suspend fun escort(t: GuideTarget, onDelivered: (Boolean) -> Unit = {}) {
        walkingPatient = true
        val ok = try {
            walkToDoctor(t)
        } finally {
            walkingPatient = false
        }
        onDelivered(ok)
        walkBack()
    }

    private fun baseSteps(t: GuideTarget): List<Step> =
        t.visitId?.let { visits[it] }?.let { Timeline.forVisit(it.visit, it.notified) }
            ?: Timeline.walkOnly(t.doctor, Clock.now())

    private suspend fun walkToDoctor(t: GuideTarget): Boolean {
        val spot = t.spot ?: return failWalk(t, baseSteps(t), NavFailure.NO_SPOT)
        val v = t.visitId?.let { visits[it] }?.visit
        NovaBrain.activity(RobotState.WALKING, walkGoal(t.patient, t.doctor))
        var steps = Timeline.set(baseSteps(t), StepKey.WALKING, StepState.ACTIVE, Clock.now(), label = Timeline.walkingLabel(t.doctor))
        show(steps, v?.let { Timeline.header(it.patientName, it.doctorName, it.appointmentTime) } ?: Timeline.header(t.patient, t.doctor, null))
        // out of the queue now
        _ui.update { it.copy(queue = null) }

        if (!Robot.ready) return failWalk(t, steps, NavFailure.NOT_CONNECTED)

        speakOrShow(
            "Tell ${t.patient ?: "the patient"} to please follow you to the room of ${t.doctor}. You will walk slowly ahead of them.",
            t.lang,
            "Please follow me to ${t.doctor}'s room.",
        )
        t.visitId?.let { id ->
            net(quiet = true) { Backend.updateVisit(id, VisitStatus.ESCORTING, "Nova is walking to $spot") }
                .onSuccess { setStatus(id, VisitStatus.ESCORTING) }
        }

        return when (val outcome = drive(spot, escort = true, lang = t.lang)) {
            NavOutcome.Arrived -> {
                steps = Timeline.set(steps, StepKey.ARRIVED, StepState.DONE, Clock.now())
                show(steps)
                t.visitId?.let { id ->
                    net(quiet = true) { Backend.updateVisit(id, VisitStatus.WITH_DOCTOR, "Arrived at $spot") }
                        .onSuccess { setStatus(id, VisitStatus.WITH_DOCTOR) }
                }
                flash(Mood.HAPPY)
                speakOrShow(
                    "You just arrived at the room of ${t.doctor} with ${t.patient ?: "the patient"}. " +
                        "Tell them this is the room and say a short, warm goodbye.",
                    t.lang,
                    "Here we are. Goodbye and all the best!",
                )
                true
            }
            is NavOutcome.Failed -> failWalk(t, steps, outcome.why)
        }
    }

    // speak, show, tell the backend and the team
    private suspend fun failWalk(t: GuideTarget, steps: List<Step>, why: NavFailure): Boolean {
        NovaBrain.failed("Could not walk to ${t.spot ?: t.doctor}: ${why.screen}")
        show(Timeline.set(steps, StepKey.WALKING, StepState.FAILED, Clock.now(), detail = why.screen))
        flash(Mood.SORRY, 4_000)
        speakOrShow(
            "Apologize to ${t.patient ?: "the patient"}: you cannot take them to the room of ${t.doctor} right now " +
                "because ${why.spoken}. Tell them a team member will come to help.",
            t.lang,
            "${why.screen}. A team member will help you.",
        )
        val detail = "Nova could not walk to ${t.spot ?: t.doctor}: ${why.screen}"
        t.visitId?.let { id ->
            net(quiet = true) { Backend.updateVisit(id, t.prevStatus ?: VisitStatus.CALLED, detail) }
                .onSuccess { setStatus(id, t.prevStatus ?: VisitStatus.CALLED) }
        }
        net(quiet = true) {
            Backend.help("Nova could not walk ${t.patient ?: "a patient"} to ${t.doctor} (${why.screen}). Please help at reception.", "normal")
        }
        return false
    }

    private suspend fun walkBack() {
        // urgent help: stay with the person
        if (NovaBrain.helpActive() || !Robot.ready) return
        val home = Robot.home
        if (home.isEmpty()) {
            show(Timeline.set(_ui.value.steps, StepKey.RETURNING, StepState.FAILED, Clock.now(), detail = "robot.homeSpot is not set"))
            return
        }
        NovaBrain.activity(RobotState.RETURNING, "Driving back to $home")
        show(Timeline.set(_ui.value.steps, StepKey.RETURNING, StepState.ACTIVE, Clock.now()))
        val outcome = drive(home, escort = false, lang = Lang(null))
        show(
            when (outcome) {
                NavOutcome.Arrived -> Timeline.set(_ui.value.steps, StepKey.RETURNING, StepState.DONE, Clock.now())
                is NavOutcome.Failed -> Timeline.set(
                    _ui.value.steps, StepKey.RETURNING, StepState.FAILED, Clock.now(), detail = outcome.why.screen,
                )
            },
        )
        if (outcome is NavOutcome.Failed) NovaBrain.returnFailed("Could not get back to $home: ${outcome.why.screen}")
    }

    // every drive of a flow: walking face, "please let me pass" when blocked, stops for urgent help
    private suspend fun drive(spot: String, escort: Boolean, lang: Lang): NavOutcome {
        setWalking(true)
        try {
            return Robot.goTo(spot, escort, stopWhen = { NovaBrain.helpActive() }) { onNavEvent(it, lang) }
        } finally {
            setWalking(false)
            _ui.update { it.copy(task = null) }
        }
    }

    fun setWalking(on: Boolean) {
        _ui.update { it.copy(walking = on) }
    }

    // blocked: ask people to let nova pass, at most every OBSTACLE_TALK_EVERY_MS
    fun onNavEvent(ev: NavEvent, lang: Lang = Lang(null)) {
        when (ev) {
            NavEvent.BLOCKED -> {
                setTask("Something is in my way")
                val now = System.currentTimeMillis()
                if (now - lastObstacleTalk >= OBSTACLE_TALK_EVERY_MS) {
                    lastObstacleTalk = now
                    scope.launch { Voice.speak("Politely ask the people in your way to let you pass.", lang) }
                }
            }
            NavEvent.CLEAR -> setTask(null)
        }
    }

    // ---- idle ----

    fun hasContent(): Boolean = _ui.value.let { it.steps.isNotEmpty() || it.subtitle.isNotBlank() || it.opener != null }

    // back to the welcome card; the next patient starts with a fresh llm context
    fun idleReset() {
        current = null
        _ui.update { it.copy(header = null, steps = emptyList(), subtitle = "", task = null, queue = null, opener = null) }
        runCatching { AgentCore.clearContext() }
    }

    // ---- helpers ----

    private suspend fun spotFor(doctor: String, known: Known?): String? {
        val pairs = buildList {
            known?.let { k -> (k.spot ?: k.visit.spot)?.let { add(k.visit.doctorName to it) } }
            visits.values.forEach { k -> (k.spot ?: k.visit.spot)?.let { add(k.visit.doctorName to it) } }
        }
        Names.resolveSpot(doctor, known = pairs)?.let { return it }
        val doctors = net(quiet = true) { Backend.doctors() }.getOrDefault(emptyList())
        Names.resolveSpot(doctor, doctors = doctors)?.let { return it }
        return Names.resolveSpot(doctor, places = Robot.places())
    }

    private fun findKnown(doctor: String?, patient: String?): Known? {
        val open = visits.values.filter {
            VisitStatus.rank(it.visit.status) in 0..VisitStatus.rank(VisitStatus.ESCORTING)
        }
        val byPatient = patient?.let { p -> Names.match(p, open) { it.visit.patientName } }
        if (byPatient != null && (doctor == null || Names.same(doctor, byPatient.visit.doctorName))) return byPatient
        return current?.let { visits[it] }?.takeIf { doctor == null || Names.same(doctor, it.visit.doctorName) }
    }

    private fun keep(v: Visit, spot: String?, notified: Boolean?): Known {
        val k = Known(v, spot ?: v.spot, notified)
        visits[v.id] = k
        current = v.id
        return k
    }

    private fun setStatus(id: String, status: String) {
        visits.computeIfPresent(id) { _, k -> k.copy(visit = k.visit.copy(status = status)) }
    }

    private suspend fun speakOrShow(task: String, lang: Lang, screen: String) {
        val ok = working { Voice.speak(task, lang) }
        if (!ok) _ui.update { it.copy(subtitle = screen, subtitleFromUser = false) }
    }

    private suspend fun sayOrShow(text: String?) {
        if (!Voice.say(text) && !text.isNullOrBlank()) {
            _ui.update { it.copy(subtitle = text, subtitleFromUser = false) }
        }
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
    private suspend fun <T> net(quiet: Boolean = false, block: () -> T): Result<T> {
        val call: suspend () -> Result<T> = {
            withContext(Dispatchers.IO) { runCatching(block) }
                .onSuccess { _ui.update { it.copy(backend = Health.OK, banner = null) } }
                .onFailure { e ->
                    val why = Backend.friendly(e)
                    Log.w(TAG, "backend: $why")
                    _ui.update { it.copy(backend = Health.DOWN, banner = "Practice server: $why") }
                }
        }
        return if (quiet) call() else working { call() }
    }

    private fun show(steps: List<Step>, header: String? = null) {
        lastFlowAt = System.currentTimeMillis()
        _ui.update { it.copy(steps = steps, header = header ?: it.header) }
    }

    // a visit from the backend: its steps, header and queue place (none when it is not waiting)
    private fun showVisit(steps: List<Step>, header: String, visit: Visit?) {
        lastFlowAt = System.currentTimeMillis()
        _ui.update { it.copy(steps = steps, header = header, queue = visit?.let(Queue::of)) }
    }

    private fun setTask(task: String?) {
        _ui.update { it.copy(task = task) }
    }

    private fun flash(mood: Mood, ms: Long = 2_500) {
        flashJob?.cancel()
        _ui.update { it.copy(flash = mood) }
        flashJob = scope.launch {
            delay(ms)
            _ui.update { s -> if (s.flash == mood) s.copy(flash = null) else s }
        }
    }
}
