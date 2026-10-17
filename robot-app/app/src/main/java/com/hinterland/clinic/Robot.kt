package com.hinterland.clinic

import android.content.Context
import com.ainirobot.coreservice.client.ApiListener
import com.ainirobot.coreservice.client.Definition
import com.ainirobot.coreservice.client.RobotApi
import com.ainirobot.coreservice.client.StatusListener
import com.ainirobot.coreservice.client.ashmem.ShareMemoryApi
import com.ainirobot.coreservice.client.listener.ActionListener
import com.ainirobot.coreservice.client.listener.CommandListener
import com.ainirobot.coreservice.client.module.ModuleCallbackApi
import com.ainirobot.coreservice.client.person.PersonApi
import java.io.FileInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

// screen = on-screen text, spoken = english hint for the llm sentence
enum class NavFailure(val screen: String, val spoken: String) {
    NOT_CONNECTED("Navigation is not connected", "your navigation system is not ready"),
    NO_SPOT("I don't know where this room is", "you do not know where this room is"),
    NOT_LOCALIZED("I lost my position on the map", "you lost your position on the map"),
    UNKNOWN_SPOT("This room is not on my map", "this room is not on your map"),
    BLOCKED("The way is blocked", "the way is blocked"),
    OUT_OF_MAP("The room is outside my map", "the room is outside your map"),
    NO_PATH("I can't find a path", "you cannot find a path there"),
    BUSY("I'm already driving", "you are already driving somewhere"),
    STOPPED("Navigation was stopped", "the navigation was stopped"),
    TIMEOUT("It took too long", "the way took too long"),
    DOCKED("I can't get off the charger", "you cannot get off your charger"),
    OTHER("Navigation failed", "something went wrong with your navigation"),
}

sealed interface NavOutcome {
    data object Arrived : NavOutcome
    // the caller stopped it on purpose (check-back, pause, new pace); navigation is stopped
    data object Interrupted : NavOutcome
    data class Failed(val why: NavFailure, val code: Int? = null) : NavOutcome
}

sealed interface ChargeOutcome {
    data object Docked : ChargeOutcome
    data class Failed(val why: String) : ChargeOutcome
}

// what a RobotApi command answered (CommandListener): the result code and the message as it came
data class CmdAnswer(val code: Int, val message: String?, val error: Boolean = false) {
    // the docs: "succeed" for switchMap / setPoseEstimate, RESULT_OK as the code
    val ok: Boolean get() = !error && (message?.trim()?.equals(Definition.SUCCEED, ignoreCase = true) == true || code == Definition.RESULT_OK)

    fun show(): String = "${if (error) "error " else ""}$code \"${message.orEmpty().replace(Regex("\\s+"), " ").take(120)}\""
}

enum class NavEvent { BLOCKED, CLEAR }

// chassis, vision, radar and battery side (RobotAPI). voice is off in RobotApi on agentos, everything
// spoken goes through AgentCore. every call here was checked in RobotAPI_en.md and with javap
// on the bundled core jar (com.orionstar.robot:core 1.0.20250821-SNAPSHOT).
object Robot {

    // RobotAPI doc recommends 0.2 m arrival range and a 30 s obstacle timeout; a bit more range so it stops before the door
    private const val ARRIVE_RANGE_M = 0.3
    private const val AVOID_TIMEOUT_MS = 30_000L
    private const val NAV_TIMEOUT_MS = 4 * 60_000L
    private const val WATCH_MS = 250L
    // a pose from the STATUS_POSE listener counts as "now" this long
    private const val POSE_FRESH_MS = 2_000L
    // RobotAPI "Rotational Motion": speed 0-50 degrees/s. calm, about 4.5 s for a half turn
    private const val TURN_DEG_PER_S = 40f
    // after stopNavigation the chassis needs a moment before a turn (FAQ: resource conflict)
    const val NAV_RELEASE_MS = 700L
    // backing off a blocked way (v3.3): slow, a person could be near
    private const val BACK_SPEED = 0.15f
    // "Stop Charging and Undock": speed and distance are the doc defaults
    private const val LEAVE_SPEED = 0.7f
    private const val LEAVE_DISTANCE_M = 0.2f
    private const val LEAVE_TIMEOUT_MS = 25_000L
    private const val CHARGE_TIMEOUT_MS = 3 * 60_000L
    // focus following: report lost after 10 s, too far after 3 m (doc: typically 5-10 s and 5 m)
    private const val FOLLOW_LOST_MS = 10_000L
    private const val FOLLOW_MAX_M = 3f
    // give the chassis a moment after focus following stopped, before a drive starts
    private const val FOLLOW_RELEASE_MS = 600L
    // doc: charging status updates take some time after undocking, ignore "charging" for a while
    private const val UNDOCK_SETTLE_MS = 30_000L
    private const val HOME_RANGE_M = 1.0
    // push events in the log, at most (calm mode field test)
    private const val PUSH_LOG_MAX = 40
    // map commands: loading another map may take a while
    private const val SWITCH_MAP_TIMEOUT_MS = 30_000L
    private const val MAP_CALL_TIMEOUT_MS = 6_000L
    // moved by hand: more than this while otto did not drive himself
    private const val MOVED_M = 0.3

    private val api get() = RobotApi.getInstance()
    private val people get() = PersonApi.getInstance()
    private val reqIds = AtomicInteger(1)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val bootAt = System.currentTimeMillis()

    @Volatile var connected = false
        private set

    // handleApiDisabled: no chassis rights for this app (not started from the robot's home screen)
    @Volatile var disabled = false
        private set

    // the system took the chassis (emergency stop, low battery, ota ... FAQ "app suspended")
    @Volatile var suspended = false
        private set

    // STATUS_EMERGENCY as far as it can be read (Json.emergency), null = unknown
    @Volatile var emergency: Boolean? = null
        private set

    val ready: Boolean get() = connected && !suspended

    // why otto can't drive right now, null = he can (or is still starting up)
    fun chassisIssue(): ChassisIssue? =
        Chassis.issue(connected, disabled, suspended, emergency, System.currentTimeMillis() - bootAt)

    // the spot of the drive that runs right now (live map target), null when not driving
    @Volatile var driving: String? = null
        private set

