package com.hinterland.clinic

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

// otto's brain. one loop looks around every second and picks the most important thing to do
// (the priorities are in Brain.decide). one task at a time: every task holds the body lock.
// a guide (staff command or the person's own request) runs as such a task. nothing new starts
// while someone is talking to otto, except what that person asked for.
object OttoBrain {

    private const val TICK_MS = 1_000L
    private const val POLL_MS = 3_000L
    // demo mode: the operator's Next must feel instant (docs/demo-mode.md)
    private const val DEMO_POLL_MS = 500L
    private const val HEARTBEAT_MS = 15_000L
    // while driving the dashboard wants the position more often
    private const val HEARTBEAT_MOVING_MS = 5_000L
    // a screen change goes to the dashboard mirror at once, but not more often than this
    private const val SCREEN_PUSH_MIN_MS = 1_500L
    // the demo's step changes go out quicker (the operator's teleprompter)
    private const val DEMO_PUSH_MIN_MS = 500L
    private const val FEED_MS = 2_000L
    private const val PLACES_MS = 60_000L
    private const val HANDLED_KEEP = 50
    private const val HOME_CHECK_MS = 20_000L
    private const val IDLE_RESET_MS = 45_000L
    private const val HELP_MAX_MS = 10 * 60_000L
    private const val HELP_CHECK_MS = 10_000L
    private const val BOOT_GRACE_MS = 20_000L
    private const val MAX_QUEUE = 5
    // faces this close count as "the person otto talks to"
    private const val TALK_RANGE_M = 2.5
    private const val ENGAGED_KEEP_MS = 10 * 60_000L
    private const val FAILURE_SHOW_MS = 2 * 60_000L
    private const val LOG_EVERY_MS = 60_000L
    // an action that never finished does not keep otto "in a conversation" forever
    private const val ACTION_MAX_MS = 6 * 60_000L
    // "moved by hand" at most this often in the log
    private const val MOVED_LOG_MS = 10_000L

    private class Running(val job: Job, val preemptible: Boolean)

    // preemptible = gives way to commands, a patient walk and urgent help (driving home, to the charger)
    private class Task(val preemptible: Boolean, val block: suspend () -> Unit)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val body = Mutex()
    @Volatile private var current: Running? = null

    private val _status = MutableStateFlow(BrainStatus())
    val status: StateFlow<BrainStatus> = _status.asStateFlow()

    private val startedAt = now()

    // conversation (agent listeners and actions, worker threads)
    @Volatile private var agent = AgentMode.IDLE
    @Volatile private var agentAt = 0L
    @Volatile private var userSpokeAt = 0L
    @Volatile private var robotSpokeAt = 0L
    private val actions = AtomicInteger(0)
    @Volatile private var actionAt = 0L
    @Volatile private var contextDirty = false

    // commands from the backend, oldest first
    private val commands = CopyOnWriteArrayList<RobotCommand>()
    // the person in front asked for these (by command id, and by journey id until the command arrives)
    private val ownByCommand = ConcurrentHashMap<String, OwnRequest>()
    private val ownByJourney = ConcurrentHashMap<String, OwnRequest>()
    // command ids already taken (from a POST answer or the poll), so a command never runs twice
    private val handled = LinkedHashSet<String>()
    @Volatile private var runningCommand: String? = null
    @Volatile private var waitingFor: String? = null
    @Volatile private var waitingSince: Long? = null

    // people in front of otto
    @Volatile private var visitor: Visitor? = null
    @Volatile private var talker: Visitor? = null
    private val engaged = ConcurrentHashMap<Int, Long>()
    @Volatile private var lastGreetAt = 0L
    @Volatile private var lastTalkAt = 0L

    // urgent help
    @Volatile private var helpAt: Long? = null
    @Volatile private var helpRequest: String? = null
    @Volatile private var helpOpen: Boolean? = null
    @Volatile private var helpCheckedAt = 0L

    // body
    @Volatile private var parkedAt: String? = null
    @Volatile private var returnHomeNow = false
    @Volatile private var returnFailedAt = 0L
    @Volatile private var chargeFailedAt = 0L
    @Volatile private var leaveFailedAt = 0L
    @Volatile private var quietSince = now()
    @Volatile private var homeCheckedAt = 0L

