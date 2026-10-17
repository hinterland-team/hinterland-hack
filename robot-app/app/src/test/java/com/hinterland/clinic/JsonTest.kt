package com.hinterland.clinic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// shapes from docs/journeys.md. place names are test data only.
class JsonTest {

    private val journeyJson = """
        {"id":"j1","ticket":"A17","robot":"nova","pace":"slow","wheelchair":true,"status":"walking",
         "legs":[{"id":"l1","fromPlaceId":"p1","toPlaceId":"p4","toName":"Dr. One","toSpot":"Room One","status":"arrived"},
                 {"id":"l2","fromPlaceId":"p4","toPlaceId":"p2","toName":"Way Out","toSpot":"Door Spot","status":"walking"}],
         "createdBy":"staff","createdAt":"2026-10-17T08:00:00Z","updatedAt":"2026-10-17T08:05:00Z",
         "events":[{"at":"2026-10-17T08:00:00Z","type":"created"}],
         "queuePosition":2,"estimatedWaitMinutes":10}
    """.trimIndent()

    @Test
    fun parsesAJourneyRawOrWrapped() {
        val j = Json.journey(journeyJson)
        assertEquals("j1", j.id)
        assertEquals("A17", j.ticket)
        assertEquals(Pace.SLOW, j.pace)
        assertTrue(j.wheelchair)
        assertEquals(JourneyStatus.WALKING, j.status)
        assertEquals(2, j.legs.size)
        // the current leg is the last one
        assertEquals("l2", j.leg?.id)
        assertEquals("Way Out", j.leg?.toName)
        assertEquals(2, j.queuePosition)
        assertEquals(10, j.estimatedWaitMinutes)
        assertEquals("j1", Json.journey("""{"journey":$journeyJson}""").id)
    }

    @Test
    fun journeyDefaultsAndNonsense() {
        val j = Json.journey("""{"id":"j2","ticket":"B03","pace":"fast","queuePosition":0,"estimatedWaitMinutes":-1}""")
        assertEquals(Pace.NORMAL, j.pace)
        assertFalse(j.wheelchair)
        assertEquals(JourneyStatus.WAITING_PICKUP, j.status)
        assertNull(j.leg)
        assertNull(j.queuePosition)
        assertNull(j.estimatedWaitMinutes)
        assertTrue(JourneyStatus.closed("done"))
        assertTrue(JourneyStatus.closed("cancelled"))
        assertFalse(JourneyStatus.closed("arrived"))
    }

    @Test
    fun parsesAGuideCommand() {
        val body = """{"id":"c1","robot":"nova","type":"guide","journeyId":"j1","legId":"l1","ticket":"a17","pace":"very_slow",
            "wheelchair":false,"from":{"name":"Front Desk","spot":"Desk Spot"},"to":{"name":"Dr. One","spot":"Room One","room":"A101"},
            "createdAt":"2026-10-17T08:00:00Z"}"""
        val c = Json.command(body)!!
        assertEquals(RobotCommand.GUIDE, c.type)
        assertEquals("j1", c.journeyId)
        assertEquals("l1", c.legId)
        assertEquals("A17", c.ticket)
        assertEquals(Pace.VERY_SLOW, c.pace)
        assertFalse(c.wheelchair)
        assertEquals(Stop("Front Desk", "Desk Spot"), c.from)
        assertEquals("Room One", c.to?.spot)
        assertEquals("Room A101", c.to?.label)
        assertEquals("c1", Json.command("""{"command":$body}""")!!.id)
    }

    @Test
    fun stopWithoutSpotUsesItsName() {
        val c = Json.command("""{"id":"c2","type":"guide","to":{"name":"Way Out"},"from":{}}""")!!
        assertEquals(Stop("Way Out", "Way Out"), c.to)
        assertNull(c.from)
        assertNull(c.pace)
    }

    @Test
    fun parsesSayGoToAndAsk() {
        val say = Json.command("""{"id":"c3","robot":"nova","type":"say","text":"The practice closes at six"}""")!!
        assertEquals(RobotCommand.SAY, say.type)
        assertEquals("The practice closes at six", say.text)
        val goTo = Json.command("""{"id":"c4","robot":"nova","type":"go_to","spot":"Room Two"}""")!!
        assertEquals("Room Two", goTo.spot)
        assertNull(goTo.journeyId)
        val ask = Json.command("""{"id":"c5","robot":"nova","type":"ask","text":"Please greet the next visitor in Turkish"}""")!!
        assertEquals(RobotCommand.ASK, ask.type)
        assertEquals("Please greet the next visitor in Turkish", ask.text)
    }

