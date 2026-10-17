package com.hinterland.clinic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class TimelineTest {

    private val utc = ZoneId.of("UTC")

    private fun visit(status: String, vararg events: Pair<String, String>) = Visit(
        id = "v1",
        patientName = "Ada Test",
        doctorId = "d1",
        doctorName = "Dr. One",
        appointmentTime = "10:00",
        language = null,
        status = status,
        createdAt = "2026-10-16T08:00:00.000Z",
        updatedAt = null,
        spot = null,
        events = events.map { VisitEvent(at = it.second, type = it.first, detail = null) },
    )

    private fun List<Step>.of(key: StepKey) = first { it.key == key }

    @Test
    fun notifiedVisitWaitsForDoctor() {
        val steps = Timeline.forVisit(
            visit("notified", "checked_in" to "2026-10-16T08:00:00Z", "notified" to "2026-10-16T08:01:00Z"),
            notified = true, zone = utc,
        )
        assertEquals(StepState.DONE, steps.of(StepKey.LISTENING).state)
        assertEquals("Got your details", steps.of(StepKey.DETAILS).label)
        assertEquals("Ada Test · Dr. One · 10:00", steps.of(StepKey.DETAILS).detail)
        assertEquals("Dr. One informed via Teams", steps.of(StepKey.NOTIFIED).label)
        assertEquals("08:01", steps.of(StepKey.NOTIFIED).time)
        assertEquals(StepState.ACTIVE, steps.of(StepKey.WAITING).state)
        assertEquals("Waiting for Dr. One", steps.of(StepKey.WAITING).label)
        assertEquals(StepState.PENDING, steps.of(StepKey.WALKING).state)
        assertEquals("Walking to Dr. One's room", steps.of(StepKey.WALKING).label)
    }

    @Test
    fun notNotifiedSaysTeamWillBeTold() {
        val steps = Timeline.forVisit(visit("checked_in"), notified = false, zone = utc)
        assertEquals("Team will be told", steps.of(StepKey.NOTIFIED).label)
        assertEquals(StepState.DONE, steps.of(StepKey.NOTIFIED).state)
        assertEquals("08:00", steps.of(StepKey.DETAILS).time)
    }

    @Test
    fun escortingMeansDoctorReadyAndWalking() {
        val steps = Timeline.forVisit(
            visit("escorting", "called" to "2026-10-16T08:10:00Z", "escorting" to "2026-10-16T08:11:00Z"),
            notified = null, zone = utc,
        )
        assertEquals("Dr. One informed via Teams", steps.of(StepKey.NOTIFIED).label)
        assertEquals("Dr. One is ready", steps.of(StepKey.WAITING).label)
        assertEquals("08:10", steps.of(StepKey.WAITING).time)
        assertEquals(StepState.ACTIVE, steps.of(StepKey.WALKING).state)
        assertEquals("08:11", steps.of(StepKey.WALKING).time)
    }

    @Test
    fun withDoctorIsArrived() {
        val steps = Timeline.forVisit(visit("with_doctor"), notified = true, zone = utc)
        assertEquals(StepState.DONE, steps.of(StepKey.WALKING).state)
        assertEquals(StepState.DONE, steps.of(StepKey.ARRIVED).state)
        assertEquals(StepState.PENDING, steps.of(StepKey.RETURNING).state)
    }

    @Test
    fun cancelledVisitFailsTheWait() {
        val steps = Timeline.forVisit(visit("cancelled"), notified = true, zone = utc)
        assertEquals(StepState.FAILED, steps.of(StepKey.WAITING).state)
        assertEquals("Visit cancelled", steps.of(StepKey.WAITING).label)
    }

    @Test
    fun stepsAfterAFailureAreSkippedNotDone() {
        var steps = Timeline.walkOnly("Dr. One", "09:00")
        steps = Timeline.set(steps, StepKey.WALKING, StepState.FAILED, "09:01", detail = "The way is blocked")
        steps = Timeline.set(steps, StepKey.RETURNING, StepState.ACTIVE, "09:02")
        assertEquals(StepState.FAILED, steps.of(StepKey.WALKING).state)
        assertEquals("The way is blocked", steps.of(StepKey.WALKING).detail)
        assertEquals(StepState.SKIPPED, steps.of(StepKey.ARRIVED).state)
        assertNull(steps.of(StepKey.ARRIVED).time)
        assertEquals(StepState.ACTIVE, steps.of(StepKey.RETURNING).state)
    }

    @Test
    fun freshConversationOnlyListens() {
        val steps = Timeline.fresh("09:00")
        assertEquals(StepState.ACTIVE, steps.of(StepKey.LISTENING).state)
        assertTrue(steps.drop(1).all { it.state == StepState.PENDING })
        assertEquals("Ada Test · Dr. One", Timeline.header("Ada Test", "Dr. One", null))
    }

    @Test
    fun moodPriority() {
        assertEquals(Mood.SPEAKING, UiState(speaking = true, walking = true, working = 1).mood())
        assertEquals(Mood.WALKING, UiState(walking = true, working = 1).mood())
        assertEquals(Mood.THINKING, UiState(working = 1, agent = AgentMode.LISTENING).mood())
        assertEquals(Mood.SORRY, UiState(flash = Mood.SORRY, speaking = true).mood())
        assertEquals(Mood.IDLE, UiState().mood())
    }

    @Test
    fun clockHelpers() {
        assertEquals("08:00", Clock.hhmm("2026-10-16T08:00:00.000Z", utc))
        assertEquals("08:00", Clock.hhmm("2026-10-16T10:00:00+02:00", utc))
        assertEquals("08:00", Clock.hhmm("1792137600000", utc))
        assertNull(Clock.hhmm("not a time", utc))
        assertEquals(15L, Clock.minutesSince("2026-10-16T08:00:00Z", Instant.parse("2026-10-16T08:15:30Z")))
    }

    @Test
    fun normalizesSpokenTimes() {
        assertEquals("10:00", Clock.normalizeTime("10"))
        assertEquals("09:30", Clock.normalizeTime("9.30"))
        assertEquals("14:05", Clock.normalizeTime(" 14:05 "))
        assertEquals("around noon", Clock.normalizeTime("around noon"))
        assertEquals("25:00", Clock.normalizeTime("25:00"))
        assertNull(Clock.normalizeTime(" "))
    }

    @Test
    fun queueInWords() {
        assertEquals("2 ahead · about 20 min", Queue.screen(3, 20))
        assertEquals("Next in line", Queue.screen(1, 0))
        assertEquals("Next in line · about 5 min", Queue.screen(1, 5))
        assertEquals("1 ahead", Queue.screen(2, null))
        assertNull(Queue.screen(null, null))

        assertEquals("Place 3 in Dr. One's queue, 2 patients ahead. Expected wait about 20 minutes.", Queue.facts("Dr. One", 3, 20))
        assertEquals("Next in line for Dr. One, nobody ahead. Expected wait: almost none.", Queue.facts("Dr. One", 1, 0))
        assertEquals("Place in Dr. One's queue is not known.", Queue.facts("Dr. One", null, null))
    }

    @Test
    fun checkInSentenceUsesOnlyKnownNumbers() {
        val known = Queue.checkInTask("Ada Test", "Dr. One", 2, 10)
        assertTrue(known.contains("1 patient is ahead"))
        assertTrue(known.contains("about 10 minutes"))
        assertTrue(known.contains("walk them over to Dr. One"))
        val many = Queue.checkInTask("Ada Test", "Dr. One", 4, 30)
        assertTrue(many.contains("3 patients are ahead"))
        // old backend without queue numbers: nothing to make up
        val unknown = Queue.checkInTask("Ada Test", "Dr. One", null, null)
        assertFalse(unknown.contains("ahead"))
        assertFalse(unknown.contains("minutes"))
    }

    @Test
    fun waitingStepShowsTheQueue() {
        val waiting = visit("notified").copy(queuePosition = 3, estimatedWaitMinutes = 20)
        assertEquals("2 ahead · about 20 min", Timeline.forVisit(waiting, notified = true, zone = utc).of(StepKey.WAITING).detail)
        assertNull(Timeline.forVisit(visit("called"), notified = true, zone = utc).of(StepKey.WAITING).detail)
        assertEquals(QueueInfo("Dr. One", 3, 20), Queue.of(waiting))
        assertNull(Queue.of(visit("called")))
    }

    @Test
    fun screenInfoTellsThePlannerTheGoalAndTheQueue() {
        val s = UiState(
            brain = BrainStatus(goal = "Waiting for patients"),
            header = "Ada Test · Dr. One",
            steps = Timeline.fresh("09:00"),
            queue = QueueInfo("Dr. One", 2, 10),
            opener = "Nova greeted a visitor who walked up and asked if they have an appointment.",
        )
        val info = s.screenInfo()
        assertTrue(info.contains("Nova right now: Waiting for patients."))
        assertTrue(info.contains("Queue: Place 2 in Dr. One's queue, 1 patient ahead. Expected wait about 10 minutes."))
        assertTrue(info.contains("asked if they have an appointment"))
    }

    @Test
    fun keepsOnlyWellFormedLanguageTags() {
        assertEquals("de", Lang.cleanTag("de"))
        assertEquals("pt-BR", Lang.cleanTag("pt_BR"))
        assertNull(Lang.cleanTag("German language"))
        assertNull(Lang.cleanTag(null))
        assertTrue(Lang("tr").instruction().contains("\"tr\""))
        assertTrue(Lang(null, "merhaba").instruction().contains("merhaba"))
    }
}
