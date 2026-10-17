package com.hinterland.clinic

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// shapes follow docs/journeys.md (v3). a patient is only a ticket: no names anywhere in here.

object JourneyStatus {
    const val WAITING_PICKUP = "waiting_pickup"
    const val WALKING = "walking"
    const val ARRIVED = "arrived"
    const val WITH_STAFF = "with_staff"
    const val DONE = "done"
    const val CANCELLED = "cancelled"

    fun closed(status: String?) = status == DONE || status == CANCELLED
}

data class Leg(
    val id: String,
    val fromPlaceId: String? = null,
    val toPlaceId: String? = null,
    val toName: String? = null,
    val toSpot: String? = null,
    val status: String? = null,
)

data class Journey(
    val id: String,
    val ticket: String,
    val pace: Pace = Pace.NORMAL,
    val wheelchair: Boolean = false,
    val status: String = JourneyStatus.WAITING_PICKUP,
    val legs: List<Leg> = emptyList(),
    val createdBy: String? = null,
    val queuePosition: Int? = null,
    val estimatedWaitMinutes: Int? = null,
) {
    // the current leg is the last one
    val leg: Leg? get() = legs.lastOrNull()
}

// a stop of a guide command (from / to). viaSpot = optional waypoint when the way is blocked (6.6)
data class Stop(
    val name: String,
    val spot: String,
    val room: String? = null,
    val kind: String? = null,
    val viaSpot: String? = null,
) {
    val label: String get() = room?.let { "Room $it" } ?: name
}

// unused fields are null: guide has the journey fields, say/ask have text, go_to has spot
data class RobotCommand(
    val id: String,
    val type: String,
    val journeyId: String? = null,
    val legId: String? = null,
    val ticket: String? = null,
    val pace: Pace? = null,
    val wheelchair: Boolean = false,
    val from: Stop? = null,
    val to: Stop? = null,
    val spot: String? = null,
    val text: String? = null,
    // walk_control: wait | resume | slower | faster
    val action: String? = null,
    // guide: the phone companion link (walkUrl from the server, or walkCode to build it)
    val walkCode: String? = null,
    val walkUrl: String? = null,
) {
    companion object {
        const val GUIDE = "guide"
        const val GO_TO = "go_to"
        const val SAY = "say"
        const val ASK = "ask"
        const val WALK_CONTROL = "walk_control"
    }
}

// walk_control actions from the phone page or the dashboard (docs/journeys.md 6.4)
enum class WalkControl(val wire: String) {
    WAIT("wait"), RESUME("resume"), SLOWER("slower"), FASTER("faster");

    companion object {
        fun of(wire: String?): WalkControl? = values().firstOrNull { it.wire == wire?.trim()?.lowercase(Locale.ROOT) }
    }
}

// the QR code on the pickup screen: <base>/walk/<walkCode>. a full walkUrl from the server wins;
// else the walk base url from local.properties, else the server nova uses right now.
object WalkLink {
    private val code = Regex("^[A-Za-z0-9_-]{4,32}$")

    fun url(walkUrl: String?, walkCode: String?, base: String?): String? {
        walkUrl?.trim()?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let { return it }
        val c = walkCode?.trim()?.takeIf { code.matches(it) } ?: return null
        val b = base?.trim()?.trimEnd('/')?.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: return null
        return "$b/walk/$c"
    }
}

data class JourneyCreated(val journey: Journey?, val command: RobotCommand?)

// battery from the robot's status listener; either part can be missing
data class PowerReading(val level: Int?, val charging: Boolean?)

// the live mirror of the robot screen for the dashboard (heartbeat `screen`)
data class ScreenMirror(
    val view: String,
    val title: String,
    val ticket: String? = null,
    val step: String? = null,
    val progress: Float? = null,
)

// one line of the live conversation feed (POST /api/robot/feed)
data class FeedItem(val at: Long, val who: String, val kind: String, val text: String) {
    companion object {
        const val NOVA = "nova"
        const val PATIENT = "patient"
        const val SAID = "said"
        const val INTENT = "intent"
        const val ACTION = "action"
    }
}

