package com.hinterland.clinic

// places come from GET /api/places (edited on the dashboard), never from code. plain kotlin, unit tested.

object PlaceKind {
    const val ROOM = "room"
    const val EXIT = "exit"
    const val RECEPTION = "reception"
    const val WAITING = "waiting"
    const val OTHER = "other"
}

data class Place(
    val id: String,
    val name: String,
    val room: String? = null,
    val spot: String,
    val kind: String = PlaceKind.OTHER,
    val public: Boolean = false,
    val avgMinutes: Int? = null,
    val active: Boolean = true,
    val selfCheckIn: Boolean = false,
    val waitingPlaceId: String? = null,
) {
    // what nova calls it: "Room B204" when it has a room number, else its name
    val label: String get() = room?.let { "Room $it" } ?: name
}

// where a self check-in goes: the room's waiting place (or the room itself), for the room
data class CheckInTarget(val to: Place, val forPlace: Place)

object Places {

    // patients may ask for these: places marked public, plus exit and reception (always public)
    fun public(places: List<Place>): List<Place> =
        places.filter { it.active && (it.public || it.kind == PlaceKind.EXIT || it.kind == PlaceKind.RECEPTION) }

    // rooms a patient can check in for on the robot screen
    fun selfCheckIn(places: List<Place>): List<Place> =
        places.filter { it.active && it.selfCheckIn }

    fun reception(places: List<Place>): Place? =
        places.firstOrNull { it.active && it.kind == PlaceKind.RECEPTION && it.spot.isNotBlank() }

    // where nova waits between guides: the reception place, else robot.homeSpot from the build
    fun homeSpot(places: List<Place>, fallback: String): String =
        reception(places)?.spot?.trim()?.takeIf { it.isNotEmpty() } ?: fallback.trim()

    // by name or by room number, language-free (Names)
    fun find(query: String?, places: List<Place>): Place? =
        Names.match(query, places) { it.name }
            ?: Names.match(query, places.filter { it.room != null }) { it.room }
            ?: Names.match(query, places.filter { it.room != null }) { it.label }

    fun checkInTarget(room: Place, places: List<Place>): CheckInTarget {
        val waiting = room.waitingPlaceId?.let { id -> places.firstOrNull { it.id == id && it.active } }
        return CheckInTarget(to = waiting ?: room, forPlace = room)
    }

    // english list for the llm and the screen info, e.g. "Exit, Reception, Room B204 (Dr. Blue)"
    fun spoken(places: List<Place>): String =
        places.joinToString(", ") { p -> if (p.room != null) "${p.label} (${p.name})" else p.name }
}