    // what the running task says it does, and the last thing that went wrong
    @Volatile private var doing: Pair<RobotState, String>? = null
    @Volatile private var failure: Pair<Long, String>? = null
    @Volatile private var lastPlan: Plan = Plan.Wait
    @Volatile private var lastSnap = Snapshot(now = now())
    @Volatile private var loggedAt = 0L

    // show mode: the small turns; someone moved otto by hand (logged, calm mode spec)
    private val showMoves = ShowMoves()
    @Volatile private var movedSum = 0.0
    @Volatile private var movedLoggedAt = 0L
    @Volatile private var lowBatteryLogged = false

    private fun now() = System.currentTimeMillis()

    // runs while the screen is in front (MainActivity). tasks live in our own scope,
    // so a walk that is under way still finishes if the activity pauses.
    suspend fun run() {
        withContext(Dispatchers.Default) {
            RobotLog.i(LogSource.APP, "Nova Clinic ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) running, servers: ${Servers.list.joinToString { ServerList.host(it) }.ifBlank { "none set" }}")
            launch { Servers.loop() }
            launch { loop() }
            launch { pollCommands() }
            launch { heartbeat() }
            launch { sendFeed() }
            launch { refreshPlaces() }
            launch { RobotLog.ship() }
            launch { LiveMap.loop() }
            // docs/robot-setup.md: live settings, the real map and the floors
            launch { Config.loop() }
            launch { MapSync.loop() }
        }
    }

    // a mode change (Modes): a drive that the new mode does not allow stops, a new show starts where otto is
    fun modeChanged(old: RobotMode, new: RobotMode) {
        if (new == RobotMode.SHOW) showMoves.reset()
        if (!ModeRules.drivesHome(new)) returnHomeNow = false
        val running = current
        if (running != null && running.preemptible && Brain.stopsFor(lastPlan, new)) {
            RobotLog.i(LogSource.BRAIN, "${Brain.goal(lastPlan, lastSnap)} stopped: ${new.label} mode")
            running.job.cancel()
        }
        if (new.micOff) Robot.stopFollow()
        lowBatteryLogged = false
        publish()
    }

    // ---- signals (listener threads) ----

    fun heardUser() {
        userSpokeAt = now()
        contextDirty = true
    }

    fun heardRobot() {
        robotSpokeAt = now()
    }

    // last time the mic heard the person (talk window at a check-back)
    fun lastUserSpeechAt(): Long = userSpokeAt

    // otto's ai is busy with an answer right now: planning, an action, or otto speaking. tighter than
    // the brain's talk level, which stays on for 8 s after the last words
    fun aiBusy(): Boolean {
        val now = now()
        val inFlight = now - actionAt < ACTION_MAX_MS && actions.get() > 0
        val thinking = agent == AgentMode.THINKING && now - agentAt < Brain.THINKING_MAX_MS
        return inFlight || thinking || Clinic.ui.value.speaking
    }

    fun agentStatus(mode: AgentMode) {
        agent = mode
        agentAt = now()
    }

    fun actionStarted() {
        actions.incrementAndGet()
        actionAt = now()
        contextDirty = true
    }

    fun actionEnded() {
        actions.updateAndGet { (it - 1).coerceAtLeast(0) }
    }

    // ---- requests from the person in front (GUIDE_TO_PLACE, CHECK_IN_FOR, self check-in screen) ----

    // the guide command for this journey may still come through the poll
    fun expectOwn(journeyId: String, own: OwnRequest) {
        ownByJourney[journeyId] = own
    }

    // the command from the POST answer: it runs next, without a pickup
    fun addOwnCommand(cmd: RobotCommand, own: OwnRequest) {
        if (!markHandled(cmd.id)) return
        ownByCommand[cmd.id] = own
        if (commands.none { it.id == cmd.id }) commands.add(cmd)
    }

    // the demo's own walk came with its check-in answer: the poll must not take it again
    fun claim(id: String) {
        markHandled(id)
    }

    // false when this command was taken before
    private fun markHandled(id: String): Boolean = synchronized(handled) {
        if (!handled.add(id)) return false
        while (handled.size > HANDLED_KEEP) handled.remove(handled.first())
        true
    }

    private fun ownFor(cmd: RobotCommand): OwnRequest? =
        ownByCommand[cmd.id] ?: cmd.journeyId?.let { ownByJourney[it] }

