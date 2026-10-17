package com.hinterland.clinic

import java.time.ZoneId

// the agent steps on the right side of the screen. labels are on-screen english, not speech.

enum class StepKey { LISTENING, DETAILS, NOTIFIED, WAITING, WALKING, ARRIVED, RETURNING }

// SKIPPED = never happened because an earlier step failed
enum class StepState { PENDING, ACTIVE, DONE, FAILED, SKIPPED }

data class Step(
    val key: StepKey,
    val label: String,
    val state: StepState = StepState.PENDING,
    val time: String? = null,
    val detail: String? = null,
)

object Timeline {

    fun header(patient: String?, doctor: String?, time: String?): String =
        listOf(patient, doctor, time).filter { !it.isNullOrBlank() }.joinToString(" · ")

    const val GOT_DETAILS = "Got your details"

    fun notifiedLabel(doctor: String?, notified: Boolean?): String = when (notified) {
        true -> "${doctorOr(doctor)} informed via Teams"
        false -> "Team will be told"
        null -> "Informing ${doctorOr(doctor)}"
    }

    fun waitingLabel(doctor: String?, ready: Boolean): String =
        if (ready) "${doctorOr(doctor)} is ready" else "Waiting for ${doctorOr(doctor)}"

    fun walkingLabel(doctor: String?): String =
        if (doctor.isNullOrBlank()) "Walking to the doctor's room" else "Walking to $doctor's room"

    private fun doctorOr(doctor: String?) = if (doctor.isNullOrBlank()) "your doctor" else doctor

    // a new conversation: only "listening" is going on
    fun fresh(now: String?): List<Step> = listOf(
        Step(StepKey.LISTENING, "Listening", StepState.ACTIVE, now),
        Step(StepKey.DETAILS, "Getting your details"),
        Step(StepKey.NOTIFIED, notifiedLabel(null, null)),
        Step(StepKey.WAITING, waitingLabel(null, false)),
        Step(StepKey.WALKING, walkingLabel(null)),
        Step(StepKey.ARRIVED, "Arrived"),
        Step(StepKey.RETURNING, "Back to reception"),
    )

    // labels with the real names once we know them
    fun named(steps: List<Step>, patient: String?, doctor: String?, time: String?): List<Step> =
        steps.map { s ->
            when (s.key) {
                StepKey.DETAILS -> if (s.state == StepState.PENDING) s else s.copy(detail = header(patient, doctor, time))
                StepKey.NOTIFIED -> if (s.state == StepState.PENDING) s.copy(label = notifiedLabel(doctor, null)) else s
                StepKey.WAITING -> if (s.state == StepState.PENDING) s.copy(label = waitingLabel(doctor, false)) else s
                StepKey.WALKING -> if (s.state == StepState.PENDING) s.copy(label = walkingLabel(doctor)) else s
                else -> s
            }
        }

    // details done: the names go in the second line so long names are not cut off
    fun gotDetails(steps: List<Step>, patient: String?, doctor: String?, time: String?, at: String?): List<Step> =
        set(steps, StepKey.DETAILS, StepState.DONE, at, detail = header(patient, doctor, time), label = GOT_DETAILS)

    // moves one step. earlier steps that were still open are closed as done,
    // or as skipped when a failed step lies before them.
    fun set(
        steps: List<Step>,
        key: StepKey,
        state: StepState,
        time: String? = null,
        detail: String? = null,
        label: String? = null,
    ): List<Step> {
        val index = steps.indexOfFirst { it.key == key }
        if (index < 0) return steps
        val lastFailed = steps.subList(0, index).indexOfLast { it.state == StepState.FAILED }
        return steps.mapIndexed { i, s ->
            when {
                i == index -> s.copy(
                    state = state,
                    time = time ?: s.time,
                    detail = detail,
                    label = label ?: s.label,
                )
                i < index && s.state == StepState.PENDING && i > lastFailed && lastFailed >= 0 ->
                    s.copy(state = StepState.SKIPPED)
                i < index && (s.state == StepState.PENDING || s.state == StepState.ACTIVE) ->
                    s.copy(state = StepState.DONE, time = s.time ?: time)
                else -> s
            }
        }
    }

