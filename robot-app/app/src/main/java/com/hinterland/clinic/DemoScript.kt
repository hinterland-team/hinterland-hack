package com.hinterland.clinic

import org.json.JSONObject
import java.util.Locale

// demo mode (docs/demo-mode.md): a fixed, rehearsed run for the judges. otto only says the script's lines word
// for word (text-to-speech, no llm, the mic is off); the "patient" is a team member who knows their lines.
// the script and its parsing, the placeholders, the patient's wait, the step by step player, the walk's cues,
// where the places are and the heartbeat state are plain kotlin, unit tested. Demo runs it on the robot.

enum class DemoWho(val wire: String) {
    OTTO("otto"),
    PATIENT("patient"),
    ACTION("action"),
    ;

    companion object {
        fun of(raw: String?): DemoWho? = entries.firstOrNull { it.wire == raw?.trim()?.lowercase(Locale.ROOT) }
    }
}

// what an action step does
enum class DemoAction(val wire: String) {
    // a real self check-in journey to the destination; {ticket} is set from then on
    CHECK_IN("check_in"),
    // the walking pace (value normal | slow | very_slow)
    PACE("pace"),
    // until someone is within DemoScripts.CLOSE_M by camera or radar, at most WAIT_CLOSE_MS, or Next
    WAIT_CLOSE("wait_close"),
    ;

    companion object {
        fun of(raw: String?): DemoAction? = entries.firstOrNull { it.wire == raw?.trim()?.lowercase(Locale.ROOT) }
    }
}

// one step. otto: says text, then waits waitS (null = voice.linePauseS). patient: otto is silent for waitS
// (null = 1.2 s + 0.4 s per word). action: what it does (pace = the value of a pace step)
data class DemoStep(
    val who: DemoWho,
    val text: String = "",
    val action: DemoAction? = null,
    val pace: Pace? = null,
    val waitS: Double? = null,
)

// how the reception part starts: someone in front of otto (camera) or the operator's Next only
enum class DemoStart(val wire: String) {
    PERSON("person"),
    NEXT("next"),
    ;

    companion object {
        fun of(raw: String?): DemoStart? = entries.firstOrNull { it.wire == raw?.trim()?.lowercase(Locale.ROOT) }
    }
}

object DemoPart {
    const val RECEPTION = "reception"
    const val FIRST_CHECK = "firstCheck"
    const val FELL_BACK = "fellBack"
    const val ARRIVE = "arrive"
    const val OBSTACLE = "obstacle"
    const val DETOUR = "detour"

    val ALL = listOf(RECEPTION, FIRST_CHECK, FELL_BACK, ARRIVE, OBSTACLE, DETOUR)

    // the walk's two cues, in the order Next plays them when neither ran yet
    val WALK = listOf(FIRST_CHECK, FELL_BACK)
}

// walkSide = the patient's side on the demo walk (walk.walkSide is not used in demo runs), glanceDeg = the firstCheck
// turn towards it and back (0 = none), fellBackAfterS = driving after the firstCheck part until fellBack,
// closeMaxS = the longest wait_close
data class DemoScript(
    val destination: String,
    val reception: String,
    val start: DemoStart = DemoStart.PERSON,
    val personM: Double = DemoScripts.PERSON_M,
    val parts: Map<String, List<DemoStep>> = emptyMap(),
    val version: Int = 1,
    val walkSide: WalkSide = WalkSide.RIGHT,
    val glanceDeg: Double = DemoScripts.GLANCE_DEG,
    val fellBackAfterS: Double = DemoScripts.FELL_BACK_AFTER_S,
    val closeMaxS: Double = DemoScripts.CLOSE_MAX_S,
) {
    fun part(name: String): List<DemoStep> = parts[name].orEmpty()

    fun has(name: String): Boolean = part(name).isNotEmpty()

    // the pace the reception part settles on: the check-in sends it along, so the dashboard shows it. null = none
    val pace: Pace? get() = part(DemoPart.RECEPTION).firstOrNull { it.action == DemoAction.PACE }?.pace

    // the first patient line of a part, for the teleprompter before the part starts
    fun firstPatientLine(name: String): String? = part(name).firstOrNull { it.who == DemoWho.PATIENT }?.text
}