    // urgent CALL_STAFF: otto stays with the person, a drive home or to the charger stops at once
    fun helpStarted() {
        helpAt = now()
        helpRequest = null
        helpOpen = null
        helpCheckedAt = 0L
        current?.takeIf { it.preemptible }?.job?.cancel()
        publish()
    }

    fun helpSent(id: String?) {
        helpRequest = id
    }

    // over when the team closed the request on the board, or after HELP_MAX_MS
    fun helpActive(): Boolean {
        val at = helpAt ?: return false
        return now() - at < HELP_MAX_MS && helpOpen != false
    }

    // ---- what running tasks report ----

    fun activity(state: RobotState, goal: String) {
        doing = state to goal
        publish()
    }

    fun failed(detail: String) {
        failure = now() to detail
        publish()
    }

    // the way back after a walk did not work; the brain tries again later
    fun returnFailed(detail: String) {
        returnFailedAt = now()
        returnHomeNow = true
        failed(detail)
    }

    // the caller holds the body lock; it is released when the task ends, however it ends
    private fun start(task: Task): Job {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                task.block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                RobotLog.e(LogSource.BRAIN, "Task failed: ${e.javaClass.simpleName} ${e.message.orEmpty().take(120)}")
                failed(e.message ?: e.javaClass.simpleName)
            }
        }
        // also runs when the job is cancelled before it started. the next tick shows the next goal.
        job.invokeOnCompletion {
            doing = null
            current = null
            quietSince = now()
            body.unlock()
        }
        current = Running(job, task.preemptible)
        job.start()
        return job
    }

    // what the running task does, null when otto has no task (DeployHold)
    fun taskState(): RobotState? {
        if (current == null) return null
        return doing?.first ?: Brain.state(lastPlan, Robot.home)
    }

    // ---- the loop ----

    private suspend fun loop() {
        while (true) {
            try {
                tick()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                RobotLog.e(LogSource.BRAIN, "Tick failed: ${e.javaClass.simpleName} ${e.message.orEmpty().take(120)}")
            }
            delay(TICK_MS)
        }
    }

    private suspend fun tick() {
        val now = now()
        val inFlight = if (now - actionAt < ACTION_MAX_MS) actions.get() else 0
        val mode = Modes.current
        Clinic.expireCheckIn(now)
        Clinic.setChassis(Robot.chassisIssue())
        // show / calm: the mic stays off (AgentOS opens it again when the app comes to the front)
        Screen.enforce()
        noteMovedByHand(now, mode)
        // the self check-in screen counts as a conversation: nothing new starts meanwhile
        val talk = if (Clinic.ui.value.checkIn != null) TalkLevel.ACTIVE
        else Brain.talkLevel(now, agent, agentAt, userSpokeAt, robotSpokeAt, inFlight)
        if (talk == TalkLevel.ACTIVE) lastTalkAt = now
        val busy = current != null || body.isLocked
        if (talk != TalkLevel.NONE || busy) quietSince = now
        if (helpAt != null && !helpActive()) {
            helpAt = null
            helpRequest = null
            helpOpen = null
            Clinic.helpEnded()
        }

        if (!busy) {
            look(now, talk)
            checkHome(now)
        }
        // show mode: is a small turn due? (nobody very close, not on the charger)
        val turn = if (!busy && ModeRules.idleTurns(mode) && Robot.ready) {
            showMoves.due(now, Robot.peopleDistances()?.let { it.minOrNull() ?: Double.MAX_VALUE }, Robot.chargingNow())
        } else {
            null
        }
        val snap = snapshot(now, talk, mode, turn)
        Brain.lowBatteryNote(snap).let { note ->
            if (note != null && !lowBatteryLogged) RobotLog.w(LogSource.BRAIN, note)
            lowBatteryLogged = note != null
        }
        val plan = Brain.decide(snap)
        trackWaiting(plan, now)
        lastSnap = snap

        val running = current
        if (running != null || body.isLocked) {
            if (running != null && running.preemptible && Brain.shouldPreempt(snap)) {
                running.job.cancel()
            }
            publish()
            return
        }
        if (!body.tryLock()) return
        var started = false
        try {
            lastPlan = plan
            steer(plan)
            maybeReset(now, plan)
            publish()
            val task = taskFor(plan) ?: return
            start(task)
            started = true
        } finally {
            if (!started) body.unlock()
        }
    }

    private fun look(now: Long, talk: TalkLevel) {
        val people = Robot.faces()
        visitor = people?.let { Brain.pickVisitor(it) }
        talker = people?.let { Brain.pickVisitor(it, TALK_RANGE_M) }
        // whoever stands there while otto talks is not greeted again later
        if (talk == TalkLevel.ACTIVE && people != null) {
            people.filter { it.id >= 0 && it.distance > 0.0 && it.distance <= TALK_RANGE_M }
                .forEach { engaged[it.id] = now }
        }
        engaged.entries.removeIf { now - it.value > ENGAGED_KEEP_MS }
    }

    private suspend fun checkHome(now: Long) {
        if (!Robot.ready || Robot.home.isEmpty() || Robot.chargingNow()) return
        if (now - homeCheckedAt < HOME_CHECK_MS) return
        homeCheckedAt = now
        Robot.checkHome()
    }

    // calm mode spec: log what happens when someone pushes otto (he does not react)
    private fun noteMovedByHand(now: Long, mode: RobotMode) {
        val d = Robot.movedByHand() ?: return
        movedSum += d
        if (now - movedLoggedAt < MOVED_LOG_MS) return
        movedLoggedAt = now
        RobotLog.i(LogSource.NAV, String.format(java.util.Locale.ROOT, "Otto was moved %.1f m while he did not drive (%s mode)", movedSum, mode.wire))
        movedSum = 0.0
    }

    private fun snapshot(now: Long, talk: TalkLevel, mode: RobotMode = Modes.current, turn: ShowTurn? = null) = Snapshot(
        now = now,
        mode = mode,
        showTurn = turn,
        robotReady = Robot.ready,
        homeSpot = Robot.home,
        helpActive = helpActive(),
        talk = talk,
        commands = commands.toList(),
        ownCommandIds = commands.mapNotNull { c -> c.id.takeIf { ownFor(c) != null } }.toSet(),
        waitingSince = waitingSince,
        visitor = visitor,
        lastGreetAt = lastGreetAt,
        lastTalkAt = lastTalkAt,
        engaged = HashMap(engaged),
        battery = Robot.battery,
        charging = Robot.chargingReported(),
        dockedByOtto = Robot.docked,
        atHome = Robot.atHome,
        parkedAt = parkedAt,
        quietSince = quietSince,
        returnHomeNow = returnHomeNow,
        returnFailedAt = returnFailedAt,
        chargeFailedAt = chargeFailedAt,
        leaveFailedAt = leaveFailedAt,
    )

    // the 20 s a command waits for a conversation to end start when the waiting starts
    private fun trackWaiting(plan: Plan, now: Long) {
        val id = (plan as? Plan.WaitForQuiet)?.command?.id
        if (id == null) {
            waitingFor = null
            waitingSince = null
        } else if (waitingFor != id) {
            waitingFor = id
            waitingSince = now
        }
    }

    // focus following on while someone talks to otto, off when he is idle
    private fun steer(plan: Plan) {
        when (plan) {
            Plan.Talk, Plan.StayWithPerson, is Plan.WaitForQuiet -> {
                val who = talker
                if (Robot.following == null && who != null) Robot.follow(who.id)
            }
            is Plan.Greet -> Unit
            else -> Robot.stopFollow()
        }
    }

    // a visit is over and otto is idle again: welcome card, and the next patient starts with a fresh llm context
    private fun maybeReset(now: Long, plan: Plan) {
        if (plan != Plan.Wait && plan != Plan.Charge) return
        if (now - quietSince < IDLE_RESET_MS) return
        if (!contextDirty && !Clinic.hasContent()) return
        contextDirty = false
        Clinic.idleReset()
    }

    private fun taskFor(plan: Plan): Task? = when (plan) {
        is Plan.Refuse -> Task(preemptible = false) { refuse(plan.command, plan.why) }
        is Plan.Turn -> Task(preemptible = true) { showTurn(plan.turn) }
        // preemptible: the poll goes on meanwhile and leaving demo mode stops it (Brain.stopsFor)
        Plan.Demo -> Task(preemptible = true) { Demo.run() }
        is Plan.RunCommand -> Task(preemptible = false) { runCommand(plan.command, plan.excuse) }
        is Plan.Greet -> Task(preemptible = false) { greet(plan.visitorId) }
        Plan.GoCharge -> Task(preemptible = true) { goCharge() }
        Plan.LeaveCharger -> Task(preemptible = false) { leaveCharger() }
        Plan.ReturnHome -> Task(preemptible = true) { returnHome() }
        else -> null
    }

    // ---- tasks ----

    // a command the mode does not take: closed as refused, a walk's leg as failed, so the dashboard says why
    private suspend fun refuse(cmd: RobotCommand, why: String) {
        commands.removeAll { it.id == cmd.id }
        markHandled(cmd.id)
        ownByCommand.remove(cmd.id)
        cmd.journeyId?.let { ownByJourney.remove(it) }
        refuseNow(cmd, why)
    }

    private suspend fun refuseNow(cmd: RobotCommand, why: String) {
        RobotLog.i(LogSource.BRAIN, "Command ${cmd.type}" + (cmd.ticket?.let { " for $it" } ?: "") + " refused: $why")
        if (cmd.type == RobotCommand.GUIDE) Clinic.refuseGuide(cmd, why)
        Clinic.commandDone(cmd.id, CommandResult(false, why))
    }

    // show mode: a small, gentle turn on the spot
    private suspend fun showTurn(t: ShowTurn) {
        val ok = Robot.turn(t.degrees.toFloat(), t.left, ShowMoves.TURN_SPEED)
        showMoves.turned(t, ok, now())
        RobotLog.d(
            LogSource.BRAIN,
            String.format(java.util.Locale.ROOT, "Show turn %.0f deg %s%s, %.0f deg from the start", t.degrees, if (t.left) "left" else "right", if (ok) "" else " (not taken)", showMoves.offsetDeg),
        )
    }

    private suspend fun runCommand(cmd: RobotCommand, excuse: Boolean) {
        commands.removeAll { it.id == cmd.id }
        markHandled(cmd.id)
        waitingFor = null
        waitingSince = null
        runningCommand = cmd.id
        activity(Brain.commandState(cmd, Robot.home), Brain.commandGoal(cmd, Robot.home))
        try {
            runCommandNow(cmd, excuse)
        } finally {
            runningCommand = null
            ownByCommand.remove(cmd.id)
            cmd.journeyId?.let { ownByJourney.remove(it) }
        }
    }

    private suspend fun runCommandNow(cmd: RobotCommand, excuse: Boolean) {
        when (cmd.type) {
            RobotCommand.GUIDE -> {
                parkedAt = null
                Guide.run(cmd, ownFor(cmd))
                // back to the reception afterwards, unless someone needs urgent help (or he is there)
                returnHomeNow = !helpActive()
            }
            RobotCommand.GO_TO -> {
                if (Clinic.goToSpot(cmd, excuse)) {
                    // he stays where the team sent him; home is not "parked"
                    parkedAt = cmd.spot?.takeUnless { Robot.isHome(it) }
                    returnHomeNow = false
                }
            }
            RobotCommand.SAY -> Clinic.sayFromTeam(cmd)
            // with a journey it comes from the patient's phone page
            RobotCommand.ASK -> if (cmd.journeyId != null) Clinic.askDuringWalk(cmd) else Clinic.askFromTeam(cmd)
            RobotCommand.WALK_CONTROL -> Clinic.walkControl(cmd)
            // docs/robot-setup.md 2: refused while guiding (the poll answers that at once), else switch and localise
            RobotCommand.SWITCH_MAP -> {
                val r = try {
                    MapSync.switchMap(cmd)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    CommandResult(false, "Map switch failed: ${e.javaClass.simpleName} ${e.message.orEmpty().take(80)}")
                }
                parkedAt = null
                Clinic.commandDone(cmd.id, r)
                publish()
            }
            else -> Clinic.skipCommand(cmd)
        }
    }

    // commands that need no body and no turn: at once, also during a walk and in every mode
    private fun runAtOnce(cmd: RobotCommand) {
        when (cmd.type) {
            RobotCommand.SET_MODE -> {
                val mode = RobotMode.of(cmd.mode)
                val r = if (mode == null) {
                    RobotLog.w(LogSource.BRAIN, "set_mode with an unknown mode \"${cmd.mode ?: ""}\"")
                    CommandResult(false, "Unknown mode \"${cmd.mode ?: ""}\": normal, show, calm or demo")
                } else {
                    Modes.request(mode, "dashboard")
                }
                scope.launch { Clinic.commandDone(cmd.id, r) }
            }
            // docs/demo-mode.md: start, next, hold, resume, restart, stop, say
            RobotCommand.DEMO -> scope.launch {
                val r = try {
                    Demo.command(cmd)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    CommandResult(false, "Demo ${cmd.op ?: "?"} failed: ${e.javaClass.simpleName} ${e.message.orEmpty().take(80)}")
                }
                Clinic.commandDone(cmd.id, r)
            }
            RobotCommand.CONFIG_CHANGED -> {
                Config.requestNow("config_changed")
                scope.launch { Clinic.commandDone(cmd.id) }
            }
            // "Recheck servers" on the dashboard's debug page: the servers, the settings, the map image
            RobotCommand.RECHECK -> scope.launch {
                RobotLog.i(LogSource.NET, "Recheck asked by the dashboard")
                Servers.checkAll()
                Config.requestNow("recheck")
                MapSync.resend()
                val up = Servers.states.value.count { it.up == true }
                Clinic.commandDone(cmd.id, CommandResult(up > 0, "$up of ${Servers.states.value.size} servers answer, using ${ServerList.host(Servers.current)}"))
            }
        }
    }

    private suspend fun greet(id: Int) {
        val now = now()
        lastGreetAt = now
        engaged[id] = now
        // someone new: the last conversation is over for good
        if (contextDirty) Clinic.idleReset()
        contextDirty = true
        Robot.follow(id)
        Clinic.greet()
    }

    private suspend fun goCharge() {
        parkedAt = null
        Clinic.sayGoingToCharge()
        Clinic.setWalking(true)
        val outcome = try {
            Robot.goCharge { Clinic.onNavEvent(it) }
        } finally {
            Clinic.setWalking(false)
        }
        when (outcome) {
            ChargeOutcome.Docked -> returnHomeNow = false
            is ChargeOutcome.Failed -> {
                chargeFailedAt = now()
                failed("Could not reach the charger: ${outcome.why}")
            }
        }
    }

    private suspend fun leaveCharger() {
        if (Robot.leavePile()) {
            returnHomeNow = true
        } else {
            leaveFailedAt = now()
            failed("Could not get off the charger")
        }
    }

    private suspend fun returnHome() {
        val home = Robot.home
        Clinic.setWalking(true)
        val outcome = try {
            Robot.goTo(home, onEvent = { Clinic.onNavEvent(it) })
        } finally {
            Clinic.setWalking(false)
        }
        when (outcome) {
            NavOutcome.Arrived -> returnHomeNow = false
            NavOutcome.Interrupted -> Unit
            is NavOutcome.Failed -> {
                returnFailedAt = now()
                failed("Could not get back to $home: ${outcome.why.screen}")
            }
        }
    }

    // ---- backend: commands, help, heartbeat ----

    // every 3 s; in demo mode every 0.5 s, so the operator's Next takes effect at once
    private suspend fun pollCommands() {
        while (true) {
            delay(if (Modes.current == RobotMode.DEMO) DEMO_POLL_MS else POLL_MS)
            try {
                pollOnce()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                quietLog("poll: ${Backend.friendly(e)}")
            }
        }
    }

    // commands stay on the server while otto can't take them (urgent help, out of service, a task
    // other than a walk). during a walk otto keeps polling: walk_control and the phone's ask must
    // reach him at once (docs/journeys.md 6.4); other commands wait in his queue until the walk ends.
    private suspend fun pollOnce() {
        val now = now()
        checkHelp(now)
        if (!Backend.configured || helpActive()) return
        // right after start the robot api may not be there yet, a walk would fail at once
        if (!Robot.ready && now - startedAt < BOOT_GRACE_MS) return
        // can't drive: leave the walks on the server, the dashboard shows why (heartbeat)
        if (Robot.chassisIssue() != null) return
        val running = current
        val guiding = Guide.current != null
        if (running != null && !running.preemptible && !guiding) return
        while (commands.size < MAX_QUEUE) {
            val cmd = Clinic.pollCommand() ?: break
            RobotLog.i(
                LogSource.BRAIN,
                "Command ${cmd.type}" + (cmd.ticket?.let { " for $it" } ?: "") + (cmd.action?.let { " ($it)" } ?: "") +
                    (cmd.mode?.let { " ($it)" } ?: "") + (cmd.mapName?.let { " ($it)" } ?: ""),
            )
            if (cmd.type == RobotCommand.WALK_CONTROL) {
                if (markHandled(cmd.id)) Clinic.walkControl(cmd) else Clinic.commandDone(cmd.id)
                continue
            }
            // mode, settings, recheck, the demo's controls: no body needed, at once
            if (cmd.type == RobotCommand.SET_MODE || cmd.type == RobotCommand.CONFIG_CHANGED || cmd.type == RobotCommand.RECHECK ||
                cmd.type == RobotCommand.DEMO
            ) {
                if (markHandled(cmd.id)) runAtOnce(cmd) else Clinic.commandDone(cmd.id)
                continue
            }
            // docs/robot-setup.md 2: no map switch while otto walks with a patient (answered via done + log)
            if (cmd.type == RobotCommand.SWITCH_MAP && guiding) {
                if (markHandled(cmd.id)) {
                    val why = "Otto is walking with a patient. Switch the map after the walk."
                    RobotLog.w(LogSource.NAV, "Map switch to ${cmd.mapName ?: "?"} refused: $why")
                    Clinic.commandDone(cmd.id, CommandResult(false, why))
                } else {
                    Clinic.commandDone(cmd.id)
                }
                continue
            }
            // show / calm / demo: walks and words are refused at once, so the dashboard can say why. the demo's own
            // walk (its command, journey or ticket, or any walk while its check-in call runs: the server queues it
            // before it answers, field test b16) is never refused: the demo takes it, its walk closes it
            val refused = ModeRules.refuses(Modes.current, cmd.type)
            if (refused != null) {
                when {
                    cmd.type == RobotCommand.GUIDE && Demo.takesGuide(cmd) -> markHandled(cmd.id)
                    markHandled(cmd.id) -> refuseNow(cmd, refused)
                    !Demo.owns(cmd.id) -> Clinic.commandDone(cmd.id)
                }
                continue
            }
            val demo = Modes.current == RobotMode.DEMO
            // demo mode: go_to ends the run under way cleanly, otto drives there and holds (Demo.goTo answers done)
            if (demo && cmd.type == RobotCommand.GO_TO) {
                if (markHandled(cmd.id)) {
                    if (!Demo.goTo(cmd)) Clinic.commandDone(cmd.id, CommandResult(false, "go_to without a spot"))
                } else {
                    Clinic.commandDone(cmd.id)
                }
                continue
            }
            if ((guiding || demo) && (cmd.type == RobotCommand.ASK || cmd.type == RobotCommand.SAY)) {
                // words need no wheels: right away, without waiting for the walk
                if (markHandled(cmd.id)) {
                    scope.launch { if (cmd.type == RobotCommand.ASK) Clinic.askDuringWalk(cmd) else Clinic.sayFromTeam(cmd) }
                } else {
                    Clinic.commandDone(cmd.id)
                }
                continue
            }
            if (commands.any { it.id == cmd.id }) break
            if (!markHandled(cmd.id)) {
                // taken before (from the POST answer): close it once that run is over, so it stops coming
                if (runningCommand != cmd.id) Clinic.commandDone(cmd.id)
                break
            }
            commands.add(cmd)
        }
    }

    private suspend fun checkHelp(now: Long) {
        val id = helpRequest ?: return
        if (helpAt == null || now - helpCheckedAt < HELP_CHECK_MS) return
        helpCheckedAt = now
        val open = withContext(Dispatchers.IO) { runCatching { Backend.openHelpIds() }.getOrNull() } ?: return
        helpOpen = id in open
    }

    // every 15 s (5 s while driving), and right away (at most every 1.5 s) when the screen changes,
    // for the dashboard mirror. with the server in use, the app version and the map position
    private suspend fun heartbeat() {
        var sentAt = 0L
        var sentScreen: ScreenMirror? = null
        var sentSetup: SetupState? = null
        var sentDemo: DemoState? = null
        while (true) {
            val now = now()
            val screen = Clinic.ui.value.mirror()
            // a new settings version, map, localisation or mode goes out at once too (the dashboard's "applied")
            val setup = SetupState(MapSync.current, MapSync.localised, Config.version, Modes.current, Modes.pending, MapSync.maps)
            // docs/demo-mode.md: the demo's step for the operator page, sooner than the screen mirror
            val demo = Demo.state.value
            val moving = Clinic.ui.value.walking || Guide.current?.walking == true
            val every = if (moving) HEARTBEAT_MOVING_MS else HEARTBEAT_MS
            val changed = screen != sentScreen || setup != sentSetup
            val demoChanged = demo != sentDemo
            val due = now - sentAt >= every || (changed && now - sentAt >= SCREEN_PUSH_MIN_MS) || (demoChanged && now - sentAt >= DEMO_PUSH_MIN_MS)
            if (due) {
                Robot.refreshPower()
                val pose = Robot.robotPose()
                publish(pose)
                sentAt = now
                sentScreen = screen
                sentSetup = setup
                sentDemo = demo
                if (Backend.configured) {
                    val body = Json.heartbeat(Backend.robot, _status.value, screen, Servers.current, BuildConfig.VERSION_NAME, pose, setup, demo)
                    withContext(Dispatchers.IO) { runCatching { Backend.heartbeat(body) } }
                        .onFailure { e -> quietLog("heartbeat: ${Backend.friendly(e)}") }
                }
            }
            delay(500)
        }
    }

    // otto's lines and the patient's intents for the dashboard's live feed (lives only in memory there)
    private suspend fun sendFeed() {
        val feed = Clinic.feed
        while (true) {
            delay(FEED_MS)
            if (!Backend.configured || feed.size() == 0) continue
            val batch = feed.drain()
            val body = Json.feed(Backend.robot, Guide.current?.journeyId?.ifBlank { null }, batch)
            withContext(Dispatchers.IO) { runCatching { Backend.feed(body) } }
                .onSuccess { show -> show?.let { feed.showPatientText = it } }
                .onFailure { e ->
                    feed.putBack(batch)
                    quietLog("feed: ${Backend.friendly(e)}")
                    delay(10_000)
                }
        }
    }

    // places for the screen, the actions and the home spot (the reception place)
    private suspend fun refreshPlaces() {
        while (true) {
            if (Backend.configured) Clinic.refreshPlaces()
            delay(PLACES_MS)
        }
    }

    // ---- status for the screen and the board ----

    @Volatile private var lastPose: RobotPose? = null

    private fun publish(pose: RobotPose? = null) {
        val now = now()
        if (pose != null) lastPose = pose
        val plan = lastPlan
        val task = doing
        val issue = Robot.chassisIssue()
        var state = task?.first ?: Brain.state(plan, Robot.home)
        if (issue != null || (task == null && plan == Plan.Wait && !Robot.ready && now - startedAt > BOOT_GRACE_MS)) {
            state = RobotState.ERROR
        }
        _status.value = BrainStatus(
            goal = if (issue != null) "Out of service" else task?.second ?: Brain.goal(plan, lastSnap),
            state = state,
            battery = Robot.battery,
            charging = Robot.chargingNow(),
            position = Robot.position ?: whereNow(),
            detail = detail(now, issue),
        )
    }

    // while driving: "near <spot>" from the map position, so the board is never empty
    private fun whereNow(): String? {
        val p = Robot.latestPose() ?: lastPose ?: return null
        val near = WalkMath.nearestSpot(p.point, LiveMap.knownPlaces())
        return near?.let { "near $it" } ?: String.format(java.util.Locale.ROOT, "x %.1f, y %.1f", p.x, p.y)
    }

    private fun detail(now: Long, issue: ChassisIssue?): String? = when {
        helpActive() -> "Urgent help was called at reception"
        issue != null -> issue.detail
        !Robot.ready && now - startedAt > BOOT_GRACE_MS -> Robot.note ?: "Robot API not connected"
        else -> failure?.takeIf { now - it.first < FAILURE_SHOW_MS }?.second ?: Brain.lowBatteryNote(lastSnap)
    }

    // the backend may be down for a while; one line a minute is enough (never the token)
    private fun quietLog(msg: String) {
        val now = now()
        if (now - loggedAt < LOG_EVERY_MS) return
        loggedAt = now
        RobotLog.w(LogSource.NET, msg)
    }
}
