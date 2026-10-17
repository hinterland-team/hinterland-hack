package com.hinterland.clinic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// live map for the dashboard (docs/journeys.md 6.5) and the chassis check for the out-of-service
// screen (6.6). the decisions are plain kotlin (MapRate, Chassis), unit tested; LiveMap sends.

data class MapReport(
    val robot: String,
    val pose: RobotPose,
    val mapName: String? = null,
    // null = not due this time
    val places: List<MapPlace>? = null,
    // map frame
    val scan: List<Point> = emptyList(),
    val follower: Point? = null,
    val followerSource: String? = null,
    val target: MapPlace? = null,
    val remainingMeters: Double? = null,
    // the robot's maps (floors), null = not due this time
    val maps: List<FloorMap>? = null,
)

// every 2 s while moving (v3.3, was 1 s: half the json and network work), every 15 s when idle;
// places on change or every 60 s. the two intervals are live settings (sensors.mapMovingS / mapIdleS)
class MapRate(
    private val movingMs: Long = MOVING_MS,
    private val idleMs: Long = IDLE_MS,
    private val placesMs: Long = PLACES_MS,
) {
    private var sentAt = 0L
    private var placesAt = 0L
    private var placesKey: Int? = null
    private var mapsAt = 0L
    private var mapsKey: Int? = null

    fun due(now: Long, moving: Boolean, movingEveryMs: Long = movingMs, idleEveryMs: Long = idleMs): Boolean =
        now - sentAt >= if (moving) movingEveryMs else idleEveryMs

    // the floors: on change or with the places every minute
    fun mapsDue(now: Long, maps: List<FloorMap>): Boolean =
        maps.isNotEmpty() && (maps.hashCode() != mapsKey || now - mapsAt >= placesMs)

    fun mapsSent(now: Long, maps: List<FloorMap>) {
        mapsAt = now
        mapsKey = maps.hashCode()
    }

    fun sent(now: Long) {
        sentAt = now
    }

    fun placesDue(now: Long, places: List<MapPlace>): Boolean =
        places.isNotEmpty() && (places.hashCode() != placesKey || now - placesAt >= placesMs)

    fun placesSent(now: Long, places: List<MapPlace>) {
        placesAt = now
        placesKey = places.hashCode()
    }

    companion object {
        const val MOVING_MS = 2_000L
        const val IDLE_MS = 15_000L
        const val PLACES_MS = 60_000L
    }
}

// why otto can't drive. title/hint for the screen, detail for the heartbeat and the log
enum class ChassisIssue(val hint: String, val detail: String) {
    NOT_STARTED(
        "Please start me from the robot's home screen.",
        "No chassis rights: the app was not started from the robot's home screen",
    ),
    DISABLED(
        "Please start me from the robot's home screen.",
        "Robot API disabled for this app: start it from the robot's home screen",
    ),
    EMERGENCY(
        "The emergency stop is pressed. Please release it.",
        "Emergency stop pressed",
    ),
    SUSPENDED(
        "The robot system paused me. I'll be back in a moment.",
        "Paused by the robot system (emergency stop, low battery, update or a fault)",
    ),
    ;

    val title: String get() = "I can't drive right now"
}

object Chassis {
    const val GRACE_MS = 20_000L

    // emergency = what STATUS_EMERGENCY said (null = unknown). an emergency value alone, while the
    // system did not suspend the app, is only a warning: the value format is not documented.
    fun issue(connected: Boolean, disabled: Boolean, suspended: Boolean, emergency: Boolean?, sinceStartMs: Long, graceMs: Long = GRACE_MS): ChassisIssue? = when {
        connected && suspended -> if (emergency == true) ChassisIssue.EMERGENCY else ChassisIssue.SUSPENDED
        connected -> null
        sinceStartMs < graceMs -> null
        disabled -> ChassisIssue.DISABLED
        else -> ChassisIssue.NOT_STARTED
    }
}

object LiveMap {

    private const val TICK_MS = 500L
    private const val FAIL_PAUSE_MS = 10_000L
    private const val PLACES_REFRESH_MS = 60_000L
    private const val PLACES_RETRY_MS = 15_000L

    private val rate = MapRate()
    @Volatile private var places: List<MapPlace> = emptyList()
    @Volatile private var placesAt = 0L
    @Volatile private var failures = 0

    // the map spots otto knows (for "near <spot>" in the heartbeat)
    fun knownPlaces(): List<MapPlace> = places

    // otto is on another map now: its spots at the next frame
    fun mapChanged() {
        placesAt = 0L
        places = emptyList()
    }

    // runs with the brain
    suspend fun loop() {
        while (true) {
            delay(TICK_MS)
            runCatching { tick() }.onFailure { RobotLog.w(LogSource.NET, "live map: ${it.message ?: it.javaClass.simpleName}") }
        }
    }

    private suspend fun tick() {
        if (!Backend.configured || !Robot.ready) return
        val now = System.currentTimeMillis()
        val guide = Guide.current
        val moving = Clinic.ui.value.walking || guide?.walking == true
        val s = Config.current
        if (!rate.due(now, moving, s.mapMovingS * 1000L, s.mapIdleS * 1000L)) return
        val pose = Robot.robotPose() ?: return
        // map spots: every minute, every 15 s while otto has none yet
        if (now - placesAt >= PLACES_REFRESH_MS || (places.isEmpty() && now - placesAt >= PLACES_RETRY_MS)) {
            placesAt = now
            Robot.mapPlaces().takeIf { it.isNotEmpty() }?.let { places = it }
        }
        val list = places
        val sendPlaces = rate.placesDue(now, list)
        // the floors (docs/robot-setup.md 2), from MapSync
        val maps = MapSync.maps
        val sendMaps = rate.mapsDue(now, maps)
        val follower = guide?.followerForMap()
        val targetSpot = guide?.targetSpot ?: Robot.driving
        val target = targetSpot?.let { WalkMath.find(it, list) }
        val report = MapReport(
            robot = Backend.robot,
            pose = pose,
            mapName = MapSync.current,
            places = list.takeIf { sendPlaces },
            scan = Radar.mapScan(now).orEmpty(),
            follower = follower?.first,
            followerSource = follower?.second,
            target = target,
            remainingMeters = target?.let { pose.point.distanceTo(it.point) },
            maps = maps.takeIf { sendMaps },
        )
        val body = Json.mapReport(report)
        rate.sent(now)
        val ok = withContext(Dispatchers.IO) { runCatching { Backend.map(body) } }
        if (ok.isSuccess) {
            if (failures > 0) RobotLog.i(LogSource.NET, "Live map is sending again")
            failures = 0
            if (sendPlaces) rate.placesSent(now, list)
            if (sendMaps) rate.mapsSent(now, maps)
        } else {
            if (failures == 0) RobotLog.w(LogSource.NET, "Live map not sent: ${Backend.friendly(ok.exceptionOrNull()!!)}")
            failures++
            delay(FAIL_PAUSE_MS)
        }
    }
}
