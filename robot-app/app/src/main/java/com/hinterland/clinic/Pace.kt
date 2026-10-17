package com.hinterland.clinic

// walking pace for a guide (docs/journeys.md). plain kotlin, unit tested.

// wire = value in the api, label = on-screen english
enum class Pace(val wire: String, val label: String) {
    NORMAL("normal", "Normal pace"),
    SLOW("slow", "Slow pace"),
    VERY_SLOW("very_slow", "Very slow pace");

    fun slower(): Pace = when (this) {
        NORMAL -> SLOW
        SLOW, VERY_SLOW -> VERY_SLOW
    }

    fun faster(): Pace = when (this) {
        VERY_SLOW -> SLOW
        SLOW, NORMAL -> NORMAL
    }

    fun isSlowerThan(other: Pace) = ordinal > other.ordinal

    companion object {
        fun of(wire: String?): Pace? = values().firstOrNull { it.wire == wire?.trim()?.lowercase() }
    }
}

// navigation speed for one pace, ready for RobotApi.startNavigation(..., linearSpeed, angularSpeed, ...)
data class Speed(val linear: Double, val angular: Double)

object PaceTable {

    // RobotAPI_en.md "Navigate to Specified Location": linearSpeed 0.1-0.85 m/s, angularSpeed 0.4-1.4 rad/s,
    // angular recommended as 0.4 + (linear - 0.1) / 3 * 4
    const val MIN_LINEAR = 0.1
    const val MAX_LINEAR = 0.85
    const val MIN_ANGULAR = 0.4
    const val MAX_ANGULAR = 1.4

    fun linear(pace: Pace): Double = when (pace) {
        Pace.NORMAL -> 0.6
        Pace.SLOW -> 0.4
        Pace.VERY_SLOW -> 0.25
    }

    fun speed(pace: Pace): Speed {
        val v = linear(pace).coerceIn(MIN_LINEAR, MAX_LINEAR)
        return Speed(v, angularFor(v))
    }

    fun angularFor(linear: Double): Double =
        (MIN_ANGULAR + (linear - MIN_LINEAR) / 3.0 * 4.0).coerceIn(MIN_ANGULAR, MAX_ANGULAR)
}

object PaceRules {

    // pace a guide starts with: wheelchair walks at least slow, the slower one wins
    fun start(staffPace: Pace, wheelchair: Boolean): Pace =
        if (wheelchair && Pace.SLOW.isSlowerThan(staffPace)) Pace.SLOW else staffPace

    // otto only asks "normally or a bit slower?" when nobody chose a slower pace already
    fun shouldAsk(staffPace: Pace, wheelchair: Boolean, chosenByPatient: Boolean): Boolean =
        !chosenByPatient && start(staffPace, wheelchair) == Pace.NORMAL
}