    // RobotApi getVersion(), read once after connecting
    @Volatile var systemVersion: String? = null
        private set

    // latest pose from the STATUS_POSE listener
    @Volatile private var lastPose: RobotPose? = null
    @Volatile private var lastPoseAt = 0L

    @Volatile var note: String? = null
        private set

    var onHealth: (Boolean, String?) -> Unit = { _, _ -> }

    // the reception place's spot from /api/places, else robot.homeSpot from local.properties
    @Volatile var homeFromPlaces: String? = null

    val home: String get() = homeFromPlaces?.trim()?.takeIf { it.isNotEmpty() } ?: BuildConfig.HOME_SPOT.trim()

    fun isHome(spot: String?): Boolean = Brain.isHome(spot, home)

    // the spot otto stands at (arrived there and has not moved since), null while moving or unknown
    @Volatile var position: String? = null
        private set

    // null = not known (start, after a failed drive)
    @Volatile var atHome: Boolean? = null
        private set

    // otto drove onto the charger himself
    @Volatile var docked = false
        private set
    @Volatile private var undockedAt = 0L

    @Volatile var battery: Int? = null
        private set
    @Volatile private var chargingRaw: Boolean? = null
    @Volatile private var chargingAt = 0L
    // once the status listener delivered a value, the getter fallback stops for it
    @Volatile private var levelFromListener = false
    @Volatile private var chargingFromListener = false

    // focus following target (person id) or null
    @Volatile var following: Int? = null
        private set
    @Volatile private var followReq = -1

    private fun now() = System.currentTimeMillis()

    // the battery says it charges (readings from just after undocking do not count)
    fun chargingReported(): Boolean = chargingRaw == true && chargingAt > undockedAt + UNDOCK_SETTLE_MS

    fun chargingNow(): Boolean = docked || chargingReported()

    private fun health(ok: Boolean, why: String?) {
        note = why
        onHealth(ok, why)
    }

    private val apiListener = object : ApiListener {
        override fun handleApiConnected() {
            connected = true
            disabled = false
            suspended = false
            runCatching { api.setCallback(moduleCallback) }
            scope.launch { setUp() }
            RobotLog.i(LogSource.NAV, "Robot API connected")
            health(true, null)
        }

        override fun handleApiDisabled() {
            connected = false
            disabled = true
            RobotLog.e(LogSource.NAV, "Robot API disabled: start Nova Clinic from the robot's home screen")
            health(false, "Robot API disabled")
        }

        override fun handleApiDisconnected() {
            connected = false
            following = null
            RobotLog.w(LogSource.NAV, "Robot API disconnected")
            health(false, "Robot API disconnected")
        }
    }

    private val moduleCallback = object : ModuleCallbackApi() {
        // old speech requests - not used on agentos
        override fun onSendRequest(reqId: Int, reqType: String?, reqText: String?, reqParam: String?): Boolean = true

        override fun onSuspend() {
            suspended = true
            following = null
            RobotLog.w(LogSource.NAV, "Suspended by the robot system")
            health(false, "Paused by the robot system")
        }

        override fun onRecovery() {
            suspended = false
            RobotLog.i(LogSource.NAV, "Control is back from the robot system")
            health(true, null)
        }
    }

    // RobotAPI "Listen for Position State Changes": Definition.STATUS_POSE, a Pose as json, continuously
    private val poseListener = object : StatusListener() {
        override fun onStatusUpdate(type: String?, data: String?) {
            Json.robotPose(data)?.let {
                lastPose = it
                lastPoseAt = System.currentTimeMillis()
            }
        }
    }

    // RobotAPI "System Status Monitoring": Definition.STATUS_EMERGENCY. the value format is not in the
    // docs: every change of the raw value goes to the log so the robot tells us
    @Volatile private var emergencyRaw: String? = null
    private val emergencyListener = object : StatusListener() {
        override fun onStatusUpdate(type: String?, data: String?) {
            val raw = data?.trim()?.take(80)
            if (raw != emergencyRaw) {
                emergencyRaw = raw
                RobotLog.i(LogSource.NAV, "Emergency status: \"$raw\"")
            }
            emergency = Json.emergency(data)
        }
    }

    // radar data (RobotAPI "Radar Data Listener"), only while Radar asks for it
    @Volatile private var radarListener: StatusListener? = null

    // battery and charging, pushed by the robot (RobotAPI "Register Status Listener", Definition.STATUS_BATTERY)
    private val batteryListener = object : StatusListener() {
        override fun onStatusUpdate(type: String?, data: String?) {
            Json.power(data)?.let { setPower(it, listener = true) }
        }
    }

    // someone pushes the robot. the docs list STATUS_ROBOT_BEING_PUSHED for another model only (Caizhao); the
    // jar also has STATUS_ROBOT_HAS_BEING_PUSHED and STATUS_PUSH_EXCEED_DISTANCE. we only log what comes, so the
    // field test shows what this robot reports (calm mode, the user's spec 17.10)
    @Volatile private var pushLogs = 0
    private val pushListener = object : StatusListener() {
        override fun onStatusUpdate(type: String?, data: String?) {
            if (pushLogs >= PUSH_LOG_MAX) return
            pushLogs++
            RobotLog.i(LogSource.NAV, "Robot reports ${type ?: "push"}: \"${data?.trim()?.take(80)}\" (mode ${Modes.current.wire})")
        }
    }

    // localised or not (OrionStar RobotSample NavFragment: {"isPoseEstimate": true|false}) and map switches,
    // also when the team switches the map on the robot itself
    @Volatile var localised: Boolean? = null
        private set
    @Volatile private var estimateRaw: String? = null
    private val estimateListener = object : StatusListener() {
        override fun onStatusUpdate(type: String?, data: String?) {
            val raw = data?.trim()?.take(80)
            if (raw != estimateRaw) {
                estimateRaw = raw
                RobotLog.i(LogSource.NAV, "Localisation status: \"$raw\"")
            }
            Json.localised(data)?.let { localised = it }
        }
    }
    @Volatile var mapEvents = 0
        private set
    private val switchMapListener = object : StatusListener() {
        override fun onStatusUpdate(type: String?, data: String?) {
            mapEvents++
            RobotLog.i(LogSource.NAV, "Robot reports a map switch: \"${data?.trim()?.take(80)}\"")
        }
    }

