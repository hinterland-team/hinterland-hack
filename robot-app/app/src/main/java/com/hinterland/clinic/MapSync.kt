package com.hinterland.clinic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Base64
import java.util.Locale

// the real map and the floors on the robot (docs/robot-setup.md 1 and 2): which map otto is on, which maps he
// has, whether he knows where he stands, the floor plan as a png for the dashboard (only when it changed), and
// the switch_map command. the decisions and the conversion are plain kotlin (Floors.kt, MapImage.kt).
object MapSync {

    private const val TICK_MS = 5_000L
    private const val NAME_EVERY_MS = 15_000L
    private const val MAPS_EVERY_MS = 60_000L
    private const val LOCALISED_EVERY_MS = 20_000L
    // the map content may change with the same name (the team edits and posts it): read it again now and then
    private const val IMAGE_EVERY_MS = 5 * 60_000L
    private const val IMAGE_RETRY_MS = 60_000L
    private const val VERIFY_TRIES = 6
    private const val VERIFY_EVERY_MS = 1_000L
    private const val RAW_LOG_CHARS = 300

    // the map otto is on (RobotApi getMapName), null = not known yet
    @Volatile var current: String? = null
        private set
    @Volatile var maps: List<FloorMap> = emptyList()
        private set
    // the map name and hash the dashboard has, so the same image is not sent again
    @Volatile private var sent: Pair<String, String>? = null
    @Volatile private var nameAt = 0L
    @Volatile private var mapsAt = 0L
    @Volatile private var localisedAt = 0L
    @Volatile private var imageAt = 0L
    @Volatile private var imageFailedAt = 0L
    @Volatile private var lastMapEvents = 0
    // the first answers of each kind go to the log as they came (what the real data looks like)
    private val logged = HashSet<String>()
    private val lock = Mutex()

    val localised: Boolean? get() = Robot.localised

    private fun now() = System.currentTimeMillis()

    // runs with the brain
    suspend fun loop() {
        while (true) {
            delay(TICK_MS)
            runCatching { lock.withLock { tick() } }
                .onFailure { RobotLog.w(LogSource.NAV, "Map check: ${it.javaClass.simpleName} ${it.message.orEmpty().take(100)}") }
        }
    }

    private suspend fun tick() {
        if (!Robot.ready) return
        val now = now()
        // the robot said its map was switched (also from its own map tool): look now
        val events = Robot.mapEvents
        if (events != lastMapEvents) {
            lastMapEvents = events
            nameAt = 0L
        }
        if (now - nameAt >= NAME_EVERY_MS) {
            nameAt = now
            refreshName()
        }
        if (now - mapsAt >= MAPS_EVERY_MS) {
            mapsAt = now
            refreshMaps()
        }
        if (now - localisedAt >= LOCALISED_EVERY_MS) {
            localisedAt = now
            Robot.isLocalised()
        }
        val map = current ?: return
        val due = sent?.first != map || now - imageAt >= IMAGE_EVERY_MS
        if (due && now - imageFailedAt >= IMAGE_RETRY_MS) {
            imageAt = now
            upload(map)
        }
    }

    private suspend fun refreshName() {
        val name = Robot.mapName() ?: return
        if (current == name) return
        val old = current
        current = name
        RobotLog.i(LogSource.NAV, if (old == null) "Map: $name" else "Map is now $name (was $old)")
        if (old != null) LiveMap.mapChanged()
        Clinic.mapChanged()
        maps = Floors.merge(name, maps)
    }

    // the floors: the multi-floor setup when there is one, else the local map list, at least the map otto is on
    private suspend fun refreshMaps() {
        val multi = Robot.multiFloorRaw()
        logRaw("multi-floor", multi)
        val local = Robot.localMapsRaw()
        logRaw("local maps", local)
        val fromMulti = multi?.takeIf { !it.error }?.message?.let { Floors.fromMultiFloor(it) }
        val fromLocal = local?.takeIf { !it.error }?.message?.let { Floors.fromMapList(it) }
        val next = Floors.merge(current, fromMulti, fromLocal)
        if (next != maps) {
            maps = next
            RobotLog.i(
                LogSource.NAV,
                "Maps: " + next.joinToString { m -> m.name + (m.floor?.let { " (floor $it)" } ?: "") } +
                    " (from ${listOfNotNull("multi-floor".takeIf { !fromMulti.isNullOrEmpty() }, "local list".takeIf { !fromLocal.isNullOrEmpty() }, "the current map").joinToString(" + ")})",
            )
        }
    }

    private fun logRaw(what: String, a: CmdAnswer?) {
        if (!logged.add(what)) return
        RobotLog.i(LogSource.NAV, "First $what answer: " + (a?.show()?.take(RAW_LOG_CHARS) ?: "none (timeout or not started)"))
    }

    // the floor plan as a png, only when it changed (hash)
    private suspend fun upload(map: String) {
        val t0 = now()
        val read = withContext(Dispatchers.IO) { Robot.readMap(map) }
        val pgm = read.getOrElse { e ->
            imageFailedAt = now()
            val why = (e as? MapFormatException)?.message ?: "${e.javaClass.simpleName} ${e.message.orEmpty().take(80)}"
            if (logged.add("map-fail:$map:$why")) RobotLog.w(LogSource.NAV, "Map image of $map not read: $why")
            return
        }
        if (logged.add("map:$map")) RobotLog.i(LogSource.NAV, "Map file of $map: ${MapImages.describe(pgm)}")
        val hash = pgm.hash()
        if (sent == map to hash) return
        if (!Backend.configured) return
        val (meta, png) = withContext(Dispatchers.Default) { MapImages.build(map, pgm) }
        val body = Json.mapImage(Backend.robot, meta, Base64.getEncoder().encodeToString(png))
        val r = withContext(Dispatchers.IO) { runCatching { Backend.mapImage(body) } }
        if (r.isSuccess) {
            sent = map to hash
            RobotLog.i(
                LogSource.NAV,
                String.format(
                    Locale.ROOT, "Map image of %s sent: %d x %d px (%d x %d cells, 1 px = %d), %.3f m per px, origin %.2f / %.2f, %d kB, %.1f s",
                    map, meta.width, meta.height, pgm.width, pgm.height, MapScale.factorFor(pgm.width, pgm.height),
                    meta.resolution, meta.originX, meta.originY, png.size / 1024, (now() - t0) / 1000.0,
                ),
            )
        } else {
            imageFailedAt = now()
            RobotLog.w(LogSource.NET, "Map image of $map not sent: ${Backend.friendly(r.exceptionOrNull()!!)}")
        }
    }

