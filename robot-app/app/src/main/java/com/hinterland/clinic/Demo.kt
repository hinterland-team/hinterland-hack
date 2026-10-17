package com.hinterland.clinic

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.random.Random

// demo mode on the robot (docs/demo-mode.md). while otto is in demo mode the brain gives the demo the body
// (Plan.Demo) and runs Demo.run: drive to the reception place, wait for a person in front (the camera's
// presence and distance as for greetings: no face recognition, no templates, nothing kept) or the operator's
// Next, the reception part with a real self check-in journey, the real walk as a fixed routine (Guide with
// DemoWalk), the arrive part, back to the reception without a word, and again, until the demo is stopped.
// the script, the player and the walk plan are plain kotlin (DemoScript.kt); this object does the robot, the server
// and the words. the team steers it with the command demo and sees it in the heartbeat's demo object; every
// step goes to the log (info, app).
object Demo {

    // the camera and the radar are looked at this often while otto waits
    private const val LOOK_MS = 200L
    private const val RETRY_DRIVE_MS = 3_000L
    private const val AT_SPOT_M = 1.0
    // a run that failed does not start again at once
    private const val FAILED_RUN_PAUSE_MS = 3_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var script: DemoScript = DemoScripts.default
    // the last script the dashboard sent with demo start, null = the built-in one
    @Volatile private var sent: DemoScript? = null

    private val _state = MutableStateFlow(DemoState())
    val state: StateFlow<DemoState> = _state.asStateFlow()

    // the team's signals (any thread)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var nexts = 0
    @Volatile var held = false
        private set
    // Next during the walk: the walk part the guide plays at its next stop
    @Volatile private var walkNext: String? = null
    // go_to in demo mode: the next run drives there and holds
    @Volatile private var moveTo: RobotCommand? = null

    // the run under way (one person: reception, walk, back); a restart cancels only this one
    @Volatile private var cycle: Job? = null
    @Volatile private var cycleScope: CoroutineScope? = null
    @Volatile private var trip: Trip? = null
    @Volatile private var player: DemoPlayer? = null
    @Volatile private var plan: DemoWalkPlan? = null
    private val partLock = Mutex()
    // the check-in call under way (since when, 0 = none) and a guide the poll got meanwhile (field test b16 run 1)
    @Volatile private var checkInSince = 0L
    @Volatile private var polledGuide: RobotCommand? = null

    // what the heartbeat shows
    @Volatile private var waitingFor: String? = null
    @Volatile private var line: String? = null
    @Volatile private var ticket: String? = null
    @Volatile private var runs = 0
    @Volatile private var walking = false
    // the guide command of the demo's own walk (from the check-in answer): the poll leaves it alone
    @Volatile private var commandId: String? = null

    // one run's check-in and walk. journeyId null = a local ticket (the server was not reachable)
    private class Trip(val place: DemoPlace, val reception: DemoPlace) {
        @Volatile var ticket: String? = null
        @Volatile var journeyId: String? = null
        @Volatile var legId: String? = null
        @Volatile var command: RobotCommand? = null
        @Volatile var pace: Pace? = null
        @Volatile var walkStarted = false
        @Volatile var walkOutcome: GuideOutcome? = null
    }

    private fun now() = System.currentTimeMillis()

    val running: Boolean get() = Modes.current == RobotMode.DEMO

    fun owns(id: String): Boolean = id == commandId

    // a guide command the poll got in demo mode: the demo's own walk (its command, journey or ticket, or any guide
    // while its check-in call runs, DemoGuides) is never refused; the poll leaves it to the demo. false = refuse it
    fun takesGuide(cmd: RobotCommand): Boolean {
        if (!running) return false
        val t = trip
        val since = checkInSince.takeIf { it > 0L }
        if (!DemoGuides.ours(cmd, commandId, t?.journeyId, t?.ticket, since, now())) return false
        if (cmd.id != commandId) {
            // the check-in's answer has not come yet: the walk came through the poll first
            if (since != null && t?.command == null) polledGuide = cmd
            RobotLog.i(LogSource.APP, "Demo: guide ${cmd.ticket ?: cmd.id} is the demo's own walk, not refused" + if (since != null) " (the check-in answer is still on its way)" else "")
        }
        return true
    }