    // the system's own "please don't push me" warning: off in calm mode (RobotApi.disablePushWarning, in the
    // jar but not in the docs), back on when calm mode ends
    @Volatile private var pushWarningOff = false
    @Volatile private var pushWarningWanted = true

    // has to happen while our activity is in the foreground, otherwise no chassis rights
    fun connect(context: Context) {
        if (connected) return
        runCatching { api.connectServer(context.applicationContext, apiListener) }
            .onFailure {
                RobotLog.e(LogSource.NAV, "connectServer failed: ${it.javaClass.simpleName} ${it.message.orEmpty().take(80)}")
                health(false, "Robot API not available")
            }
    }

    private fun setUp() {
        // keep the app in charge while docked: otto still talks there, only the chassis is locked.
        // also needed before leaveChargingPile (RobotAPI "Disable Battery Interface")
        runCatching { api.disableBattery() }
        runCatching { api.unregisterStatusListener(batteryListener) }
        runCatching { api.registerStatusListener(Definition.STATUS_BATTERY, batteryListener) }
        runCatching { api.unregisterStatusListener(poseListener) }
        runCatching { api.registerStatusListener(Definition.STATUS_POSE, poseListener) }
            .onFailure { RobotLog.w(LogSource.NAV, "Pose listener not registered: ${it.message}") }
        runCatching { api.unregisterStatusListener(emergencyListener) }
        runCatching { api.registerStatusListener(Definition.STATUS_EMERGENCY, emergencyListener) }
        // docs/robot-setup.md: localisation and map switches; calm mode: what the robot says about pushes
        runCatching { api.unregisterStatusListener(estimateListener) }
        runCatching { api.registerStatusListener(Definition.STATUS_POSE_ESTIMATE, estimateListener) }
        runCatching { api.unregisterStatusListener(switchMapListener) }
        runCatching { api.registerStatusListener(Definition.STATUS_SWITCH_MAP, switchMapListener) }
        runCatching { api.unregisterStatusListener(pushListener) }
        for (status in listOf(Definition.STATUS_ROBOT_BEING_PUSHED, Definition.STATUS_ROBOT_HAS_BEING_PUSHED, Definition.STATUS_PUSH_EXCEED_DISTANCE)) {
            runCatching { api.registerStatusListener(status, pushListener) }
                .onFailure { RobotLog.w(LogSource.NAV, "No listener for $status: ${it.message}") }
        }
        // after a reconnect the system forgot it
        if (!pushWarningWanted) {
            pushWarningOff = false
            pushWarning(false)
        }
        // sync binder call (RobotAPI "Get System Version"); we are on a worker thread here
        runCatching { api.version }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let {
            systemVersion = it
            RobotLog.i(LogSource.APP, "Robot system version $it")
        }
        // current value once (the listener only reports changes), with its own listener object
        runCatching {
            api.getRobotStatus(Definition.STATUS_BATTERY, object : StatusListener() {
                override fun onStatusUpdate(type: String?, data: String?) {
                    Json.power(data)?.let { setPower(it, listener = true) }
                }
            })
        }
    }

    private fun setPower(p: PowerReading, listener: Boolean) {
        p.level?.let {
            battery = it
            if (listener) levelFromListener = true
        }
        p.charging?.let {
            chargingRaw = it
            chargingAt = now()
            if (listener) chargingFromListener = true
        }
    }

    // fallback for what the listener has not said yet: the plain getters (not in RobotAPI_en.md,
    // checked with javap; sync binder calls, never on the main thread).
    // 0 is what getBatteryLevel returns when it has no value, so it is ignored.
    fun refreshPower() {
        if (!connected) return
        val level = if (levelFromListener) null else runCatching { api.batteryLevel }.getOrNull()?.takeIf { it in 1..100 }
        val charging = if (chargingFromListener) null else runCatching { api.chargeStatus }.getOrNull()
        setPower(PowerReading(level, charging), listener = false)
    }

    // ---- vision (PersonApi, works once RobotApi is connected) ----

    // people with a face in view; null when the vision api gives nothing
    fun faces(): List<Visitor>? {
        if (!ready) return null
        val list = runCatching { people.allFaceList }.getOrNull() ?: return null
        return list.mapNotNull { p -> runCatching { Visitor(p.id, p.distance, p.isWithFace) }.getOrNull() }
    }

    // distances (m) of everyone the vision sees right now, face or body (RobotAPI "Get All Persons
    // Information", Person.getDistance). live only, nothing is stored. null when the vision api gives nothing.
    fun peopleDistances(): List<Double>? {
        if (!ready) return null
        val list = runCatching { people.allPersons }.getOrNull() ?: return null
        return list.mapNotNull { p -> runCatching { p.distance }.getOrNull()?.takeIf { it > 0.0 } }
    }

    // the same with where each one is in view (Person.getAngleInView, degrees; javap-checked on core
    // 1.0.20250821): only distance and angle, never a face, an id or a picture. null = no vision data
    fun people(): List<SeenPerson>? {
        if (!ready) return null
        val list = runCatching { people.allPersons }.getOrNull() ?: return null
        return list.mapNotNull { p ->
            runCatching { SeenPerson(p.distance, p.angleInView.takeIf { it.isFinite() } ?: 0.0) }.getOrNull()?.takeIf { it.distance > 0.0 }
        }
    }

    // ---- focus following: turns otto towards the person he talks to. it holds the chassis,
    // so every drive stops it first (RobotAPI "Focus Following", note 1).
    // the jar marks start/stopFocusFollow deprecated (for startSmartFocusFollow, which is not in
    // the docs), so we stay with the documented call. robot.focusFollow=false turns it off. ----

    @Suppress("DEPRECATION")
    fun follow(personId: Int) {
        if (!BuildConfig.FOCUS_FOLLOW) return
        if (!ready || personId < 0 || following == personId || chargingNow()) return
        stopFollow()
        val id = reqIds.getAndIncrement()
        followReq = id
        val listener = object : ActionListener() {
            override fun onStatusUpdate(status: Int, data: String?) {
                if (status == Definition.STATUS_GUEST_LOST) endFollow(id, stop = true)
            }

            override fun onError(errorCode: Int, errorString: String?) = endFollow(id, stop = false)

            override fun onResult(status: Int, response: String?) = endFollow(id, stop = false)
        }
        val started = runCatching { api.startFocusFollow(id, personId, FOLLOW_LOST_MS, FOLLOW_MAX_M, listener) }
            .getOrDefault(-1)
        following = if (started >= 0) personId else null
    }

