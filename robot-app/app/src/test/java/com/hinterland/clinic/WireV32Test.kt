package com.hinterland.clinic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// v3.2 shapes: commands, robot data, the live map body, the QR code, the out-of-service and staff
// screens. place names are test data only.
class WireV32Test {

    // ---- commands ----

    @Test
    fun walkControlCommand() {
        val c = Json.command("""{"id":"c9","robot":"nova","type":"walk_control","journeyId":"j1","action":"Slower","createdAt":"x"}""")!!
        assertEquals(RobotCommand.WALK_CONTROL, c.type)
        assertEquals("j1", c.journeyId)
        assertEquals(WalkControl.SLOWER, WalkControl.of(c.action))
        assertEquals(WalkControl.RESUME, WalkControl.of("resume"))
        assertNull(WalkControl.of("dance"))
    }

    @Test
    fun guideCommandWithWalkCodeAndWaypoint() {
        val c = Json.command(
            """{"id":"c1","type":"guide","journeyId":"j1","legId":"l1","ticket":"a17","walkCode":"Kx7pQ2mZ",
               "from":{"name":"Front Desk","spot":"Desk Spot"},
               "to":{"name":"Dr. One","spot":"Room One","room":"B204","kind":"room","viaSpot":"Hall Corner"}}""",
        )!!
        assertEquals("A17", c.ticket)
        assertEquals("Kx7pQ2mZ", c.walkCode)
        assertEquals("Hall Corner", c.to?.viaSpot)
        assertNull(c.from?.viaSpot)
        val place = Json.place(org.json.JSONObject("""{"id":"p1","name":"Dr. One","spot":"Room One","viaSpot":"Hall Corner"}"""))
        assertEquals("Hall Corner", place.viaSpot)
    }

    @Test
    fun walkLinkForTheQrCode() {
        assertEquals("https://clinic.example.app/walk/Kx7pQ2mZ", WalkLink.url(null, "Kx7pQ2mZ", "https://clinic.example.app/"))
        // the server's own link wins
        assertEquals("https://phones.example/walk/abc", WalkLink.url("https://phones.example/walk/abc", "Kx7pQ2mZ", "http://10.0.0.20:3000"))
        assertNull(WalkLink.url(null, null, "https://clinic.example.app"))
        assertNull(WalkLink.url(null, "bad code/../x", "https://clinic.example.app"))
        assertNull(WalkLink.url(null, "Kx7pQ2mZ", ""))
        assertNull(WalkLink.url("javascript:alert(1)", null, null))
    }

    // ---- robot data ----

    @Test
    fun posesWithHeading() {
        val p = Json.robotPose("""{"px":1.5,"py":-2.25,"theta":1.57,"name":"","status":0}""")!!
        assertEquals(1.5, p.x, 1e-9)
        assertEquals(-2.25, p.y, 1e-9)
        assertEquals(1.57, p.theta, 1e-9)
        // a robot that reports degrees
        assertEquals(Math.toRadians(180.0), Json.robotPose("""{"x":0,"y":0,"theta":180}""")!!.theta, 1e-9)
        assertEquals(0.0, Json.robotPose("""{"px":1,"py":2}""")!!.theta, 1e-9)
        assertNull(Json.robotPose("nope"))
    }

    @Test
    fun radarDataInTheShapesItMayCome() {
        // a Gson list of Pose, as RobotAPI_en.md shows for STATUS_LINE_DATA
        val poses = Json.scan("""[{"px":1.0,"py":0.5,"theta":0.0,"time":1,"status":0,"distance":0.0},{"px":-0.8,"py":0.1}]""")!!
        assertEquals(listOf(Point(1.0, 0.5), Point(-0.8, 0.1)), poses)
        assertEquals(listOf(Point(2.0, 3.0)), Json.scan("""[[2,3],[1],["a","b"]]"""))
        assertEquals(1, Json.scan("""{"points":[{"x":1,"y":2}]}""")!!.size)
        assertTrue(Json.scan("[]")!!.isEmpty())
        assertNull(Json.scan("garbage"))
        assertNull(Json.scan(null))
    }

