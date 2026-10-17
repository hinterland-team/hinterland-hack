package com.hinterland.clinic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// place names here are test data only - the app gets them from GET /api/places
class PlacesTest {

    private val reception = Place("p1", "Front Desk", spot = "Desk Spot", kind = PlaceKind.RECEPTION)
    private val exit = Place("p2", "Way Out", spot = "Door Spot", kind = PlaceKind.EXIT)
    private val waiting = Place("p3", "Waiting Area", spot = "Seats", kind = PlaceKind.WAITING)
    private val roomOne = Place("p4", "Dr. One", room = "A101", spot = "Room One", kind = PlaceKind.ROOM, selfCheckIn = true, waitingPlaceId = "p3")
    private val roomTwo = Place("p5", "Dr. Two", room = "A102", spot = "Room Two", kind = PlaceKind.ROOM)
    private val toilets = Place("p6", "Toilets", spot = "WC", kind = PlaceKind.OTHER, public = true)
    private val closed = Place("p7", "Old Room", spot = "Old", kind = PlaceKind.OTHER, public = true, active = false)
    private val all = listOf(reception, exit, waiting, roomOne, roomTwo, toilets, closed)

    @Test
    fun publicMeansMarkedPlusExitAndReception() {
        assertEquals(listOf(reception, exit, toilets), Places.public(all))
    }

    @Test
    fun selfCheckInRoomsOnly() {
        assertEquals(listOf(roomOne), Places.selfCheckIn(all))
    }

    @Test
    fun homeSpotIsTheReception() {
        assertEquals("Desk Spot", Places.homeSpot(all, "Fallback"))
        assertEquals("Fallback", Places.homeSpot(listOf(exit), " Fallback "))
        assertEquals("", Places.homeSpot(emptyList(), ""))
    }

    @Test
    fun findsByNameOrRoomNumber() {
        assertEquals(exit, Places.find("way out", all))
        assertEquals(roomTwo, Places.find("A102", all))
        assertEquals(roomTwo, Places.find("room a102", all))
        assertEquals(roomOne, Places.find("Doctor One", all))
        assertNull(Places.find("Pharmacy", all))
        assertNull(Places.find("  ", all))
    }

    @Test
    fun selfCheckInGoesToTheWaitingPlace() {
        val t = Places.checkInTarget(roomOne, all)
        assertEquals(waiting, t.to)
        assertEquals(roomOne, t.forPlace)
        // no waiting place (or a closed one): straight to the room
        assertEquals(roomTwo, Places.checkInTarget(roomTwo, all).to)
        assertEquals(roomOne, Places.checkInTarget(roomOne, all - waiting).to)
    }

    @Test
    fun labels() {
        assertEquals("Room A101", roomOne.label)
        assertEquals("Way Out", exit.label)
        assertEquals("Room A101 (Dr. One), Toilets", Places.spoken(listOf(roomOne, toilets)))
    }
}
