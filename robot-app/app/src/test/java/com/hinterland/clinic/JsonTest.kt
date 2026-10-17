package com.hinterland.clinic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonTest {

    private val visitJson = """
        {"id":"v1","patientName":"Ada Test","doctorId":"d1","doctorName":"Dr. One","appointmentTime":"10:00",
         "language":"de","robot":"nova","status":"notified","createdAt":"2026-10-16T08:00:00.000Z",
         "updatedAt":"2026-10-16T08:01:00.000Z",
         "events":[{"at":"2026-10-16T08:00:00.000Z","type":"checked_in"},
                   {"at":"2026-10-16T08:01:00.000Z","type":"notified","detail":null}]}
    """.trimIndent()

    @Test
    fun parsesCheckInResponse() {
        val r = Json.checkIn("""{"visit":$visitJson,"notified":true,"doctorKnown":true,"spot":"Room One"}""")
        assertEquals("v1", r.visit.id)
        assertEquals("Ada Test", r.visit.patientName)
        assertEquals("Dr. One", r.visit.doctorName)
        assertEquals("de", r.visit.language)
        assertEquals(2, r.visit.events.size)
        assertNull(r.visit.events[1].detail)
        assertTrue(r.notified)
        assertTrue(r.doctorKnown)
        assertEquals("Room One", r.spot)
    }

    @Test
    fun checkInWithUnknownDoctorAndNoSpot() {
        val r = Json.checkIn("""{"visit":$visitJson,"notified":false,"doctorKnown":false,"spot":null}""")
        assertFalse(r.notified)
        assertFalse(r.doctorKnown)
        assertNull(r.spot)
    }

    @Test
    fun parsesVisitRawOrWrapped() {
        assertEquals("v1", Json.visit(visitJson).id)
        assertEquals("notified", Json.visit("""{"visit":$visitJson}""").status)
    }

    @Test
    fun jsonNullIsNotTheWordNull() {
        val v = Json.visit("""{"id":"v2","patientName":"B","doctorName":"Dr. Two","appointmentTime":null,"language":null,"status":"checked_in"}""")
        assertNull(v.appointmentTime)
        assertNull(v.language)
        assertTrue(v.events.isEmpty())
    }

    @Test
    fun parsesCommand() {
        val body = """{"id":"c1","robot":"nova","type":"call_patient","visitId":"v1","patientName":"Ada Test",
            "doctorName":"Dr. One","spot":"Room One","createdAt":"2026-10-16T08:05:00.000Z"}"""
        val c = Json.command(body)!!
        assertEquals("c1", c.id)
        assertEquals(RobotCommand.CALL_PATIENT, c.type)
        assertEquals("v1", c.visitId)
        assertEquals("Room One", c.spot)
        assertEquals("c1", Json.command("""{"command":$body}""")!!.id)
    }

    @Test
    fun emptyCommandBodyMeansNothing() {
        assertNull(Json.command(""))
        assertNull(Json.command("null"))
        assertNull(Json.command("{}"))
        assertNull(Json.command("""{"command":null}"""))
    }

    @Test
    fun parsesDoctorsArrayOrWrapped() {
        val arr = """[{"id":"d1","name":"Dr. One","spot":"Room One","active":true},{"id":"d2","name":"Dr. Two","active":false}]"""
        val list = Json.doctors(arr)
        assertEquals(2, list.size)
        assertEquals("Room One", list[0].spot)
        assertNull(list[1].spot)
        assertFalse(list[1].active)
        assertEquals(2, Json.doctors("""{"doctors":$arr}""").size)
    }

    @Test
    fun parsesVisitList() {
        assertEquals(1, Json.visits("[$visitJson]").size)
        assertEquals(1, Json.visits("""{"visits":[$visitJson]}""").size)
        assertTrue(Json.visits("").isEmpty())
    }

    @Test
    fun readsBackendError() {
        assertEquals("unauthorized", Json.errorMessage("""{"error":"unauthorized"}"""))
        assertNull(Json.errorMessage("<html>"))
    }

    // ---- v2: queue, more commands, heartbeat, battery, help ----

    @Test
    fun parsesQueueFields() {
        val v = Json.visit(
            """{"id":"v3","patientName":"A","doctorName":"Dr. One","status":"notified","queuePosition":3,"estimatedWaitMinutes":20}""",
        )
        assertEquals(3, v.queuePosition)
        assertEquals(20, v.estimatedWaitMinutes)

        val notWaiting = Json.visit(
            """{"id":"v4","patientName":"A","doctorName":"Dr. One","status":"called","queuePosition":null,"estimatedWaitMinutes":null}""",
        )
        assertNull(notWaiting.queuePosition)
        assertNull(notWaiting.estimatedWaitMinutes)

        // a backend without the fields
        assertNull(Json.visit(visitJson).queuePosition)
        assertNull(Json.visit(visitJson).estimatedWaitMinutes)

        // numbers as strings or floats still work, nonsense does not
        val loose = Json.visit("""{"id":"v5","queuePosition":"2","estimatedWaitMinutes":5.0}""")
        assertEquals(2, loose.queuePosition)
        assertEquals(5, loose.estimatedWaitMinutes)
        assertNull(Json.visit("""{"id":"v6","queuePosition":0,"estimatedWaitMinutes":-3}""").queuePosition)
        assertNull(Json.visit("""{"id":"v6","queuePosition":"soon"}""").queuePosition)
    }

    @Test
    fun checkInResponseCarriesTheQueue() {
        val r = Json.checkIn(
            """{"visit":{"id":"v7","patientName":"A","doctorName":"Dr. One","status":"checked_in",
               "queuePosition":1,"estimatedWaitMinutes":0},"notified":true,"doctorKnown":true,"spot":"Room One"}""",
        )
        assertEquals(1, r.visit.queuePosition)
        assertEquals(0, r.visit.estimatedWaitMinutes)
    }

    @Test
    fun parsesSayAndGoToCommands() {
        val say = Json.command("""{"id":"c2","robot":"nova","type":"say","text":"The practice closes at six","createdAt":"2026-10-17T08:00:00Z"}""")!!
        assertEquals(RobotCommand.SAY, say.type)
        assertEquals("The practice closes at six", say.text)
        assertNull(say.spot)
        assertNull(say.visitId)

        val goTo = Json.command("""{"id":"c3","robot":"nova","type":"go_to","spot":"Room Two","createdAt":"2026-10-17T08:00:00Z"}""")!!
        assertEquals(RobotCommand.GO_TO, goTo.type)
        assertEquals("Room Two", goTo.spot)
        assertNull(goTo.text)
        assertNull(goTo.patientName)
    }

    @Test
    fun buildsTheHeartbeat() {
        val walking = Json.heartbeat(
            "nova",
            BrainStatus(goal = "Walking Ada Test to Dr. One", state = RobotState.WALKING, battery = 64, charging = false),
        )
        assertEquals("nova", walking.getString("robot"))
        assertEquals(64, walking.getInt("battery"))
        assertFalse(walking.getBoolean("charging"))
        assertEquals("walking", walking.getString("state"))
        assertTrue(walking.isNull("position"))
        assertEquals("Walking Ada Test to Dr. One", walking.getString("goal"))
        // exactly the keys from docs/api.md; detail only when there is one
        assertEquals(setOf("robot", "battery", "charging", "state", "position", "goal"), walking.keys().asSequence().toSet())

        val docked = Json.heartbeat(
            "nova",
            BrainStatus(goal = "", state = RobotState.CHARGING, battery = null, charging = true, position = "Room One", detail = "Could not get off the charger"),
        )
        assertTrue(docked.has("battery"))
        assertTrue(docked.isNull("battery"))
        assertTrue(docked.getBoolean("charging"))
        assertEquals("charging", docked.getString("state"))
        assertEquals("Room One", docked.getString("position"))
        assertTrue(docked.isNull("goal"))
        assertEquals("Could not get off the charger", docked.getString("detail"))

        // every state has the wire name from the contract
        assertEquals(
            listOf("idle", "greeting", "talking", "fetching", "walking", "returning", "charging", "error"),
            RobotState.values().map { it.wire },
        )
    }

    @Test
    fun parsesBatteryStatus() {
        assertEquals(PowerReading(80, false), Json.power("""{"level":80,"bmsTemp":30,"isCharging":false,"isLow":false}"""))
        assertEquals(PowerReading(15, true), Json.power("""{"level":"15","isCharging":"true"}"""))
        assertEquals(PowerReading(55, null), Json.power("55"))
        assertEquals(PowerReading(null, true), Json.power("""{"charging":true}"""))
        // out of range is "unknown", the rest of the reading still counts
        assertEquals(PowerReading(null, false), Json.power("""{"level":140,"isCharging":false}"""))
        assertNull(Json.power("140"))
        assertNull(Json.power(""))
        assertNull(Json.power(null))
        assertNull(Json.power("not json"))
        assertNull(Json.power("""{"bmsTemp":30}"""))
    }

    @Test
    fun readsHelpIds() {
        assertEquals("h1", Json.helpId("""{"ok":true,"id":"h1","notified":false,"help":{"id":"h1"}}"""))
        assertNull(Json.helpId("{}"))
        assertNull(Json.helpId("<html>"))
        val open = Json.openHelpIds("""[{"id":"h1","status":"open"},{"id":"h2","status":"done"},{"id":"h3"}]""")
        assertEquals(setOf("h1", "h3"), open)
        assertEquals(setOf("h1"), Json.openHelpIds("""{"help":[{"id":"h1","status":"open"}]}"""))
        assertTrue(Json.openHelpIds("[]").isEmpty())
    }
}