// a script read from json: what to use, what was wrong with it, and whether the built-in one took over
data class DemoScriptRead(val script: DemoScript, val warnings: List<String>, val builtIn: Boolean)

object DemoScripts {
    const val DESTINATION = "Dr. Green"
    const val RECEPTION = "Reception Point"
    const val PERSON_M = 1.2
    const val MIN_PERSON_M = 0.5
    const val MAX_PERSON_M = 3.0
    // the demo walk (docs/demo-mode.md flow 4): the defaults and the ranges of the script's fields
    const val GLANCE_DEG = 60.0
    const val MAX_GLANCE_DEG = 90.0
    const val FELL_BACK_AFTER_S = 6.0
    const val MIN_FELL_BACK_AFTER_S = 2.0
    const val MAX_FELL_BACK_AFTER_S = 30.0
    const val CLOSE_MAX_S = 8.0
    const val MIN_CLOSE_MAX_S = 3.0
    const val MAX_CLOSE_MAX_S = 20.0
    // wait_close: someone this close beside (radar) or in front (camera), this long without a break
    const val CLOSE_M = 1.5
    const val CLOSE_HOLD_MS = 1_000L
    // a waitS longer than this is a typo
    const val MAX_WAIT_S = 120.0
    const val MAX_TEXT = 300

    // the built-in script, word for word as in docs/demo-mode.md
    const val DEFAULT_JSON = """
{
  "version": 1,
  "destination": "Dr. Green",
  "reception": "Reception Point",
  "start": "person",
  "personM": 1.2,
  "walkSide": "right",
  "glanceDeg": 60,
  "fellBackAfterS": 6,
  "closeMaxS": 8,
  "parts": {
    "reception": [
      { "who": "otto", "text": "Hello, I'm Otto. How can I help you? Do you have an appointment?" },
      { "who": "patient", "text": "Yes, I have an appointment with Dr. Green." },
      { "who": "action", "do": "check_in" },
      { "who": "otto", "text": "Thank you. You're checked in for Dr. Green." },
      { "who": "otto", "text": "Your ticket number is {ticket}. I only use this number, never your name." },
      { "who": "otto", "text": "Shall I walk at a normal pace, or a little slower?" },
      { "who": "patient", "text": "A little slower, please." },
      { "who": "action", "do": "pace", "value": "slow" },
      { "who": "otto", "text": "Of course. Please walk on my right. Let's go!" }
    ],
    "firstCheck": [
      { "who": "otto", "text": "Is everything okay? Am I too fast?" },
      { "who": "patient", "text": "No, it's perfect, thank you." },
      { "who": "otto", "text": "Great, let's go on." }
    ],
    "fellBack": [
      { "who": "otto", "text": "I'll wait for you. Take your time." },
      { "who": "action", "do": "wait_close" },
      { "who": "patient", "text": "Sorry, here I am." },
      { "who": "otto", "text": "No problem. We're almost there." }
    ],
    "arrive": [
      { "who": "otto", "text": "Here we are, this is Dr. Green's room. Please take a seat. Dr. Green's team knows you're here." },
      { "who": "patient", "text": "Thank you, Otto!" },
      { "who": "otto", "text": "You're welcome. Get well soon!" }
    ],
    "obstacle": [{ "who": "otto", "text": "Excuse me, may I pass?" }],
    "detour": [{ "who": "otto", "text": "This way is blocked. Let's take another way." }]
  }
}
"""

    val default: DemoScript by lazy { read(JSONObject(DEFAULT_JSON), ArrayList()) ?: error("the built-in demo script does not read") }

    // a script from the dashboard. a broken one (no json object, no parts, not one usable step) falls back to the
    // built-in script; missing parts are skipped, unknown fields ignored, a bad value warns and takes the default
    fun parse(raw: String?): DemoScriptRead {
        val o = runCatching { JSONObject(raw?.trim().orEmpty()) }.getOrNull()
            ?: return DemoScriptRead(default, listOf("the script is not a json object, using the built-in one"), builtIn = true)
        return parse(o)
    }

