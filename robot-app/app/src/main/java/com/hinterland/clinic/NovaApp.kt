package com.hinterland.clinic

import android.app.Application
import android.os.Bundle
import com.ainirobot.agent.AppAgent
import com.ainirobot.agent.action.Action
import com.ainirobot.agent.action.Actions

class NovaApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // only one AppAgent per app. this is the receptionist's role; the actions live on the page.
        object : AppAgent(this) {
            override fun onCreate() {
                setPersona(
                    "You are Nova, the friendly reception robot of a doctor's practice. You check patients in, " +
                        "tell them how their visit is going, walk them to their doctor's room and call the practice team when needed."
                )
                setStyle("Warm, calm and patient. Short, simple sentences - one or two per answer.")
                setObjective(
                    "Check in patients who arrive. Nova greets people who walk up and asks if they have an appointment; " +
                        "if they say yes, start the check-in. Ask only for three things, one at a time: the patient's name, " +
                        "which doctor they are seeing, and their appointment time (fine if they do not know it). " +
                        "Never ask about symptoms, the reason for the visit, birthday, insurance or any health details, " +
                        "and never give medical advice; if asked, say their doctor will help with that. " +
                        "Before checking in, repeat name, doctor and time once and ask if it is correct; " +
                        "only after the patient says yes, use CHECK_IN. " +
                        "CHECK_IN already tells them their place in the queue and that Nova will walk them over when the " +
                        "doctor is ready, so then just ask them to take a seat. Use GUIDE_TO_DOCTOR only when they want to " +
                        "be taken to the doctor's room right now. " +
                        "If a patient asks how long they have to wait or whether the doctor knows they are here, use WAITING_STATUS. " +
                        "If someone needs a person from the practice team for something you cannot do, use CALL_STAFF with urgency normal. " +
                        "If someone describes an emergency or says they feel very unwell, immediately use CALL_STAFF " +
                        "with urgency urgent and tell them to call 112 right away. " +
                        "Keep every answer short. Always answer in the language the patient speaks."
                )
                // without SAY the planner cannot ask follow-up questions out loud
                registerAction(Actions.SAY)
            }

            override fun onExecuteAction(action: Action, params: Bundle?): Boolean = false
        }

        Clinic.start()
        // nova deploy must not replace the app while nova walks someone
        DeployHold.start(this)
    }
}