    // the dashboard asks for a new image (recheck): the next tick sends it again
    fun resend() {
        sent = null
        imageFailedAt = 0L
        nameAt = 0L
        mapsAt = 0L
    }

    // ---- switch_map (docs/robot-setup.md 2): refuse while guiding, switch, localise at the spot, check ----

    suspend fun switchMap(cmd: RobotCommand): CommandResult = lock.withLock {
        val plan = SwitchMapRules.plan(cmd.mapName, cmd.spot, Guide.current != null, current ?: Robot.mapName())
        RobotLog.i(LogSource.NAV, "Map switch asked: to ${cmd.mapName ?: "?"}, Otto stands at ${cmd.spot ?: "?"}")
        when (plan) {
            is SwitchPlan.Refuse -> {
                RobotLog.w(LogSource.NAV, "Map switch refused: ${plan.why}")
                CommandResult(false, plan.why)
            }
            is SwitchPlan.Already -> {
                RobotLog.i(LogSource.NAV, "Map switch: ${plan.why}")
                CommandResult(true, plan.why)
            }
            is SwitchPlan.Relocalise -> relocalise(plan.spot, current.orEmpty())
            is SwitchPlan.Switch -> switch(plan.mapName, plan.spot)
        }
    }

    private suspend fun switch(map: String, spot: String?): CommandResult {
        val from = current
        // the spot on the new map, before switching (when the robot tells): no switch into a map without it
        if (spot != null) {
            val (raw, places) = Robot.placesOf(map)
            logRaw("places of another map", raw)
            if (SwitchMapRules.spotOnMap(spot, places) == false) {
                val why = "Map $map has no spot called $spot. Nothing was switched."
                RobotLog.w(LogSource.NAV, "Map switch refused: $why (spots there: ${places.orEmpty().take(12).joinToString { it.name }})")
                return CommandResult(false, why)
            }
        }
        RobotLog.i(LogSource.NAV, "Switching map from ${from ?: "?"} to $map")
        val a = Robot.switchMap(map)
        RobotLog.i(LogSource.NAV, "switchMap answered: ${a?.show() ?: "nothing (timeout)"}")
        // what the robot says it is on now counts, not the answer
        val now = Robot.mapName()
        if (now != null && now != current) {
            current = now
            LiveMap.mapChanged()
            Clinic.mapChanged()
        }
        maps = Floors.merge(now, maps)
        if (!Floors.same(now, map)) {
            val why = "The robot did not switch to $map (it is on ${now ?: "an unknown map"})" + (a?.let { ": ${it.show()}" } ?: "")
            RobotLog.w(LogSource.NAV, "Map switch failed. $why")
            return CommandResult(false, why)
        }
        RobotLog.i(LogSource.NAV, "Map switched to $map")
        sent = null
        imageAt = 0L
        if (spot == null) {
            val loc = verifyLocalised()
            val why = "Switched to $map. No spot given: " + if (loc == true) "Otto knows where he is." else "please set Otto's position on the robot."
            RobotLog.w(LogSource.NAV, why)
            return CommandResult(loc == true, why)
        }
        return relocalise(spot, map)
    }

    private suspend fun relocalise(spot: String, map: String): CommandResult {
        val places = Robot.mapPlaces()
        val at = SwitchMapRules.spot(spot, places)
        if (at == null) {
            val why = "There is no spot called $spot on $map, so Otto does not know where he stands. Set his position on the robot."
            RobotLog.w(LogSource.NAV, "Localising failed: $why (spots: ${places.take(12).joinToString { it.name }})")
            return CommandResult(false, why)
        }
        val theta = at.theta ?: 0.0
        if (at.theta == null) RobotLog.w(LogSource.NAV, "Spot $spot has no direction, localising with 0")
        val a = Robot.setPoseEstimate(at.point.x, at.point.y, theta)
        RobotLog.i(
            LogSource.NAV,
            String.format(Locale.ROOT, "setPoseEstimate at %s (%.2f, %.2f, %.2f rad) answered: %s", spot, at.point.x, at.point.y, theta, a?.show() ?: "nothing (timeout)"),
        )
        val loc = verifyLocalised()
        return if (loc == true) {
            Robot.relocated(at.name)
            RobotLog.i(LogSource.NAV, "Localised on $map at $spot")
            CommandResult(true, "Otto is on $map at $spot and knows where he is")
        } else {
            val why = "Otto is on $map but not localised at $spot (the robot says ${loc ?: "nothing"}). Set his position on the robot."
            RobotLog.w(LogSource.NAV, why)
            CommandResult(false, why)
        }
    }

    // isRobotEstimate a few times: the robot needs a moment after the switch / the new pose
    private suspend fun verifyLocalised(): Boolean? {
        var last: Boolean? = null
        repeat(VERIFY_TRIES) {
            delay(VERIFY_EVERY_MS)
            last = Robot.isLocalised() ?: last
            if (last == true) return true
        }
        return last
    }
}