    fun parse(o: JSONObject): DemoScriptRead {
        val warnings = ArrayList<String>()
        val s = read(o, warnings) ?: return DemoScriptRead(default, warnings + "using the built-in script", builtIn = true)
        return DemoScriptRead(s, warnings, builtIn = false)
    }

    // null = broken
    private fun read(o: JSONObject, warnings: MutableList<String>): DemoScript? {
        val parts = o.optJSONObject("parts")
        if (parts == null) {
            warnings += "the script has no parts"
            return null
        }
        val read = LinkedHashMap<String, List<DemoStep>>()
        for (name in DemoPart.ALL) {
            val arr = parts.optJSONArray(name) ?: continue
            val steps = ArrayList<DemoStep>()
            for (i in 0 until arr.length()) {
                step(arr.optJSONObject(i)) { why -> warnings += "$name step ${i + 1}: $why" }?.let { steps += it }
            }
            if (steps.isNotEmpty()) read[name] = steps
        }
        if (read.isEmpty()) {
            warnings += "the script has no usable step"
            return null
        }
        val destination = o.text("destination") ?: DESTINATION.also { warnings += "no destination, using $it" }
        val reception = o.text("reception") ?: RECEPTION.also { warnings += "no reception, using $it" }
        val start = when {
            !o.has("start") || o.isNull("start") -> DemoStart.PERSON
            else -> DemoStart.of(o.optString("start")) ?: DemoStart.PERSON.also { warnings += "start must be person or next, using person" }
        }
        val personM = number(o, "personM", PERSON_M, MIN_PERSON_M, MAX_PERSON_M, "m", warnings)
        val walkSide = when {
            !o.has("walkSide") || o.isNull("walkSide") -> WalkSide.RIGHT
            else -> when (o.text("walkSide")?.lowercase(Locale.ROOT)) {
                "right" -> WalkSide.RIGHT
                "left" -> WalkSide.LEFT
                else -> WalkSide.RIGHT.also { warnings += "walkSide must be right or left, using right" }
            }
        }
        val glanceDeg = number(o, "glanceDeg", GLANCE_DEG, 0.0, MAX_GLANCE_DEG, "deg", warnings)
        val fellBackAfterS = number(o, "fellBackAfterS", FELL_BACK_AFTER_S, MIN_FELL_BACK_AFTER_S, MAX_FELL_BACK_AFTER_S, "s", warnings)
        val closeMaxS = number(o, "closeMaxS", CLOSE_MAX_S, MIN_CLOSE_MAX_S, MAX_CLOSE_MAX_S, "s", warnings)
        val version = SettingValues.number(o.opt("version"))?.toInt() ?: 1
        return DemoScript(destination, reception, start, personM, read, version, walkSide, glanceDeg, fellBackAfterS, closeMaxS)
    }

    // a number in its range; missing = the default, anything else = the default with a warning
    private fun number(o: JSONObject, key: String, default: Double, min: Double, max: Double, unit: String, warnings: MutableList<String>): Double {
        if (!o.has(key) || o.isNull(key)) return default
        return SettingValues.number(o.opt(key))?.takeIf { it in min..max }
            ?: default.also { warnings += "$key must be ${fmt(min)}-${fmt(max)} $unit, using ${fmt(it)}" }
    }

    private fun fmt(v: Double) = SettingValues.fmt(v, whole = false)

