package com.hinterland.clinic

import android.util.Log
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

// nova's brain. one loop looks around every second and picks the most important thing to do
// (the priorities are in Brain.decide). one task at a time: every task holds the body lock,
// and an action that walks a patient takes the same lock. nothing new starts while someone
// is talking to nova.
object NovaBrain {

    private const val TAG = "NovaBrain"
    private const val TICK_MS = 1_000L
    private const val POLL_MS = 3_000L
    private const val HEARTBEAT_MS = 15_000L
    private const val HOME_CHECK_MS = 20_000L
    private const val IDLE_RESET_MS = 45_000L
    private const val HELP_MAX_MS = 10 * 60_000L
    private const val HELP_CHECK_MS = 10_000L
    private const val BODY_WAIT_MS = 6_000L
    private const val BOOT_GRACE_MS = 20_000L
    private const val MAX_QUEUE = 5
    // faces this close count as "the person nova talks to"
    private const val TALK_RANGE_M = 2.5
    private const val ENGAGED_KEEP_MS = 10 * 60_000L
    private const val FAILURE_SHOW_MS = 2 * 60_000L
    private const val LOG_EVERY_MS = 60_000L
    // an action that never finished does not keep nova "in a conversation" forever
    private const val ACTION_MAX_MS = 6 * 60_000L

    private class Running(val job: Job, val preemptible: Boolean)

    // preemptible = gives way to commands, a patient walk and urgent help (driving home, to the charger)
    private class Task(val preemptible: Boolean, val block: suspend () -> Unit)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val body = Mutex()
    private val bodyWanted = AtomicInteger(0)
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
    @Volatile private var waitingFor: String? = null
    @Volatile private var waitingSince: Long? = null

    // people in front of nova
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

    private fun now() = System.currentTimeMillis()

    // runs while the screen is in front (MainActivity). tasks live in our own scope,
    // so a walk that is under way still finishes if the activity pauses.
    suspend fun run() {
        withContext(Dispatchers.Default) {
            launch { loop() }
            launch { pollCommands() }
            launch { heartbeat() }
        }
    }

    // ---- signals (listener threads) ----

    fun heardUser() {
        userSpokeAt = now()
        contextDirty = true
    }