    @Test
    fun emergencyOnlyInClearForms() {
        assertEquals(true, Json.emergency("1"))
        assertEquals(false, Json.emergency("false"))
        assertEquals(true, Json.emergency("""{"status":1}"""))
        assertEquals(false, Json.emergency("""{"emergency":false}"""))
        assertNull(Json.emergency("pressed?"))
        assertNull(Json.emergency("""{"other":1}"""))
    }

    // ---- live map ----

    @Test
    fun mapReportIsSmallAndInTheMapFrame() {
        val scan = ScanSim(stepDeg = 0.25).corridor().scanInMap(RobotPose(5.0, 2.0, 0.3))
        assertTrue(scan.size > 1_000)
        val body = Json.mapReport(
            MapReport(
                robot = "nova",
                pose = RobotPose(5.12345, 2.0, 0.3 + 2 * Math.PI),
                mapName = "Floor 1",
                places = listOf(MapPlace("Room One", Point(9.876, -1.0))),
                scan = scan,
                follower = Point(4.0, 1.7),
                followerSource = "lidar",
                target = MapPlace("Room One", Point(9.876, -1.0)),
                remainingMeters = 5.6789,
            ),
        )
        assertEquals("Floor 1", body.getString("mapName"))
        val pose = body.getJSONObject("pose")
        assertEquals(5.123, pose.getDouble("x"), 1e-9)
        // theta comes in -pi..pi
        assertEquals(0.3, pose.getDouble("theta"), 1e-9)
        assertEquals(Json.MAP_SCAN_MAX, body.getJSONArray("scan").length())
        assertEquals(2, body.getJSONArray("scan").getJSONArray(0).length())
        assertEquals(9.88, body.getJSONArray("places").getJSONObject(0).getDouble("x"), 1e-9)
        assertEquals("lidar", body.getJSONObject("follower").getString("source"))
        assertEquals("Room One", body.getJSONObject("target").getString("name"))
        assertEquals(5.7, body.getDouble("remainingMeters"), 1e-9)
        // nothing optional when it is not there
        val bare = Json.mapReport(MapReport(robot = "nova", pose = RobotPose(0.0, 0.0, 0.0)))
        assertFalse(bare.has("places"))
        assertFalse(bare.has("scan"))
        assertFalse(bare.has("follower"))
        assertFalse(bare.has("mapName"))
    }

    @Test
    fun mapRates() {
        val r = MapRate()
        val t = 1_000_000L
        assertTrue(r.due(t, moving = false))
        r.sent(t)
        assertFalse(r.due(t + 900, moving = true))
        assertTrue(r.due(t + 1_000, moving = true))
        assertFalse(r.due(t + 14_000, moving = false))
        assertTrue(r.due(t + 15_000, moving = false))
        val places = listOf(MapPlace("A", Point(1.0, 1.0)))
        assertTrue(r.placesDue(t, places))
        r.placesSent(t, places)
        assertFalse(r.placesDue(t + 30_000, places))
        // a changed list goes out at once, the same list every minute
        assertTrue(r.placesDue(t + 30_000, places + MapPlace("B", Point(2.0, 2.0))))
        assertTrue(r.placesDue(t + 60_000, places))
        assertFalse(r.placesDue(t, emptyList()))
    }

    @Test
    fun heartbeatSaysServerVersionAndPose() {
        val hb = Json.heartbeat("nova", BrainStatus(goal = "Guiding A17", position = "near Room One"), null, "http://10.0.0.20:3000", "0.3", RobotPose(1.234, -5.0, 0.5))
        assertEquals("http://10.0.0.20:3000", hb.getString("server"))
        assertEquals("0.3", hb.getString("version"))
        assertEquals(1.23, hb.getJSONObject("pose").getDouble("x"), 1e-9)
        assertEquals("near Room One", hb.getString("position"))
        assertFalse(Json.heartbeat("nova", BrainStatus()).has("pose"))
    }

    // ---- chassis ----

