package com.hinterland.clinic

import android.os.Bundle
import com.ainirobot.agent.action.Action
import com.ainirobot.agent.action.ActionExecutor
import com.ainirobot.agent.base.ActionResult
import com.ainirobot.agent.base.ActionStatus
import com.ainirobot.agent.base.Parameter
import com.ainirobot.agent.base.ParameterType
import com.ainirobot.agent.coroutine.AOCoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

// page actions. the llm picks them by desc and fills the params from the conversation.
object ClinicActions {

    private const val NS = "com.hinterland.clinic"

    fun all(): List<Action> = listOf(checkIn(), guideToDoctor(), callStaff(), waitingStatus())

    private fun checkIn() = Action(
        name = "$NS.CHECK_IN",
        displayName = "Check in",
        desc = "Check a patient in at the reception of the doctor's practice: saves the visit and informs their doctor. " +
            "Use it only after the patient confirmed their name, their doctor and, if they know it, the appointment time.",
        parameters = listOf(
            Parameter("patient_name", ParameterType.STRING, "Full name of the patient, as the patient said it", true),
            Parameter("doctor", ParameterType.STRING, "Name of the doctor the patient has come to see, as the patient said it", true),
            Parameter(
                "appointment_time", ParameterType.STRING,
                "Appointment time in 24-hour HH:mm format, only if the patient said one", false,
            ),
            Parameter(
                "language", ParameterType.STRING,
                "BCP-47 language tag of the language the patient is speaking, lowercase", false,
            ),
            Parameter(
                "reply", ParameterType.STRING,
                "Short sentence in the patient's language that confirms they are checked in, says their doctor will be told " +
                    "and that Nova will walk them to the doctor's room when the doctor is ready.",
                true,
            ),
        ),
        executor = executor { action, p ->
            val name = p.text("patient_name") ?: return@executor false
            val doctor = p.text("doctor") ?: return@executor false
            Clinic.checkIn(
                patient = name,
                doctor = doctor,
                time = Clock.normalizeTime(p.text("appointment_time")),
                language = Lang.cleanTag(p.text("language")),
                reply = p.text("reply"),
                sample = action.userQuery,
            )
        },
    )

    private fun guideToDoctor() = Action(
        name = "$NS.GUIDE_TO_DOCTOR",
        displayName = "Walk to the doctor",
        desc = "Nova physically walks ahead of the patient to their doctor's treatment room, says goodbye at the door " +
            "and drives back to reception. Use when the patient wants to be taken or shown the way to their doctor's room.",
        parameters = listOf(
            Parameter("doctor", ParameterType.STRING, "Name of the doctor whose room the patient should be taken to", true),
            Parameter("patient_name", ParameterType.STRING, "Name of the patient who is being taken there, if known", false),
        ),
        executor = executor { action, p ->
            val doctor = p.text("doctor") ?: return@executor false
            Clinic.guide(doctor, p.text("patient_name"), action.userQuery)
        },
    )

    private fun callStaff() = Action(
        name = "$NS.CALL_STAFF",
        displayName = "Call the team",
        desc = "Sends a message to the practice team in Microsoft Teams that a person at reception needs a human, " +
            "for example a question Nova cannot answer, a problem with an appointment, or an emergency.",
        parameters = listOf(
            Parameter(
                "summary", ParameterType.STRING,
                "One short sentence for the team about what kind of help is needed. " +
                    "No symptoms, no diagnosis and no other medical details.",
                true,
            ),
            Parameter(
                "urgency", ParameterType.ENUM,
                "urgent only for emergencies where someone may be in danger, otherwise normal",
                true, listOf("normal", "urgent"),
            ),
        ),
        executor = executor { action, p ->
            val summary = p.text("summary") ?: return@executor false
            val urgency = if (p.text("urgency") == "urgent") "urgent" else "normal"
            Clinic.callStaff(summary, urgency, action.userQuery)
        },
    )

    private fun waitingStatus() = Action(
        name = "$NS.WAITING_STATUS",
        displayName = "Waiting status",
        desc = "Looks up a checked-in patient's visit and tells them where they stand: their place in the doctor's queue, " +
            "about how long the wait still is, whether their doctor has been informed and whether the doctor is ready.",
        parameters = listOf(
            Parameter("patient_name", ParameterType.STRING, "Name of the patient who asks about their visit", true),
        ),
        executor = executor { action, p ->
            val name = p.text("patient_name") ?: return@executor false
            Clinic.waitingStatus(name, action.userQuery)
        },
    )

    // onExecute has to return at once: the work runs in a coroutine and notify() runs on every path.
    // the brain counts running actions as "someone is talking to nova" and starts nothing meanwhile.
    private fun executor(block: suspend (Action, Bundle?) -> Boolean) = object : ActionExecutor {
        override fun onExecute(action: Action, params: Bundle?): Boolean {
            NovaBrain.actionStarted()
            val notified = AtomicBoolean(false)
            fun finish(ok: Boolean) {
                if (notified.compareAndSet(false, true)) {
                    NovaBrain.actionEnded()
                    action.notify(ActionResult(if (ok) ActionStatus.SUCCEEDED else ActionStatus.FAILED))
                }
            }
            val job = AOCoroutineScope.launch {
                var ok = false
                try {
                    ok = block(action, params)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Clinic.reportError(e)
                } finally {
                    finish(ok)
                }
            }
            // also when the coroutine was cancelled before it ran
            job.invokeOnCompletion { finish(false) }
            return true
        }
    }

    private fun Bundle?.text(key: String): String? =
        this?.getString(key)?.trim()?.takeIf { it.isNotEmpty() }
}
