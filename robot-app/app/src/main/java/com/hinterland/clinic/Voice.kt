package com.hinterland.clinic

import com.ainirobot.agent.AgentCore
import com.ainirobot.agent.base.llm.LLMConfig
import com.ainirobot.agent.base.llm.LLMMessage
import com.ainirobot.agent.base.llm.Role

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

    private const val SYSTEM =
        "You are Otto, the guide robot of a doctor's practice. You walk patients to the room the team chose, at their pace. " +
            "Write exactly what Otto says out loud right now. $STYLE " +
            "No quotation marks, no stage directions. " +
            "Never say or ask for a person's name: a patient is only a ticket number. " +
            "Never give medical advice and never talk about symptoms."

    // text that is already written (the team's say command) - speak it as it is
    suspend fun say(text: String?): Boolean {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return true
        return runCatching { AgentCore.ttsSync(t, 60_000).isSuccess }.getOrDefault(false)
    }

    // english instruction in, spoken sentence out (streamed to tts by the sdk)
    suspend fun speak(task: String, lang: Lang): Boolean {
        val messages = listOf(
            LLMMessage(Role.SYSTEM, SYSTEM),
            LLMMessage(Role.USER, "${lang.instruction()}\nWhat Otto has to say: $task"),
        )
        repeat(2) {
            val ok = runCatching {
                AgentCore.llmSync(messages, LLMConfig(temperature = 0.4f, maxTokens = 140), 30_000, isStreaming = true).isSuccess
            }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }

    // the team's `ask` command: AgentOS plans it like something a person said (SDK 4.9 "Text Commands")
    fun ask(text: String): Boolean = runCatching { AgentCore.query(text) }.isSuccess
}