    // ---- the mode (Modes.apply) ----

    // demo mode on: a new demo with the last script the dashboard sent, else the built-in one (the brain starts
    // the run). off: the brain stops the run, whose cleanup ends a journey that is under way
    fun modeChanged(old: RobotMode, new: RobotMode) {
        if (new == RobotMode.DEMO) {
            runs = 0
            held = false
            walkNext = null
            line = null
            script = sent ?: DemoScripts.default
            RobotLog.i(LogSource.APP, "Demo on with the ${if (sent != null) "dashboard's" else "built-in"} script: ${describe(script)}")
        } else if (old == RobotMode.DEMO) {
            held = false
            walkNext = null
            Speech.stopScript("the demo is over")
            moveTo?.let { m ->
                moveTo = null
                scope.launch { Clinic.commandDone(m.id, CommandResult(false, "The demo ended before Otto drove to ${m.spot}")) }
            }
            RobotLog.i(LogSource.APP, "Demo off after $runs run${if (runs == 1) "" else "s"}")
        }
        publish()
    }

    // ---- the team's command demo (docs/demo-mode.md), answered with done { ok, detail } ----

    suspend fun command(cmd: RobotCommand): CommandResult = when (cmd.op) {
        "start" -> start(cmd.script, "dashboard")
        "next" -> next("dashboard")
        "hold" -> hold(true)
        "resume" -> hold(false)
        "restart" -> restart("dashboard")
        "stop" -> stop("dashboard")
        "say" -> say(cmd.text)
        else -> CommandResult(false, "Unknown demo op \"${cmd.op ?: ""}\": start, next, hold, resume, restart, stop or say")
    }

    // demo mode on (with this script, else the last one or the built-in one): to the reception, wait for a person.
    // already on: the run under way ends cleanly and a new one starts with the script
    fun start(raw: String?, source: String): CommandResult {
        var note = ""
        if (raw != null) {
            val r = DemoScripts.parse(raw)
            r.warnings.forEach { RobotLog.w(LogSource.APP, "Demo script: $it") }
            sent = if (r.builtIn) null else r.script
            if (r.builtIn) note = " (the script sent did not read, the built-in one is used)"
            else if (r.warnings.isNotEmpty()) note = " (${r.warnings.size} warning${if (r.warnings.size == 1) "" else "s"} in the log)"
        }
        val use = sent ?: DemoScripts.default
        if (running) {
            script = use
            restartRun("start, $source")
            return CommandResult(true, "Demo starts again at the reception: ${describe(use)}$note")
        }
        val r = Modes.request(RobotMode.DEMO, "demo start ($source)")
        return CommandResult(r.ok, (if (running) "Demo started: ${describe(use)}" else r.detail) + note)
    }

    // ends the wait under way at once (a person, a patient's line, wait_close). during the walk with no part
    // running: the next walk part not played yet (firstCheck, then fellBack)
    fun next(source: String): CommandResult {
        if (!running) return CommandResult(false, "The demo is not running")
        val before = waitingFor
        val p = player
        nexts++
        val detail = when {
            p != null && p.skip(now()) -> "Next: the wait ends (${p.part} ${p.stepNo()}/${p.total})"
            before == DemoWait.PERSON -> "Next: the reception part starts"
            before == DemoWait.CLOSE -> "Next: the patient is close"
            walking && p == null -> {
                val part = plan?.nextByHand()
                if (part != null) {
                    walkNext = part
                    // otto waits for a patient who is behind or out of sight: that wait ends first
                    val g = Guide.current
                    if (g != null && g.phase in setOf(GuidePhase.WAITING, GuidePhase.LOOKING, GuidePhase.LOST)) g.present()
                    "Next: Otto stops for $part"
                } else {
                    // like "I'm here" on the screen: a silent wait for the patient ends
                    Guide.current?.present()
                    "Next: both walk parts were played, Otto goes on"
                }
            }
            before == DemoWait.HOLD -> "Next noted: the demo is held, resume to go on"
            else -> "Nothing to skip right now: Otto is talking or driving"
        }
        wake.trySend(Unit)
        RobotLog.i(LogSource.APP, "Demo ($source) $detail")
        publish()
        return CommandResult(true, detail)
    }

    // hold: pause before the next step (during the walk otto stands too); resume: go on
    fun hold(on: Boolean): CommandResult {
        if (!running) return CommandResult(false, "The demo is not running")
        if (held == on) return CommandResult(true, if (on) "The demo is held already" else "The demo runs")
        held = on
        wake.trySend(Unit)
        val detail = if (on) "Held: Otto stops before the next step" + (if (walking) " and stands" else "") else "Resumed"
        RobotLog.i(LogSource.APP, "Demo: $detail")
        publish()
        return CommandResult(true, detail)
    }

    // the run under way ends cleanly, back to the reception, wait for the next person
    fun restart(source: String): CommandResult {
        if (!running) return start(null, source)
        restartRun(source)
        return CommandResult(true, "Restarted: the run under way ends, Otto goes back to the reception")
    }

    // demo mode off (back to normal mode); a journey under way ends cleanly
    fun stop(source: String): CommandResult {
        if (!running) return CommandResult(true, "The demo is not running")
        val r = Modes.request(RobotMode.NORMAL, "demo stop ($source)")
        return CommandResult(r.ok, "Demo stopped, Otto is in Normal mode")
    }

    // the team's line, said right away as it is (the mic stays off)
    suspend fun say(text: String?): CommandResult {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return CommandResult(false, "No text to say")
        RobotLog.i(LogSource.APP, "Demo: the team's line \"${t.take(80)}\"")
        line = t
        publish()
        return when (val o = Speech.say(SpeechKind.TEAM, t, script = running)) {
            TurnOutcome.SAID -> CommandResult(true, "Said: \"${t.take(60)}\"")
            TurnOutcome.SILENT -> CommandResult(false, "Otto is in ${Modes.current.label} mode and does not talk")
            else -> CommandResult(false, "Not said (${o.name.lowercase(Locale.ROOT)})")
        }
    }

    // go_to in demo mode: the run under way ends cleanly, otto drives there and holds. resume = wait for the next
    // person there, restart = back to the reception. done is answered when he got there
    fun goTo(cmd: RobotCommand): Boolean {
        if (cmd.spot.isNullOrBlank()) return false
        moveTo?.let { old -> scope.launch { Clinic.commandDone(old.id, CommandResult(false, "Another go_to came first")) } }
        moveTo = cmd
        restartRun("go_to ${cmd.spot}")
        return true
    }

    private fun restartRun(why: String) {
        RobotLog.i(LogSource.APP, "Demo: restart ($why)")
        held = false
        walkNext = null
        Speech.stopScript("the demo starts again")
        cycle?.cancel()
        wake.trySend(Unit)
    }

    // ---- the run (the brain's task in demo mode, cancelled when the mode changes) ----

    suspend fun run() {
        RobotLog.i(LogSource.APP, "Demo: Otto takes over for the demo")
        try {
            coroutineScope {
                while (isActive && running) {
                    val c = launch { oneRun() }
                    cycle = c
                    c.join()
                }
            }
        } finally {
            cycle = null
            withContext(NonCancellable) {
                waitingFor = null
                publish()
            }
        }
    }

    // one person: reception, walk, back. a restart cancels it; the cleanup runs on every path
    private suspend fun oneRun() = coroutineScope {
        cycleScope = this
        val s = script
        var run: Trip? = null
        ticket = null
        try {
            val t = Trip(where(s.destination), where(s.reception))
            run = t
            trip = t
            val move = moveTo
            moveTo = null
            if (move != null) {
                val spot = move.spot.orEmpty().trim()
                val ok = drive(DemoPlace(spot, spot, null), back = false)
                Clinic.commandDone(
                    move.id,
                    CommandResult(ok, if (ok) "Otto is at $spot, the demo is held there: resume = the next person there, restart = back to the reception" else "Could not reach $spot"),
                )
                held = true
                RobotLog.i(LogSource.APP, "Demo: held at $spot after go_to")
                waitForPerson(s, DemoPlace(spot, spot, null))
            } else {
                drive(t.reception, back = false)
                waitForPerson(s, t.reception)
            }
            OttoBrain.activity(RobotState.TALKING, "Demo: reception part")
            playPart(DemoPart.RECEPTION)
            // a script without a check-in: the walk still needs its journey
            if (t.command == null) checkIn(t)
            walk(t)
            drive(where(s.reception), back = true)
            runs++
            RobotLog.i(LogSource.APP, "Demo: run $runs done" + (t.ticket?.let { " (ticket $it)" } ?: ""))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RobotLog.e(LogSource.APP, "Demo run failed: ${e.javaClass.simpleName} ${e.message.orEmpty().take(100)}")
            delay(FAILED_RUN_PAUSE_MS)
        } finally {
            withContext(NonCancellable) { run?.let { endRun(it) } }
            cycleScope = null
        }
    }

    // the end of a run, also a stopped or restarted one: no part, no ticket card, a journey under way ends cleanly
    // (the leg fails with the reason, its command is closed), navigation is stopped by the cancel
    private suspend fun endRun(t: Trip) {
        player = null
        walking = false
        plan = null
        walkNext = null
        checkInSince = 0L
        // a guide the poll took for the demo while its check-in ran, but the walk never used: refused now, so it
        // does not hang on the board (only when it was not the demo's own after all)
        polledGuide?.let { g ->
            polledGuide = null
            if (g.id != t.command?.id) refuseLater(g)
        }
        val journey = t.journeyId
        if (t.command != null && t.walkOutcome == null) {
            Speech.stopScript("the demo run ended")
            if (journey != null) {
                Clinic.net(quiet = true) {
                    Backend.patchJourney(journey, t.legId, LegStatus.FAILED, JourneyEvent.FAILED, "The demo was ended by the team")
                }.onFailure { RobotLog.w(LogSource.NET, "Demo: journey update failed: ${Backend.friendly(it)}") }
                // the walk closes its command itself; before the walk it is closed here
                if (!t.walkStarted) t.command?.let { c -> Clinic.net(quiet = true) { Backend.commandDone(c.id) } }
            }
            RobotLog.i(LogSource.APP, "Demo: ticket ${t.ticket ?: "-"} ended cleanly" + if (journey == null) " (local ticket)" else "")
        }
        if (commandId == t.command?.id) commandId = null
        if (trip === t) trip = null
        if (Guide.current == null) Clinic.showGuide(null)
        Clinic.demoTask(null)
        ticket = null
        waitingFor = null
        publish()
    }

    // ---- the steps ----

    // the coordinator's rule 17.10: the dashboard's place by name, then by spot; with no places (empty or not
    // reachable) the map spot of that name, so otto drives there directly
    private suspend fun where(name: String): DemoPlace {
        val fresh = Clinic.refreshPlaces()
        val places = Clinic.placesHere(fresh ?: Clinic.ui.value.places)
        val p = DemoPlaces.resolve(name, places)
        if (p.place == null) {
            RobotLog.w(
                LogSource.APP,
                if (places.isEmpty()) "Demo: no places from the dashboard, \"$name\" is taken as a spot on Otto's map"
                else "Demo: \"$name\" is no place on the dashboard, it is taken as a spot on Otto's map",
            )
        }
        return p
    }

    // to the reception place (back = after a walk, without a word), or the go_to spot. a drive that does not work
    // is tried once more, then otto waits where he is (the operator can restart or send him). true = he is there
    private suspend fun drive(at: DemoPlace, back: Boolean): Boolean {
        val spot = at.spot
        if (spot.isBlank()) return false
        if (Names.same(Robot.position, spot) || Robot.isAt(spot, AT_SPOT_M) == true) {
            if (!back) RobotLog.i(LogSource.APP, "Demo: Otto is at ${at.label} already")
            return true
        }
        set(if (back) DemoWait.BACK else DemoWait.RECEPTION)
        OttoBrain.activity(if (back) RobotState.RETURNING else RobotState.WALKING, if (back) "Demo: driving back to ${at.label}" else "Demo: driving to ${at.label}")
        RobotLog.i(LogSource.APP, if (back) "Demo: driving back to ${at.label} (spot $spot), without a word" else "Demo: driving to ${at.label} (spot $spot)")
        for (attempt in 1..2) {
            Clinic.setWalking(true)
            val o = try {
                // no words on the way: a blocked way is waited out
                Robot.goTo(spot, onEvent = { Clinic.onNavEvent(it, own = {}) })
            } finally {
                Clinic.setWalking(false)
            }
            when (o) {
                NavOutcome.Arrived -> {
                    RobotLog.i(LogSource.APP, "Demo: at ${at.label}")
                    return true
                }
                NavOutcome.Interrupted -> return false
                is NavOutcome.Failed -> {
                    RobotLog.w(LogSource.APP, "Demo: could not reach ${at.label}: ${o.why.screen}" + if (attempt == 1) ", trying once more" else ", waiting here")
                    if (attempt == 1) delay(RETRY_DRIVE_MS)
                }
            }
        }
        return false
    }

    // step 2: someone stands in front of otto (camera, within personM for about a second) or the operator's Next.
    // start "next": the operator only. a hold keeps otto waiting
    private suspend fun waitForPerson(s: DemoScript, at: DemoPlace) {
        val watch = PresenceWatch(s.personM)
        val nextBefore = nexts
        OttoBrain.activity(RobotState.IDLE, "Demo: waiting for a person at ${at.label}")
        RobotLog.i(
            LogSource.APP,
            "Demo: waiting for a person at ${at.label}" +
                if (s.start == DemoStart.NEXT) " (starts with Next only)" else " (within ${fmt(s.personM)} m, or Next)",
        )
        while (true) {
            set(if (held) DemoWait.HOLD else DemoWait.PERSON)
            if (!held && nexts != nextBefore) {
                RobotLog.i(LogSource.APP, "Demo: Next, the reception part starts")
                return
            }
            if (!held && s.start == DemoStart.PERSON) {
                val d = nearestPerson()
                if (watch.seen(now(), d)) {
                    RobotLog.i(LogSource.APP, "Demo: someone in front of Otto (%.1f m), the reception part starts".format(Locale.ROOT, d ?: 0.0))
                    return
                }
            } else {
                watch.reset()
            }
            withTimeoutOrNull(LOOK_MS) { wake.receive() }
        }
    }

    // the nearest person the camera sees: the faces as for greetings, else anyone (a body). a distance, nothing else
    private fun nearestPerson(): Double? {
        val face = Robot.faces()?.filter { it.distance > 0.0 }?.minOfOrNull { it.distance }
        val body = Robot.peopleDistances()?.filter { it > 0.0 }?.minOrNull()
        return listOfNotNull(face, body).minOrNull()
    }

    // someone close again (wait_close): a person-like shape beside otto on the radar, either side, or a person in
    // front on the camera
    private fun nearestClose(): Double? = listOfNotNull(
        Radar.nearestBeside(now())?.takeIf { it > 0.0 },
        Robot.peopleDistances()?.filter { it > 0.0 }?.minOrNull(),
    ).minOrNull()

    // a guide the demo took while its check-in ran, but it was not the demo's walk: refused as in any demo run
    private fun refuseLater(g: RobotCommand) {
        val why = ModeRules.refuses(RobotMode.DEMO, RobotCommand.GUIDE) ?: return
        RobotLog.i(LogSource.APP, "Demo: guide ${g.ticket ?: g.id} was not the demo's walk, refused: $why")
        scope.launch {
            Clinic.refuseGuide(g, why)
            Clinic.commandDone(g.id, CommandResult(false, why))
        }
    }

    // one part, step by step (DemoPlayer), one part at a time. otto's lines go through the turn manager as script
    // lines (never dropped) and each is said to its end before the next step
    private suspend fun playPart(name: String) {
        val steps = script.part(name)
        if (steps.isEmpty()) {
            RobotLog.i(LogSource.APP, "Demo: no $name part in the script, skipped")
            return
        }
        partLock.withLock { playSteps(name, steps) }
    }

    private suspend fun playSteps(name: String, steps: List<DemoStep>) {
        val t = trip
        val p = DemoPlayer(name, steps, Settings.ms(Config.current.linePauseS)) { DemoText.fill(it, ticket, t?.place?.label ?: script.destination) }
        player = p
        RobotLog.i(LogSource.APP, "Demo: $name part starts (${steps.size} step${if (steps.size == 1) "" else "s"})")
        var logged = -1
        try {
            while (true) {
                val no = p.stepNo()
                when (val d = p.next(now(), held)) {
                    is DemoDo.Say -> {
                        line = d.text
                        set(null)
                        RobotLog.i(LogSource.APP, "Demo $name $no/${p.total}: Otto says \"${d.text}\"")
                        val o = Speech.say(SpeechKind.SCRIPT, d.text, script = true)
                        if (o != TurnOutcome.SAID) {
                            RobotLog.w(LogSource.APP, "Demo $name $no/${p.total}: the line was not said (${o.name.lowercase(Locale.ROOT)}), going on")
                        }
                        p.said(now())
                    }
                    is DemoDo.Wait -> {
                        val w = p.waitingFor()
                        if (w == DemoWait.PATIENT && logged != no) {
                            logged = no
                            RobotLog.i(
                                LogSource.APP,
                                "Demo $name $no/${p.total}: the patient says \"${p.nextPatientLine()}\" (%.1f s or Next)".format(Locale.ROOT, (d.untilMs - now()) / 1000.0),
                            )
                        }
                        set(w)
                        withTimeoutOrNull((d.untilMs - now()).coerceIn(10L, 1_000L)) { wake.receive() }
                    }
                    is DemoDo.Act -> {
                        set(if (d.step.action == DemoAction.WAIT_CLOSE) DemoWait.CLOSE else null)
                        act(name, no, p.total, d.step)
                        p.done()
                    }
                    DemoDo.Held -> {
                        if (waitingFor != DemoWait.HOLD) RobotLog.i(LogSource.APP, "Demo $name: held before step $no/${p.total}")
                        set(DemoWait.HOLD)
                        withTimeoutOrNull(1_000L) { wake.receive() }
                    }
                    DemoDo.Done -> break
                }
            }
            RobotLog.i(LogSource.APP, "Demo: $name part done")
        } finally {
            if (player === p) player = null
            publish()
        }
    }

    private suspend fun act(part: String, no: Int, total: Int, step: DemoStep) {
        when (step.action) {
            DemoAction.CHECK_IN -> {
                val t = trip
                RobotLog.i(LogSource.APP, "Demo $part $no/$total: check-in for ${script.destination}")
                if (t != null) checkIn(t)
            }
            DemoAction.PACE -> {
                val pace = step.pace ?: return
                trip?.pace = pace
                RobotLog.i(LogSource.APP, "Demo $part $no/$total: pace ${pace.wire}")
                // a pace step during the walk: the guide takes it after this stop
                Guide.current?.choosePace(pace)
            }
            DemoAction.WAIT_CLOSE -> waitClose(part, no, total)
            null -> Unit
        }
    }

    // wait_close: a person within DemoScripts.CLOSE_M beside (radar, either side) or in front (camera) for a
    // second, at most the script's closeMaxS, or Next (or "I'm here" tapped on the screen)
    private suspend fun waitClose(part: String, no: Int, total: Int) {
        val maxMs = Settings.ms(script.closeMaxS)
        RobotLog.i(
            LogSource.APP,
            "Demo $part $no/$total: waiting until the patient is close (${fmt(DemoScripts.CLOSE_M)} m beside or in front, at most ${fmt(script.closeMaxS)} s, or Next)",
        )
        val nextBefore = nexts
        val from = now()
        val until = from + maxMs
        val close = PresenceWatch(DemoScripts.CLOSE_M, holdMs = DemoScripts.CLOSE_HOLD_MS)
        while (now() < until) {
            if (nexts != nextBefore) {
                RobotLog.i(LogSource.APP, "Demo $part $no/$total: Next, going on")
                return
            }
            if (Guide.current?.presentSince(from) == true) {
                RobotLog.i(LogSource.APP, "Demo $part $no/$total: \"I'm here\" was tapped")
                return
            }
            val d = nearestClose()
            if (close.seen(now(), d)) {
                RobotLog.i(LogSource.APP, "Demo $part $no/$total: the patient is close (%.1f m)".format(Locale.ROOT, d ?: 0.0))
                return
            }
            withTimeoutOrNull(LOOK_MS) { wake.receive() }
        }
        RobotLog.i(LogSource.APP, "Demo $part $no/$total: nobody close after ${fmt(script.closeMaxS)} s, going on")
    }

    // the real self check-in: a journey to the destination (ticket, dashboard, notifications). the server not
    // reachable (or saying no): a local ticket, and the walk goes to the place on otto's map
    private suspend fun checkIn(t: Trip) {
        if (t.command != null) return
        val s = script
        val room = t.place.place
        Clinic.demoTask("Getting your ticket")
        // from now on a guide from the poll is the demo's own (the server queues it before it answers); the window
        // stays open until the answer is taken, so there is no gap
        polledGuide = null
        checkInSince = now()
        try {
            val r = if (room != null) {
                // a room open for self check-in goes as the room itself (no waiting room in the demo); a public place as is
                val forId = room.id.takeIf { room.selfCheckIn }
                Clinic.net { Backend.createJourney(room.id, forId, s.pace, null) }
            } else {
                null
            }
            val created = r?.getOrNull()
            val polled = polledGuide
            val cmd = created?.command
            val journey = created?.journey
            when {
                cmd != null && journey != null && cmd.to != null -> {
                    OttoBrain.claim(cmd.id)
                    commandId = cmd.id
                    t.command = cmd
                    t.journeyId = journey.id
                    t.legId = cmd.legId ?: journey.leg?.id
                    t.ticket = journey.ticket.ifBlank { cmd.ticket.orEmpty() }.ifBlank { localTicket() }
                    RobotLog.i(LogSource.APP, "Demo: checked in for ${t.place.label}, ticket ${t.ticket} (journey on the dashboard)")
                    // something else came through the poll meanwhile: refused as in any demo run
                    if (polled != null && polled.id != cmd.id) refuseLater(polled)
                    polledGuide = null
                }
                // the answer got lost, but the server made the journey: its walk came through the poll
                polled != null && polled.to != null && !polled.journeyId.isNullOrBlank() -> {
                    OttoBrain.claim(polled.id)
                    commandId = polled.id
                    t.command = polled
                    t.journeyId = polled.journeyId
                    t.legId = polled.legId
                    t.ticket = polled.ticket?.ifBlank { null } ?: localTicket()
                    polledGuide = null
                    RobotLog.w(LogSource.APP, "Demo: the check-in answer did not come, its walk came through the poll: ticket ${t.ticket}")
                }
                else -> {
                    val err = r?.exceptionOrNull()
                    val why = when {
                        room == null -> "\"${s.destination}\" is no place on the dashboard"
                        err != null -> Backend.friendly(err)
                        else -> "the answer had no walk"
                    }
                    val local = localTicket()
                    t.ticket = local
                    t.command = RobotCommand(
                        id = "demo-${now()}",
                        type = RobotCommand.GUIDE,
                        ticket = local,
                        from = Stop(t.reception.label, t.reception.spot),
                        to = t.place.stop(),
                    )
                    RobotLog.w(LogSource.APP, "Demo: the check-in did not reach the server ($why): local ticket $local, the walk goes to ${t.place.spot} on Otto's map")
                }
            }
        } finally {
            checkInSince = 0L
            Clinic.demoTask(null)
        }
        ticket = t.ticket
        // the ticket card for the rest of the reception part (the walk screen follows)
        val to = t.command?.to ?: t.place.stop()
        Clinic.showGuide(
            GuideUi(
                ticket = t.ticket.orEmpty(),
                destination = to.label,
                destinationNote = to.room?.let { to.name.takeIf { n -> !Names.same(n, it) } },
                from = t.reception.label,
                phase = GuidePhase.PICKUP,
                pace = t.pace ?: Pace.NORMAL,
                side = Config.current.walkSide,
                checkedIn = true,
            ),
        )
        publish()
    }

    // step 4: the real walk at the chosen pace as the demo's fixed routine (Guide with DemoWalk)
    private suspend fun walk(t: Trip) {
        val cmd0 = t.command ?: return
        while (held) {
            set(DemoWait.HOLD)
            withTimeoutOrNull(1_000L) { wake.receive() }
        }
        val pace = t.pace ?: cmd0.pace ?: Pace.NORMAL
        val cmd = cmd0.copy(pace = pace)
        val s = script
        // the walk parts by driving time (walk.firstCheckS), not by the radar
        val p = DemoWalkPlan(s, Config.current.firstCheckS * 1000L)
        plan = p
        walkNext = null
        walking = true
        t.walkStarted = true
        set(DemoWait.WALK)
        RobotLog.i(
            LogSource.APP,
            "Demo: walk with ticket ${t.ticket} to ${cmd.to?.label} at ${pace.label.lowercase(Locale.ROOT)}, the patient on Otto's ${s.walkSide.wire}",
        )
        try {
            val o = Guide.run(cmd, OwnRequest(paceChosen = true, checkIn = true), hooks(s, p))
            t.walkOutcome = o
            if (o == GuideOutcome.ARRIVED) RobotLog.i(LogSource.APP, "Demo: walk done, arrived at ${cmd.to?.label}")
            else RobotLog.w(LogSource.APP, "Demo: the walk ended without arriving (${o.name.lowercase(Locale.ROOT)}), back to the reception")
        } finally {
            walking = false
            plan = null
            walkNext = null
        }
    }

    // what the demo changes in this walk (Guide): the script's side and glance, the walk plan, its parts
    private fun hooks(s: DemoScript, p: DemoWalkPlan) = object : DemoWalk {
        override val side: WalkSide = s.walkSide

        override val glanceDeg: Double = s.glanceDeg

        override val plan: DemoWalkPlan = p

        override suspend fun play(part: String) {
            p.played(part)
            if (walkNext == part) walkNext = null
            playPart(part)
            if (walking) set(DemoWait.WALK)
        }

        // the obstacle line while otto waits at it; never in the middle of another part
        override fun playLater(part: String) {
            val sc = cycleScope ?: return
            if (partLock.isLocked) return
            sc.launch {
                playPart(part)
                if (walking) set(DemoWait.WALK)
            }
        }

        override fun held(): Boolean = held

        override fun nextWanted(): Boolean = walkNext != null

        override fun takeNext(): String? = walkNext.also { walkNext = null }
    }

    // ---- what the heartbeat and the staff menu show ----

    private fun set(wait: String?) {
        waitingFor = wait
        publish()
    }

    private fun publish() {
        val p = player
        val s = script
        _state.value = if (!running) {
            DemoState(running = false, line = line, runs = runs)
        } else {
            DemoState(
                running = true,
                part = p?.part,
                step = p?.stepNo() ?: 0,
                total = p?.total ?: 0,
                waitingFor = waitingFor,
                line = line,
                nextPatientLine = p?.nextPatientLine() ?: upcomingPatientLine(s),
                ticket = ticket,
                runs = runs,
            )
        }
    }

    // the patient's next line while no part plays: during the walk the next cue's (else arrive's), otherwise the
    // reception's first
    private fun upcomingPatientLine(s: DemoScript): String? {
        val part = if (walking) plan?.nextByHand() ?: DemoPart.ARRIVE else DemoPart.RECEPTION
        return s.firstPatientLine(part)?.let { DemoText.fill(it, ticket, trip?.place?.label ?: s.destination) }
    }

    // one line for the staff menu
    fun summary(): String {
        val st = _state.value
        val done = "$runs run${if (runs == 1) "" else "s"} done"
        if (!st.running) return "Off · Start uses the ${if (sent != null) "dashboard's" else "built-in"} script" + if (runs > 0) " · $done" else ""
        val what = when (st.waitingFor) {
            DemoWait.RECEPTION -> "driving to the reception"
            DemoWait.PERSON -> "waiting for a person"
            DemoWait.PATIENT -> "patient: \"${st.nextPatientLine.orEmpty().take(40)}\""
            DemoWait.CLOSE -> "waiting until the patient is close"
            DemoWait.WALK -> "walking"
            DemoWait.HOLD -> "held (resume on the dashboard)"
            DemoWait.BACK -> "driving back"
            else -> st.line?.let { "Otto: \"${it.take(40)}\"" } ?: "starting"
        }
        return (st.part?.let { "$it ${st.step}/${st.total} · " } ?: "") + what + (st.ticket?.let { " · ticket $it" } ?: "") + " · $done"
    }

    private fun describe(s: DemoScript): String =
        "${s.destination} from ${s.reception}, start by ${s.start.wire}" + if (s.start == DemoStart.PERSON) " within ${fmt(s.personM)} m" else ""

    // a ticket when the server is not reachable (a letter and two digits, like the server's)
    private fun localTicket(): String = "A" + Random.nextInt(10, 100)

    private fun fmt(v: Double) = SettingValues.fmt(v, whole = false)
}