    @Suppress("DEPRECATION")
    private fun endFollow(id: Int, stop: Boolean) {
        if (followReq != id) return
        if (stop) runCatching { api.stopFocusFollow(id) }
        following = null
    }

    // true when there was something to stop
    @Suppress("DEPRECATION")
    fun stopFollow(): Boolean {
        if (following == null) return false
        runCatching { api.stopFocusFollow(followReq) }
        following = null
        return true
    }

    // ---- where otto is ----

    // RobotAPI "Check if Robot is at Position Point"; null when it can't tell
    suspend fun isAt(spot: String, rangeM: Double): Boolean? {
        if (!ready || spot.isBlank()) return null
        return withTimeoutOrNull(3_000) {
            suspendCancellableCoroutine<Boolean?> { cont ->
                val finished = AtomicBoolean(false)
                fun finish(v: Boolean?) {
                    if (finished.compareAndSet(false, true)) cont.resume(v)
                }
                val params = JSONObject()
                    .put(Definition.JSON_NAVI_TARGET_PLACE_NAME, spot)
                    .put(Definition.JSON_NAVI_COORDINATE_DEVIATION, rangeM)
                    .toString()
                val started = runCatching {
                    api.isRobotInlocations(reqIds.getAndIncrement(), params, object : CommandListener() {
                        override fun onResult(result: Int, message: String?) {
                            finish(runCatching { JSONObject(message.orEmpty()).getBoolean(Definition.JSON_NAVI_IS_IN_LOCATION) }.getOrNull())
                        }

                        override fun onError(errorCode: Int, errorString: String?) = finish(null)
                    })
                }.getOrDefault(-1)
                if (started < 0) finish(null)
            }
        }
    }

    // is otto at his home spot? keeps a non-home spot he was parked at
    suspend fun checkHome() {
        if (home.isEmpty()) return
        val at = isAt(home, HOME_RANGE_M) ?: return
        atHome = at
        if (at) {
            position = home
        } else if (isHome(position)) {
            position = null
        }
    }

    // ---- driving ----

    // drives to a map spot at the default speed or the given one (a guide's pace). gets off the charger
    // first, stops focus following, gives up when blocked for BLOCKED_LIMIT_MS in total or when
    // stopWhen() says so. interrupt() stops it on purpose (Interrupted). onTick runs every WATCH_MS.
    // way = the blocked count to use (a walk counts the whole leg, v3.3); null = a fresh one for this drive
    suspend fun goTo(
        spot: String,
        speed: Speed? = null,
        stopWhen: () -> Boolean = { false },
        interrupt: () -> Boolean = { false },
        onTick: (blocked: Boolean) -> Unit = {},
        onEvent: (NavEvent) -> Unit = {},
        way: WayBlock? = null,
    ): NavOutcome {
        if (!ready) return NavOutcome.Failed(NavFailure.NOT_CONNECTED)
        if (chargingNow() && !leavePile()) return NavOutcome.Failed(NavFailure.DOCKED)
        if (stopFollow()) delay(FOLLOW_RELEASE_MS)
        position = null
        atHome = false
        driving = spot
        var outcome: NavOutcome = NavOutcome.Failed(NavFailure.OTHER)
        try {
            for (attempt in 0..1) {
                outcome = watched(spot, speed, stopWhen, interrupt, onTick, onEvent, way)
                // the chassis may still be held (focus following, a turn that just ended) - try once more
                val busy = (outcome as? NavOutcome.Failed)?.why == NavFailure.BUSY
                if (!busy || attempt == 1) break
                delay(1_000)
            }
        } finally {
            driving = null
        }
        when (outcome) {
            NavOutcome.Arrived -> RobotLog.i(LogSource.NAV, "Arrived at $spot")
            NavOutcome.Interrupted -> RobotLog.d(LogSource.NAV, "Stopped on the way to $spot")
            is NavOutcome.Failed -> RobotLog.w(
                LogSource.NAV,
                "Could not reach $spot: ${outcome.why.screen}${outcome.code?.let { " (code $it)" }.orEmpty()}",
            )
        }
        when (outcome) {
            NavOutcome.Arrived -> {
                position = spot
                atHome = isHome(spot)
            }
            NavOutcome.Interrupted -> {
                position = null
                atHome = false
            }
            is NavOutcome.Failed -> {
                position = null
                atHome = null
            }
        }
        return outcome
    }

