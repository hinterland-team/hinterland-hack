package com.hinterland.clinic

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToLong

// live settings (docs/robot-setup.md 3): GET /api/robot/config -> { version, settings }. every setting has a
// built-in default (the BuildConfig value from local.properties where there is one, else what the code used
// before), a range and one line of help. a missing value keeps the default; an unknown or out-of-range one falls
// back to the default with a warning. plain kotlin, unit tested.

data class Settings(
    // ---- walk ----
    val speedNormal: Double = 0.6,
    val speedSlow: Double = 0.4,
    val speedVerySlow: Double = 0.25,
    val walkSide: WalkSide = WalkSide.LEFT,
    val firstCheckS: Int = 10,
    val checkMinS: Int = 10,
    val checkMaxS: Int = 30,
    val besideLostS: Int = 5,
    val askEvery: Int = 2,
    val listenS: Int = 5,
    val pickupTimeoutS: Int = 180,
    val pickupRemindS: Int = 45,
    val farWaitS: Int = 20,
    val lostCallAgainS: Int = 12,
    val lostReportS: Int = 30,
    val lostGiveUpS: Int = 60,
    // ---- way ----
    val blockedS: Int = 25,
    val backOff: Boolean = true,
    val autoWaypoints: Boolean = true,
    val maxWaypoints: Int = 2,
    val helpAfterS: Int = 90,
    // ---- voice ----
    // null = leave the robot's volume as it is
    val volume: Int? = null,
    // BCP-47 tags; the built-in ones come from local.properties (robot.callLanguages). empty = the device language
    val callLanguages: List<String> = emptyList(),
    val styleNote: String = "",
    val hideVoiceBar: Boolean = true,
    val openMicOnWalks: Boolean = true,
    val linePauseS: Double = 0.8,
    val answerWaitS: Double = 7.0,
    val afterPatientS: Double = 0.7,
    val maxWaitingLines: Int = 1,
    // ---- sensors ----
    val radarWatch: Boolean = true,
    val mapMovingS: Int = 2,
    val mapIdleS: Int = 15,
    val debugLogs: Boolean = false,
    // ---- screen ----
    // empty = no privacy line on the walk screen
    val privacyLine: String = PRIVACY_LINE,
    val talkHints: Boolean = true,
    // settings.mode: normal / show / calm. null = the dashboard did not say (the mode stays as it is)
    val mode: RobotMode? = null,
) {
    fun speed(p: Pace): Double = when (p) {
        Pace.NORMAL -> speedNormal
        Pace.SLOW -> speedSlow
        Pace.VERY_SLOW -> speedVerySlow
    }

    val turns: TurnTiming
        get() = TurnTiming(
            pauseMs = ms(linePauseS),
            answerWaitMs = ms(answerWaitS),
            afterUserMs = ms(afterPatientS),
            maxWaiting = maxWaitingLines,
        )

    companion object {
        // docs/journeys.md 1: the short line on the robot screen during a guide
        const val PRIVACY_LINE = "Otto only knows your ticket and the room. Nothing is recorded."

        fun ms(seconds: Double): Long = (seconds * 1000.0).roundToLong()
    }
}

// one setting: where it lives in the json (group.key, or only key at the top), its range and what the dashboard explains
sealed class SettingField(val group: String, val key: String, val help: String) {
    val path: String get() = if (group.isEmpty()) key else "$group.$key"

    // the value from the json; null = not usable (the caller warns and keeps the default)
    abstract fun read(raw: Any, s: Settings): Settings?

    // json null: the default, except where null means something (volume)
    open fun empty(s: Settings, d: Settings): Settings = reset(s, d)

    abstract fun reset(s: Settings, d: Settings): Settings
    abstract fun show(s: Settings): String
    abstract fun range(): String
}