    @Test
    fun outOfServiceReasons() {
        // starting up: no verdict yet
        assertNull(Chassis.issue(connected = false, disabled = false, suspended = false, emergency = null, sinceStartMs = 5_000))
        // field test: started from the installer, not the robot's home screen
        assertEquals(ChassisIssue.NOT_STARTED, Chassis.issue(false, false, false, null, 30_000))
        assertEquals(ChassisIssue.DISABLED, Chassis.issue(false, true, false, null, 30_000))
        assertEquals(ChassisIssue.SUSPENDED, Chassis.issue(true, false, true, null, 30_000))
        assertEquals(ChassisIssue.EMERGENCY, Chassis.issue(true, false, true, true, 30_000))
        // an emergency value alone does not stop nova (its format is not documented)
        assertNull(Chassis.issue(true, false, false, true, 30_000))
        assertNull(Chassis.issue(true, false, false, null, 30_000))
        assertTrue(ChassisIssue.NOT_STARTED.hint.contains("home screen"))
    }

    @Test
    fun outOfServiceScreenMirrorAndPlanner() {
        val exit = Place("p2", "Way Out", spot = "Door Spot", kind = PlaceKind.EXIT)
        val room = Place("p4", "Dr. One", room = "B204", spot = "Room One", kind = PlaceKind.ROOM, selfCheckIn = true)
        val s = UiState(chassis = ChassisIssue.NOT_STARTED, places = listOf(exit, room))
        val m = s.mirror()
        assertEquals("help", m.view)
        assertEquals("Out of service", m.title)
        assertTrue(m.step!!.contains("home screen"))
        assertTrue(s.screenInfo(), s.screenInfo().contains("cannot drive right now"))
        // no self check-in while nova can't walk
        assertFalse(s.canSelfCheckIn)
        // a walk on screen still wins over the out-of-service card
        val g = GuideUi(ticket = "A17", destination = "Room B204", phase = GuidePhase.FAILED)
        assertEquals("help", s.copy(guide = g).mirror().view)
        assertEquals("A17 to Room B204", s.copy(guide = g).mirror().title)
    }

    @Test
    fun talkWindowOnScreen() {
        val g = GuideUi(ticket = "A17", destination = "Room B204", phase = GuidePhase.CHECKING)
        val talking = g.copy(talk = TalkUi("All good? Shall I slow down?", TalkStage.LISTENING))
        assertEquals("All good?", talking.step())
        assertEquals("Checking on you", g.step())
        // the talk has its own buttons, the "I'm here" button waits
        assertFalse(talking.needsPresence)
        assertTrue(g.needsPresence)
        assertEquals(Mood.LISTENING, UiState(guide = talking).mood())
        val info = UiState(guide = talking).screenInfo()
        assertTrue(info, info.contains("SET_PACE") && info.contains("PAUSE_WALK"))
        assertEquals("check_back", UiState(guide = talking).mirror().view)
        assertEquals("Taking another way", g.copy(phase = GuidePhase.WALKING, detour = true).step())
    }

    // ---- QR ----

    @Test
    fun qrCodeIsMadeOnTheRobot() {
        val q = QrMatrix.encode("https://clinic.example.app/walk/Kx7pQ2mZ")
        assertNotNull(q)
        q!!
        // a QR version: 21, 25, 29 ... modules
        assertEquals(0, (q.size - 21) % 4)
        // the three finder patterns: dark corners with a light ring inside
        for ((ox, oy) in listOf(0 to 0, q.size - 7 to 0, 0 to q.size - 7)) {
            assertTrue(q[ox, oy] && q[ox + 6, oy + 6] && q[ox + 3, oy + 3])
            assertFalse(q[ox + 1, oy + 1])
        }
        assertNull(QrMatrix.encode(" "))
    }

    @Test
    fun theQrCodeReadsBackToTheLink() {
        val url = "https://clinic.example.app/walk/Kx7pQ2mZ"
        val q = QrMatrix.encode(url)!!
        // draw it like a phone camera would see it: 4 px per module, white margin of 4 modules
        val scale = 4
        val side = (q.size + 8) * scale
        val px = IntArray(side * side) { 0xFFFFFFFF.toInt() }
        for (y in 0 until q.size) for (x in 0 until q.size) {
            if (!q[x, y]) continue
            for (dy in 0 until scale) for (dx in 0 until scale) {
                px[((y + 4) * scale + dy) * side + (x + 4) * scale + dx] = 0xFF0B2540.toInt()
            }
        }
        val bitmap = com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(com.google.zxing.RGBLuminanceSource(side, side, px)))
        assertEquals(url, com.google.zxing.qrcode.QRCodeReader().decode(bitmap).text)
    }
}