    @Test
    fun emptyCommandBodyMeansNothing() {
        assertNull(Json.command(""))
        assertNull(Json.command("null"))
        assertNull(Json.command("{}"))
        assertNull(Json.command("""{"command":null}"""))
    }

    @Test
    fun parsesJourneyCreated() {
        val r = Json.journeyCreated("""{"journey":$journeyJson,"command":{"id":"c9","type":"guide","journeyId":"j1","legId":"l2","ticket":"A17","to":{"name":"Way Out","spot":"Door Spot"}}}""")
        assertEquals("j1", r.journey?.id)
        assertEquals("c9", r.command?.id)
        val noCommand = Json.journeyCreated("""{"journey":$journeyJson}""")
        assertNull(noCommand.command)
    }

    @Test
    fun parsesPlacesArrayOrWrapped() {
        val arr = """[{"id":"p1","name":"Front Desk","spot":"Desk Spot","kind":"reception","public":true,"avgMinutes":5,"active":true},
            {"id":"p4","name":"Dr. One","room":"A101","kind":"room","selfCheckIn":true,"waitingPlaceId":"p3"},
            {"id":"p9","kind":"room"}]"""
        val list = Json.places(arr)
        // a place without a name is skipped
        assertEquals(2, list.size)
        assertEquals(PlaceKind.RECEPTION, list[0].kind)
        assertTrue(list[0].public)
        assertEquals(5, list[0].avgMinutes)
        // spot defaults to the name
        assertEquals("Dr. One", list[1].spot)
        assertTrue(list[1].selfCheckIn)
        assertEquals("p3", list[1].waitingPlaceId)
        assertTrue(list[1].active)
        assertFalse(list[1].public)
        assertEquals(2, Json.places("""{"places":$arr}""").size)
        assertTrue(Json.places("").isEmpty())
    }

    @Test
    fun patchBody() {
        val full = Json.journeyPatch("l1", "walking", "walking", "Walking at slow pace")
        assertEquals("l1", full.getString("legId"))
        assertEquals("walking", full.getString("status"))
        assertEquals("walking", full.getJSONObject("event").getString("type"))
        assertEquals("Walking at slow pace", full.getJSONObject("event").getString("detail"))
        val eventOnly = Json.journeyPatch(null, null, "feedback", "up")
        assertEquals(setOf("event"), eventOnly.keys().asSequence().toSet())
        val noDetail = Json.journeyPatch("l1", "failed", "failed", " ")
        assertFalse(noDetail.getJSONObject("event").has("detail"))
    }

    @Test
    fun buildsTheHeartbeatWithTheScreen() {
        val walking = Json.heartbeat(
            "nova",
            BrainStatus(goal = "Guiding A17 to Room B204", state = RobotState.WALKING, battery = 64, charging = false),
            ScreenMirror(view = "walking", title = "A17 to Room B204", ticket = "A17", step = "Walking with you", progress = 0.456f),
        )
        assertEquals("nova", walking.getString("robot"))
        assertEquals(64, walking.getInt("battery"))
        assertEquals("walking", walking.getString("state"))
        assertTrue(walking.isNull("position"))
        assertEquals("Guiding A17 to Room B204", walking.getString("goal"))
        val screen = walking.getJSONObject("screen")
        assertEquals("walking", screen.getString("view"))
        assertEquals("A17", screen.getString("ticket"))
        assertEquals("Walking with you", screen.getString("step"))
        assertEquals(0.45, screen.getDouble("progress"), 1e-9)

        val idle = Json.heartbeat("nova", BrainStatus(goal = "", battery = null, charging = true, detail = "Could not get off the charger"))
        assertTrue(idle.isNull("battery"))
        assertTrue(idle.isNull("goal"))
        assertFalse(idle.has("screen"))
        assertEquals("Could not get off the charger", idle.getString("detail"))
        val bare = Json.screen(ScreenMirror(view = "idle", title = "Hello, I'm Otto"))
        assertEquals(setOf("view", "title"), bare.keys().asSequence().toSet())
        assertEquals(
            listOf("idle", "greeting", "talking", "fetching", "walking", "returning", "charging", "error"),
            RobotState.values().map { it.wire },
        )
    }

