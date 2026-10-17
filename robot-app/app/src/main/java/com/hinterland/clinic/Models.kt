package com.hinterland.clinic

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// shapes follow docs/api.md

object VisitStatus {
    const val CHECKED_IN = "checked_in"
    const val NOTIFIED = "notified"
    const val CALLED = "called"
    const val ESCORTING = "escorting"
    const val WITH_DOCTOR = "with_doctor"
    const val DONE = "done"
    const val CANCELLED = "cancelled"

    private val order = listOf(CHECKED_IN, NOTIFIED, CALLED, ESCORTING, WITH_DOCTOR, DONE)

    // position in the normal flow, -1 for cancelled or unknown
    fun rank(status: String?): Int = order.indexOf(status)
}

data class VisitEvent(val at: String?, val type: String, val detail: String?)

data class Visit(
    val id: String,
    val patientName: String,
    val doctorId: String?,
    val doctorName: String,
    val appointmentTime: String?,
    val language: String?,
    val status: String,
    val createdAt: String?,
    val updatedAt: String?,
    val spot: String?,
    val events: List<VisitEvent>,
    // computed by the backend: 1-based place among the doctor's waiting visits, null when not waiting
    val queuePosition: Int? = null,
    val estimatedWaitMinutes: Int? = null,
)

data class CheckInResult(
    val visit: Visit,
    val notified: Boolean,
    val doctorKnown: Boolean,
    val spot: String?,
)

// unused fields are null: say has text, go_to has spot, call_patient has the visit fields
data class RobotCommand(
    val id: String,
    val type: String,
    val visitId: String? = null,
    val patientName: String? = null,
    val doctorName: String? = null,
    val spot: String? = null,
    val text: String? = null,
) {
    companion object {
        const val CALL_PATIENT = "call_patient"
        const val GO_TO = "go_to"
        const val SAY = "say"
    }
}

// battery from the robot's status listener; either part can be missing
data class PowerReading(val level: Int?, val charging: Boolean?)

data class Doctor(val id: String?, val name: String, val spot: String?, val active: Boolean)

// language for robot-initiated sentences: the visit's tag, else the patient's own words, else the device locale
data class Lang(val tag: String?, val sample: String? = null) {
    fun instruction(): String = when {
        !tag.isNullOrBlank() -> "Speak in the language with the BCP-47 tag \"${tag.trim()}\"."
        !sample.isNullOrBlank() -> "Speak in the same language the patient used in this sentence: \"${sample.trim()}\"."
        else -> "Speak in the language with the BCP-47 tag \"${Locale.getDefault().toLanguageTag()}\"."
    }

    companion object {
        private val tagPattern = Regex("^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$")

        // keeps a well-formed tag, drops anything else (a language name is not a tag)
        fun cleanTag(raw: String?): String? =
            raw?.trim()?.replace('_', '-')?.takeIf { tagPattern.matches(it) }
    }
}

object Json {

    fun checkIn(body: String): CheckInResult {
        val o = JSONObject(body)
        val v = visit(o.optJSONObject("visit") ?: o)
        return CheckInResult(
            visit = v,
            notified = o.optBoolean("notified", false),
            doctorKnown = if (o.has("doctorKnown")) o.optBoolean("doctorKnown", true) else true,
            spot = o.str("spot") ?: v.spot,
        )
    }

    fun visit(body: String): Visit {
        val o = JSONObject(body)
        return visit(o.optJSONObject("visit") ?: o)
    }

    fun visit(o: JSONObject): Visit {
        val events = o.optJSONArray("events")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val e = arr.optJSONObject(i) ?: return@mapNotNull null
                val type = e.str("type") ?: return@mapNotNull null
                VisitEvent(at = e.str("at"), type = type, detail = e.str("detail"))
            }
        }.orEmpty()
        return Visit(
            id = o.str("id") ?: error("visit without id"),
            patientName = o.str("patientName").orEmpty(),
            doctorId = o.str("doctorId"),
            doctorName = o.str("doctorName") ?: o.str("doctor").orEmpty(),
            appointmentTime = o.str("appointmentTime"),
            language = o.str("language"),
            status = o.str("status") ?: VisitStatus.CHECKED_IN,
            createdAt = o.str("createdAt"),
            updatedAt = o.str("updatedAt"),
            spot = o.str("spot"),
            events = events,
            queuePosition = o.int("queuePosition")?.takeIf { it >= 1 },
            estimatedWaitMinutes = o.int("estimatedWaitMinutes")?.takeIf { it >= 0 },
        )
    }

    fun visits(body: String): List<Visit> =
        list(body, "visits").objects().mapNotNull { runCatching { visit(it) }.getOrNull() }

    // empty body or an object without id means "nothing to do"
    fun command(body: String): RobotCommand? {
        val t = body.trim()
        if (t.isEmpty() || t == "null") return null
        val root = JSONObject(t)
        val o = root.optJSONObject("command") ?: root
        val id = o.str("id") ?: return null
        return RobotCommand(
            id = id,
            type = o.str("type").orEmpty(),
            visitId = o.str("visitId"),
            patientName = o.str("patientName"),
            doctorName = o.str("doctorName"),
            spot = o.str("spot"),
            text = o.str("text"),
        )
    }

    // POST /api/robot/heartbeat body. unknown values go as json null, detail only when there is one
    fun heartbeat(robot: String, s: BrainStatus): JSONObject {
        val o = JSONObject()
            .put("robot", robot)
            .put("battery", s.battery?.coerceIn(0, 100) ?: JSONObject.NULL)
            .put("charging", s.charging)
            .put("state", s.state.wire)
            .put("position", s.position?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
            .put("goal", s.goal.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
        s.detail?.takeIf { it.isNotBlank() }?.let { o.put("detail", it) }
        return o
    }

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

    // POST /api/help -> { ok, id, ... }
    fun helpId(body: String): String? =
        runCatching { JSONObject(body).str("id") }.getOrNull()

    // GET /api/help?open=1 -> ids that are still open
    fun openHelpIds(body: String): Set<String> =
        list(body, "help").objects()
            .filter { it.str("status") != "done" }
            .mapNotNull { it.str("id") }
            .toSet()

    fun doctors(body: String): List<Doctor> =
        list(body, "doctors").objects().mapNotNull { o ->
            val name = o.str("name") ?: return@mapNotNull null
            Doctor(
                id = o.str("id"),
                name = name,
                spot = o.str("spot"),
                active = o.optBoolean("active", true),
            )
        }

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

    fun minutesSince(value: String?, now: Instant = Instant.now()): Long? =
        instant(value)?.let { ((now.toEpochMilli() - it.toEpochMilli()) / 60_000).coerceAtLeast(0) }

    // the planner should send HH:mm, but people say "10" or "9.30" - keep anything else as it was said
    fun normalizeTime(raw: String?): String? {
        val t = raw?.trim().orEmpty()
        if (t.isEmpty()) return null
        val m = Regex("^(\\d{1,2})(?:\\s*[:.h]\\s*(\\d{2}))?$").find(t) ?: return t
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].ifEmpty { "0" }.toInt()
        if (h > 23 || min > 59) return t
        return String.format(Locale.ROOT, "%02d:%02d", h, min)
    }
}