// language for robot-initiated sentences: a tag, else the patient's own words, else the device locale.
// tags = say it once in each of these languages (the ticket call, robot.callLanguages)
data class Lang(val tag: String?, val sample: String? = null, val tags: List<String> = emptyList()) {
    fun instruction(): String = when {
        tags.isNotEmpty() ->
            "Say it once in each of the languages with these BCP-47 tags, one short version after the other, " +
                "in this order: ${tags.joinToString(", ")}."
        !tag.isNullOrBlank() -> "Speak in the language with the BCP-47 tag \"${tag.trim()}\"."
        !sample.isNullOrBlank() -> "Speak in the same language the person used in this sentence: \"${sample.trim()}\"."
        else -> "Speak in the language with the BCP-47 tag \"${Locale.getDefault().toLanguageTag()}\"."
    }

    companion object {
        private val tagPattern = Regex("^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$")

        // keeps a well-formed tag, drops anything else (a language name is not a tag)
        fun cleanTag(raw: String?): String? =
            raw?.trim()?.replace('_', '-')?.takeIf { tagPattern.matches(it) }

        // "de, en" from local.properties -> [de, en]
        fun tagList(raw: String?): List<String> =
            raw.orEmpty().split(',', ';', ' ').mapNotNull { cleanTag(it) }.distinct()
    }
}

object Json {

    fun places(body: String): List<Place> =
        list(body, "places").objects().mapNotNull { o -> runCatching { place(o) }.getOrNull() }

    fun place(o: JSONObject): Place {
        val name = o.str("name") ?: error("place without name")
        return Place(
            id = o.str("id") ?: error("place without id"),
            name = name,
            room = o.str("room"),
            spot = o.str("spot") ?: name,
            kind = o.str("kind")?.lowercase(Locale.ROOT) ?: PlaceKind.OTHER,
            public = o.bool("public") ?: false,
            avgMinutes = o.int("avgMinutes"),
            active = o.bool("active") ?: true,
            selfCheckIn = o.bool("selfCheckIn") ?: false,
            waitingPlaceId = o.str("waitingPlaceId"),
            viaSpot = o.str("viaSpot"),
        )
    }

    fun journey(body: String): Journey {
        val o = JSONObject(body)
        return journey(o.optJSONObject("journey") ?: o)
    }

    fun journey(o: JSONObject): Journey {
        val legs = o.optJSONArray("legs")?.objects().orEmpty().mapNotNull { l ->
            val id = l.str("id") ?: return@mapNotNull null
            Leg(
                id = id,
                fromPlaceId = l.str("fromPlaceId"),
                toPlaceId = l.str("toPlaceId"),
                toName = l.str("toName"),
                toSpot = l.str("toSpot"),
                status = l.str("status"),
            )
        }
        return Journey(
            id = o.str("id") ?: error("journey without id"),
            ticket = o.str("ticket").orEmpty(),
            pace = Pace.of(o.str("pace")) ?: Pace.NORMAL,
            wheelchair = o.bool("wheelchair") ?: false,
            status = o.str("status") ?: JourneyStatus.WAITING_PICKUP,
            legs = legs,
            createdBy = o.str("createdBy"),
            queuePosition = o.int("queuePosition")?.takeIf { it >= 1 },
            estimatedWaitMinutes = o.int("estimatedWaitMinutes")?.takeIf { it >= 0 },
        )
    }

    // POST /api/journeys -> { journey, command }
    fun journeyCreated(body: String): JourneyCreated {
        val o = JSONObject(body)
        return JourneyCreated(
            journey = o.optJSONObject("journey")?.let { runCatching { journey(it) }.getOrNull() },
            command = o.optJSONObject("command")?.let { command(it) },
        )
    }

    // empty body or an object without id means "nothing to do"
    fun command(body: String): RobotCommand? {
        val t = body.trim()
        if (t.isEmpty() || t == "null") return null
        val root = JSONObject(t)
        return command(root.optJSONObject("command") ?: root)
    }