    private fun step(o: JSONObject?, problem: (String) -> Unit): DemoStep? {
        if (o == null) {
            problem("left out, not an object")
            return null
        }
        val who = DemoWho.of(o.text("who"))
        if (who == null) {
            problem("left out, who must be otto, patient or action")
            return null
        }
        // a bad waitS keeps the step with the default wait
        val waitS = if (!o.has("waitS") || o.isNull("waitS")) {
            null
        } else {
            SettingValues.number(o.opt("waitS"))?.takeIf { it in 0.0..MAX_WAIT_S }
                ?: null.also { problem("waitS must be 0-${MAX_WAIT_S.toInt()} s, the step keeps the default wait") }
        }
        return when (who) {
            DemoWho.OTTO, DemoWho.PATIENT -> {
                val text = o.text("text")
                when {
                    text == null -> null.also { problem("left out, no text") }
                    text.length > MAX_TEXT -> null.also { problem("left out, the text is longer than $MAX_TEXT characters") }
                    else -> DemoStep(who, text, waitS = waitS)
                }
            }
            DemoWho.ACTION -> {
                val action = DemoAction.of(o.text("do"))
                when {
                    action == null -> null.also { problem("left out, do must be check_in, pace or wait_close") }
                    action == DemoAction.PACE -> Pace.of(o.text("value"))?.let { DemoStep(who, action = action, pace = it, waitS = waitS) }
                        ?: null.also { problem("left out, the pace value must be normal, slow or very_slow") }
                    else -> DemoStep(who, action = action, waitS = waitS)
                }
            }
        }
    }

    // a string value, whitespace folded; null for missing, json null and blank
    private fun JSONObject.text(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).replace(Regex("\\s+"), " ").trim().takeIf { it.isNotEmpty() }
}

object DemoText {
    const val PATIENT_BASE_S = 1.2
    const val PATIENT_PER_WORD_S = 0.4
    private val space = Regex("\\s+")

    // {ticket} and {place} filled in; a ticket that is not there yet leaves nothing behind
    fun fill(text: String, ticket: String?, place: String): String =
        text.replace("{ticket}", ticket.orEmpty()).replace("{place}", place).replace(space, " ").trim()

    fun words(text: String): Int = text.split(space).count { w -> w.any { it.isLetterOrDigit() } }

    // how long otto is silent for the patient's line: waitS, else 1.2 s + 0.4 s per word
    fun patientWaitMs(step: DemoStep): Long = Settings.ms(step.waitS ?: (PATIENT_BASE_S + PATIENT_PER_WORD_S * words(step.text)))
}

// a person stands in front of otto: someone within personM for holdMs without a break. only the camera's
// presence and distance, no face, no id, nothing kept. a gap up to graceMs (the camera drops a frame) is no break
class PresenceWatch(
    private val personM: Double,
    private val holdMs: Long = HOLD_MS,
    private val graceMs: Long = GRACE_MS,
) {
    private var since: Long? = null
    private var lastSeen = 0L

    // nearestM = the closest person the camera sees now, null = nobody (or no camera). true once someone stood there long enough
    fun seen(now: Long, nearestM: Double?): Boolean {
        val near = nearestM != null && nearestM > 0.0 && nearestM <= personM
        if (!near) {
            if (since != null && now - lastSeen > graceMs) since = null
            return false
        }
        val from = since?.takeIf { now - lastSeen <= graceMs } ?: now
        since = from
        lastSeen = now
        return now - from >= holdMs
    }

    fun reset() {
        since = null
        lastSeen = 0L
    }

    companion object {
        const val HOLD_MS = 1_000L
        const val GRACE_MS = 500L
    }
}

// what the robot does next while a part plays (DemoPlayer.next)
sealed interface DemoDo {
    // say this line, then call said()
    data class Say(val text: String) : DemoDo
    // nothing until untilMs, or Next (the pause after a line, the patient's line)
    data class Wait(val untilMs: Long) : DemoDo
    // do this action, then call done()
    data class Act(val step: DemoStep) : DemoDo
    // the operator holds the demo: nothing until resume
    data object Held : DemoDo
    // the part is over
    data object Done : DemoDo
}

