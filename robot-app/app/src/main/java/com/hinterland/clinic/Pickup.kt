package com.hinterland.clinic

import java.util.Locale

// picking a patient up: otto calls the ticket, reminds every 45 s and gives up after 3 min.
// plain kotlin, unit tested.

enum class PickupStep { WAIT, REMIND, TIMEOUT }

class PickupTimeline(
    private val remindEveryMs: Long = REMIND_EVERY_MS,
    private val timeoutMs: Long = TIMEOUT_MS,
) {
    private var reminders = 0

    val remindersDone get() = reminders

    // elapsed = ms since otto first called the ticket
    fun next(elapsed: Long): PickupStep {
        if (elapsed >= timeoutMs) return PickupStep.TIMEOUT
        if (elapsed >= (reminders + 1) * remindEveryMs) {
            reminders++
            return PickupStep.REMIND
        }
        return PickupStep.WAIT
    }

    // how long nothing happens from here
    fun msUntilNext(elapsed: Long): Long =
        (minOf((reminders + 1) * remindEveryMs, timeoutMs) - elapsed).coerceAtLeast(0)

    companion object {
        const val REMIND_EVERY_MS = 45_000L
        const val TIMEOUT_MS = 3 * 60_000L
    }
}

object Tickets {
    private val pattern = Regex("^[A-Z][0-9]{2}$")

    // "a17", "A 17", "A-17." -> "A17"; anything else -> null
    fun normalize(raw: String?): String? {
        val t = raw.orEmpty().uppercase(Locale.ROOT).filter { it.isLetterOrDigit() }
        return t.takeIf { pattern.matches(it) }
    }

    fun same(a: String?, b: String?): Boolean {
        val x = normalize(a) ?: return false
        return x == normalize(b)
    }
}