    fun command(o: JSONObject): RobotCommand? {
        val id = o.str("id") ?: return null
        return RobotCommand(
            id = id,
            type = o.str("type").orEmpty(),
            journeyId = o.str("journeyId"),
            legId = o.str("legId"),
            ticket = Tickets.normalize(o.str("ticket")) ?: o.str("ticket"),
            pace = Pace.of(o.str("pace")),
            wheelchair = o.bool("wheelchair") ?: false,
            from = o.optJSONObject("from")?.let(::stop),
            to = o.optJSONObject("to")?.let(::stop),
            spot = o.str("spot"),
            text = o.str("text"),
            action = o.str("action")?.lowercase(Locale.ROOT),
            walkCode = o.str("walkCode"),
            walkUrl = o.str("walkUrl"),
        )
    }

    private fun stop(o: JSONObject): Stop? {
        val name = o.str("name")
        val spot = o.str("spot") ?: name ?: return null
        return Stop(name = name ?: spot, spot = spot, room = o.str("room"), kind = o.str("kind"), viaSpot = o.str("viaSpot"))
    }

    // PATCH /api/journeys/:id body
    fun journeyPatch(legId: String?, status: String?, eventType: String?, detail: String?): JSONObject {
        val o = JSONObject()
        legId?.let { o.put("legId", it) }
        status?.let { o.put("status", it) }
        if (eventType != null) {
            val e = JSONObject().put("type", eventType)
            detail?.takeIf { it.isNotBlank() }?.let { e.put("detail", it) }
            o.put("event", e)
        }
        return o
    }

