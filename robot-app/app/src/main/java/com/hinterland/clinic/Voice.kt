package com.hinterland.clinic

import com.ainirobot.agent.AgentCore
import com.ainirobot.agent.base.llm.LLMConfig
import com.ainirobot.agent.base.llm.LLMMessage
import com.ainirobot.agent.base.llm.Role

// no phrase lists here: sentences nova starts by itself are written by the llm in the patient's language
object Voice {

    private const val SYSTEM =
        "You are Nova, the friendly reception robot of a doctor's practice. " +
            "Write exactly what Nova says out loud right now: one or two short, warm sentences of plain spoken text. " +
            "No lists, no emojis, no quotation marks, no stage directions. " +
            "Never give medical advice and never talk about symptoms."

    // text the planner already wrote (the reply param) - speak it as it is
    suspend fun say(text: String?): Boolean {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return true
        return runCatching { AgentCore.ttsSync(t, 60_000).isSuccess }.getOrDefault(false)
    }

    // english instruction in, spoken sentence out (streamed to tts by the sdk)
    suspend fun speak(task: String, lang: Lang): Boolean {
        val messages = listOf(
            LLMMessage(Role.SYSTEM, SYSTEM),
            LLMMessage(Role.USER, "${lang.instruction()}\nWhat Nova has to say: $task"),
        )
        repeat(2) {
            val ok = runCatching {
                AgentCore.llmSync(messages, LLMConfig(temperature = 0.4f, maxTokens = 120), 30_000, isStreaming = true).isSuccess
            }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }
}