// one part, step by step. the time comes in; Next ends a wait at once, a hold stops before the next step.
// any thread: the run asks next(), the team's commands call skip()
class DemoPlayer(
    val part: String,
    private val steps: List<DemoStep>,
    private val pauseMs: Long,
    private val fill: (String) -> String = { it },
) {
    private enum class Stage { NEW, SAYING, WAITING, ACTING }

    private var stage = Stage.NEW
    private var until = 0L

    // the step that runs now, 0-based (= total once the part is over)
    var index = 0
        private set
    val total: Int get() = steps.size

    // the line otto says now or said last in this part, filled in
    @Volatile var line: String? = null
        private set

    @Synchronized
    fun next(now: Long, held: Boolean = false): DemoDo {
        while (true) {
            when (stage) {
                Stage.NEW -> {
                    val s = steps.getOrNull(index) ?: return DemoDo.Done
                    if (held) return DemoDo.Held
                    return when (s.who) {
                        DemoWho.OTTO -> {
                            stage = Stage.SAYING
                            val t = fill(s.text)
                            line = t
                            DemoDo.Say(t)
                        }
                        DemoWho.PATIENT -> {
                            stage = Stage.WAITING
                            until = now + DemoText.patientWaitMs(s)
                            DemoDo.Wait(until)
                        }
                        DemoWho.ACTION -> {
                            stage = Stage.ACTING
                            DemoDo.Act(s)
                        }
                    }
                }
                // the line or the action is still under way
                Stage.SAYING, Stage.ACTING -> return DemoDo.Wait(now + BUSY_MS)
                Stage.WAITING -> {
                    if (now < until) return DemoDo.Wait(until)
                    advance()
                }
            }
        }
    }

    // otto's line is over: the pause after it (waitS, else voice.linePauseS)
    @Synchronized
    fun said(now: Long) {
        if (stage != Stage.SAYING) return
        stage = Stage.WAITING
        until = now + (steps[index].waitS?.let { Settings.ms(it) } ?: pauseMs)
    }

    // the action is over
    @Synchronized
    fun done() {
        if (stage == Stage.ACTING) advance()
    }

    // Next: the wait that runs now ends at once (a patient's line, the pause after a line). false = nothing waits
    @Synchronized
    fun skip(now: Long): Boolean {
        if (stage != Stage.WAITING || now >= until) return false
        until = now
        return true
    }

    // the 1-based step for the heartbeat
    @Synchronized
    fun stepNo(): Int = (index + 1).coerceAtMost(steps.size)

    // what otto waits for now: the patient's line, someone close again (wait_close), null = he talks or acts
    @Synchronized
    fun waitingFor(): String? {
        val s = steps.getOrNull(index) ?: return null
        return when {
            stage == Stage.WAITING && s.who == DemoWho.PATIENT -> DemoWait.PATIENT
            stage == Stage.ACTING && s.action == DemoAction.WAIT_CLOSE -> DemoWait.CLOSE
            else -> null
        }
    }

    // the patient line that is awaited now or comes next in this part, filled in
    @Synchronized
    fun nextPatientLine(): String? = steps.drop(index).firstOrNull { it.who == DemoWho.PATIENT }?.let { fill(it.text) }

    private fun advance() {
        index++
        stage = Stage.NEW
    }

    companion object {
        const val BUSY_MS = 250L
    }
}

// the demo walk as a fixed routine (docs/demo-mode.md flow 4; field test b16: the radar took walls and other
// people for the patient, so nothing here depends on it). firstCheck after firstCheckMs of driving, fellBack
// fellBackAfterS of driving after the firstCheck part, each once; Next plays the next one at once. driving =
// navigation time while not blocked (a stop, a hold or an obstacle does not count)
class DemoWalkPlan(private val script: DemoScript, private val firstCheckMs: Long) {
    private val played = HashSet<String>()
    private var driven = 0L
    private var lastTick: Long? = null
    // the driving time when the firstCheck part was played (or its moment passed without one in the script)
    private var firstDoneAt: Long? = null

    val fellBackAfterMs: Long get() = Settings.ms(script.fellBackAfterS)

    // every quarter second while the navigation runs
    @Synchronized
    fun tick(now: Long, blocked: Boolean) {
        val last = lastTick
        lastTick = now
        // a long gap (a paused thread) never counts as seconds of driving
        if (last != null && !blocked) driven += (now - last).coerceIn(0, MAX_STEP_MS)
    }