    @Test
    fun buildsTheFeed() {
        val body = Json.feed(
            "nova", "j1",
            listOf(FeedItem(0L, FeedItem.OTTO, FeedItem.SAID, "Please follow me"), FeedItem(1_000L, FeedItem.PATIENT, FeedItem.INTENT, "Asked to walk slower")),
        )
        assertEquals("nova", body.getString("robot"))
        assertEquals("j1", body.getString("journeyId"))
        val items = body.getJSONArray("items")
        assertEquals(2, items.length())
        assertEquals("1970-01-01T00:00:00Z", items.getJSONObject(0).getString("at"))
        assertEquals("patient", items.getJSONObject(1).getString("who"))
        assertEquals("intent", items.getJSONObject(1).getString("kind"))
        assertFalse(Json.feed("nova", null, emptyList()).has("journeyId"))
        assertEquals(true, Json.showPatientText("""{"ok":true,"showPatientText":true}"""))
        assertEquals(false, Json.showPatientText("""{"showPatientText":false}"""))
        assertNull(Json.showPatientText("{}"))
        assertNull(Json.showPatientText("<html>"))
    }

    @Test
    fun parsesPoseAndMapPlaces() {
        assertEquals(Point(1.5, -2.0), Json.pose("""{"px":1.5,"py":-2.0,"theta":0.3}"""))
        assertEquals(Point(1.0, 2.0), Json.pose("""{"x":"1","y":2}"""))
        assertNull(Json.pose("""{"theta":1}"""))
        assertNull(Json.pose(null))
        val map = Json.mapPlaces("""[{"name":"Lift","x":0.5,"y":1.0,"theta":0,"id":"a","status":0},{"name":"Bad"},{"x":1,"y":1}]""")
        assertEquals(listOf(MapPlace("Lift", Point(0.5, 1.0))), map)
        assertTrue(Json.mapPlaces("oops").isEmpty())
    }

    @Test
    fun parsesBatteryStatus() {
        assertEquals(PowerReading(80, false), Json.power("""{"level":80,"bmsTemp":30,"isCharging":false,"isLow":false}"""))
        assertEquals(PowerReading(15, true), Json.power("""{"level":"15","isCharging":"true"}"""))
        assertEquals(PowerReading(55, null), Json.power("55"))
        assertEquals(PowerReading(null, true), Json.power("""{"charging":true}"""))
        assertEquals(PowerReading(null, false), Json.power("""{"level":140,"isCharging":false}"""))
        assertNull(Json.power("140"))
        assertNull(Json.power(""))
        assertNull(Json.power(null))
        assertNull(Json.power("not json"))
        assertNull(Json.power("""{"bmsTemp":30}"""))
    }

    @Test
    fun readsHelpIdsAndErrors() {
        assertEquals("h1", Json.helpId("""{"ok":true,"id":"h1","notified":false,"help":{"id":"h1"}}"""))
        assertNull(Json.helpId("{}"))
        val open = Json.openHelpIds("""[{"id":"h1","status":"open"},{"id":"h2","status":"done"},{"id":"h3"}]""")
        assertEquals(setOf("h1", "h3"), open)
        assertEquals(setOf("h1"), Json.openHelpIds("""{"help":[{"id":"h1","status":"open"}]}"""))
        assertEquals("unauthorized", Json.errorMessage("""{"error":"unauthorized"}"""))
        assertNull(Json.errorMessage("<html>"))
    }

    @Test
    fun languages() {
        assertEquals(listOf("de", "en"), Lang.tagList("de, en"))
        assertEquals(listOf("de", "en-GB"), Lang.tagList("de;en_GB;de;German"))
        assertTrue(Lang.tagList("").isEmpty())
        assertTrue(Lang(null, tags = listOf("de", "en")).instruction().contains("de, en"))
        assertTrue(Lang("tr").instruction().contains("\"tr\""))
        assertTrue(Lang(null, "Wie weit ist es?").instruction().contains("Wie weit ist es?"))
        assertNull(Lang.cleanTag("German"))
    }
}
