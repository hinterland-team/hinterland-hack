package com.hinterland.clinic

import com.ainirobot.agent.AgentCore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// runs SpeechTurns on the robot: one loop says the lines one after another (ready lines by text-to-speech,
// written ones by the llm, Clinic.playText / playTask), the speech listeners tell it when the patient or
// otto's own agent talks, taps end the wait for an answer. timings are live settings (voice group).
object Speech {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val turns = SpeechTurns(timing = { Config.current.turns }, log = { RobotLog.i(LogSource.VOICE, it) })
    private val wake = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var started = false

    private fun now() = System.currentTimeMillis()

    fun start() {
        if (started) return
        started = true
        scope.launch { loop() }
    }

    // a ready line, said as it is (starts at once, no llm). suspends until it was said, replaced or dropped
    suspend fun say(
        kind: SpeechKind,
        text: String,
        question: Boolean = false,
        urgent: Boolean = false,
        topic: String? = null,
        fits: () -> Boolean = { true },
    ): TurnOutcome {
        val t = text.trim()
        if (t.isEmpty()) return TurnOutcome.SAID
        return offer(SpeechLine(kind, t, question = question, urgent = urgent, topic = topic, fits = fits))
    }

    fun sayLater(kind: SpeechKind, text: String, fits: () -> Boolean = { true }) {
        scope.launch { say(kind, text, fits = fits) }
    }

    // an english task the llm says in the person's language; fallback is said when the llm does not deliver
    suspend fun speak(
        kind: SpeechKind,
        task: String,
        lang: Lang,
        fallback: String,
        question: Boolean = false,
        urgent: Boolean = false,
        fits: () -> Boolean = { true },
    ): TurnOutcome = offer(SpeechLine(kind, fallback.trim(), task = task, lang = lang, question = question, urgent = urgent, fits = fits))

    fun speakLater(kind: SpeechKind, task: String, lang: Lang, fallback: String) {
        scope.launch { speak(kind, task, lang, fallback) }
    }

    private suspend fun offer(turn: SpeechLine): TurnOutcome {
        val done = CompletableDeferred<TurnOutcome>()
        val r = turns.offer(turn, now()) { done.complete(it) }
        if (r.cut) stopNow()
        wake.trySend(Unit)
        return done.await()
    }

    // ---- signals (listener threads) ----

    fun userSpeech(final: Boolean) {
        turns.userSpeech(now(), final)
        wake.trySend(Unit)
    }

    // true = our own line, false = otto's agent talks by itself (or the robot system)
    fun robotSpeech(final: Boolean): Boolean {
        val ours = turns.robotSpeech(now(), final)
        wake.trySend(Unit)
        return ours
    }

    // a tap answered the question, or the walk's own listening is over
    fun answered(how: String) {
        turns.answered(now(), how)
        wake.trySend(Unit)
    }

    // show / calm: nothing is said; a line that plays stops at once
    fun setSilent(on: Boolean, why: String) {
        if (turns.setSilent(on, why)) stopNow()
        wake.trySend(Unit)
    }

    val silent: Boolean get() = turns.silent

    fun clear(why: String) {
        turns.clear(why)
    }

    fun playing(): Boolean = turns.isPlaying()

    private fun stopNow() {
        runCatching { AgentCore.stopTTS() }
    }

    private suspend fun loop() {
        while (true) {
            try {
                when (val step = turns.next(now())) {
                    TurnStep.Idle -> wake.receive()
                    is TurnStep.Wait -> withTimeoutOrNull((step.untilMs - now()).coerceIn(20, 5_000)) { wake.receive() }
                    is TurnStep.Play -> play(step.utterance)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                RobotLog.e(LogSource.VOICE, "Speech loop: ${e.javaClass.simpleName} ${e.message.orEmpty().take(100)}")
                delay(500)
            }
        }
    }

    private suspend fun play(u: Utterance) {
        var outcome = TurnOutcome.FAILED
        try {
            val llm = u.llm
            outcome = if (llm != null) {
                Clinic.playTask(llm.task.orEmpty(), llm.lang ?: Lang(null), llm.text)
            } else {
                Clinic.playText(u.text)
            }
        } finally {
            turns.finished(u, outcome, now())
        }
    }
}
