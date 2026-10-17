package com.hinterland.clinic

import java.util.Locale

// otto's turns when he talks (field test 17.10: after "check in for Dr. Blue at 9:40" he said five lines right
// after one another, "like vomit"). everything otto says goes through here:
//  - at most one line plays and timing.maxWaiting wait; a newer line of the same kind replaces a waiting one
//  - a line that no longer fits (the patient confirmed, the walk is over) is dropped right before it is said
//  - lines that belong together are said as one: an info line followed by a question, or lines of one topic
//  - after a question otto waits for the answer (speech or a tap) up to timing.answerWaitMs
//  - while the patient talks otto waits, and timing.afterUserMs after their last word
//  - a short pause (timing.pauseMs) between two lines; while otto's own agent talks, our lines wait
//  - show and calm mode: nothing is said at all
//  - demo mode: only script lines (the demo's own and the team's say), and those are never dropped or replaced
// plain kotlin, every call gets the time, unit tested. Speech runs it on the robot.

// priority decides who waits when the queue is full; the same kind replaces a waiting line
enum class SpeechKind(val wire: String, val priority: Int) {
    // urgent help, the walk failed or ends
    ALERT("alert", 6),
    // the team's say command
    TEAM("team", 5),
    // a line of the demo script (docs/demo-mode.md)
    SCRIPT("script", 4),
    QUESTION("question", 4),
    // calling a ticket, calling a patient who is out of sight
    CALL("call", 4),
    // the answer to what someone asked otto
    REPLY("reply", 3),
    // slower / faster / I'll wait
    PACE("pace", 3),
    ARRIVAL("arrival", 3),
    // the start of a walk, the checks, waiting, found again
    WALK("walk", 2),
    // excuse me, stepping back, another way
    WAY("way", 2),
    // going to charge, otto has to go
    INFO("info", 2),
    MILESTONE("milestone", 1),
    GREETING("greeting", 1),
}

data class TurnTiming(
    val pauseMs: Long = 800,
    val answerWaitMs: Long = 7_000,
    val afterUserMs: Long = 700,
    val maxWaiting: Int = 1,
)

enum class TurnOutcome {
    // spoken to the end
    SAID,
    // started, the patient talked over it (not said again)
    INTERRUPTED,
    // the speech did not work
    FAILED,
    // a newer line of the same kind took its place
    REPLACED,
    // it no longer fitted, or too many lines were waiting
    DROPPED,
    // show / calm mode
    SILENT,
}

// one line: ready text (said as it is), or an llm task in the patient's language with text as its fallback.
// fits is asked right before it is said. topic = lines with the same topic may be said as one
class SpeechLine(
    val kind: SpeechKind,
    val text: String,
    val task: String? = null,
    val lang: Lang? = null,
    val question: Boolean = false,
    // not held by a question or the pause (urgent help); the patient is still let finish
    val urgent: Boolean = false,
    val topic: String? = null,
    val fits: () -> Boolean = { true },
    // a demo line (the script's or the team's say in demo mode): never dropped or replaced, always fits, no pause
    // before it (the demo keeps its own pace), and in demo mode the only lines that are said
    val script: Boolean = false,
) {
    val llm: Boolean get() = task != null
}

// what otto says in one go: one turn, or several that belong together
class Utterance internal constructor(val id: Long, val queuedAt: Long) {
    internal val parts = ArrayList<Part>()

    val turns: List<SpeechLine> get() = parts.map { it.turn }
    val kinds: String get() = parts.joinToString("+") { it.turn.kind.wire }
    val priority: Int get() = parts.maxOfOrNull { it.turn.kind.priority } ?: 0
    // a question is always the last part (the join rule)
    val question: Boolean get() = parts.lastOrNull()?.turn?.question == true
    val urgent: Boolean get() = parts.any { it.turn.urgent }
    val script: Boolean get() = parts.any { it.turn.script }
    val text: String get() = parts.joinToString(" ") { it.turn.text.trim() }
    // the llm line, when this is one (llm lines are never joined)
    val llm: SpeechLine? get() = parts.singleOrNull()?.turn?.takeIf { it.llm }
}

