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
// one job per action, at most two params, closed sets as ENUM (agentos-nova skill, section 3).
object ClinicActions {

    private const val NS = "com.hinterland.clinic"

    fun all(): List<Action> = listOf(
        confirmTicket(), setPace(), pauseWalk(), walkStatus(),
        guideToPlace(), checkInFor(), waitingStatus(), callStaff(),
    )

    private fun confirmTicket() = Action(
        name = "$NS.CONFIRM_TICKET",
        displayName = "That's me",
        desc = "Confirms that the person in front of Nova is the patient Nova is calling. Use it when Nova calls a ticket " +
            "number and the person says it is them or says their ticket number (one letter and two digits, like A17).",
        parameters = listOf(
            Parameter(
                "ticket", ParameterType.STRING,
                "The ticket number the person said: one letter followed by two digits, written without spaces, for example A17",
                true,
            ),
        ),
        executor = executor { action, p -> Clinic.confirmTicket(p.text("ticket"), action.userQuery) },
    )

    private fun setPace() = Action(
        name = "$NS.SET_PACE",
        displayName = "Walking pace",
        desc = "Changes how fast Nova walks with the patient. Use it when the patient answers Nova's question about the pace, " +
            "asks Nova to walk slower or faster, or says they are ready to go on after Nova waited for them.",
        parameters = listOf(
            Parameter(
                "pace", ParameterType.ENUM,
                "normal for the usual walking speed, slow for a bit slower, very_slow for much slower or when walking is hard. " +
                    "For faster, take the next quicker pace than the current one on the screen.",
                true, listOf("normal", "slow", "very_slow"),
            ),
        ),
        executor = executor { action, p ->
            val pace = Pace.of(p.text("pace")) ?: return@executor false
            Clinic.setPace(pace, action.userQuery)
        },
    )

    private fun pauseWalk() = Action(
        name = "$NS.PAUSE_WALK",
        displayName = "Wait",
        desc = "Nova stops walking and waits right where it is. Use it when the patient says wait, stop, one moment " +
            "or that they need a short break while Nova walks with them.",
        parameters = emptyList(),
        executor = executor { action, _ -> Clinic.pauseWalk(action.userQuery) },
    )

    private fun walkStatus() = Action(
        name = "$NS.WALK_STATUS",
        displayName = "How far is it",
        desc = "Tells the patient during a walk how far it still is to the destination, about how long it takes and where " +
            "they are right now. Use it when the patient asks how far it is, how long it takes or where they are.",
        parameters = emptyList(),
        executor = executor { action, _ -> Clinic.walkStatus(action.userQuery) },
    )

    private fun guideToPlace() = Action(
        name = "$NS.GUIDE_TO_PLACE",
        displayName = "Show the way",
        desc = "Nova walks a visitor to a public place of the practice, for example the exit or the reception, when they ask " +
            "to be taken or shown the way there. Only the public places from the screen info. Treatment rooms are " +
            "assigned by the reception desk, Nova does not take people there on request.",
        parameters = listOf(
            Parameter("place", ParameterType.STRING, "Name of the place the visitor wants to go to, as they said it", true),
        ),
        executor = executor { action, p -> Clinic.guideToPlace(p.text("place"), action.userQuery) },
    )

    private fun checkInFor() = Action(
        name = "$NS.CHECK_IN_FOR",
        displayName = "Check in",
        desc = "Self check-in: a visitor who has an appointment tells Nova which room or which practice member they are " +
            "here to see. Nova gives them a ticket, lets the team know and walks them to the waiting area. Use it only " +
            "for the rooms listed for self check-in in the screen info. If they are not sure, use the reception.",
        parameters = listOf(
            Parameter(
                "place", ParameterType.STRING,
                "The room, room number or practice member they are here to see, as they said it. Never the visitor's own name.",
                true,
            ),
        ),
        executor = executor { action, p -> Clinic.checkInFor(p.text("place"), action.userQuery) },
    )

    private fun waitingStatus() = Action(
        name = "$NS.WAITING_STATUS",
        displayName = "Waiting status",
        desc = "Looks up a ticket and tells the person where it stands: whether Nova picks them up soon, walks them, " +
            "their place in line and about how long the wait still is. Use it when someone asks how long they have to " +
            "wait or what happens next. Ask for the ticket number first if you do not know it.",
        parameters = listOf(
            Parameter(
                "ticket", ParameterType.STRING,
                "The person's ticket number: one letter followed by two digits, written without spaces, for example A17",
                true,
            ),
        ),
        executor = executor { action, p -> Clinic.waitingStatus(p.text("ticket"), action.userQuery) },
    )

    private fun callStaff() = Action(
        name = "$NS.CALL_STAFF",
        displayName = "Call the team",
        desc = "Sends a message to the practice team that a person needs a human, for example a question Nova cannot " +
            "answer, a problem, someone who feels unwell, or an emergency.",
        parameters = listOf(
            Parameter(
                "summary", ParameterType.STRING,
                "One short sentence for the team about what kind of help is needed. No names, no symptoms, " +
                    "no diagnosis and no other health details.",
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
