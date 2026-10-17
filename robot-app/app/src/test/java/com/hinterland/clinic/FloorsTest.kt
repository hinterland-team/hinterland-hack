package com.hinterland.clinic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// floors = the robot's maps (docs/robot-setup.md 2): what the robot reports, which places otto offers, and
// what the switch_map command does. map and place names are test data only
class FloorsTest {

    @Test
    fun theMultiFloorSetupGivesNamesAndFloors() {
        // RobotApi.getMultiFloorConfigAndPose answers a list of MultiFloorInfo (javap: floorId, floorIndex,
        // floorAlias, floorState, mapName, availableElevators, ..., poseList)
        val raw = """[{"floorId":1,"floorIndex":1,"floorAlias":"EG","floorState":1,"mapName":"Ground-0912","availableElevators":["Lift A"],"poseList":[{"px":1.0,"py":2.0,"theta":0.0,"name":"Lift A"}]},
                     {"floorId":2,"floorIndex":2,"floorAlias":"OG","mapName":"Upper-0913"},
                     {"floorId":3,"floorIndex":3}]"""
        assertEquals(listOf(FloorMap("Ground-0912", 1), FloorMap("Upper-0913", 2)), Floors.fromMultiFloor(raw))
        assertNull(Floors.fromMultiFloor("not json"))
        assertNull(Floors.fromMultiFloor(""))
        assertEquals(emptyList<FloorMap>(), Floors.fromMultiFloor("[]"))
        assertEquals(listOf(FloorMap("A", 1)), Floors.fromMultiFloor("""{"data":[{"mapName":"A","floorIndex":"1"}]}"""))
    }

    @Test
    fun theLocalMapListIsReadInWhateverShapeItComes() {
        assertEquals(listOf(FloorMap("A"), FloorMap("B")), Floors.fromMapList("""["A", " B ", ""]"""))
        assertEquals(listOf(FloorMap("A"), FloorMap("B", 2)), Floors.fromMapList("""[{"mapName":"A","state":2},{"map_name":"B","floor":2},{"x":1}]"""))
        assertEquals(listOf(FloorMap("C")), Floors.fromMapList("""{"mapInfoList":[{"name":"C"}]}"""))
        assertNull(Floors.fromMapList("""{"something":"else"}"""))
    }

    @Test
    fun theMapsAreOneListWithTheCurrentMapInIt() {
        val multi = listOf(FloorMap("Ground", 1), FloorMap("Upper", 2))
        val local = listOf(FloorMap("upper"), FloorMap("Test map"))
        val all = Floors.merge("Ground", multi, local)
        // one entry per name (case does not matter), the floor from any source
        assertEquals(listOf(FloorMap("Ground", 1), FloorMap("Upper", 2), FloorMap("Test map")), all)
        // nothing reported: at least the map otto is on
        assertEquals(listOf(FloorMap("Hinterland 2-1016223633")), Floors.merge("Hinterland 2-1016223633", null, null))
        assertEquals(emptyList<FloorMap>(), Floors.merge(null, null))
    }

    @Test
    fun ottoOnlyOffersPlacesOnHisFloor() {
        val places = listOf(
            Place("1", "Reception", spot = "Reception Point", kind = PlaceKind.RECEPTION),
            Place("2", "Dr. Blue", room = "B204", spot = "Dr. Blue", kind = PlaceKind.ROOM, selfCheckIn = true, mapName = "Upper"),
            Place("3", "Dr. Red", room = "A101", spot = "Dr. Red", kind = PlaceKind.ROOM, selfCheckIn = true, mapName = "Ground"),
            Place("4", "Exit", spot = "Home", kind = PlaceKind.EXIT, mapName = " ground "),
        )
        // no map on the place = any floor; the map name is compared without case and spaces around it
        assertEquals(listOf("1", "3", "4"), Places.onMap(places, "Ground").map { it.id })
        assertEquals(listOf("1", "2"), Places.onMap(places, "Upper").map { it.id })
        // otto does not know his map yet: nothing is hidden
        assertEquals(4, Places.onMap(places, null).size)
        assertTrue(Floors.onMap(null, "Ground"))
        assertTrue(Floors.onMap("", "Ground"))
        assertFalse(Floors.onMap("Upper", "Ground"))
        // the screen and the planner see the same
        val ui = UiState(places = places, mapName = "Ground")
        assertEquals(listOf("Dr. Red"), Places.selfCheckIn(ui.placesHere).map { it.name })
        assertTrue(ui.screenInfo().contains("Room A101 (Dr. Red)"))
        assertFalse(ui.screenInfo().contains("Dr. Blue"))
        // the place's map comes from the dashboard
        val p = Json.place(org.json.JSONObject("""{"id":"9","name":"Lab","mapName":"Upper"}"""))
        assertEquals("Upper", p.mapName)
    }