    // POST /api/robot/heartbeat body. unknown values go as json null, detail only when there is one.
    // server = the team-inbox url nova uses (6.1), version = the app version
    fun heartbeat(
        robot: String,
        s: BrainStatus,
        screen: ScreenMirror? = null,
        server: String? = null,
        version: String? = null,
        pose: RobotPose? = null,
    ): JSONObject {
        val o = JSONObject()
            .put("robot", robot)
            .put("battery", s.battery?.coerceIn(0, 100) ?: JSONObject.NULL)
            .put("charging", s.charging)
            .put("state", s.state.wire)
            .put("position", s.position?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
            .put("goal", s.goal.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
        s.detail?.takeIf { it.isNotBlank() }?.let { o.put("detail", it) }
        screen?.let { o.put("screen", screen(it)) }
        server?.takeIf { it.isNotBlank() }?.let { o.put("server", it) }
        version?.takeIf { it.isNotBlank() }?.let { o.put("version", it) }
        // map coordinates from getPosition, also while driving (field test: position was empty then)
        pose?.let { o.put("pose", JSONObject().put("x", round(it.x, 2)).put("y", round(it.y, 2)).put("theta", round(ScanMath.wrap(it.theta), 2))) }
        return o
    }

    // POST /api/robot/logs body
    fun logs(robot: String, items: List<LogLine>): JSONObject {
        val arr = JSONArray()
        items.forEach { l ->
            arr.put(
                JSONObject()
                    .put("at", Instant.ofEpochMilli(l.at).toString())
                    .put("level", l.level.wire)
                    .put("source", l.source.wire)
                    .put("text", l.text),
            )
        }
        return JSONObject().put("robot", robot).put("items", arr)
    }

    // POST /api/robot/map body (docs/journeys.md 6.5). places only when they are due, at most
    // MAP_SCAN_MAX scan points (map frame), numbers rounded so a report stays a few kB.
    fun mapReport(r: MapReport): JSONObject {
        val o = JSONObject().put("robot", r.robot)
        r.mapName?.takeIf { it.isNotBlank() }?.let { o.put("mapName", it) }
        o.put(
            "pose",
            JSONObject().put("x", round(r.pose.x, 3)).put("y", round(r.pose.y, 3)).put("theta", round(ScanMath.wrap(r.pose.theta), 3)),
        )
        r.places?.let { list ->
            val arr = JSONArray()
            list.forEach { p -> arr.put(JSONObject().put("name", p.name).put("x", round(p.point.x, 2)).put("y", round(p.point.y, 2))) }
            o.put("places", arr)
        }
        if (r.scan.isNotEmpty()) {
            val arr = JSONArray()
            ScanMath.downsample(r.scan, MAP_SCAN_MAX).forEach { p -> arr.put(JSONArray().put(round(p.x, 2)).put(round(p.y, 2))) }
            o.put("scan", arr)
        }
        r.follower?.let { f ->
            o.put("follower", JSONObject().put("x", round(f.x, 2)).put("y", round(f.y, 2)).put("source", r.followerSource ?: "lidar"))
        }
        r.target?.let { t -> o.put("target", JSONObject().put("name", t.name).put("x", round(t.point.x, 2)).put("y", round(t.point.y, 2))) }
        r.remainingMeters?.let { o.put("remainingMeters", round(it, 1)) }
        return o
    }

    const val MAP_SCAN_MAX = 240

    // RobotApi getPosition / STATUS_POSE: {"px":..,"py":..,"theta":..}; x/y also accepted
    fun robotPose(message: String?): RobotPose? {
        val o = runCatching { JSONObject(message.orEmpty()) }.getOrNull() ?: return null
        val x = o.number("px") ?: o.number("x") ?: return null
        val y = o.number("py") ?: o.number("y") ?: return null
        val t = o.number("theta") ?: o.number("z") ?: 0.0
        return RobotPose(x, y, ScanMath.theta(t))
    }

    // Definition.STATUS_LINE_DATA: a list of Pose ({"px":..,"py":..}), also [[x,y],...] or {"points":[...]}.
    // bad entries are skipped; null when it is not a list at all
    fun scan(data: String?): List<Point>? {
        val t = data?.trim().orEmpty()
        if (t.isEmpty()) return null
        val arr = when {
            t.startsWith("[") -> runCatching { JSONArray(t) }.getOrNull()
            t.startsWith("{") -> runCatching { JSONObject(t).let { it.optJSONArray("points") ?: it.optJSONArray("data") } }.getOrNull()
            else -> null
        } ?: return null
        val out = ArrayList<Point>(arr.length())
        for (i in 0 until arr.length()) {
            val p = when (val item = arr.opt(i)) {
                is JSONObject -> {
                    val x = item.number("px") ?: item.number("x")
                    val y = item.number("py") ?: item.number("y")
                    if (x != null && y != null) Point(x, y) else null
                }
                is JSONArray -> if (item.length() >= 2) {
                    val x = item.optDouble(0)
                    val y = item.optDouble(1)
                    if (!x.isNaN() && !y.isNaN()) Point(x, y) else null
                } else {
                    null
                }
                else -> null
            }
            if (p != null && p.x.isFinite() && p.y.isFinite()) out += p
        }
        return out
    }

    // Definition.STATUS_EMERGENCY. the docs do not show the value, so only clear forms count:
    // "1"/"true" = pressed, "0"/"false" = released, or a json object with one of those under a known key
    fun emergency(data: String?): Boolean? {
        val t = data?.trim().orEmpty()
        when (t.lowercase(Locale.ROOT)) {
            "1", "true" -> return true
            "0", "false" -> return false
        }
        val o = runCatching { JSONObject(t) }.getOrNull() ?: return null
        return listOf("emergency", "isEmergency", "status", "state").firstNotNullOfOrNull { o.bool(it) }
    }

    private fun round(v: Double, digits: Int): Double {
        val f = Math.pow(10.0, digits.toDouble())
        return Math.round(v * f) / f
    }

    fun screen(m: ScreenMirror): JSONObject {
        val o = JSONObject().put("view", m.view).put("title", m.title)
        m.ticket?.let { o.put("ticket", it) }
        m.step?.let { o.put("step", it) }
        m.progress?.let { o.put("progress", (it.coerceIn(0f, 1f) * 100).toInt() / 100.0) }
        return o
    }

    // POST /api/robot/feed body
    fun feed(robot: String, journeyId: String?, items: List<FeedItem>): JSONObject {
        val arr = JSONArray()
        items.forEach { i ->
            arr.put(
                JSONObject()
                    .put("at", Instant.ofEpochMilli(i.at).toString())
                    .put("who", i.who)
                    .put("kind", i.kind)
                    .put("text", i.text),
            )
        }
        val o = JSONObject().put("robot", robot).put("items", arr)
        journeyId?.let { o.put("journeyId", it) }
        return o
    }

    // the feed answer says if the dashboard wants the patient's own words (demo switch)
    fun showPatientText(body: String): Boolean? =
        runCatching { JSONObject(body).bool("showPatientText") }.getOrNull()

    // Definition.STATUS_BATTERY data. the docs do not show the format, so this takes a json object
    // (BatteryBean has level / isCharging) or a bare number, and anything else is "unknown"
    fun power(data: String?): PowerReading? {
        val t = data?.trim().orEmpty()
        if (t.isEmpty()) return null
        t.toDoubleOrNull()?.let { return PowerReading(percent(it), null).takeIf { r -> r.level != null } }
        val o = runCatching { JSONObject(t) }.getOrNull() ?: return null
        val level = listOf("level", "battery", "batteryLevel").firstNotNullOfOrNull { k -> o.number(k)?.let(::percent) }
        val charging = listOf("isCharging", "charging").firstNotNullOfOrNull { k -> o.bool(k) }
        if (level == null && charging == null) return null
        return PowerReading(level, charging)
    }

    // RobotApi getPosition: {"px":..,"py":..,"theta":..} (Definition.JSON_NAVI_POSITION_X/Y), x/y also accepted
    fun pose(message: String?): Point? = robotPose(message)?.point

    // RobotApi getPlaceList: [{"name":..,"x":..,"y":..}, ...]
    fun mapPlaces(message: String?): List<MapPlace> {
        val arr = runCatching { JSONArray(message.orEmpty()) }.getOrNull() ?: return emptyList()
        return arr.objects().mapNotNull { o ->
            val name = o.str("name") ?: return@mapNotNull null
            val x = o.number("x") ?: o.number("px") ?: return@mapNotNull null
            val y = o.number("y") ?: o.number("py") ?: return@mapNotNull null
            MapPlace(name, Point(x, y))
        }
    }

    // POST /api/help -> { ok, id, ... }
    fun helpId(body: String): String? =
        runCatching { JSONObject(body).str("id") }.getOrNull()

    // GET /api/help?open=1 -> ids that are still open
    fun openHelpIds(body: String): Set<String> =
        list(body, "help").objects()
            .filter { it.str("status") != "done" }
            .mapNotNull { it.str("id") }
            .toSet()

    // {"error": "..."} from the backend, if there is one
    fun errorMessage(body: String): String? =
        runCatching { JSONObject(body).str("error") }.getOrNull()

    private fun list(body: String, key: String): JSONArray {
        val t = body.trim()
        if (t.isEmpty()) return JSONArray()
        return if (t.startsWith("[")) JSONArray(t) else JSONObject(t).optJSONArray(key) ?: JSONArray()
    }

    private fun JSONArray.objects(): List<JSONObject> =
        (0 until length()).mapNotNull { optJSONObject(it) }

    // null for missing, json null and blank - optString would give "null" or ""
    private fun JSONObject.str(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).trim().takeIf { it.isNotEmpty() }

    // numbers may come as 3, 3.0 or "3"
    private fun JSONObject.number(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        return when (val v = opt(key)) {
            is Number -> v.toDouble()
            is String -> v.trim().toDoubleOrNull()
            else -> null
        }?.takeIf { !it.isNaN() }
    }

    private fun JSONObject.int(key: String): Int? = number(key)?.toInt()

    private fun JSONObject.bool(key: String): Boolean? {
        if (!has(key) || isNull(key)) return null
        return when (val v = opt(key)) {
            is Boolean -> v
            is Number -> v.toInt() != 0
            is String -> when (v.trim().lowercase(Locale.ROOT)) {
                "true", "1" -> true
                "false", "0" -> false
                else -> null
            }
            else -> null
        }
    }

    private fun percent(value: Double): Int? = value.toInt().takeIf { it in 0..100 }
}

object Clock {
    private val hhmm = DateTimeFormatter.ofPattern("HH:mm")

    fun now(zone: ZoneId = ZoneId.systemDefault()): String = hhmm.format(Instant.now().atZone(zone))

    fun instant(value: String?): Instant? {
        val v = value?.trim().orEmpty()
        if (v.isEmpty()) return null
        if (v.all { it.isDigit() }) return v.toLongOrNull()?.let { Instant.ofEpochMilli(it) }
        return runCatching { Instant.parse(v) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(v).toInstant() }.getOrNull()
    }

    fun hhmm(value: String?, zone: ZoneId = ZoneId.systemDefault()): String? =
        instant(value)?.let { hhmm.format(it.atZone(zone)) }
}
