package com.hinterland.clinic

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hinterland.clinic.ui.ClinicScreen
import com.hinterland.clinic.ui.NovaTheme

// debug builds only: the clinic screen in any moment of a visit, with made-up data, to check the
// screens without the robot or the backend.
//   adb shell am start -n com.hinterland.relay/com.hinterland.clinic.ScreenGalleryActivity --es screen walking
class ScreenGalleryActivity : ComponentActivity() {

    private var screen by mutableStateOf("welcome")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        read(intent)
        setContent {
            NovaTheme { ClinicScreen(SCREENS[screen] ?: UiState()) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        read(intent)
    }

    private fun read(intent: Intent) {
        intent.getStringExtra("screen")?.let { screen = it }
    }
}

private val ROOMS = listOf(
    Place("p1", "Dr. Blue", room = "B204", spot = "Dr. Blue", kind = PlaceKind.ROOM, selfCheckIn = true),
    Place("p2", "Dr. Red", room = "B206", spot = "Dr. Red", kind = PlaceKind.ROOM, selfCheckIn = true),
    Place("p3", "Dr. Green", room = "A101", spot = "Dr. Green", kind = PlaceKind.ROOM, selfCheckIn = true),
)

private val WALK = GuideUi(
    ticket = "A17", destination = "Room B204", destinationNote = "Dr. Blue", from = "Reception",
    phase = GuidePhase.WALKING, progress = 0.45f,
)

private fun guide(g: GuideUi, said: String = "") = UiState(guide = g, subtitle = said, places = ROOMS)

private fun checkIn(step: CheckInStep) =
    UiState(places = ROOMS, checkIn = CheckInUi(step = step, rooms = ROOMS, room = ROOMS[0]))

val SCREENS: Map<String, UiState> = linkedMapOf(
    // otto talking with the big face: his words in the bubble on the right
    "speaking" to UiState(speaking = true, subtitle = "Hello! I'm Otto. Who are you here to see?"),
    "speaking-long" to UiState(
        speaking = true,
        subtitle = "Good morning! I can walk you to your doctor's room, at your own pace. Just tell me your doctor's name, or tap the screen to check in.",
    ),
    // the hardest cases for the bubble: leaning in with rings, and hopping with confetti
    "speaking-listening" to UiState(
        speaking = true, agent = AgentMode.LISTENING,
        subtitle = "Of course. Dr. Blue is in Room B204, on this floor. I can walk you there at your own pace.",
    ),
    "speaking-excited" to UiState(speaking = true, flash = Mood.EXCITED, subtitle = "We made it! This is Room B204. Please take a seat."),
    // the welcome shows after a tap on the face
    "welcome" to UiState(places = ROOMS),
    "welcome-desk" to UiState(),
    "checkin-who" to checkIn(CheckInStep.WHO),
    "checkin-pace" to checkIn(CheckInStep.PACE),
    "checkin-confirm" to checkIn(CheckInStep.CONFIRM),
    "checkin-sending" to checkIn(CheckInStep.SENDING),
    "checkin-failed" to checkIn(CheckInStep.FAILED),
    "checkin-desk" to checkIn(CheckInStep.ASK_DESK),
    "to-pickup" to guide(WALK.copy(phase = GuidePhase.TO_PICKUP, progress = null, from = "the waiting area")),
    "pickup" to guide(WALK.copy(phase = GuidePhase.PICKUP, progress = null), "Ticket A17, please come to me."),
    "pickup-phone" to guide(WALK.copy(phase = GuidePhase.PICKUP, progress = null, walkUrl = "https://example.org/walk/abc123")),
    "pace" to guide(WALK.copy(phase = GuidePhase.PACE, progress = null)),
    "walking" to guide(WALK, "Please follow me, it's this way."),
    "walking-unknown" to guide(WALK.copy(progress = null)),
    "detour" to guide(WALK.copy(detour = true, progress = 0.6f)),
    "checking" to guide(WALK.copy(phase = GuidePhase.CHECKING, progress = 0.7f)),
    "talk" to guide(WALK.copy(phase = GuidePhase.CHECKING, talk = TalkUi("All good? Shall I walk slower?", stage = TalkStage.LISTENING, listened = 0.4f))),
    "waiting" to guide(WALK.copy(phase = GuidePhase.WAITING)),
    "paused" to guide(WALK.copy(phase = GuidePhase.PAUSED)),
    "looking" to guide(WALK.copy(phase = GuidePhase.LOOKING)),
    "lost" to guide(WALK.copy(phase = GuidePhase.LOST)),
    "arrived" to guide(WALK.copy(phase = GuidePhase.ARRIVED, progress = 1f), "This is Room B204. Please take a seat."),
    "arrived-thanks" to guide(WALK.copy(phase = GuidePhase.ARRIVED, progress = 1f, feedback = "up")),
    "arrived-waiting" to guide(WALK.copy(phase = GuidePhase.ARRIVED, progress = 1f, waiting = true, destination = "Waiting area B")),
    "arrived-exit" to guide(WALK.copy(phase = GuidePhase.ARRIVED, progress = 1f, exit = true, destination = "Exit", destinationNote = null)),
    "nobody-came" to guide(WALK.copy(phase = GuidePhase.NOBODY_CAME)),
    "failed" to guide(WALK.copy(phase = GuidePhase.FAILED, note = "The way is blocked")),
    "help" to UiState(help = HelpUi(urgent = true, sent = true), subtitle = "The team is informed. Please call 112 now."),
    "help-failed" to UiState(help = HelpUi(urgent = true, sent = false)),
    "out-of-service" to UiState(chassis = ChassisIssue.NOT_STARTED),
    "returning" to UiState(brain = BrainStatus(goal = "Going back to my spot", state = RobotState.RETURNING)),
    "charging" to UiState(brain = BrainStatus(goal = "Charging", state = RobotState.CHARGING, battery = 40, charging = true)),
)