class NumberField(
    group: String, key: String, help: String,
    val min: Double, val max: Double, val unit: String, val whole: Boolean,
    private val get: (Settings) -> Double,
    private val set: (Settings, Double) -> Settings,
) : SettingField(group, key, help) {
    override fun read(raw: Any, s: Settings): Settings? {
        val v = SettingValues.number(raw) ?: return null
        val n = if (whole) Math.round(v).toDouble() else v
        if (n < min || n > max) return null
        return set(s, n)
    }

    override fun reset(s: Settings, d: Settings) = set(s, get(d))
    override fun show(s: Settings) = SettingValues.fmt(get(s), whole)
    override fun range() = "${SettingValues.fmt(min, whole)}-${SettingValues.fmt(max, whole)} $unit".trim()
}

class SwitchField(
    group: String, key: String, help: String,
    private val get: (Settings) -> Boolean,
    private val set: (Settings, Boolean) -> Settings,
) : SettingField(group, key, help) {
    override fun read(raw: Any, s: Settings): Settings? = SettingValues.bool(raw)?.let { set(s, it) }
    override fun reset(s: Settings, d: Settings) = set(s, get(d))
    override fun show(s: Settings) = if (get(s)) "on" else "off"
    override fun range() = "on or off"
}

class ChoiceField(
    group: String, key: String, help: String,
    val options: List<String>,
    private val get: (Settings) -> String,
    private val set: (Settings, String) -> Settings,
) : SettingField(group, key, help) {
    override fun read(raw: Any, s: Settings): Settings? {
        val v = (raw as? String)?.trim()?.lowercase(Locale.ROOT) ?: return null
        return options.firstOrNull { it == v }?.let { set(s, it) }
    }

    override fun reset(s: Settings, d: Settings) = set(s, get(d))
    override fun show(s: Settings) = get(s)
    override fun range() = options.joinToString(" or ")
}

class TextField(
    group: String, key: String, help: String,
    val maxChars: Int,
    private val get: (Settings) -> String,
    private val set: (Settings, String) -> Settings,
) : SettingField(group, key, help) {
    override fun read(raw: Any, s: Settings): Settings? {
        val v = (raw as? String) ?: return null
        val clean = v.replace(Regex("\\s+"), " ").filter { !it.isISOControl() }.trim()
        if (clean.length > maxChars) return null
        return set(s, clean)
    }

    override fun reset(s: Settings, d: Settings) = set(s, get(d))
    override fun show(s: Settings) = get(s).let { if (it.isEmpty()) "(empty)" else "\"${it.take(40)}${if (it.length > 40) "..." else ""}\"" }
    override fun range() = "text, at most $maxChars characters"
}

// BCP-47 tags as a json list or "de, en". tags that are no tag are left out; none left = not usable
class TagsField(
    group: String, key: String, help: String,
    private val get: (Settings) -> List<String>,
    private val set: (Settings, List<String>) -> Settings,
) : SettingField(group, key, help) {
    override fun read(raw: Any, s: Settings): Settings? {
        val items = when (raw) {
            is JSONArray -> (0 until raw.length()).mapNotNull { raw.opt(it) as? String }
            is String -> raw.split(',', ';', ' ')
            else -> return null
        }
        val tags = items.mapNotNull { Lang.cleanTag(it) }.distinct()
        if (tags.isEmpty()) return null
        return set(s, tags)
    }

    // the tags that were left out, for the warning
    fun dropped(raw: Any): List<String> {
        val items = when (raw) {
            is JSONArray -> (0 until raw.length()).map { raw.opt(it)?.toString().orEmpty() }
            is String -> raw.split(',', ';', ' ')
            else -> emptyList()
        }
        return items.map { it.trim() }.filter { it.isNotEmpty() && Lang.cleanTag(it) == null }
    }

    override fun reset(s: Settings, d: Settings) = set(s, get(d))
    override fun show(s: Settings) = get(s).joinToString(",").ifEmpty { "(the robot's language)" }
    override fun range() = "BCP-47 language tags"
}

// a number where json null (or no value) means "leave it as it is" (the robot's own volume)
class OptionalNumberField(
    group: String, key: String, help: String,
    val min: Int, val max: Int, val unit: String,
    private val get: (Settings) -> Int?,
    private val set: (Settings, Int?) -> Settings,
) : SettingField(group, key, help) {
    override fun read(raw: Any, s: Settings): Settings? {
        val v = SettingValues.number(raw) ?: return null
        val n = Math.round(v).toInt()
        if (n < min || n > max) return null
        return set(s, n)
    }

    override fun empty(s: Settings, d: Settings) = set(s, null)
    override fun reset(s: Settings, d: Settings) = set(s, get(d))
    override fun show(s: Settings) = get(s)?.toString() ?: "as set on the robot"
    override fun range() = "$min-$max $unit, or empty".trim()
}

