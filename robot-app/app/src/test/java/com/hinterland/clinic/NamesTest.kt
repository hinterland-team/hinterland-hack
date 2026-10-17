package com.hinterland.clinic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// names here are test data only - the app gets them from the backend and the robot map
class NamesTest {

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
        val rooms = listOf("Room North", "Hall North")
        assertNull(Names.match("the north", rooms) { it })
        assertEquals("Hall North", Names.match("hall north", rooms) { it })
    }

    @Test
    fun blankMatchesNothing() {
        assertNull(Names.match("  ", listOf("Room A")) { it })
        assertFalse(Names.same("x", null))
    }
}