    // ---- switch_map ----

    @Test
    fun noSwitchWhileGuiding() {
        val p = SwitchMapRules.plan("Upper", "Lift", guiding = true, current = "Ground")
        assertTrue(p is SwitchPlan.Refuse)
        assertTrue((p as SwitchPlan.Refuse).why.contains("walking with a patient"))
    }

    @Test
    fun aMissingMapNameIsRefused() {
        assertEquals(SwitchPlan.Refuse("The command has no map name."), SwitchMapRules.plan(" ", "Lift", guiding = false, current = "Ground"))
        assertEquals(SwitchPlan.Refuse("The command has no map name."), SwitchMapRules.plan(null, null, guiding = false, current = null))
    }

    @Test
    fun theSameMapOnlyLocalisesOrDoesNothing() {
        assertEquals(SwitchPlan.Relocalise("Lift"), SwitchMapRules.plan("ground", " Lift ", guiding = false, current = "Ground"))
        assertTrue(SwitchMapRules.plan("Ground", null, guiding = false, current = "Ground") is SwitchPlan.Already)
    }

    @Test
    fun anotherMapIsSwitchedThenLocalisedAtTheSpot() {
        assertEquals(SwitchPlan.Switch("Upper", "Lift"), SwitchMapRules.plan(" Upper ", "Lift", guiding = false, current = "Ground"))
        // the team did not say where otto stands: switch, then they set the position on the robot
        assertEquals(SwitchPlan.Switch("Upper", null), SwitchMapRules.plan("Upper", "", guiding = false, current = "Ground"))
        // otto does not know his map yet: switch
        assertEquals(SwitchPlan.Switch("Upper", "Lift"), SwitchMapRules.plan("Upper", "Lift", guiding = false, current = null))
    }

    @Test
    fun theSpotIsFoundOnTheNewMapWithItsDirection() {
        // RobotApi.getPlaceList after the switch: name, x, y, theta
        val list = Json.mapPlaces("""[{"name":"Lift","x":3.5,"y":-1.25,"theta":1.57,"id":"p1","status":0},{"name":"Stairs","x":0,"y":0,"theta":0}]""")
        val lift = SwitchMapRules.spot("lift", list)!!
        assertEquals(Point(3.5, -1.25), lift.point)
        assertEquals(1.57, lift.theta!!, 1e-9)
        assertNull(SwitchMapRules.spot("Kitchen", list))
        // getPlaceListByMapName may answer with PlaceBean fields
        val beans = Json.mapPlaces("""[{"placeId":"x","pointX":1.0,"pointY":2.0,"pointTheta":-0.5,"placeNameList":{"en_US":"Lift"},"mapName":"Upper"}]""")
        assertEquals("Lift", beans.single().name)
        assertEquals(-0.5, beans.single().theta!!, 1e-9)
        // before the switch: is the spot on the new map? null = the robot did not tell
        assertEquals(true, SwitchMapRules.spotOnMap("Lift", list))
        assertEquals(false, SwitchMapRules.spotOnMap("Kitchen", list))
        assertNull(SwitchMapRules.spotOnMap("Lift", null))
        assertNull(SwitchMapRules.spotOnMap("Lift", emptyList()))
        assertNull(SwitchMapRules.spotOnMap(null, list))
    }