// the robot mode. json null or no value: the mode stays as it is
class ModeField(
    group: String, key: String, help: String,
    private val get: (Settings) -> RobotMode?,
    private val set: (Settings, RobotMode?) -> Settings,
) : SettingField(group, key, help) {
    override fun read(raw: Any, s: Settings): Settings? = RobotMode.of(raw as? String)?.let { set(s, it) }
    override fun empty(s: Settings, d: Settings) = set(s, null)
    override fun reset(s: Settings, d: Settings) = set(s, get(d))
    override fun show(s: Settings) = get(s)?.wire ?: "as it is"
    override fun range() = RobotMode.entries.joinToString(" or ") { it.wire }
}

// what one config answer gave: the settings to use, its version and what was wrong with it
data class ConfigRead(
    val version: Int?,
    val settings: Settings,
    // one english line per value that was not used
    val warnings: List<String>,
    // how many values came from the server (the rest are defaults)
    val fromServer: Int,
    val unknown: List<String>,
)

object SettingsSpec {

    val fields: List<SettingField> = listOf(
        // ---- walk ----
        NumberField(
            "walk", "speedNormal", "Walking speed at the normal pace (navigation accepts 0.1-0.85 m/s).",
            PaceTable.MIN_LINEAR, PaceTable.MAX_LINEAR, "m/s", false, { it.speedNormal }, { s, v -> s.copy(speedNormal = v) },
        ),
        NumberField(
            "walk", "speedSlow", "Walking speed at the slow pace (also for wheelchairs).",
            PaceTable.MIN_LINEAR, PaceTable.MAX_LINEAR, "m/s", false, { it.speedSlow }, { s, v -> s.copy(speedSlow = v) },
        ),
        NumberField(
            "walk", "speedVerySlow", "Walking speed at the very slow pace.",
            PaceTable.MIN_LINEAR, PaceTable.MAX_LINEAR, "m/s", false, { it.speedVerySlow }, { s, v -> s.copy(speedVerySlow = v) },
        ),
        ChoiceField(
            "walk", "walkSide", "The side the patient walks on, next to Otto (the radar sees the sides, not the back).",
            listOf("left", "right"), { it.walkSide.wire }, { s, v -> s.copy(walkSide = WalkSide.of(v)) },
        ),
        NumberField(
            "walk", "firstCheckS", "First check on the patient after this much walking.",
            3.0, 60.0, "s", true, { it.firstCheckS.toDouble() }, { s, v -> s.copy(firstCheckS = v.toInt()) },
        ),
        NumberField(
            "walk", "checkMinS", "Shortest time between two checks (patient far behind).",
            5.0, 120.0, "s", true, { it.checkMinS.toDouble() }, { s, v -> s.copy(checkMinS = v.toInt()) },
        ),
        NumberField(
            "walk", "checkMaxS", "Longest time between two checks (patient right beside Otto).",
            5.0, 180.0, "s", true, { it.checkMaxS.toDouble() }, { s, v -> s.copy(checkMaxS = v.toInt()) },
        ),
        NumberField(
            "walk", "besideLostS", "Nobody beside Otto on the radar this long while he drives: he checks at once.",
            2.0, 30.0, "s", true, { it.besideLostS.toDouble() }, { s, v -> s.copy(besideLostS = v.toInt()) },
        ),
        NumberField(
            "walk", "askEvery", "Ask \"All good?\" at every Nth stop (0 = only when the patient fell behind).",
            0.0, 10.0, "", true, { it.askEvery.toDouble() }, { s, v -> s.copy(askEvery = v.toInt()) },
        ),
        NumberField(
            "walk", "listenS", "How long Otto listens for an answer at a stop.",
            2.0, 20.0, "s", true, { it.listenS.toDouble() }, { s, v -> s.copy(listenS = v.toInt()) },
        ),
        NumberField(
            "walk", "pickupTimeoutS", "How long Otto waits for a called ticket before he gives up.",
            30.0, 900.0, "s", true, { it.pickupTimeoutS.toDouble() }, { s, v -> s.copy(pickupTimeoutS = v.toInt()) },
        ),
        NumberField(
            "walk", "pickupRemindS", "How often Otto calls the ticket again while he waits.",
            10.0, 300.0, "s", true, { it.pickupRemindS.toDouble() }, { s, v -> s.copy(pickupRemindS = v.toInt()) },
        ),
        NumberField(
            "walk", "farWaitS", "How long Otto waits for a patient who is a few meters behind.",
            5.0, 120.0, "s", true, { it.farWaitS.toDouble() }, { s, v -> s.copy(farWaitS = v.toInt()) },
        ),
        NumberField(
            "walk", "lostCallAgainS", "Nobody near Otto: he calls the ticket again after this long.",
            3.0, 120.0, "s", true, { it.lostCallAgainS.toDouble() }, { s, v -> s.copy(lostCallAgainS = v.toInt()) },
        ),
        NumberField(
            "walk", "lostReportS", "Nobody near Otto: he tells the team after this long.",
            10.0, 300.0, "s", true, { it.lostReportS.toDouble() }, { s, v -> s.copy(lostReportS = v.toInt()) },
        ),
        NumberField(
            "walk", "lostGiveUpS", "After telling the team Otto waits this long more, then goes back.",
            10.0, 600.0, "s", true, { it.lostGiveUpS.toDouble() }, { s, v -> s.copy(lostGiveUpS = v.toInt()) },
        ),
        // ---- way ----
        NumberField(
            "way", "blockedS", "Blocked this long (in total, on one try) before Otto tries another way.",
            5.0, 180.0, "s", true, { it.blockedS.toDouble() }, { s, v -> s.copy(blockedS = v.toInt()) },
        ),
        SwitchField(
            "way", "backOff", "First detour step: back off a little and try the same way again (only when nobody is behind).",
            { it.backOff }, { s, v -> s.copy(backOff = v) },
        ),
        SwitchField(
            "way", "autoWaypoints", "Detours over other map spots that lead around the blocked spot.",
            { it.autoWaypoints }, { s, v -> s.copy(autoWaypoints = v) },
        ),
        NumberField(
            "way", "maxWaypoints", "How many waypoints Otto tries on one leg.",
            0.0, 5.0, "", true, { it.maxWaypoints.toDouble() }, { s, v -> s.copy(maxWaypoints = v.toInt()) },
        ),
        NumberField(
            "way", "helpAfterS", "Blocked this long on one leg in total: Otto calls the team (also before all detours).",
            20.0, 600.0, "s", true, { it.helpAfterS.toDouble() }, { s, v -> s.copy(helpAfterS = v.toInt()) },
        ),
        // ---- voice ----
        OptionalNumberField(
            "voice", "volume", "Speaker volume (Android music stream). Empty = leave it as set on the robot.",
            0, 100, "%", { it.volume }, { s, v -> s.copy(volume = v) },
        ),
        TagsField(
            "voice", "callLanguages", "Languages Otto calls a ticket in until the patient speaks (BCP-47 tags).",
            { it.callLanguages }, { s, v -> s.copy(callLanguages = v) },
        ),
        TextField(
            "voice", "styleNote", "An extra note for how Otto talks, added to his speaking style.",
            300, { it.styleNote }, { s, v -> s.copy(styleNote = v) },
        ),
        SwitchField(
            "voice", "hideVoiceBar", "Hide the robot's own voice bar while Otto's screen is in front.",
            { it.hideVoiceBar }, { s, v -> s.copy(hideVoiceBar = v) },
        ),
        SwitchField(
            "voice", "openMicOnWalks", "During walks Otto listens without anyone facing him (the patient walks beside him).",
            { it.openMicOnWalks }, { s, v -> s.copy(openMicOnWalks = v) },
        ),
        NumberField(
            "voice", "linePauseS", "Short pause between two things Otto says.",
            0.0, 5.0, "s", false, { it.linePauseS }, { s, v -> s.copy(linePauseS = v) },
        ),
        NumberField(
            "voice", "answerWaitS", "After a question Otto waits this long for an answer before he says anything else.",
            2.0, 30.0, "s", false, { it.answerWaitS }, { s, v -> s.copy(answerWaitS = v) },
        ),
        NumberField(
            "voice", "afterPatientS", "When the patient spoke, Otto waits this long after their last word.",
            0.0, 3.0, "s", false, { it.afterPatientS }, { s, v -> s.copy(afterPatientS = v) },
        ),
        NumberField(
            "voice", "maxWaitingLines", "How many lines may wait while Otto is talking (older or less important ones are dropped).",
            1.0, 5.0, "", true, { it.maxWaitingLines.toDouble() }, { s, v -> s.copy(maxWaitingLines = v.toInt()) },
        ),
        // ---- sensors ----
        SwitchField(
            "sensors", "radarWatch", "Radar follower watch: nobody beside Otto makes him check at once, and he skips the stop when he sees the patient beside him.",
            { it.radarWatch }, { s, v -> s.copy(radarWatch = v) },
        ),
        NumberField(
            "sensors", "mapMovingS", "Live map update while Otto moves.",
            1.0, 30.0, "s", true, { it.mapMovingS.toDouble() }, { s, v -> s.copy(mapMovingS = v.toInt()) },
        ),
        NumberField(
            "sensors", "mapIdleS", "Live map update while Otto stands.",
            5.0, 120.0, "s", true, { it.mapIdleS.toDouble() }, { s, v -> s.copy(mapIdleS = v.toInt()) },
        ),
        SwitchField(
            "sensors", "debugLogs", "Send debug log lines to the dashboard too (they always show in the staff menu).",
            { it.debugLogs }, { s, v -> s.copy(debugLogs = v) },
        ),
        // ---- screen ----
        TextField(
            "screen", "privacyLine", "The privacy line on the walk screen. Empty = none.",
            140, { it.privacyLine }, { s, v -> s.copy(privacyLine = v) },
        ),
        SwitchField(
            "screen", "talkHints", "Show \"just talk to me\" hints on the screen.",
            { it.talkHints }, { s, v -> s.copy(talkHints = v) },
        ),
        // ---- the mode (settings.mode, also the command set_mode and the staff menu) ----
        ModeField(
            "", "mode", "normal = receptionist; show = lively face, small turns, silent, no walks; calm = quiet and passive, no automatic driving.",
            { it.mode }, { s, v -> s.copy(mode = v) },
        ),
    )