    // timeline for a visit that comes from the backend (status + events)
    fun forVisit(visit: Visit, notified: Boolean?, zone: ZoneId = ZoneId.systemDefault()): List<Step> {
        fun at(vararg types: String): String? =
            visit.events.lastOrNull { it.type in types }?.at?.let { Clock.hhmm(it, zone) }

        val status = visit.status
        val rank = VisitStatus.rank(status)
        val created = Clock.hhmm(visit.createdAt, zone)
        val doctor = visit.doctorName
        val wasNotified = notified
            ?: (rank >= VisitStatus.rank(VisitStatus.NOTIFIED) || visit.events.any { it.type == VisitStatus.NOTIFIED })

        var steps = named(fresh(created), visit.patientName, doctor, visit.appointmentTime)
        steps = gotDetails(steps, visit.patientName, doctor, visit.appointmentTime, at(VisitStatus.CHECKED_IN) ?: created)
        steps = set(
            steps, StepKey.NOTIFIED, StepState.DONE,
            time = at(VisitStatus.NOTIFIED) ?: at(VisitStatus.CHECKED_IN) ?: created,
            label = notifiedLabel(doctor, wasNotified),
        )

        val ready = waitingLabel(doctor, true)
        steps = when (status) {
            VisitStatus.CANCELLED ->
                set(steps, StepKey.WAITING, StepState.FAILED, at(VisitStatus.CANCELLED), label = "Visit cancelled")
            VisitStatus.CALLED ->
                set(steps, StepKey.WAITING, StepState.DONE, at(VisitStatus.CALLED), label = ready)
            VisitStatus.ESCORTING -> {
                val s = set(steps, StepKey.WAITING, StepState.DONE, at(VisitStatus.CALLED), label = ready)
                set(s, StepKey.WALKING, StepState.ACTIVE, at(VisitStatus.ESCORTING))
            }
            VisitStatus.WITH_DOCTOR, VisitStatus.DONE -> {
                val s = set(steps, StepKey.WAITING, StepState.DONE, at(VisitStatus.CALLED), label = ready)
                val w = set(s, StepKey.WALKING, StepState.DONE, at(VisitStatus.ESCORTING))
                set(w, StepKey.ARRIVED, StepState.DONE, at(VisitStatus.WITH_DOCTOR))
            }
            // still waiting: the queue goes in the second line
            else -> set(
                steps, StepKey.WAITING, StepState.ACTIVE,
                detail = Queue.screen(visit.queuePosition, visit.estimatedWaitMinutes),
            )
        }
        return steps
    }

    // when nova walks without a known visit (patient just asked for the way)
    fun walkOnly(doctor: String?, now: String?): List<Step> = listOf(
        Step(StepKey.LISTENING, "Listening", StepState.DONE, now),
        Step(StepKey.WALKING, walkingLabel(doctor), StepState.ACTIVE, now),
        Step(StepKey.ARRIVED, "Arrived"),
        Step(StepKey.RETURNING, "Back to reception"),
    )
}

// the queue numbers from the backend (queuePosition / estimatedWaitMinutes), in words.
// screen = short on-screen english, facts = english for the llm and the planner.
// nothing is made up: unknown numbers are left out.
object Queue {

    fun of(v: Visit): QueueInfo? =
        v.queuePosition?.let { QueueInfo(v.doctorName, it, v.estimatedWaitMinutes) }

    fun screen(position: Int?, minutes: Int?): String? {
        if (position == null || position < 1) return null
        val place = if (position == 1) "Next in line" else "${position - 1} ahead"
        return if (minutes != null && minutes > 0) "$place · about $minutes min" else place
    }

    fun facts(doctor: String, position: Int?, minutes: Int?): String = buildString {
        when {
            position == null || position < 1 -> append("Place in $doctor's queue is not known.")
            position == 1 -> append("Next in line for $doctor, nobody ahead.")
            else -> append("Place $position in $doctor's queue, ${position - 1} ${patients(position - 1)} ahead.")
        }
        if (position != null && position >= 1 && minutes != null) {
            append(if (minutes > 0) " Expected wait about $minutes minutes." else " Expected wait: almost none.")
        }
    }

    // what nova says right after a check-in, as an english instruction for the llm
    fun checkInTask(patient: String, doctor: String, position: Int?, minutes: Int?): String = buildString {
        append("In ONE natural, warm sentence tell $patient that they are checked in for $doctor")
        when {
            position == null || position < 1 -> append(" and that $doctor knows they are here")
            position == 1 -> append(", that they are next in line")
            else -> append(", that ${position - 1} ${patients(position - 1)} ${if (position == 2) "is" else "are"} ahead of them")
        }
        if (position != null && position >= 1 && minutes != null && minutes > 0) append(" and the wait is about $minutes minutes")
        append(", and that you will walk them over to $doctor when the doctor is ready. Use only these facts.")
    }

    private fun patients(n: Int) = if (n == 1) "patient" else "patients"
}