    private suspend fun watched(
        spot: String,
        speed: Speed?,
        stopWhen: () -> Boolean,
        interrupt: () -> Boolean,
        onTick: (Boolean) -> Unit,
        onEvent: (NavEvent) -> Unit,
        way: WayBlock?,
    ): NavOutcome = coroutineScope {
        // asks people to step aside (twice), then gives up on this way (BlockedWatch, or the leg's LegBlock)
        val blocked = way ?: BlockedWatch()
        var blockedFrom: Point? = null
        val lock = Any()
        // a late obstacle status after this drive ended must not count for the next one (a leg's count lives on)
        var live = true
        val nav = async {
            navigate(spot, speed) { ev ->
                val counted = synchronized(lock) {
                    if (live) {
                        if (ev == NavEvent.BLOCKED) blocked.blocked(now()) else blocked.clear(now())
                    }
                    live
                }
                if (counted && ev == NavEvent.CLEAR) onEvent(ev)
            }
        }
        RobotLog.i(LogSource.NAV, "Driving to $spot" + (speed?.let { " at ${it.linear} m/s" } ?: ""))
        var result: NavOutcome? = null
        try {
            while (result == null) {
                result = withTimeoutOrNull(WATCH_MS) { nav.await() }
                if (result != null) break
                val t = now()
                // "blocked" but the robot drove on: the end-of-obstacle status got lost, so it is clear
                val pose = latestPose(t)
                val unstuck = synchronized(lock) {
                    if (!blocked.isBlocked) {
                        blockedFrom = null
                        false
                    } else {
                        val from = blockedFrom ?: pose?.point.also { blockedFrom = it }
                        if (from != null && pose != null && blocked.moved(t, from.distanceTo(pose.point))) {
                            blockedFrom = null
                            true
                        } else {
                            false
                        }
                    }
                }
                if (unstuck) {
                    RobotLog.i(LogSource.NAV, "Obstacle status cleared: Otto moved on")
                    onEvent(NavEvent.CLEAR)
                }
                val step = synchronized(lock) { blocked.step(t) }
                onTick(synchronized(lock) { blocked.isBlocked })
                when {
                    stopWhen() -> {
                        nav.cancel()
                        result = NavOutcome.Failed(NavFailure.STOPPED)
                    }
                    interrupt() -> {
                        nav.cancel()
                        result = NavOutcome.Interrupted
                    }
                    step == BlockedStep.GIVE_UP -> {
                        nav.cancel()
                        val leg = (blocked as? LegBlock)?.let { ", blocked ${it.usedMs(t) / 1000} s in this try, ${it.totalMs(t) / 1000} s on this leg" }.orEmpty()
                        RobotLog.w(LogSource.NAV, "Way to $spot stays blocked after asking ${blocked.asksDone} times$leg")
                        result = NavOutcome.Failed(NavFailure.BLOCKED)
                    }
                    step == BlockedStep.ASK -> {
                        RobotLog.i(LogSource.NAV, "Blocked on the way to $spot, asking people to step aside")
                        onEvent(NavEvent.BLOCKED)
                    }
                }
            }
        } finally {
            // the drive is over: an open blocked time is closed (a leg's count keeps the rest)
            synchronized(lock) {
                live = false
                blocked.stop(now())
            }
        }
        result ?: NavOutcome.Failed(NavFailure.OTHER)
    }

