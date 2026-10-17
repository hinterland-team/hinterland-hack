package com.hinterland.clinic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// names here are test data only - the app gets them from the backend and the robot map
class NamesTest {

    private val doctors = listOf(
        Doctor("d1", "Dr. One", "Room A", true),
        Doctor("d2", "Dr. Two", "Room B", true),
        Doctor("d3", "Dr. Three", "Room C", false),
    )

    @Test
    fun ignoresCaseSpacesAndDots() {
        assertTrue(Names.same("Dr One", "Dr. One"))
        assertTrue(Names.same("dr.one", "Dr. One"))
        assertFalse(Names.same("Dr. Two", "Dr. One"))
    }

    @Test
    fun matchesOnLastWordWhenUnique() {
        assertTrue(Names.same("Doctor One", "Dr. One"))
        assertTrue(Names.same("One", "Dr. One"))
    }

    @Test
    fun ambiguousLastWordIsNoMatch() {
        val people = listOf("Anna Smith", "Ben Smith")
        assertNull(Names.match("Mrs Smith", people) { it })
        assertEquals("Ben Smith", Names.match("ben smith", people) { it })
    }

    @Test
    fun spotFromKnownVisitFirst() {
        val spot = Names.resolveSpot("dr one", known = listOf("Dr. One" to "Room X"), doctors = doctors)
        assertEquals("Room X", spot)
    }

    @Test
    fun spotFromDoctorListSkipsInactive() {
        assertEquals("Room B", Names.resolveSpot("Doctor Two", doctors = doctors))
        assertNull(Names.resolveSpot("Dr. Three", doctors = doctors))
    }

    @Test
    fun spotFromMapPlacesLast() {
        val places = listOf("Reception", "Dr. Four", "Charging")
        assertEquals("Dr. Four", Names.resolveSpot("dr four", doctors = doctors, places = places))
        assertNull(Names.resolveSpot("Dr. Five", doctors = doctors, places = places))
    }

    @Test
    fun blankQueryMatchesNothing() {
        assertNull(Names.resolveSpot("  ", doctors = doctors, places = listOf("Room A")))
    }
}
