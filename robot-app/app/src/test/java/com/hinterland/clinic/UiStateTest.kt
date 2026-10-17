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
        assertEquals(Mood.SPEAKING, UiState(speaking = true, walking = true).mood())
        assertEquals(Mood.WALKING, UiState(walking = true).mood())
        assertEquals(Mood.HAPPY, UiState(flash = Mood.HAPPY, speaking = true).mood())
        assertEquals(Mood.THINKING, UiState(working = 1).mood())
        assertEquals(Mood.LISTENING, UiState(agent = AgentMode.LISTENING).mood())
        // the face follows the walk
        assertEquals(Mood.SORRY, UiState(guide = guide.copy(phase = GuidePhase.LOOKING), walking = true).mood())
        assertEquals(Mood.LISTENING, UiState(guide = guide.copy(phase = GuidePhase.CHECKING)).mood())
        assertEquals(Mood.HAPPY, UiState(guide = guide.copy(phase = GuidePhase.ARRIVED)).mood())
        assertEquals(Mood.WALKING, UiState(guide = guide, walking = true).mood())
        assertEquals(Mood.LISTENING, UiState(help = HelpUi(urgent = true, sent = null)).mood())
    }
}