    fun heardRobot() {
        robotSpokeAt = now()
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

    // urgent CALL_STAFF: nova stays with the person, a drive home or to the charger stops at once
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

    // a patient asked to be walked (GUIDE_TO_DOCTOR): take the body - a drive home or to the
    // charger gives way - and run block as the task. null when nova is busy with something
    // that can't be dropped.
    suspend fun runBody(state: RobotState, goal: String, block: suspend () -> Unit): Job? {
        bodyWanted.incrementAndGet()
        try {
            current?.takeIf { it.preemptible }?.job?.cancel()
            val deadline = now() + BODY_WAIT_MS
            while (!body.tryLock()) {
                if (now() > deadline) return null
                delay(200)
            }
        } finally {
            bodyWanted.decrementAndGet()
        }
        return start(Task(preemptible = false) {
            activity(state, goal)
            block()
        })
    }

    // the caller holds the body lock; it is released when the task ends, however it ends
    private fun start(task: Task): Job {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                task.block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "task failed", e)
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

    // what the running task does, null when nova has no task (DeployHold)
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
                Log.w(TAG, "tick failed", e)
            }
            delay(TICK_MS)
        }
    }

    private suspend fun tick() {
        val now = now()
        val inFlight = if (now - actionAt < ACTION_MAX_MS) actions.get() else 0
        val talk = Brain.talkLevel(now, agent, agentAt, userSpokeAt, robotSpokeAt, inFlight)
        if (talk == TalkLevel.ACTIVE) lastTalkAt = now
        val busy = current != null || body.isLocked
        if (talk != TalkLevel.NONE || busy) quietSince = now
        if (helpAt != null && !helpActive()) {
            helpAt = null
            helpRequest = null
            helpOpen = null
        }

        if (!busy) {
            look(now, talk)
            checkHome(now)
        }
        val snap = snapshot(now, talk)
        val plan = Brain.decide(snap)
        trackWaiting(plan, now)
        lastSnap = snap

        val running = current
        if (running != null || body.isLocked) {
            if (running != null && running.preemptible && Brain.shouldPreempt(snap, bodyWanted.get() > 0)) {
                running.job.cancel()
            }
            publish()
            return
        }
        // an action is about to take the body, or just took it
        if (bodyWanted.get() > 0 || !body.tryLock()) return
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
        // whoever stands there while nova talks is not greeted again later
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

    private fun snapshot(now: Long, talk: TalkLevel) = Snapshot(
        now = now,
        robotReady = Robot.ready,
        homeSpot = Robot.home,
        helpActive = helpActive(),
        talk = talk,
        commands = commands.toList(),
        waitingSince = waitingSince,
        visitor = visitor,
        lastGreetAt = lastGreetAt,
        lastTalkAt = lastTalkAt,
        engaged = HashMap(engaged),
        battery = Robot.battery,
        charging = Robot.chargingReported(),
        dockedByNova = Robot.docked,
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

    // focus following on while someone talks to nova, off when she is idle
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

    // a visit is over and nova is idle again: welcome card, and the next patient starts with a fresh llm context
    private fun maybeReset(now: Long, plan: Plan) {
        if (plan != Plan.Wait && plan != Plan.Charge) return
        if (now - quietSince < IDLE_RESET_MS) return
        if (!contextDirty && !Clinic.hasContent()) return
        contextDirty = false
        Clinic.idleReset()
    }

    private fun taskFor(plan: Plan): Task? = when (plan) {
        is Plan.RunCommand -> Task(preemptible = false) { runCommand(plan.command, plan.excuse) }
        is Plan.Greet -> Task(preemptible = false) { greet(plan.visitorId) }
        Plan.GoCharge -> Task(preemptible = true) { goCharge() }
        Plan.LeaveCharger -> Task(preemptible = false) { leaveCharger() }
        Plan.ReturnHome -> Task(preemptible = true) { returnHome() }
        else -> null
    }

    // ---- tasks ----

    private suspend fun runCommand(cmd: RobotCommand, excuse: Boolean) {
        commands.removeAll { it.id == cmd.id }
        waitingFor = null
        waitingSince = null
        activity(Brain.commandState(cmd, Robot.home), Brain.commandGoal(cmd, Robot.home))
        when (cmd.type) {
            RobotCommand.CALL_PATIENT -> {
                parkedAt = null
                Clinic.fetch(cmd, excuse)
            }
            RobotCommand.GO_TO -> {
                if (Clinic.goToSpot(cmd, excuse)) {
                    // she stays where the team sent her; home is not "parked"
                    parkedAt = cmd.spot?.takeUnless { Robot.isHome(it) }
                    returnHomeNow = false
                }
            }
            RobotCommand.SAY -> Clinic.sayFromTeam(cmd)
            else -> Clinic.skipCommand(cmd)
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
            Robot.goTo(home, escort = false, onEvent = { Clinic.onNavEvent(it) })
        } finally {
            Clinic.setWalking(false)
        }
        when (outcome) {
            NavOutcome.Arrived -> returnHomeNow = false
            is NavOutcome.Failed -> {
                returnFailedAt = now()
                failed("Could not get back to $home: ${outcome.why.screen}")
            }
        }
    }

    // ---- backend: commands, help, heartbeat ----

    private suspend fun pollCommands() {
        while (true) {
            delay(POLL_MS)
            try {
                pollOnce()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                quietLog("poll: ${Backend.friendly(e)}")
            }
        }
    }

    // commands stay on the server while nova can't take them (walking, urgent help)
    private suspend fun pollOnce() {
        val now = now()
        checkHelp(now)
        if (!Backend.configured || helpActive()) return
        // right after start the robot api may not be there yet, a walk would fail at once
        if (!Robot.ready && now - startedAt < BOOT_GRACE_MS) return
        val running = current
        if (running != null && !running.preemptible) return
        while (commands.size < MAX_QUEUE) {
            val cmd = Clinic.pollCommand() ?: break
            if (commands.none { it.id == cmd.id }) commands.add(cmd)
        }
    }

    private suspend fun checkHelp(now: Long) {
        val id = helpRequest ?: return
        if (helpAt == null || now - helpCheckedAt < HELP_CHECK_MS) return
        helpCheckedAt = now
        val open = withContext(Dispatchers.IO) { runCatching { Backend.openHelpIds() }.getOrNull() } ?: return
        helpOpen = id in open
    }

    private suspend fun heartbeat() {
        while (true) {
            Robot.refreshPower()
            publish()
            if (Backend.configured) {
                val body = Json.heartbeat(Backend.robot, _status.value)
                withContext(Dispatchers.IO) { runCatching { Backend.heartbeat(body) } }
                    .onFailure { e -> quietLog("heartbeat: ${Backend.friendly(e)}") }
            }
            delay(HEARTBEAT_MS)
        }
    }

    // ---- status for the screen and the board ----

    private fun publish() {
        val now = now()
        val plan = lastPlan
        val task = doing
        var state = task?.first ?: Brain.state(plan, Robot.home)
        if (task == null && plan == Plan.Wait && !Robot.ready && now - startedAt > BOOT_GRACE_MS) {
            state = RobotState.ERROR
        }
        _status.value = BrainStatus(
            goal = task?.second ?: Brain.goal(plan, lastSnap),
            state = state,
            battery = Robot.battery,
            charging = Robot.chargingNow(),
            position = Robot.position,
            detail = detail(now),
        )
    }

    private fun detail(now: Long): String? = when {
        helpActive() -> "Urgent help was called at reception"
        !Robot.ready && now - startedAt > BOOT_GRACE_MS -> Robot.note ?: "Robot API not connected"
        else -> failure?.takeIf { now - it.first < FAILURE_SHOW_MS }?.second
    }

    // the backend may be down for a while; one line a minute is enough (never the token)
    private fun quietLog(msg: String) {
        val now = now()
        if (now - loggedAt < LOG_EVERY_MS) return
        loggedAt = now
        Log.w(TAG, msg)
    }
}
