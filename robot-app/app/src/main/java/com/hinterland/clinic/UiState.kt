package com.hinterland.clinic

enum class Health { UNKNOWN, OK, DOWN }

// what the agentos planner is doing (from OnAgentStatusChangedListener)
enum class AgentMode { IDLE, LISTENING, THINKING }

enum class Mood { IDLE, LISTENING, THINKING, SPEAKING, WALKING, HAPPY, SORRY }

// the visit's place in the doctor's queue (from the backend)
data class QueueInfo(val doctor: String, val position: Int, val minutes: Int?)

data class UiState(
    val agent: AgentMode = AgentMode.IDLE,
    val agentNote: String? = null,
    // own network / llm work in flight - the face looks "thinking" while > 0
    val working: Int = 0,
    val task: String? = null,
    val speaking: Boolean = false,
    val walking: Boolean = false,
    val flash: Mood? = null,
    val subtitle: String = "",
    val subtitleFromUser: Boolean = false,
    val header: String? = null,
    val steps: List<Step> = emptyList(),
    val queue: QueueInfo? = null,
    // what nova said first when she greeted someone, so the planner knows the answer is to that
    val opener: String? = null,
    val banner: String? = null,
    val backend: Health = Health.UNKNOWN,
    val robot: Health = Health.UNKNOWN,
    val robotNote: String? = null,
    val brain: BrainStatus = BrainStatus(),
) {
    fun mood(): Mood = when {
        flash != null -> flash
        speaking -> Mood.SPEAKING
        walking -> Mood.WALKING
        working > 0 || agent == AgentMode.THINKING -> Mood.THINKING
        agent == AgentMode.LISTENING -> Mood.LISTENING
        else -> Mood.IDLE
    }

    // what the planner should know about the screen (uploadInterfaceInfo replaces it every time)
    fun screenInfo(): String = buildString {
        appendLine("Nova Clinic reception screen.")
        appendLine("Nova right now: ${brain.goal}.")
        opener?.let { appendLine(it) }
        if (steps.isEmpty()) {
            append("Welcome card. Nova can check patients in (name, doctor, appointment time), ")
            append("tell them their waiting status, walk them to the doctor's room and call the practice team.")
        } else {
            if (!header.isNullOrBlank()) appendLine("Visit on screen: $header")
            queue?.let { appendLine("Queue: ${Queue.facts(it.doctor, it.position, it.minutes)}") }
            append("Progress: ")
            append(steps.joinToString("; ") { "${it.label} (${it.state.name.lowercase()})" })
            if (walking) append("\nNova is walking right now.")
        }
    }
}
