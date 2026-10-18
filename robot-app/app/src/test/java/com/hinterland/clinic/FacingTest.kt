package com.hinterland.clinic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// after a check-back turn otto ends up facing the patient (field test b16: still 79 deg to the right while he
// talked): a correction turn the shorter way when they are more than 15 deg off, at most twice
class FacingTest {

    @Test
    fun aPatientOffToTheSideGetsACorrectionTheShorterWay() {
        // the b16 case: the patient at -79 deg after the turn
        assertEquals(Turn(79f, left = false), Facing.correction(-79.0, done = 0))
        assertEquals(Turn(40f, left = true), Facing.correction(40.0, done = 1))
        // behind: the shorter way, at most 180 deg
        assertEquals(Turn(170f, left = false), Facing.correction(-170.0, done = 0))
        assertEquals(Turn(100f, left = false), Facing.correction(260.0, done = 0))
    }

    @Test
    fun within15DegIsFacingAndTwoCorrectionsAreTheMost() {
        assertNull(Facing.correction(4.0, done = 0))
        assertNull(Facing.correction(-15.0, done = 0))
        assertEquals(Turn(16f, left = false), Facing.correction(-16.0, done = 0))
        // after two corrections otto talks, wherever the patient is
        assertNull(Facing.correction(-60.0, done = 2))
        // nothing seen: no correction
        assertNull(Facing.correction(null, done = 0))
    }

    @Test
    fun drivingOnTurnsBackByTheTotalTheShorterWay() {
        // turned right 103 deg and right 79 deg more: back 178 deg to the left
        assertEquals(Turn(178f, left = true), Facing.back(-182.0 + 4.0))
        // turned 200 deg to the right in all: back 160 deg to the right (the shorter way)
        assertEquals(Turn(160f, left = false), Facing.back(-200.0))
        assertEquals(Turn(60f, left = false), Facing.back(60.0))
        assertNull(Facing.back(10.0))
    }
}
