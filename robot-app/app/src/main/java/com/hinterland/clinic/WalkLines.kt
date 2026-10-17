package com.hinterland.clinic

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.Locale

// the walk's short lines (v3.3, docs/journeys.md 6.7): written once per walk by the llm in the patient's
// language (one call, in otto's own voice: Voice.STYLE), so the stops only need text-to-speech and never
// wait for the llm. every line has a fixed english fallback for when the llm fails or is slow. the lines
// only ever mention the ticket, the destination and the walk side: no names, nothing the patient said.
// plain kotlin, unit tested.

// the side the patient walks on, seen from otto (local.properties robot.walkSide, default left)
enum class WalkSide(val wire: String) {
    LEFT("left"),
    RIGHT("right"),
    ;

    // robot frame: + = left
    val sign: Int get() = if (this == LEFT) 1 else -1

    companion object {
        fun of(raw: String?): WalkSide = if (raw?.trim()?.lowercase(Locale.ROOT) == "right") RIGHT else LEFT
    }
}

enum class Arrival { ROOM, WAITING, EXIT }

// what one walk's lines may mention
data class LineContext(
    val ticket: String,
    val destination: String,
    val side: WalkSide = WalkSide.LEFT,
    val arrival: Arrival = Arrival.ROOM,
    // the patient asked at the robot: the start line also tells them their ticket
    val ownRequest: Boolean = false,
)

enum class LineKey {
    START, PACE_QUESTION, CHECK_OK, ASK, ASK_ALMOST, GO_ON, SIDE_REMINDER, WAIT_FOR_YOU, THANKS_SLOWER,
    CALL_OUT, FOUND_AGAIN, HALF_WAY, ALMOST_THERE, ARRIVED, SLOWER_OK, FASTER_OK, PAUSE_OK, EXCUSE_ME,
    // a blocked way (Detours.kt): stepping back, another way, the team is called, going back
    BACK_UP, DETOUR, BLOCKED_HELP, GOING_BACK,
}

object LineSet {
    const val MAX_CHARS = 220
    // fewer good lines than this: the answer is not trusted, english for the walk
    const val MIN_LINES = 6
    const val MAX_TOKENS = 1_000

    private fun ticketOr(c: LineContext) = c.ticket.ifBlank { "your ticket" }

    // the fixed english line (also the example for the llm). calm, hardly any exclamation marks (Voice.STYLE)
    fun english(key: LineKey, c: LineContext): String = when (key) {
        LineKey.START -> buildString {
            if (c.ownRequest && c.ticket.isNotBlank()) append("Your ticket is ${c.ticket}. ")
            append("Please walk next to me, on my ${c.side.wire} side, a little behind me. We're going to ${c.destination}.")
        }
        LineKey.PACE_QUESTION -> "Shall I walk normally, or a bit slower?"
        LineKey.CHECK_OK -> "Good. Let's keep going."
        LineKey.ASK -> "All good? Shall I slow down?"
        LineKey.ASK_ALMOST -> "All good? We're almost there."
        LineKey.GO_ON -> "Alright, let's go on."
        LineKey.SIDE_REMINDER -> "There you are. Please stay next to me, on my ${c.side.wire} side."
        LineKey.WAIT_FOR_YOU -> "I'll wait for you. Take your time."
        LineKey.THANKS_SLOWER -> "Thank you. I'll walk a little slower now."
        LineKey.CALL_OUT -> "Ticket ${ticketOr(c)}, I'm over here."
        LineKey.FOUND_AGAIN -> "There you are. I'll walk a little slower now."
        LineKey.HALF_WAY -> "We're half way there."
        LineKey.ALMOST_THERE -> "We're almost there."
        LineKey.ARRIVED -> when (c.arrival) {
            Arrival.EXIT -> "Here is the exit. Goodbye, and get well soon."
            Arrival.WAITING -> "Please take a seat here. I'll come for ticket ${ticketOr(c)} when it's your turn."
            Arrival.ROOM -> "This is ${c.destination}. Please take a seat, the team knows you're here."
        }
        LineKey.SLOWER_OK -> "Alright, I'll walk a bit slower."
        LineKey.FASTER_OK -> "Alright, I'll walk a bit faster."
        LineKey.PAUSE_OK -> "Of course. I'll wait right here, just tell me when you're ready."
        LineKey.EXCUSE_ME -> "Excuse me, may I pass, please?"
        LineKey.BACK_UP -> "Let me step back a little. Please mind your feet."
        LineKey.DETOUR -> "This way is blocked. Let's take another way."
        LineKey.BLOCKED_HELP -> "This way is blocked. I've asked the team for help, please wait here with me."
        LineKey.GOING_BACK -> "I'm going back to the reception now. The team will help you."
    }

