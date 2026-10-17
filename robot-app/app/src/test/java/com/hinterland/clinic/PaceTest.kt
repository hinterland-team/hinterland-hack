package com.hinterland.clinic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaceTest {

    @Test
    fun speedsFromTheContract() {
        assertEquals(0.6, PaceTable.speed(Pace.NORMAL).linear, 1e-9)
        assertEquals(0.4, PaceTable.speed(Pace.SLOW).linear, 1e-9)
        assertEquals(0.25, PaceTable.speed(Pace.VERY_SLOW).linear, 1e-9)
    }

    @Test
    fun everySpeedIsInsideWhatStartNavigationAccepts() {
        // RobotAPI_en.md: linearSpeed 0.1-0.85 m/s, angularSpeed 0.4-1.4 rad/s
        for (p in Pace.values()) {
            val s = PaceTable.speed(p)
            assertTrue("$p linear ${s.linear}", s.linear in 0.1..0.85)
            assertTrue("$p angular ${s.angular}", s.angular in 0.4..1.4)
        }
    }

    @Test
    fun angularFollowsTheDocFormula() {
        // angularSpeed = 0.4 + (linearSpeed - 0.1) / 3 * 4
        assertEquals(0.4 + 0.5 / 3 * 4, PaceTable.speed(Pace.NORMAL).angular, 1e-9)
        assertEquals(0.8, PaceTable.speed(Pace.SLOW).angular, 1e-9)
        assertEquals(0.6, PaceTable.speed(Pace.VERY_SLOW).angular, 1e-9)
        // clamped at both ends
        assertEquals(0.4, PaceTable.angularFor(0.05), 1e-9)
        assertEquals(1.4, PaceTable.angularFor(2.0), 1e-9)
    }

    @Test
    fun slowerAndFasterStopAtTheEnds() {
        assertEquals(Pace.SLOW, Pace.NORMAL.slower())
        assertEquals(Pace.VERY_SLOW, Pace.SLOW.slower())
        assertEquals(Pace.VERY_SLOW, Pace.VERY_SLOW.slower())
        assertEquals(Pace.SLOW, Pace.VERY_SLOW.faster())
        assertEquals(Pace.NORMAL, Pace.SLOW.faster())
        assertEquals(Pace.NORMAL, Pace.NORMAL.faster())
        assertTrue(Pace.VERY_SLOW.isSlowerThan(Pace.SLOW))
        assertFalse(Pace.NORMAL.isSlowerThan(Pace.SLOW))
    }

    @Test
    fun wireValues() {
        assertEquals(listOf("normal", "slow", "very_slow"), Pace.values().map { it.wire })
        assertEquals(Pace.VERY_SLOW, Pace.of(" Very_Slow "))
        assertNull(Pace.of("fast"))
        assertNull(Pace.of(null))
    }

    @Test
    fun wheelchairWalksSlowAndTheSlowerOneWins() {
        assertEquals(Pace.NORMAL, PaceRules.start(Pace.NORMAL, wheelchair = false))
        assertEquals(Pace.SLOW, PaceRules.start(Pace.NORMAL, wheelchair = true))
        assertEquals(Pace.SLOW, PaceRules.start(Pace.SLOW, wheelchair = true))
        assertEquals(Pace.VERY_SLOW, PaceRules.start(Pace.VERY_SLOW, wheelchair = true))
    }

    @Test
    fun asksOnlyWhenNobodyChoseSlower() {
        assertTrue(PaceRules.shouldAsk(Pace.NORMAL, wheelchair = false, chosenByPatient = false))
        assertFalse(PaceRules.shouldAsk(Pace.SLOW, wheelchair = false, chosenByPatient = false))
        assertFalse(PaceRules.shouldAsk(Pace.VERY_SLOW, wheelchair = false, chosenByPatient = false))
        assertFalse(PaceRules.shouldAsk(Pace.NORMAL, wheelchair = true, chosenByPatient = false))
        // picked on the self check-in screen already
        assertFalse(PaceRules.shouldAsk(Pace.NORMAL, wheelchair = false, chosenByPatient = true))
    }
}