    // the navigation stopped: the next tick starts a new stretch
    @Synchronized
    fun pause() {
        lastTick = null
    }

    // the walk part that is due now, null = none
    @Synchronized
    fun due(): String? {
        if (DemoPart.FIRST_CHECK !in played) {
            if (driven < firstCheckMs) return null
            if (script.has(DemoPart.FIRST_CHECK)) return DemoPart.FIRST_CHECK
            // no firstCheck in the script: its moment passes, fellBack counts from here
            played(DemoPart.FIRST_CHECK)
        }
        val from = firstDoneAt ?: return null
        if (DemoPart.FELL_BACK in played || !script.has(DemoPart.FELL_BACK)) return null
        return DemoPart.FELL_BACK.takeIf { driven - from >= fellBackAfterMs }
    }

    // played (or skipped): it does not come again
    @Synchronized
    fun played(part: String) {
        played += part
        if (part == DemoPart.FIRST_CHECK && firstDoneAt == null) firstDoneAt = driven
    }

    @Synchronized
    fun wasPlayed(part: String): Boolean = part in played

    // Next during the walk with no part running: the walk part not played yet (firstCheck, then fellBack)
    @Synchronized
    fun nextByHand(): String? = DemoPart.WALK.firstOrNull { script.has(it) && it !in played }

    @Synchronized
    fun drivenMs(): Long = driven

    companion object {
        private const val MAX_STEP_MS = 2_000L
    }
}

// a guide command during a demo run (field test b16, run 1: the server queues the demo's walk before it answers
// the check-in, the quick demo poll got it first, refused it, and the server failed the leg). it is the demo's
// own when it is the check-in's command, journey or ticket, or when it comes while the check-in call runs (at
// most RACE_MS): never refused then
object DemoGuides {
    const val RACE_MS = 5_000L

    fun ours(cmd: RobotCommand, commandId: String?, journeyId: String?, ticket: String?, checkInSince: Long?, now: Long): Boolean = when {
        cmd.type != RobotCommand.GUIDE -> false
        commandId != null && cmd.id == commandId -> true
        journeyId != null && cmd.journeyId == journeyId -> true
        ticket != null && Tickets.same(cmd.ticket, ticket) -> true
        checkInSince != null && now - checkInSince in 0..RACE_MS -> true
        else -> false
    }
}

// where a script's place is (destination, reception): the dashboard's place by name (any case) or id, then by
// its spot; else the map spot with that name (no places from the dashboard: empty or not reachable)
data class DemoPlace(val spot: String, val label: String, val place: Place?) {
    // the walk's destination
    fun stop(): Stop = place?.let { Stop(it.name, it.spot, it.room, it.kind, it.viaSpot, it.mapName) } ?: Stop(label, spot)
}

object DemoPlaces {
    fun resolve(name: String, places: List<Place>): DemoPlace {
        val n = name.trim()
        val active = places.filter { it.active }
        val p = active.firstOrNull { it.name.trim().equals(n, ignoreCase = true) }
            ?: active.firstOrNull { it.id == n }
            ?: active.firstOrNull { it.spot.trim().equals(n, ignoreCase = true) }
        return if (p != null) DemoPlace(p.spot.trim(), p.label, p) else DemoPlace(n, n, null)
    }
}

// what the demo waits for (the heartbeat's waitingFor)
object DemoWait {
    const val RECEPTION = "reception"
    const val PERSON = "person"
    const val PATIENT = "patient"
    const val CLOSE = "close"
    const val WALK = "walk"
    const val HOLD = "hold"
    const val BACK = "back"
}

// the heartbeat's demo object (docs/demo-mode.md). line = what otto says or said last, nextPatientLine = the
// patient line that comes next or is awaited now, runs = finished runs since the demo started
data class DemoState(
    val running: Boolean = false,
    val part: String? = null,
    val step: Int = 0,
    val total: Int = 0,
    val waitingFor: String? = null,
    val line: String? = null,
    val nextPatientLine: String? = null,
    val ticket: String? = null,
    val runs: Int = 0,
)