    @Test
    fun theMapCommandsAreRead() {
        val sw = Json.command("""{"id":"c1","robot":"nova","type":"switch_map","mapName":"Upper","spot":"Lift"}""")!!
        assertEquals(RobotCommand.SWITCH_MAP, sw.type)
        assertEquals("Upper", sw.mapName)
        assertEquals("Lift", sw.spot)
        assertEquals("show", Json.command("""{"id":"c2","type":"set_mode","mode":"Show"}""")!!.mode)
        assertEquals(RobotCommand.CONFIG_CHANGED, Json.command("""{"id":"c3","type":"config_changed"}""")!!.type)
        assertEquals(RobotCommand.RECHECK, Json.command("""{"id":"c4","type":"recheck"}""")!!.type)
        // a refused command says why
        val done = Json.commandDone(CommandResult(false, "Otto is walking with a patient."))
        assertEquals(false, done.getBoolean("ok"))
        assertEquals("Otto is walking with a patient.", done.getString("detail"))
        assertEquals(0, Json.commandDone(null).length())
        // the guide's destination may say its map
        val g = Json.command("""{"id":"g","type":"guide","journeyId":"j","legId":"l","ticket":"A17","to":{"name":"Dr. Blue","spot":"Dr. Blue","mapName":"Upper"}}""")!!
        assertEquals("Upper", g.to?.mapName)
    }

    @Test
    fun theHeartbeatAndTheMapFrameCarryTheSetup() {
        val hb = Json.heartbeat(
            "nova", BrainStatus(goal = "Show mode"),
            setup = SetupState("Upper", true, 4, RobotMode.SHOW, RobotMode.NORMAL, listOf(FloorMap("Ground", 1), FloorMap("Upper", 2))),
        )
        assertEquals("Upper", hb.getString("mapName"))
        assertEquals(2, hb.getJSONArray("maps").getJSONObject(1).getInt("floor"))
        assertTrue(hb.getBoolean("localised"))
        assertEquals(4, hb.getInt("configVersion"))
        assertEquals("show", hb.getString("mode"))
        assertEquals("normal", hb.getString("modePending"))
        // unknown values go as null, the mode always
        val none = Json.heartbeat("nova", BrainStatus(), setup = SetupState())
        assertTrue(none.isNull("mapName"))
        assertTrue(none.isNull("localised"))
        assertTrue(none.isNull("configVersion"))
        assertEquals("normal", none.getString("mode"))
        assertFalse(none.has("modePending"))
        assertFalse(none.has("maps"))
        // the live map frame: the floors when due
        val frame = Json.mapReport(MapReport("nova", RobotPose(1.0, 2.0, 0.0), mapName = "Upper", maps = listOf(FloorMap("Ground", 1), FloorMap("Upper"))))
        val maps = frame.getJSONArray("maps")
        assertEquals("Ground", maps.getJSONObject(0).getString("name"))
        assertEquals(1, maps.getJSONObject(0).getInt("floor"))
        assertFalse(maps.getJSONObject(1).has("floor"))
        assertFalse(Json.mapReport(MapReport("nova", RobotPose(1.0, 2.0, 0.0))).has("maps"))
        // the map image body (docs/robot-setup.md 1)
        val img = Json.mapImage("nova", MapImageMeta("Upper", 400, 300, 0.1, -12.5, -3.0, "abc"), "iVBORw0KGgo=")
        assertEquals(listOf("hash", "height", "mapName", "originX", "originY", "png", "resolution", "robot", "width"), img.keys().asSequence().toList().sorted())
        assertEquals(0.1, img.getDouble("resolution"), 1e-12)
        // the robot's localisation status (OrionStar sample: {"isPoseEstimate": true})
        assertEquals(true, Json.localised("""{"isPoseEstimate":true}"""))
        assertEquals(false, Json.localised("false"))
        assertNull(Json.localised("whatever"))
    }

    @Test
    fun theFloorsGoOutOnChangeOrEveryMinute() {
        val r = MapRate()
        val maps = listOf(FloorMap("A"))
        assertTrue(r.mapsDue(0, maps))
        r.mapsSent(0, maps)
        assertFalse(r.mapsDue(30_000, maps))
        assertTrue(r.mapsDue(30_000, maps + FloorMap("B")))
        assertTrue(r.mapsDue(60_000, maps))
        assertFalse(r.mapsDue(0, emptyList()))
        // the live map intervals are settings
        assertFalse(r.due(1_000, moving = true, movingEveryMs = 3_000, idleEveryMs = 20_000))
        assertTrue(r.due(3_000, moving = true, movingEveryMs = 3_000, idleEveryMs = 20_000))
    }
}