    private fun norm(s: String) = s.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    private val byFull: Map<String, SettingField> = fields.associateBy { norm(it.group + it.key) }
    private val byKey: Map<String, SettingField> = fields.associateBy { norm(it.key) }

    // "walk.speedNormal", "walk.speed_normal", "walk": { "speed": { "normal" } } and a bare "speedNormal" all mean
    // the same setting; seconds may be spelled out ("firstCheckSeconds", "firstCheck")
    fun find(path: String): SettingField? {
        val n = norm(path)
        val tries = listOf(n, n.removeSuffix("seconds") + "s", n.removeSuffix("secs") + "s", n.removeSuffix("sec") + "s", n + "s")
        return tries.firstNotNullOfOrNull { byFull[it] } ?: tries.firstNotNullOfOrNull { byKey[it] }
    }

    // the answer of GET /api/robot/config. a body that is no json at all: null (the caller keeps what it has)
    fun parse(body: String?, defaults: Settings): ConfigRead? {
        val root = runCatching { JSONObject(body?.trim().orEmpty()) }.getOrNull() ?: return null
        return parse(root, defaults)
    }

    fun parse(root: JSONObject, defaults: Settings): ConfigRead {
        val version = SettingValues.number(root.opt("version"))?.takeIf { it >= 0 && it <= Int.MAX_VALUE }?.toInt()
        // { version, settings } as in the contract; a bare object of settings is read too
        val settings = root.optJSONObject("settings") ?: root
        val flat = LinkedHashMap<String, Any?>()
        flatten(settings, "", flat)
        var s = defaults
        val warnings = ArrayList<String>()
        val unknown = ArrayList<String>()
        val seen = HashSet<SettingField>()
        var fromServer = 0
        for ((path, raw) in flat) {
            if (path == "version" && settings === root) continue
            val f = find(path)
            if (f == null) {
                unknown += path
                continue
            }
            if (!seen.add(f)) continue
            if (raw == null || raw == JSONObject.NULL) {
                s = f.empty(s, defaults)
                continue
            }
            val next = f.read(raw, s)
            if (next == null) {
                warnings += "${f.path} = ${SettingValues.quote(raw)} is not ${f.range()}, using ${f.show(defaults)}"
                s = f.reset(s, defaults)
                continue
            }
            if (f is TagsField) f.dropped(raw).takeIf { it.isNotEmpty() }?.let { warnings += "${f.path}: left out ${it.joinToString()} (not a language tag)" }
            s = next
            fromServer++
        }
        // values that only make sense together
        if (!(s.speedNormal >= s.speedSlow && s.speedSlow >= s.speedVerySlow)) {
            warnings += "walk speeds must go normal >= slow >= very slow (got ${SettingValues.fmt(s.speedNormal, false)} / " +
                "${SettingValues.fmt(s.speedSlow, false)} / ${SettingValues.fmt(s.speedVerySlow, false)}), using the defaults"
            s = s.copy(speedNormal = defaults.speedNormal, speedSlow = defaults.speedSlow, speedVerySlow = defaults.speedVerySlow)
        }
        if (s.checkMinS > s.checkMaxS) {
            warnings += "walk.checkMinS (${s.checkMinS}) is longer than walk.checkMaxS (${s.checkMaxS}), using ${defaults.checkMinS} and ${defaults.checkMaxS}"
            s = s.copy(checkMinS = defaults.checkMinS, checkMaxS = defaults.checkMaxS)
        }
        if (unknown.isNotEmpty()) warnings += "unknown settings left out: ${unknown.take(8).joinToString()}" + if (unknown.size > 8) " and ${unknown.size - 8} more" else ""
        return ConfigRead(version, s, warnings, fromServer, unknown)
    }

