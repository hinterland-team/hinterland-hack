package com.hinterland.clinic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// the screen mirror for the dashboard and the screen info for the llm. place names are test data.
class UiStateTest {

    private val guide = GuideUi(ticket = "A17", destination = "Room B204", destinationNote = "Dr. One", from = "Front Desk", phase = GuidePhase.WALKING, pace = Pace.SLOW, progress = 0.4f)
    private val room = Place("p4", "Dr. One", room = "B204", spot = "Room One", kind = PlaceKind.ROOM, selfCheckIn = true)
    private val exit = Place("p2", "Way Out", spot = "Door Spot", kind = PlaceKind.EXIT)

    @Test
    fun idleMirror() {
        val m = UiState(brain = BrainStatus(goal = "Waiting for patients")).mirror()
        assertEquals("idle", m.view)
        assertEquals("Hello, I'm Nova", m.title)
        assertEquals("Waiting for patients", m.step)
        assertNull(m.ticket)
        assertNull(m.progress)
    }

    @Test
    fun guideMirrorHasTheTicketAndTheStep() {
        val m = UiState(guide = guide).mirror()
        assertEquals("walking", m.view)
        assertEquals("A17 to Room B204", m.title)
        assertEquals("A17", m.ticket)
        assertEquals("Walking with you", m.step)
        assertEquals(0.4f, m.progress!!, 1e-6f)
    }

    @Test
    fun everyPhaseHasAContractView() {
        val views = setOf("idle", "checkin", "pickup", "walking", "check_back", "waiting", "arrived", "help")
        for (p in GuidePhase.values()) {
            val m = UiState(guide = guide.copy(phase = p)).mirror()
            assertTrue("$p -> ${m.view}", m.view in views)
            assertTrue(m.step!!.isNotBlank())
        }
        assertEquals("pickup", UiState(guide = guide.copy(phase = GuidePhase.PICKUP)).mirror().view)
        assertEquals("check_back", UiState(guide = guide.copy(phase = GuidePhase.CHECKING)).mirror().view)
        assertEquals("waiting", UiState(guide = guide.copy(phase = GuidePhase.LOOKING)).mirror().view)
        assertEquals("help", UiState(guide = guide.copy(phase = GuidePhase.LOST)).mirror().view)
        assertEquals("arrived", UiState(guide = guide.copy(phase = GuidePhase.ARRIVED)).mirror().view)
    }

    @Test
    fun helpAndCheckInMirrors() {
        val help = UiState(guide = guide, help = HelpUi(urgent = true, sent = true)).mirror()
        assertEquals("help", help.view)
        assertEquals("Help is on the way", help.title)
        val checkIn = UiState(checkIn = CheckInUi(step = CheckInStep.PACE, room = room)).mirror()
        assertEquals("checkin", checkIn.view)
        assertEquals("How shall I walk?", checkIn.step)
    }

    @Test
    fun stepsInPlainWords() {
        assertEquals("Please come to me", guide.copy(phase = GuidePhase.PICKUP).step())
        assertEquals("Coming to pick you up", guide.copy(phase = GuidePhase.TO_PICKUP).step())
        assertEquals("Checking on you", guide.copy(phase = GuidePhase.CHECKING).step())
        assertEquals("We're here", guide.copy(phase = GuidePhase.ARRIVED).step())
        assertEquals("Goodbye and get well soon", guide.copy(phase = GuidePhase.ARRIVED, exit = true).step())
        assertTrue(guide.copy(phase = GuidePhase.LOST).needsPresence)
        assertFalse(guide.needsPresence)
    }

    @Test
    fun screenInfoTellsThePlannerWhatItCanDo() {
        val info = UiState(guide = guide.copy(phase = GuidePhase.PICKUP), places = listOf(room, exit)).screenInfo()
        assertTrue(info, info.contains("ticket A17"))
        assertTrue(info, info.contains("CONFIRM_TICKET"))
        assertTrue(info, info.contains("Current pace: slow"))
        assertTrue(info, info.contains("Rooms for self check-in (CHECK_IN_FOR): Room B204 (Dr. One)"))
        assertTrue(info, info.contains("Public places (GUIDE_TO_PLACE): Way Out"))
        val idle = UiState(places = listOf(exit)).screenInfo()
        assertTrue(idle, idle.contains("Welcome screen"))
        assertFalse(idle, idle.contains("CHECK_IN_FOR"))
        assertTrue(UiState(places = listOf(room)).canSelfCheckIn)
        assertFalse(UiState(places = listOf(exit)).canSelfCheckIn)
    }

    @Test
    fun checkInSteps() {
        assertEquals(1, CheckInUi(step = CheckInStep.WHO).stepNumber())
        assertEquals(1, CheckInUi(step = CheckInStep.ASK_DESK).stepNumber())
        assertEquals(2, CheckInUi(step = CheckInStep.PACE).stepNumber())
        assertEquals(3, CheckInUi(step = CheckInStep.CONFIRM).stepNumber())
        assertEquals("Who are you here to see?", CheckInUi().stepLabel())
    }

