package com.hinterland.clinic

import com.ainirobot.agent.AgentCore
import com.ainirobot.agent.base.llm.LLMConfig
import com.ainirobot.agent.base.llm.LLMMessage
import com.ainirobot.agent.base.llm.Role
import kotlinx.coroutines.withTimeoutOrNull

// no phrase lists here: sentences otto starts by itself are written by the llm in the patient's language
object Voice {

    // how otto talks: the agent's own answers (AppAgent.setStyle) and what the app asks him to say use the same words.
    // the voice itself (a calm male voice) is set on the robot, AgentOS has no api for it.
    const val STYLE =
        "Talk like a calm, kind man who has walked patients through this practice for many years: unhurried, warm and " +
            "reassuring, never hectic. Most patients are older, so use plain everyday words, one idea at a time, one or two " +
            "short sentences. Use full stops and commas so the speech has natural pauses. " +
            "Sound like a person, not like a machine or a call centre: never say things like 'As an AI', 'Certainly!', " +
            "'Absolutely!', 'Great question', 'I'd be happy to help' or 'How can I assist you today', and do not end every " +
            "answer with a question or an offer of more help. Hardly any exclamation marks. No lists, no emojis, no technical " +
            "words. Say ticket numbers character by character, for example 'A, 1, 7'."

    // otto's style plus the dashboard's extra note (voice.styleNote, a live setting)
    fun style(note: String = Config.current.styleNote): String =
        note.trim().let { if (it.isEmpty()) STYLE else "$STYLE Also: $it" }

    private fun system(): String =
        "You are Otto, the guide robot of a doctor's practice. You walk patients to the room the team chose, at their pace. " +
            "Write exactly what Otto says out loud right now. ${style()} " +
            "No quotation marks, no stage directions. " +
            "Never say or ask for a person's name: a patient is only a ticket number. " +
            "Never give medical advice and never talk about symptoms."

    // text that is already written (the team's say command, the walk's ready lines) - speak it as it is
    suspend fun say(text: String?): Boolean {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return true
        return runCatching { AgentCore.ttsSync(t, 60_000).isSuccess }.getOrDefault(false)
    }

    // english instruction in, spoken sentence out (streamed to tts by the sdk)
    suspend fun speak(task: String, lang: Lang): Boolean = speakTimed(task, lang).ok

    // one llm line and how it went (v3.3): ok, or interrupted = the patient spoke over it (barge-in), then it
    // is not said again. firstWordsMs = until the first words were heard, totalMs = until it was over
    data class Line(val ok: Boolean, val interrupted: Boolean, val firstWordsMs: Long?, val totalMs: Long, val tries: Int)

    @Volatile private var markAt = 0L
    @Volatile private var firstWordsAt = 0L

    // otto's own words came through the speech listener (OnTranscribeListener.onTTSResult)
    fun heardRobot(at: Long) {
        if (markAt != 0L && firstWordsAt == 0L && at >= markAt) firstWordsAt = at
    }

    suspend fun speakTimed(task: String, lang: Lang): Line {
        val messages = listOf(
            LLMMessage(Role.SYSTEM, system()),
            LLMMessage(Role.USER, "${lang.instruction()}\nWhat Otto has to say: $task"),
        )
        val t0 = System.currentTimeMillis()
        markAt = t0
        firstWordsAt = 0L
        var tries = 0
        var ok = false
        var interrupted = false
        while (!ok && tries < 2) {
            tries++
            ok = runCatching {
                AgentCore.llmSync(messages, LLMConfig(temperature = 0.4f, maxTokens = 140), 30_000, isStreaming = true).isSuccess
            }.getOrDefault(false)
            // the patient talked meanwhile: the agent answers them, saying the line again would talk over them
            interrupted = !ok && Clinic.lastVoiceAt() >= t0
            if (interrupted) break
        }
        val first = firstWordsAt.takeIf { it != 0L }?.let { it - t0 }
        markAt = 0L
        return Line(ok, interrupted, first, System.currentTimeMillis() - t0, tries)
    }

    // one written answer, not spoken (the walk's line set). null when it failed or took longer than timeoutMs
    suspend fun write(system: String, user: String, maxTokens: Int, timeoutMs: Long): String? {
        val messages = listOf(LLMMessage(Role.SYSTEM, system), LLMMessage(Role.USER, user))
        return withTimeoutOrNull(timeoutMs + 2_000) {
            runCatching {
                val r = AgentCore.llmSync(messages, LLMConfig(temperature = 0.3f, maxTokens = maxTokens), timeoutMs, isStreaming = false)
                if (r.isSuccess) r.result?.message?.content else null
            }.getOrNull()
        }
    }

    // the team's `ask` command: AgentOS plans it like something a person said (SDK 4.9 "Text Commands")
    fun ask(text: String): Boolean = runCatching { AgentCore.query(text) }.isSuccess
}