internal class Part(val turn: SpeechLine, val done: (TurnOutcome) -> Unit)

sealed interface TurnStep {
    data object Idle : TurnStep
    // something waits; nothing may be said before untilMs
    data class Wait(val untilMs: Long, val why: String) : TurnStep
    class Play(val utterance: Utterance) : TurnStep
}

// queued = it waits (alone or joined); cut = it is urgent and the line playing now should stop for it
data class Offered(val queued: Boolean, val cut: Boolean)

class SpeechTurns(
    private val timing: () -> TurnTiming = { TurnTiming() },
    private val log: (String) -> Unit = {},
) {
    private val waiting = ArrayList<Utterance>()
    private var playing: Utterance? = null
    private var playingSince = 0L
    private var ids = 0L
    private var lastEndAt = 0L
    private var userAt = 0L
    private var userFinal = true
    private var agentUntil = 0L
    private var questionUntil = 0L

    var silent = false
        private set
    private var silentWhy = ""

    // demo mode: only script lines are said
    var scriptOnly = false
        private set

    @Synchronized
    fun offer(turn: SpeechLine, now: Long, done: (TurnOutcome) -> Unit = {}): Offered {
        if (silent) {
            log("Not said (${turn.kind.wire}, $silentWhy): ${short(turn)}")
            done(TurnOutcome.SILENT)
            return Offered(queued = false, cut = false)
        }
        if (scriptOnly && !turn.script) {
            log("Not said (${turn.kind.wire}, demo mode: only the script's lines): ${short(turn)}")
            done(TurnOutcome.SILENT)
            return Offered(queued = false, cut = false)
        }
        val part = Part(turn, done)
        // a newer line of the same kind takes the place of the waiting one (script lines never take or give a place)
        if (!turn.script) {
            for (u in waiting) {
                val old = u.parts.filter { it.turn.kind == turn.kind && !it.turn.script }
                old.forEach { p ->
                    u.parts.remove(p)
                    log("Replaced ${p.turn.kind.wire} by a newer one: ${short(p.turn)}")
                    p.done(TurnOutcome.REPLACED)
                }
            }
        }
        waiting.removeAll { it.parts.isEmpty() }
        val cut = turn.urgent && (playing?.let { it.priority < turn.kind.priority } == true)
        // it belongs to the line that waits last: one utterance
        val last = waiting.lastOrNull()
        if (last != null && canJoin(last, turn)) {
            last.parts += part
            log("Merged ${turn.kind.wire} into ${last.kinds.substringBeforeLast('+')}: \"${clip(last.text)}\"")
            return Offered(queued = true, cut = cut)
        }
        val cap = timing().maxWaiting.coerceAtLeast(1)
        if (waiting.size >= cap) {
            // script lines are never dropped: the lowest other line goes, or the new one when it is no script line
            val lowest = waiting.filter { !it.script }.sortedWith(compareBy<Utterance> { it.priority }.thenBy { it.queuedAt }).firstOrNull()
            if (lowest != null && (turn.script || turn.kind.priority >= lowest.priority)) {
                waiting.remove(lowest)
                lowest.parts.forEach { p ->
                    log("Dropped ${p.turn.kind.wire}: a newer or more important line came (${turn.kind.wire}): ${short(p.turn)}")
                    p.done(TurnOutcome.DROPPED)
                }
            } else if (!turn.script) {
                log("Dropped ${turn.kind.wire}: ${waiting.size} line${if (waiting.size == 1) "" else "s"} waiting already (max $cap): ${short(turn)}")
                done(TurnOutcome.DROPPED)
                return Offered(queued = false, cut = false)
            }
        }
        waiting += Utterance(++ids, now).also { it.parts += part }
        return Offered(queued = true, cut = cut)
    }

    // what to do now: say the next line, wait until a time, or nothing is waiting
    @Synchronized
    fun next(now: Long): TurnStep {
        if (playing != null) return TurnStep.Wait(now + BUSY_MS, "a line is playing")
        // what no longer fits goes first
        val each = waiting.iterator()
        while (each.hasNext()) {
            val u = each.next()
            val stale = u.parts.filter { p -> !p.turn.script && !runCatching { p.turn.fits() }.getOrDefault(false) }
            stale.forEach { p ->
                u.parts.remove(p)
                log("Dropped ${p.turn.kind.wire}: no longer fits: ${short(p.turn)}")
                p.done(TurnOutcome.DROPPED)
            }
            if (u.parts.isEmpty()) each.remove()
        }
        if (waiting.isEmpty()) return TurnStep.Idle
        val head = waiting.sortedWith(compareByDescending<Utterance> { it.priority }.thenBy { it.queuedAt }).first()
        val t = timing()
        var until = 0L
        var why = ""
        fun hold(at: Long, reason: String) {
            if (at > until) {
                until = at
                why = reason
            }
        }
        // the patient talks: their turn first, also before urgent lines
        if (userAt > 0) hold(userAt + (if (userFinal) t.afterUserMs else PARTIAL_GAP_MS + t.afterUserMs), "the patient is talking")
        if (!head.urgent && !head.script) {
            if (agentUntil > 0) hold(agentUntil + t.pauseMs, "Otto's agent is talking")
            if (questionUntil > 0) hold(questionUntil, "waiting for an answer")
            if (lastEndAt > 0) hold(lastEndAt + t.pauseMs, "a short pause")
        }
        if (until > now) return TurnStep.Wait(until, why)
        waiting.remove(head)
        playing = head
        playingSince = now
        // a new line: the wait for an answer is over (an urgent line cut into it)
        questionUntil = 0
        return TurnStep.Play(head)
    }

    @Synchronized
    fun finished(u: Utterance, outcome: TurnOutcome, now: Long) {
        if (playing === u) playing = null
        lastEndAt = now
        val inLine = (playingSince - u.queuedAt).coerceAtLeast(0)
        val took = (now - playingSince).coerceAtLeast(0)
        when (outcome) {
            TurnOutcome.SAID -> {
                if (u.question) questionUntil = now + timing().answerWaitMs
                log(
                    "Said ${u.kinds} (${secs(inLine)} in line, ${secs(took)})" +
                        (if (u.question) ", waiting up to ${secs(timing().answerWaitMs)} for the answer" else "") +
                        ": \"${clip(u.llm?.task ?: u.text)}\"",
                )
            }
            TurnOutcome.INTERRUPTED -> log("Talked over after ${secs(took)}, not said again: ${u.kinds} \"${clip(u.llm?.task ?: u.text)}\"")
            else -> log("Not said (${outcome.name.lowercase(Locale.ROOT)}): ${u.kinds} \"${clip(u.llm?.task ?: u.text)}\"")
        }
        u.parts.forEach { it.done(outcome) }
    }

    // the mic heard the patient (final = the end of what they said). a final answer ends the wait after a question
    @Synchronized
    fun userSpeech(now: Long, final: Boolean) {
        userAt = now
        userFinal = final
        if (final && questionUntil > 0) {
            questionUntil = 0
            log("Answer heard (speech)")
        }
    }

    // otto's voice came through the speech listener. true = our own line (one plays, or just ended);
    // else it is otto's agent answering by itself, and our lines wait until it is done
    @Synchronized
    fun robotSpeech(now: Long, final: Boolean): Boolean {
        if (playing != null || (lastEndAt > 0 && now - lastEndAt < OWN_ECHO_MS)) return true
        agentUntil = now + if (final) AGENT_FINAL_MS else AGENT_PARTIAL_MS
        return false
    }

    // a tap on the screen (or the guide decided) ends the wait for an answer
    @Synchronized
    fun answered(now: Long, how: String) {
        if (questionUntil <= 0) return
        questionUntil = 0
        log("Answer: $how")
    }

    // show / calm mode: nothing waits or starts. true = a line plays right now (the caller stops the speech)
    @Synchronized
    fun setSilent(on: Boolean, why: String): Boolean {
        silent = on
        silentWhy = why
        if (!on) return false
        drop(why, TurnOutcome.SILENT)
        questionUntil = 0
        return playing != null
    }

    // demo mode: everything but script lines is left out from now on. true = a line that is no script line
    // plays right now (the caller stops the speech)
    @Synchronized
    fun setScriptOnly(on: Boolean, why: String): Boolean {
        scriptOnly = on
        if (!on) return false
        val others = waiting.filter { !it.script }
        val n = others.sumOf { it.parts.size }
        if (n > 0) log("Dropped $n waiting line${if (n == 1) "" else "s"}: $why")
        others.forEach { u -> u.parts.forEach { it.done(TurnOutcome.SILENT) } }
        waiting.removeAll(others.toSet())
        questionUntil = 0
        return playing?.script == false
    }

    // the demo stops or starts again: its waiting lines go. true = a script line plays right now
    @Synchronized
    fun dropScript(why: String): Boolean {
        val mine = waiting.filter { it.script }
        val n = mine.sumOf { it.parts.size }
        if (n > 0) log("Dropped $n waiting line${if (n == 1) "" else "s"}: $why")
        mine.forEach { u -> u.parts.forEach { it.done(TurnOutcome.DROPPED) } }
        waiting.removeAll(mine.toSet())
        return playing?.script == true
    }

    // everything that waits goes (back to the welcome screen)
    @Synchronized
    fun clear(why: String) = drop(why, TurnOutcome.DROPPED)

    @Synchronized
    fun waitingKinds(): List<String> = waiting.map { it.kinds }

    @Synchronized
    fun waitingText(): List<String> = waiting.map { it.text }

    @Synchronized
    fun isPlaying(): Boolean = playing != null

    @Synchronized
    fun awaitingAnswer(now: Long): Boolean = questionUntil > now

    private fun drop(why: String, outcome: TurnOutcome) {
        val n = waiting.sumOf { it.parts.size }
        if (n > 0) log("Dropped $n waiting line${if (n == 1) "" else "s"}: $why")
        waiting.forEach { u -> u.parts.forEach { it.done(outcome) } }
        waiting.clear()
    }

    // ready lines only; the waiting line must not end with a question; an info line before a question,
    // or lines of one topic
    private fun canJoin(u: Utterance, t: SpeechLine): Boolean {
        if (t.llm || u.parts.any { it.turn.llm }) return false
        // a demo line is said on its own, word for word
        if (t.script || u.script) return false
        if (u.question || u.urgent != t.urgent || u.parts.size >= MAX_PARTS) return false
        if (u.text.length + 1 + t.text.trim().length > MAX_JOINED_CHARS) return false
        val sameTopic = t.topic != null && u.parts.all { it.turn.topic == t.topic }
        return t.question || sameTopic
    }

    private fun short(t: SpeechLine) = "\"${clip(t.task ?: t.text)}\""

    private fun clip(s: String): String {
        val one = s.replace(Regex("\\s+"), " ").trim()
        return if (one.length <= CLIP) one else one.take(CLIP).trimEnd() + "..."
    }

    private fun secs(ms: Long) = String.format(Locale.ROOT, "%.1f s", ms / 1000.0)

    companion object {
        // no final word for this long after the last partial one: the patient is done
        const val PARTIAL_GAP_MS = 1_500L
        // otto's agent: a final sentence is over shortly after it came, a partial one may go on
        const val AGENT_FINAL_MS = 600L
        const val AGENT_PARTIAL_MS = 4_000L
        // our own line's words may come through the listener shortly after the speech ended
        const val OWN_ECHO_MS = 1_000L
        const val MAX_JOINED_CHARS = 240
        const val MAX_PARTS = 3
        const val BUSY_MS = 250L
        private const val CLIP = 70
    }
}
