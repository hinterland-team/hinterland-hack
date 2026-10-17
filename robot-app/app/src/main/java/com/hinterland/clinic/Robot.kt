package com.hinterland.clinic

import android.content.Context
import android.util.Log
import com.ainirobot.coreservice.client.ApiListener
import com.ainirobot.coreservice.client.Definition
import com.ainirobot.coreservice.client.RobotApi
import com.ainirobot.coreservice.client.StatusListener
import com.ainirobot.coreservice.client.listener.ActionListener
import com.ainirobot.coreservice.client.listener.CommandListener
import com.ainirobot.coreservice.client.module.ModuleCallbackApi
import com.ainirobot.coreservice.client.person.PersonApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
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
    data class Failed(val why: NavFailure, val code: Int? = null) : NavOutcome
}

sealed interface ChargeOutcome {
    data object Docked : ChargeOutcome
    data class Failed(val why: String) : ChargeOutcome
}

enum class NavEvent { BLOCKED, CLEAR }

// chassis, vision and battery side (RobotAPI). voice is off in RobotApi on agentos, everything
// spoken goes through AgentCore. every call here was checked in RobotAPI_en.md and with javap
// on the bundled core jar (com.orionstar.robot:core 1.0.20250821-SNAPSHOT).
object Robot {

    private const val TAG = "NovaRobot"

    // RobotAPI doc recommends 0.2 m arrival range and a 30 s obstacle timeout; a bit more range so it stops before the door
    private const val ARRIVE_RANGE_M = 0.3
    private const val AVOID_TIMEOUT_MS = 30_000L
    // slower when a patient walks behind; angular follows the doc formula 0.4 + (v - 0.1) / 3 * 4
    private const val ESCORT_LINEAR = 0.45
    private const val ESCORT_ANGULAR = 0.87
    private const val NAV_TIMEOUT_MS = 4 * 60_000L
    // blocked longer than this in total during one drive -> give up
    private const val BLOCKED_LIMIT_MS = 25_000L
    private const val WATCH_MS = 500L
    // while still blocked, ask again this often
    private const val NAG_EVERY_MS = 10_000L
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

    private val api get() = RobotApi.getInstance()
    private val people get() = PersonApi.getInstance()
    private val reqIds = AtomicInteger(1)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile var connected = false
        private set

    // the system took the chassis (emergency stop, ota ...)
    @Volatile var suspended = false
        private set

    val ready: Boolean get() = connected && !suspended

    @Volatile var note: String? = null
        private set

    var onHealth: (Boolean, String?) -> Unit = { _, _ -> }

    val home: String get() = BuildConfig.HOME_SPOT.trim()

    fun isHome(spot: String?): Boolean = Brain.isHome(spot, home)

    // the spot nova stands at (arrived there and has not moved since), null while moving or unknown
    @Volatile var position: String? = null
        private set

    // null = not known (start, after a failed drive)
    @Volatile var atHome: Boolean? = null
        private set