    // what the line has to say, for the llm
    fun task(key: LineKey, c: LineContext): String = when (key) {
        LineKey.START -> buildString {
            if (c.ownRequest && c.ticket.isNotBlank()) append("Tell the patient their ticket is ${c.ticket}. ")
            append(
                "Ask them to walk next to you on your ${c.side.wire} side, a little behind you, so you can see them, " +
                    "and say you are going to ${c.destination} together.",
            )
        }
        LineKey.PACE_QUESTION -> "Ask in one short question whether you should walk normally or a bit slower."
        LineKey.CHECK_OK -> "The patient is right with you: say very briefly and warmly that all is fine and you keep going."
        LineKey.ASK -> "Ask the patient in one short yes/no question if all is good and whether you should slow down."
        LineKey.ASK_ALMOST -> "Ask in one short yes/no question if all is good, and say you are almost there."
        LineKey.GO_ON -> "Say very briefly that you walk on now."
        LineKey.SIDE_REMINDER -> "You see the patient again: say so briefly and ask them to stay next to you on your ${c.side.wire} side."
        LineKey.WAIT_FOR_YOU -> "The patient fell behind: say kindly that you wait for them."
        LineKey.THANKS_SLOWER -> "Thank the patient for catching up and say you will walk a little slower now."
        LineKey.CALL_OUT -> "Call out for the patient with ticket ${ticketOr(c)}: you are over here."
        LineKey.FOUND_AGAIN -> "Say you are glad to see the patient again and that you will walk a little slower now."
        LineKey.HALF_WAY -> "Say briefly and cheerfully that you are half way to ${c.destination}."
        LineKey.ALMOST_THERE -> "Say briefly and warmly that you are almost at ${c.destination}."
        LineKey.ARRIVED -> when (c.arrival) {
            Arrival.EXIT -> "You arrived at the exit with the patient: say goodbye warmly and wish them to get well soon."
            Arrival.WAITING ->
                "You arrived at the waiting area ${c.destination}: ask them to take a seat here, you or the team will " +
                    "come for ticket ${ticketOr(c)} when it is their turn."
            Arrival.ROOM ->
                "You arrived at ${c.destination}: say this is ${c.destination}, they can take a seat, the team knows they are here."
        }
        LineKey.SLOWER_OK -> "Confirm in a few warm words that you walk a bit slower now."
        LineKey.FASTER_OK -> "Confirm in a few warm words that you walk a bit faster now."
        LineKey.PAUSE_OK -> "Say: of course, you wait right here, they can tell you when they are ready."
        LineKey.EXCUSE_ME -> "Politely ask the people in your way to let you pass."
        LineKey.BACK_UP -> "Say you will step back a little, and ask the patient kindly to mind their feet."
        LineKey.DETOUR -> "Tell the patient kindly that this way is blocked and you will take another way."
        LineKey.BLOCKED_HELP ->
            "Tell the patient kindly that the way is blocked, you asked the team for help, and they should please wait here with you."
        LineKey.GOING_BACK -> "Say briefly that you go back to the reception now and the team will help them."
    }

    // the same voice as everything else otto says
    const val SYSTEM =
        "You write the short lines Otto, the guide robot of a doctor's practice, says out loud while he walks a patient " +
            "to a room. ${Voice.STYLE} Each line is one or two very short sentences. A patient is only a ticket number, " +
            "never a name. No medical advice, no symptoms, no quotation marks inside the lines, no stage directions."

    // the user message: the language, then one line per key with what to say and the english example
    fun prompt(c: LineContext, lang: Lang): String = buildString {
        append(lang.instruction())
        append(" Write every line in that language.\n")
        append("Answer with one JSON object and nothing else. Use exactly these keys; each value is the spoken text.\n")
        LineKey.entries.forEach { k ->
            append(k.name).append(": ").append(task(k, c))
            append(" (English example: ").append(english(k, c)).append(")\n")
        }
    }

    // the llm's answer -> the lines that look right (one line of text each, not too long). a code fence or
    // words around the object are fine; unknown keys and broken values are left out
    fun parse(raw: String?): Map<LineKey, String> {
        val t = raw?.trim().orEmpty()
        val from = t.indexOf('{')
        val to = t.lastIndexOf('}')
        if (from < 0 || to <= from) return emptyMap()
        val o = runCatching { JSONObject(t.substring(from, to + 1)) }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<LineKey, String>()
        for (k in LineKey.entries) {
            val v = o.opt(k.name) as? String ?: continue
            clean(v)?.let { out[k] = it }
        }
        return out
    }

    private val space = Regex("\\s+")

    private fun clean(v: String): String? {
        val s = v.replace(space, " ").trim().trim('"', '“', '”', '„').trim()
        if (s.isEmpty() || s.length > MAX_CHARS) return null
        // an echoed placeholder or a nested object is no line
        if (s.contains('{') || s.contains('}')) return null
        return s
    }
}

// one walk's lines: the llm's version once it is there, else the english one. any thread
class WalkLines(val context: LineContext) {
    @Volatile private var lines: Map<LineKey, String>? = null
    private val settled = MutableStateFlow(false)

    // the lines were written from the patient's own words (not the default call language)
    @Volatile var fromWords = false
        private set

    val ready: Boolean get() = lines != null

    fun text(key: LineKey): String = lines?.get(key) ?: LineSet.english(key, context)

    // true when this line is in the patient's language
    fun localized(key: LineKey): Boolean = lines?.containsKey(key) == true

    fun accept(made: Map<LineKey, String>, fromWords: Boolean) {
        lines = made
        this.fromWords = fromWords
        settled.value = true
    }

    // the llm did not deliver: english it is, nobody waits any longer
    fun failed() {
        settled.value = true
    }

    // waits up to ms for the llm's lines. true when they are there
    suspend fun await(ms: Long): Boolean {
        if (ready) return true
        withTimeoutOrNull(ms) { settled.first { it } }
        return ready
    }
}