    @Test
    fun moods() {
        assertEquals(Mood.IDLE, UiState().mood())
        // talking keeps the mood, only the mouth moves
        assertEquals(Mood.WALKING, UiState(speaking = true, walking = true).mood())
        assertEquals(Mood.IDLE, UiState(speaking = true).mood())
        assertEquals(Mood.WALKING, UiState(walking = true).mood())
        assertEquals(Mood.HAPPY, UiState(flash = Mood.HAPPY, speaking = true).mood())
        assertEquals(Mood.THINKING, UiState(working = 1).mood())
        assertEquals(Mood.LISTENING, UiState(agent = AgentMode.LISTENING).mood())
        // the face follows the walk
        assertEquals(Mood.CONCERNED, UiState(guide = guide.copy(phase = GuidePhase.LOOKING), walking = true).mood())
        assertEquals(Mood.SORRY, UiState(guide = guide.copy(phase = GuidePhase.NOBODY_CAME)).mood())
        assertEquals(Mood.LISTENING, UiState(guide = guide.copy(phase = GuidePhase.CHECKING)).mood())
        assertEquals(Mood.HAPPY, UiState(guide = guide.copy(phase = GuidePhase.ARRIVED)).mood())
        assertEquals(Mood.WALKING, UiState(guide = guide, walking = true).mood())
        assertEquals(Mood.CONCERNED, UiState(help = HelpUi(urgent = true, sent = null)).mood())
    }

    @Test
    fun theRobotsOwnStateShowsWhenNothingElseIsGoingOn() {
        assertEquals(Mood.GREETING, UiState(brain = BrainStatus(state = RobotState.GREETING), speaking = true).mood())
        assertEquals(Mood.CONFUSED, UiState(brain = BrainStatus(state = RobotState.ERROR)).mood())
        assertEquals(Mood.SLEEPY, UiState(brain = BrainStatus(state = RobotState.CHARGING, charging = true)).mood())
        // someone talks to her while charging: she wakes up
        assertEquals(Mood.IDLE, UiState(brain = BrainStatus(state = RobotState.CHARGING, charging = true), speaking = true).mood())
        assertEquals(Mood.LISTENING, UiState(brain = BrainStatus(state = RobotState.CHARGING, charging = true), agent = AgentMode.LISTENING).mood())
        assertEquals(Mood.TIRED, UiState(brain = BrainStatus(battery = LOW_BATTERY)).mood())
        assertEquals(Mood.IDLE, UiState(brain = BrainStatus(battery = LOW_BATTERY, charging = true)).mood())
        assertEquals(Mood.IDLE, UiState(brain = BrainStatus(battery = LOW_BATTERY + 1)).mood())
        // a walk wins over a low battery
        assertEquals(Mood.WALKING, UiState(brain = BrainStatus(battery = 5), walking = true).mood())
    }

    @Test
    fun plainWaitingIsOnlyTheFace() {
        assertNull(UiState().focus())
        assertNull(UiState(brain = BrainStatus(state = RobotState.TALKING)).focus())
    }

    @Test
    fun theInfoStaysWhileTheScreenWaitsForATap() {
        for (p in listOf(GuidePhase.PICKUP, GuidePhase.PACE, GuidePhase.PAUSED, GuidePhase.ARRIVED)) {
            assertTrue(p.name, UiState(guide = guide.copy(phase = p)).focus()!!.holds)
        }
        assertFalse(UiState(guide = guide.copy(phase = GuidePhase.ARRIVED, feedback = "up")).focus()!!.holds)
        assertTrue(UiState(checkIn = CheckInUi(step = CheckInStep.WHO)).focus()!!.holds)
        assertTrue(UiState(help = HelpUi(urgent = true, sent = null), guide = guide).focus()!!.holds)
    }

    @Test
    fun passingMomentsShowAndThenLetTheFaceBack() {
        for (p in listOf(GuidePhase.TO_PICKUP, GuidePhase.WALKING, GuidePhase.CHECKING, GuidePhase.LOST, GuidePhase.FAILED)) {
            assertFalse(p.name, UiState(guide = guide.copy(phase = p)).focus()!!.holds)
        }
        assertFalse(UiState(checkIn = CheckInUi(step = CheckInStep.SENDING)).focus()!!.holds)
        assertFalse(UiState(brain = BrainStatus(state = RobotState.RETURNING)).focus()!!.holds)
    }

    @Test
    fun theWalkQuestionWaitsForAnAnswer() {
        val asked = UiState(guide = guide.copy(phase = GuidePhase.CHECKING, talk = TalkUi("All good?", id = 1)))
        assertTrue(asked.focus()!!.holds)
        assertTrue(asked.focus()!!.key != UiState(guide = guide.copy(phase = GuidePhase.CHECKING)).focus()!!.key)
        // a new question shows the info again
        assertTrue(asked.focus()!!.key != UiState(guide = guide.copy(phase = GuidePhase.CHECKING, talk = TalkUi("All good?", id = 2))).focus()!!.key)
        assertEquals(Mood.LISTENING, UiState(guide = guide.copy(phase = GuidePhase.WALKING, talk = TalkUi("All good?"))).mood())
    }

    @Test
    fun outOfServiceShowsAndThenLetsTheFaceBack() {
        assertFalse(UiState(chassis = ChassisIssue.NOT_STARTED).focus()!!.holds)
        // help and a walk still come first, like on the screen
        assertEquals("help", UiState(chassis = ChassisIssue.NOT_STARTED, help = HelpUi(urgent = true, sent = null)).focus()!!.key)
    }

    @Test
    fun everyNewStepIsANewFocus() {
        val walking = UiState(guide = guide).focus()!!.key
        assertEquals(walking, UiState(guide = guide.copy(progress = 0.9f)).focus()!!.key)
        assertTrue(walking != UiState(guide = guide.copy(phase = GuidePhase.CHECKING)).focus()!!.key)
        assertTrue(UiState(checkIn = CheckInUi(step = CheckInStep.WHO)).focus()!!.key != UiState(checkIn = CheckInUi(step = CheckInStep.PACE)).focus()!!.key)
    }
}