    // nova drove onto the charger herself
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
            suspended = false
            runCatching { api.setCallback(moduleCallback) }
            scope.launch { setUp() }
            health(true, null)
        }

        override fun handleApiDisabled() {
            connected = false
            health(false, "Robot API disabled")
        }

        override fun handleApiDisconnected() {
            connected = false
            following = null
            health(false, "Robot API disconnected")
        }
    }

    private val moduleCallback = object : ModuleCallbackApi() {
        // old speech requests - not used on agentos
        override fun onSendRequest(reqId: Int, reqType: String?, reqText: String?, reqParam: String?): Boolean = true

        override fun onSuspend() {
            suspended = true
            following = null
            health(false, "Paused by the robot system")
        }

        override fun onRecovery() {
            suspended = false
            health(true, null)
        }
    }

    // battery and charging, pushed by the robot (RobotAPI "Register Status Listener", Definition.STATUS_BATTERY)
    private val batteryListener = object : StatusListener() {
        override fun onStatusUpdate(type: String?, data: String?) {
            Json.power(data)?.let { setPower(it, listener = true) }
        }
    }

    // has to happen while our activity is in the foreground, otherwise no chassis rights
    fun connect(context: Context) {
        if (connected) return
        runCatching { api.connectServer(context.applicationContext, apiListener) }
            .onFailure {
                Log.w(TAG, "connectServer failed", it)
                health(false, "Robot API not available")
            }
    }

    private fun setUp() {
        // keep the app in charge while docked: nova still talks there, only the chassis is locked.
        // also needed before leaveChargingPile (RobotAPI "Disable Battery Interface")
        runCatching { api.disableBattery() }
        runCatching { api.unregisterStatusListener(batteryListener) }
        runCatching { api.registerStatusListener(Definition.STATUS_BATTERY, batteryListener) }
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

    // how many people (face or body) are within range; null when the vision api gives nothing
    fun peopleNear(rangeM: Double): Int? {
        if (!ready) return null
        val list = runCatching { people.allPersons }.getOrNull() ?: return null
        return list.count { p -> runCatching { p.distance > 0.0 && p.distance <= rangeM }.getOrDefault(false) }
    }

    // ---- focus following: turns nova towards the person she talks to. it holds the chassis,
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

    // ---- where nova is ----

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

    // is nova at her home spot? keeps a non-home spot she was parked at
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

    // drives to a map spot. gets off the charger first, stops focus following, gives up when
    // blocked for BLOCKED_LIMIT_MS in total or when stopWhen() says so.
    suspend fun goTo(
        spot: String,
        escort: Boolean,
        stopWhen: () -> Boolean = { false },
        onEvent: (NavEvent) -> Unit = {},
    ): NavOutcome {
        if (!ready) return NavOutcome.Failed(NavFailure.NOT_CONNECTED)
        if (chargingNow() && !leavePile()) return NavOutcome.Failed(NavFailure.DOCKED)
        if (stopFollow()) delay(FOLLOW_RELEASE_MS)
        position = null
        atHome = false
        var outcome: NavOutcome = NavOutcome.Failed(NavFailure.OTHER)
        for (attempt in 0..1) {
            outcome = watched(spot, escort, stopWhen, onEvent)
            // the chassis may still be held by the focus following we just stopped - try once more
            val busy = (outcome as? NavOutcome.Failed)?.why == NavFailure.BUSY
            if (!busy || attempt == 1) break
            delay(1_000)
        }
        if (outcome == NavOutcome.Arrived) {
            position = spot
            atHome = isHome(spot)
        } else {
            position = null
            atHome = null
        }
        return outcome
    }

    private suspend fun watched(
        spot: String,
        escort: Boolean,
        stopWhen: () -> Boolean,
        onEvent: (NavEvent) -> Unit,
    ): NavOutcome = coroutineScope {
        val clock = BlockedClock()
        val nav = async {
            navigate(spot, escort) { ev ->
                if (ev == NavEvent.BLOCKED) clock.blocked(now()) else clock.clear(now())
                onEvent(ev)
            }
        }
        var nagAt = now()
        var result: NavOutcome? = null
        while (result == null) {
            result = withTimeoutOrNull(WATCH_MS) { nav.await() }
            if (result != null) break
            val t = now()
            when {
                stopWhen() -> {
                    nav.cancel()
                    result = NavOutcome.Failed(NavFailure.STOPPED)
                }
                clock.total(t) > BLOCKED_LIMIT_MS -> {
                    nav.cancel()
                    result = NavOutcome.Failed(NavFailure.BLOCKED)
                }
                !clock.isBlocked() -> nagAt = t
                t - nagAt >= NAG_EVERY_MS -> {
                    nagAt = t
                    onEvent(NavEvent.BLOCKED)
                }
            }
        }
        result ?: NavOutcome.Failed(NavFailure.OTHER)
    }

    private suspend fun navigate(spot: String, escort: Boolean, onEvent: (NavEvent) -> Unit): NavOutcome {
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
                    if (escort) {
                        api.startNavigation(id, spot, ARRIVE_RANGE_M, AVOID_TIMEOUT_MS, ESCORT_LINEAR, ESCORT_ANGULAR, listener)
                    } else {
                        api.startNavigation(id, spot, ARRIVE_RANGE_M, AVOID_TIMEOUT_MS, listener)
                    }
                }.getOrElse {
                    Log.w(TAG, "startNavigation threw", it)
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

    // ---- charging ----

    // RobotAPI "Start Auto Charging": drives to the charging pile of the map and docks
    suspend fun goCharge(onEvent: (NavEvent) -> Unit = {}): ChargeOutcome {
        if (!ready) return ChargeOutcome.Failed(NavFailure.NOT_CONNECTED.screen)
        if (stopFollow()) delay(FOLLOW_RELEASE_MS)
        position = null
        atHome = false
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
        val ok = withTimeoutOrNull(LEAVE_TIMEOUT_MS) {
            suspendCancellableCoroutine<Boolean> { cont ->
                val finished = AtomicBoolean(false)
                fun finish(v: Boolean) {
                    if (finished.compareAndSet(false, true)) cont.resume(v)
                }
                val started = runCatching {
                    api.leaveChargingPile(reqIds.getAndIncrement(), LEAVE_SPEED, LEAVE_DISTANCE_M, object : CommandListener() {
                        override fun onResult(result: Int, message: String?) = finish(result == Definition.RESULT_OK)

                        override fun onError(errorCode: Int, errorString: String?) {
                            Log.w(TAG, "leaveChargingPile error $errorCode")
                            finish(false)
                        }
                    })
                }.getOrDefault(-1)
                if (started < 0) finish(false)
            }
        } ?: false
        if (ok) {
            docked = false
            undockedAt = now()
            position = null
            atHome = false
        }
        return ok
    }

    // names of all spots on the current map (empty if the robot api is not there)
    suspend fun places(): List<String> {
        if (!ready) return emptyList()
        return withTimeoutOrNull(5_000) {
            suspendCancellableCoroutine<List<String>> { cont ->
                val finished = AtomicBoolean(false)
                fun finish(names: List<String>) {
                    if (finished.compareAndSet(false, true)) cont.resume(names)
                }
                val started = runCatching {
                    api.getPlaceList(reqIds.getAndIncrement(), object : CommandListener() {
                        override fun onResult(result: Int, message: String?) {
                            val names = runCatching {
                                val arr = JSONArray(message.orEmpty())
                                (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() } }
                            }.getOrDefault(emptyList())
                            finish(names)
                        }

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
