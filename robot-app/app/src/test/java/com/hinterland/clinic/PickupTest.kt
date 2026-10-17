package com.hinterland.clinic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PickupTest {

    @Test
    fun remindsEvery45SecondsAndGivesUpAfter3Minutes() {
        val p = PickupTimeline()
        val steps = mutableListOf<Pair<Long, PickupStep>>()
        var t = 0L
        while (t <= 200_000) {
            val s = p.next(t)
            if (s != PickupStep.WAIT) steps += t to s
            if (s == PickupStep.TIMEOUT) break
            t += 1_000
        }
        assertEquals(
            listOf(45_000L to PickupStep.REMIND, 90_000L to PickupStep.REMIND, 135_000L to PickupStep.REMIND, 180_000L to PickupStep.TIMEOUT),
            steps,
        )
        assertEquals(3, p.remindersDone)
    }

    @Test
    fun timeUntilTheNextStep() {
        val p = PickupTimeline()
        assertEquals(45_000, p.msUntilNext(0))
        assertEquals(5_000, p.msUntilNext(40_000))
        p.next(45_000)
        assertEquals(45_000, p.msUntilNext(45_000))
        repeat(2) { p.next(180_000 - 1) }
        assertEquals(1, p.msUntilNext(180_000 - 1))
    }

    @Test
    fun ticketsAreOneLetterAndTwoDigits() {
        assertEquals("A17", Tickets.normalize("a17"))
        assertEquals("A17", Tickets.normalize(" A 17 "))
        assertEquals("B05", Tickets.normalize("B-05."))
        assertNull(Tickets.normalize("A7"))
        assertNull(Tickets.normalize("AB17"))
        assertNull(Tickets.normalize("17A"))
        assertNull(Tickets.normalize("A170"))
        assertNull(Tickets.normalize(""))
        assertNull(Tickets.normalize(null))
    }

    @Test
    fun sameTicket() {
        assertTrue(Tickets.same("a 17", "A17"))
        assertFalse(Tickets.same("A18", "A17"))
        assertFalse(Tickets.same(null, "A17"))
        assertFalse(Tickets.same("nonsense", "nonsense"))
    }
}
