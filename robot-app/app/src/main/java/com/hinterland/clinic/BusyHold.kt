package com.hinterland.clinic

// busy hold for nova deploy (docs/deploy.md): while otto drives someone around, nova deploy must
// not replace this app. plain kotlin, unit tested; DeployHold sends what this says.

data class HoldMessage(val seconds: Int, val reason: String)

class BusyHold(private val renewMs: Long = RENEW_MS) {

    private var sentAt = 0L
    private var sentReason: String? = null

    // state = what otto's running task does (null = no task). the broadcast to send now, or null.
    fun next(state: RobotState?, now: Long): HoldMessage? {
        val reason = state?.let(::reason)
        if (reason == null) {
            if (sentReason == null) return null
            sentReason = null
            return HoldMessage(0, "")
        }
        // a clock that jumped back counts as "renew now"
        if (reason == sentReason && now - sentAt in 0 until renewMs) return null
        sentReason = reason
        sentAt = now
        return HoldMessage(SECONDS, reason)
    }

    companion object {
        // the hold runs out by itself after this, so a crashed app can't block updates
        const val SECONDS = 60
        const val RENEW_MS = 15_000L

        // only the drives count. no names in here: the reason shows up on nova deploy's dashboard
        fun reason(state: RobotState): String? = when (state) {
            RobotState.WALKING -> "Walking to a room"
            RobotState.FETCHING -> "Fetching a patient"
            RobotState.RETURNING -> "Driving back"
            else -> null
        }
    }
}