    // RobotAPI "Navigate to Specified Location": startNavigation(reqId, destName, coordinateDeviation, time,
    // [linearSpeed, angularSpeed,] listener). cancelling the coroutine calls stopNavigation(reqId).
    private suspend fun navigate(spot: String, speed: Speed?, onEvent: (NavEvent) -> Unit): NavOutcome {
        if (!ready) return NavOutcome.Failed(NavFailure.NOT_CONNECTED)
        val id = reqIds.getAndIncrement()
        val outcome = withTimeoutOrNull(NAV_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val finished = AtomicBoolean(false)
                fun finish(o: NavOutcome) {
                    if (finished.compareAndSet(false, true)) cont.resume(o)
                }

                // the dispatcher calls the 3-arg and the 2-arg overloads, so only the 2-arg ones are used
                val listener = object : ActionListener() {
                    override fun onResult(status: Int, response: String?) {
                        when {
                            status == Definition.RESULT_OK && response == "true" -> finish(NavOutcome.Arrived)
                            status == Definition.ACTION_RESPONSE_STOP_SUCCESS ->
                                finish(NavOutcome.Failed(NavFailure.STOPPED, status))
                            else -> finish(NavOutcome.Failed(NavFailure.OTHER, status))
                        }
                    }

                    override fun onError(errorCode: Int, errorString: String?) {
                        if (errorCode == Definition.ERROR_IN_DESTINATION) {
                            finish(NavOutcome.Arrived)
                        } else {
                            finish(NavOutcome.Failed(failureFor(errorCode), errorCode))
                        }
                    }

                    override fun onStatusUpdate(status: Int, data: String?) {
                        when (status) {
                            Definition.STATUS_NAVI_AVOID -> onEvent(NavEvent.BLOCKED)
                            Definition.STATUS_NAVI_AVOID_END -> onEvent(NavEvent.CLEAR)
                            Definition.STATUS_NAVI_OUT_MAP -> {
                                runCatching { api.stopNavigation(id) }
                                finish(NavOutcome.Failed(NavFailure.OUT_OF_MAP, status))
                            }
                            Definition.STATUS_NAVI_GLOBAL_PATH_FAILED -> {
                                runCatching { api.stopNavigation(id) }
                                finish(NavOutcome.Failed(NavFailure.NO_PATH, status))
                            }
                        }
                    }
                }

                val started = runCatching {
                    if (speed != null) {
                        api.startNavigation(id, spot, ARRIVE_RANGE_M, AVOID_TIMEOUT_MS, speed.linear, speed.angular, listener)
                    } else {
                        api.startNavigation(id, spot, ARRIVE_RANGE_M, AVOID_TIMEOUT_MS, listener)
                    }
                }.getOrElse {
                    RobotLog.e(LogSource.NAV, "startNavigation threw: ${it.javaClass.simpleName} ${it.message.orEmpty().take(80)}")
                    -1
                }
                if (started < 0) {
                    finish(NavOutcome.Failed(if (connected) NavFailure.OTHER else NavFailure.NOT_CONNECTED, started))
                }
                cont.invokeOnCancellation { runCatching { api.stopNavigation(id) } }
            }
        }
        if (outcome == null) runCatching { api.stopNavigation(id) }
        return outcome ?: NavOutcome.Failed(NavFailure.TIMEOUT)
    }

    // ---- turning on the spot (RobotAPI "Rotational Motion": turnLeft/turnRight(reqId, speed deg/s,
    // angle deg, listener), stopped with stopMove). never while navigating: the guide stops that first. ----

    // true when the robot took the command. waits until the turn had its time, the result may come early.
    // speed in degrees per second (0-50, the show turns are gentle)
    suspend fun turn(degrees: Float, left: Boolean, speed: Float = TURN_DEG_PER_S): Boolean {
        if (!ready) return false
        if (stopFollow()) delay(FOLLOW_RELEASE_MS)
        val angle = degrees.coerceIn(1f, 360f)
        val degPerS = speed.coerceIn(5f, 50f)
        val expectedMs = (angle / degPerS * 1000f).toLong()
        val startedAt = now()
        selfMoving++
        try {
            val ok = withTimeoutOrNull(expectedMs + 6_000) {
                suspendCancellableCoroutine<Boolean> { cont ->
                    val finished = AtomicBoolean(false)
                    fun finish(v: Boolean) {
                        if (finished.compareAndSet(false, true)) cont.resume(v)
                    }
                    val listener = object : CommandListener() {
                        override fun onResult(result: Int, message: String?) =
                            finish(result == Definition.RESULT_OK || message?.contains("succeed") == true)

                        override fun onError(errorCode: Int, errorString: String?) {
                            RobotLog.w(LogSource.NAV, "Turn error $errorCode")
                            finish(false)
                        }
                    }
                    val id = reqIds.getAndIncrement()
                    val started = runCatching {
                        if (left) api.turnLeft(id, degPerS, angle, listener) else api.turnRight(id, degPerS, angle, listener)
                    }.getOrDefault(-1)
                    if (started < 0) finish(false)
                    cont.invokeOnCancellation { stopMove() }
                }
            }
            if (ok == null) stopMove()
            val rest = expectedMs - (now() - startedAt)
            if (ok == true && rest > 0) delay(rest + 200)
            return ok == true
        } finally {
            selfMoving--
            movedRef = null
        }
    }

    fun stopMove() {
        runCatching { api.stopMove(reqIds.getAndIncrement(), object : CommandListener() {}) }
    }

    // RobotAPI "Linear Motion": goBackward(reqId, speed m/s, distance m, listener), javap-checked. backwards
    // there is no obstacle check (the docs: only forward avoids), so the guide only backs off when nobody
    // is behind otto. true when the robot took the command; waits until the move had its time
    suspend fun back(distanceM: Float, speed: Float = BACK_SPEED): Boolean {
        if (!ready) return false
        if (stopFollow()) delay(FOLLOW_RELEASE_MS)
        val d = distanceM.coerceIn(0.05f, 1.0f)
        val expectedMs = (d / speed * 1000f).toLong()
        val startedAt = now()
        movedRef = null
        val ok = withTimeoutOrNull(expectedMs + 4_000) {
            suspendCancellableCoroutine<Boolean> { cont ->
                val finished = AtomicBoolean(false)
                fun finish(v: Boolean) {
                    if (finished.compareAndSet(false, true)) cont.resume(v)
                }
                val started = runCatching {
                    api.goBackward(reqIds.getAndIncrement(), speed, d, object : CommandListener() {
                        override fun onResult(result: Int, message: String?) =
                            finish(result == Definition.RESULT_OK || message?.contains("succeed") == true)

                        override fun onError(errorCode: Int, errorString: String?) {
                            RobotLog.w(LogSource.NAV, "Back off error $errorCode")
                            finish(false)
                        }
                    })
                }.getOrDefault(-1)
                if (started < 0) finish(false)
                cont.invokeOnCancellation { stopMove() }
            }
        }
        if (ok == null) stopMove()
        val rest = expectedMs - (now() - startedAt)
        if (ok == true && rest > 0) delay(rest + 200)
        return ok == true
    }

    // the STATUS_POSE value when it is fresh, else null
    fun latestPose(now: Long = now()): RobotPose? = lastPose?.takeIf { now - lastPoseAt <= POSE_FRESH_MS }

    // heading in radians from the fresh pose, for the turn check
    fun heading(): Double? = latestPose()?.theta

    // where otto is: the fresh STATUS_POSE value, else RobotAPI "Get Robot Current Position"
    // (needs localization). null when it can't tell
    suspend fun robotPose(): RobotPose? {
        if (!ready) return null
        latestPose()?.let { return it }
        return withTimeoutOrNull(2_500) {
            suspendCancellableCoroutine<RobotPose?> { cont ->
                val finished = AtomicBoolean(false)
                fun finish(p: RobotPose?) {
                    if (finished.compareAndSet(false, true)) cont.resume(p)
                }
                val started = runCatching {
                    api.getPosition(reqIds.getAndIncrement(), object : CommandListener() {
                        override fun onResult(result: Int, message: String?) {
                            val p = Json.robotPose(message)
                            if (p != null) {
                                lastPose = p
                                lastPoseAt = System.currentTimeMillis()
                            }
                            finish(p)
                        }

                        override fun onError(errorCode: Int, errorString: String?) = finish(null)
                    })
                }.getOrDefault(-1)
                if (started < 0) finish(null)
            }
        }
    }

    suspend fun pose(): Point? = robotPose()?.point

    // RobotAPI "Get Current Map Name"; null when it can't tell
    suspend fun mapName(): String? {
        if (!ready) return null
        return withTimeoutOrNull(3_000) {
            suspendCancellableCoroutine<String?> { cont ->
                val finished = AtomicBoolean(false)
                fun finish(v: String?) {
                    if (finished.compareAndSet(false, true)) cont.resume(v)
                }
                val started = runCatching {
                    api.getMapName(reqIds.getAndIncrement(), object : CommandListener() {
                        override fun onResult(result: Int, message: String?) = finish(message?.trim()?.takeIf { it.isNotEmpty() })

                        override fun onError(errorCode: Int, errorString: String?) = finish(null)
                    })
                }.getOrDefault(-1)
                if (started < 0) finish(null)
            }
        }
    }

    // ---- maps and floors (docs/robot-setup.md 1 and 2). every call checked with javap on core
    // 1.0.20250821-SNAPSHOT; the answers that RobotAPI_en.md does not show are logged on first use ----

    // a RobotApi command with a CommandListener (only the 2-arg callbacks: the dispatcher calls both
    // overloads, ActionMessage, javap). null when it did not start or took longer than timeoutMs
    private suspend fun command(timeoutMs: Long, start: (Int, CommandListener) -> Int): CmdAnswer? {
        if (!ready) return null
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<CmdAnswer?> { cont ->
                val finished = AtomicBoolean(false)
                fun finish(v: CmdAnswer?) {
                    if (finished.compareAndSet(false, true)) cont.resume(v)
                }
                val listener = object : CommandListener() {
                    override fun onResult(result: Int, message: String?) = finish(CmdAnswer(result, message))

                    override fun onError(errorCode: Int, errorString: String?) = finish(CmdAnswer(errorCode, errorString, error = true))
                }
                val started = runCatching { start(reqIds.getAndIncrement(), listener) }.getOrElse {
                    RobotLog.w(LogSource.NAV, "Robot call threw: ${it.javaClass.simpleName} ${it.message.orEmpty().take(80)}")
                    -1
                }
                if (started < 0) finish(null)
            }
        }
    }

    // RobotAPI "Switch Map": switchMap(reqId, mapName, listener), "succeed" when it worked. re-localise after it
    suspend fun switchMap(mapName: String): CmdAnswer? {
        movedRef = null
        position = null
        atHome = null
        localised = null
        return command(SWITCH_MAP_TIMEOUT_MS) { id, l -> api.switchMap(id, mapName, l) }
    }

    // RobotAPI "Localization": setPoseEstimate(reqId, {"px","py","theta"} (Definition.JSON_NAVI_POSITION_*), listener)
    suspend fun setPoseEstimate(x: Double, y: Double, theta: Double): CmdAnswer? {
        val params = JSONObject()
            .put(Definition.JSON_NAVI_POSITION_X, x)
            .put(Definition.JSON_NAVI_POSITION_Y, y)
            .put(Definition.JSON_NAVI_POSITION_THETA, theta)
            .toString()
        movedRef = null
        return command(MAP_CALL_TIMEOUT_MS) { id, l -> api.setPoseEstimate(id, params, l) }
    }

    // RobotAPI "Check if Currently Localized": isRobotEstimate(reqId, listener), message "true" when it is
    @Volatile private var estimateLogged = false

    suspend fun isLocalised(): Boolean? {
        val a = command(3_000) { id, l -> api.isRobotEstimate(id, l) } ?: return null
        if (!estimateLogged) {
            estimateLogged = true
            RobotLog.i(LogSource.NAV, "First isRobotEstimate answer: ${a.show()}")
        }
        if (a.error) return null
        val v = when (a.message?.trim()?.lowercase(java.util.Locale.ROOT)) {
            "true" -> true
            "false" -> false
            else -> Json.localised(a.message)
        }
        if (v != null) localised = v
        return v
    }

    // the team switched the map and said where otto stands: he is at that spot now
    fun relocated(spot: String) {
        position = spot
        atHome = isHome(spot)
        movedRef = null
    }

    // RobotApi.getMultiFloorConfigAndPose: the raw answer (a MultiFloorInfo list, Floors.fromMultiFloor)
    suspend fun multiFloorRaw(): CmdAnswer? = command(MAP_CALL_TIMEOUT_MS) { id, l -> api.getMultiFloorConfigAndPose(id, l) }

    // RobotApi.getLocalMapInfoList: the raw answer (not documented, Floors.fromMapList)
    suspend fun localMapsRaw(): CmdAnswer? = command(MAP_CALL_TIMEOUT_MS) { id, l -> api.getLocalMapInfoList(id, l) }

    // RobotApi.getPlaceListByMapName: the spots of a map otto is not on yet (null = no usable answer)
    suspend fun placesOf(mapName: String): Pair<CmdAnswer?, List<MapPlace>?> {
        val a = command(MAP_CALL_TIMEOUT_MS) { id, l -> api.getPlaceListByMapName(id, mapName, l) }
        val list = a?.takeIf { !it.error }?.message?.let { m -> Json.mapPlaces(m).takeIf { it.isNotEmpty() } }
        return a to list
    }

    // RobotAPI "Read and Display Map PGM File": ShareMemoryApi.getMapPgmPFD(mapName), read as OrionStar's
    // RoverMap data (MapImage.kt), releaseGetMapPgmPFD() after. the stream is not closed by us: the
    // ParcelFileDescriptor owns the descriptor and the release closes it. blocking - never on the main thread
    fun readMap(mapName: String): Result<PgmMap> {
        if (!ready) return Result.failure(IllegalStateException("robot api not connected"))
        val share = ShareMemoryApi.getInstance()
        return try {
            val pfd = share.getMapPgmPFD(mapName) ?: return Result.failure(IllegalStateException("no map file from the robot for \"$mapName\""))
            Result.success(Pgm.read(FileInputStream(pfd.fileDescriptor)))
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            runCatching { share.releaseGetMapPgmPFD() }
        }
    }

    // ---- calm mode: pushes ----

    fun pushWarning(enabled: Boolean) {
        pushWarningWanted = enabled
        if (!connected) return
        if (!enabled && !pushWarningOff) {
            runCatching { api.disablePushWarning() }
                .onSuccess {
                    pushWarningOff = true
                    RobotLog.i(LogSource.NAV, "Robot push warning switched off (calm mode)")
                }
                .onFailure { RobotLog.w(LogSource.NAV, "Push warning not switched off: ${it.message}") }
        } else if (enabled && pushWarningOff) {
            runCatching { api.enablePushWarning() }
            pushWarningOff = false
            RobotLog.i(LogSource.NAV, "Robot push warning back on")
        }
    }

    // otto moves himself (a turn, a drive): no "moved by hand" then
    @Volatile private var selfMoving = 0
    @Volatile private var movedRef: Point? = null

    // called every second by the brain: meters someone moved otto while he did not drive, once per push
    // (the log learns what happens when someone pushes him). null = nothing to report
    fun movedByHand(): Double? {
        if (driving != null || selfMoving > 0) {
            movedRef = null
            return null
        }
        val p = latestPose()?.point ?: return null
        val ref = movedRef
        if (ref == null) {
            movedRef = p
            return null
        }
        val d = ref.distanceTo(p)
        if (d < MOVED_M) return null
        movedRef = p
        position = null
        atHome = null
        return d
    }

    // ---- radar (RobotAPI "Enable/Disable Radar Data Reporting" + "Radar Data Listener"):
    // setNavigationLineData(reqId, enabled) and Definition.STATUS_LINE_DATA. off again after use. ----

    // true when the robot took it. onData runs on a binder thread with the raw json
    fun startRadar(onData: (String?) -> Unit): Boolean {
        if (!ready) return false
        stopRadar()
        val listener = object : StatusListener() {
            override fun onStatusUpdate(type: String?, data: String?) = onData(data)
        }
        val enabled = runCatching { api.setNavigationLineData(reqIds.getAndIncrement(), true) }.getOrDefault(-1)
        if (enabled < 0) {
            RobotLog.w(LogSource.LIDAR, "Radar data could not be switched on ($enabled)")
            return false
        }
        val reg = runCatching { api.registerStatusListener(Definition.STATUS_LINE_DATA, listener) }
        if (reg.isFailure) {
            RobotLog.w(LogSource.LIDAR, "Radar listener not registered: ${reg.exceptionOrNull()?.message}")
            runCatching { api.setNavigationLineData(reqIds.getAndIncrement(), false) }
            return false
        }
        radarListener = listener
        return true
    }

    fun stopRadar() {
        val l = radarListener ?: return
        radarListener = null
        runCatching { api.unregisterStatusListener(l) }
        runCatching { api.setNavigationLineData(reqIds.getAndIncrement(), false) }
    }

    // ---- charging ----

    // RobotAPI "Start Auto Charging": drives to the charging pile of the map and docks
    suspend fun goCharge(onEvent: (NavEvent) -> Unit = {}): ChargeOutcome {
        if (!ready) return ChargeOutcome.Failed(NavFailure.NOT_CONNECTED.screen)
        if (stopFollow()) delay(FOLLOW_RELEASE_MS)
        position = null
        atHome = false
        selfMoving++
        try {
            return charge(onEvent)
        } finally {
            selfMoving--
            movedRef = null
        }
    }

    private suspend fun charge(onEvent: (NavEvent) -> Unit): ChargeOutcome {
        val id = reqIds.getAndIncrement()
        val outcome: ChargeOutcome = withTimeoutOrNull(CHARGE_TIMEOUT_MS + 30_000) {
            suspendCancellableCoroutine<ChargeOutcome> { cont ->
                val finished = AtomicBoolean(false)
                fun finish(o: ChargeOutcome) {
                    if (finished.compareAndSet(false, true)) cont.resume(o)
                }
                val listener = object : ActionListener() {
                    override fun onResult(status: Int, response: String?) {
                        finish(if (status == Definition.RESULT_OK) ChargeOutcome.Docked else ChargeOutcome.Failed("Charging did not start"))
                    }

                    override fun onError(errorCode: Int, errorString: String?) =
                        finish(ChargeOutcome.Failed(failureFor(errorCode).screen))

                    override fun onStatusUpdate(status: Int, data: String?) {
                        when (status) {
                            Definition.STATUS_NAVI_AVOID -> onEvent(NavEvent.BLOCKED)
                            Definition.STATUS_NAVI_AVOID_END -> onEvent(NavEvent.CLEAR)
                            Definition.STATUS_NAVI_OUT_MAP, Definition.STATUS_NAVI_GLOBAL_PATH_FAILED -> {
                                runCatching { api.stopAutoChargeAction(id, true) }
                                finish(ChargeOutcome.Failed(NavFailure.NO_PATH.screen))
                            }
                        }
                    }
                }
                val started = runCatching { api.startNaviToAutoChargeAction(id, CHARGE_TIMEOUT_MS, listener) }
                    .getOrDefault(-1)
                if (started < 0) finish(ChargeOutcome.Failed(NavFailure.OTHER.screen))
                cont.invokeOnCancellation { runCatching { api.stopAutoChargeAction(id, true) } }
            }
        } ?: run {
            runCatching { api.stopAutoChargeAction(id, true) }
            ChargeOutcome.Failed(NavFailure.TIMEOUT.screen)
        }
        if (outcome == ChargeOutcome.Docked) docked = true
        return outcome
    }

    // RobotAPI "Stop Charging and Undock from Charging Pile" (needs disableBattery, done in setUp)
    suspend fun leavePile(): Boolean {
        if (!ready) return false
        runCatching { api.disableBattery() }
        selfMoving++
        val ok = try {
            leave()
        } finally {
            selfMoving--
            movedRef = null
        }
        if (ok) {
            docked = false
            undockedAt = now()
            position = null
            atHome = false
        }
        return ok
    }

    private suspend fun leave(): Boolean {
        return withTimeoutOrNull(LEAVE_TIMEOUT_MS) {
            suspendCancellableCoroutine<Boolean> { cont ->
                val finished = AtomicBoolean(false)
                fun finish(v: Boolean) {
                    if (finished.compareAndSet(false, true)) cont.resume(v)
                }
                val started = runCatching {
                    api.leaveChargingPile(reqIds.getAndIncrement(), LEAVE_SPEED, LEAVE_DISTANCE_M, object : CommandListener() {
                        override fun onResult(result: Int, message: String?) = finish(result == Definition.RESULT_OK)

                        override fun onError(errorCode: Int, errorString: String?) {
                            RobotLog.w(LogSource.NAV, "leaveChargingPile error $errorCode")
                            finish(false)
                        }
                    })
                }.getOrDefault(-1)
                if (started < 0) finish(false)
            }
        } ?: false
    }

    // all spots on the current map with coordinates (RobotAPI "Get All Position Points on Current Map")
    suspend fun mapPlaces(): List<MapPlace> {
        if (!ready) return emptyList()
        return withTimeoutOrNull(5_000) {
            suspendCancellableCoroutine<List<MapPlace>> { cont ->
                val finished = AtomicBoolean(false)
                fun finish(list: List<MapPlace>) {
                    if (finished.compareAndSet(false, true)) cont.resume(list)
                }
                val started = runCatching {
                    api.getPlaceList(reqIds.getAndIncrement(), object : CommandListener() {
                        override fun onResult(result: Int, message: String?) = finish(Json.mapPlaces(message))

                        override fun onError(errorCode: Int, errorString: String?) = finish(emptyList())
                    })
                }.getOrDefault(-1)
                if (started < 0) finish(emptyList())
            }
        } ?: emptyList()
    }

    private fun failureFor(code: Int): NavFailure = when (code) {
        Definition.ERROR_NOT_ESTIMATE, Definition.ERROR_ESTIMATE_ERROR -> NavFailure.NOT_LOCALIZED
        Definition.ERROR_DESTINATION_NOT_EXIST -> NavFailure.UNKNOWN_SPOT
        Definition.ERROR_DESTINATION_CAN_NOT_ARRAIVE,
        Definition.ERROR_NAVIGATION_AVOID_TIMEOUT,
        Definition.ERROR_MULTI_ROBOT_WAITING_TIMEOUT -> NavFailure.BLOCKED
        Definition.ACTION_RESPONSE_ALREADY_RUN,
        Definition.ACTION_RESPONSE_REQUEST_RES_ERROR,
        Definition.ACTION_RESPONSE_RES_UNAVAILBALE -> NavFailure.BUSY
        else -> NavFailure.OTHER
    }
}