    // nested groups become dotted paths. an object with a "value" is one setting ({ value, default, min, max })
    private fun flatten(o: JSONObject, prefix: String, out: MutableMap<String, Any?>) {
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = o.opt(k)
            val path = if (prefix.isEmpty()) k else "$prefix.$k"
            when {
                v is JSONObject && v.has("value") -> out[path] = v.opt("value")
                v is JSONObject -> flatten(v, path, out)
                else -> out[path] = v
            }
        }
    }

    // what changed between two settings, for the log ("walk.speedSlow 0.4 -> 0.35")
    fun changes(old: Settings, new: Settings): List<String> =
        fields.filter { it.show(old) != it.show(new) }.map { "${it.path} ${it.show(old)} -> ${it.show(new)}" }
}

object SettingValues {

    // numbers may come as 3, 3.0 or "3"
    fun number(raw: Any?): Double? = when (raw) {
        is Number -> raw.toDouble()
        is String -> raw.trim().toDoubleOrNull()
        else -> null
    }?.takeIf { it.isFinite() }

    fun bool(raw: Any?): Boolean? = when (raw) {
        is Boolean -> raw
        is Number -> when (raw.toDouble()) {
            1.0 -> true
            0.0 -> false
            else -> null
        }
        is String -> when (raw.trim().lowercase(Locale.ROOT)) {
            "true", "1", "on", "yes" -> true
            "false", "0", "off", "no" -> false
            else -> null
        }
        else -> null
    }

    fun fmt(v: Double, whole: Boolean): String =
        if (whole || v == Math.floor(v)) v.toLong().toString() else String.format(Locale.ROOT, "%.2f", v).trimEnd('0').trimEnd('.')

    fun quote(raw: Any?): String = when (raw) {
        is String -> "\"${raw.take(30)}\""
        else -> raw.toString().take(30)
    }
}
