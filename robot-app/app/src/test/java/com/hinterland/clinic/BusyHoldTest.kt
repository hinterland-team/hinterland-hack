package com.hinterland.clinic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// the busy hold nova clinic sends to nova deploy while she drives
class BusyHoldTest {

    private val now = 10_000_000_000L

    @Test
    fun nothingWhileNovaStandsStill() {
        val h = BusyHold()
        for (s in listOf(null, RobotState.IDLE, RobotState.GREETING, RobotState.TALKING, RobotState.CHARGING, RobotState.ERROR)) {
            assertNull(h.next(s, now))
        }
    }

    @Test
    fun holdWhileMovingRenewedEveryFifteenSeconds() {
        val h = BusyHold()
        assertEquals(HoldMessage(60, "Walking to a room"), h.next(RobotState.WALKING, now))
        assertNull(h.next(RobotState.WALKING, now + 1_000))
        assertNull(h.next(RobotState.WALKING, now + 14_999))
        assertEquals(HoldMessage(60, "Walking to a room"), h.next(RobotState.WALKING, now + 15_000))
        assertNull(h.next(RobotState.WALKING, now + 16_000))
    }

    @Test
    fun newReasonGoesOutAtOnce() {
        val h = BusyHold()
        assertEquals("Fetching a patient", h.next(RobotState.FETCHING, now)!!.reason)
        assertEquals("Walking to a room", h.next(RobotState.WALKING, now + 2_000)!!.reason)
        assertEquals("Driving back", h.next(RobotState.RETURNING, now + 4_000)!!.reason)
    }

    @Test
    fun releaseOnceWhenDone() {
        val h = BusyHold()
        h.next(RobotState.RETURNING, now)
        assertEquals(HoldMessage(0, ""), h.next(RobotState.IDLE, now + 3_000))
        assertNull(h.next(RobotState.IDLE, now + 4_000))
        assertNull(h.next(null, now + 5_000))
        // the task ended without a state (null) also releases
        h.next(RobotState.WALKING, now + 6_000)
        assertEquals(0, h.next(null, now + 7_000)!!.seconds)
    }

    @Test
    fun clockJumpBackRenews() {
        val h = BusyHold()
        h.next(RobotState.WALKING, now)
        assertEquals(60, h.next(RobotState.WALKING, now - 5_000)!!.seconds)
    }

    @Test
    fun renewsLongBeforeTheHoldRunsOut() {
        // nova deploy drops a hold that is not renewed; three renewals fit in one hold
        assertTrue(BusyHold.RENEW_MS * 3 < BusyHold.SECONDS * 1000L)
        assertNull(BusyHold.reason(RobotState.TALKING))
    }
}
