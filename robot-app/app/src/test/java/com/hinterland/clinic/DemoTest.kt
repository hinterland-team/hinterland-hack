package com.hinterland.clinic

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// demo mode (docs/demo-mode.md): the script and its parsing, the placeholders, the patient's wait, the step by
// step player, the walk's cues, the places, the heartbeat object and the gates in the modes and the speech manager
class DemoTest {

    private val t0 = 10_000_000_000L

    private fun lines(s: DemoScript, part: String, who: DemoWho) = s.part(part).filter { it.who == who }.map { it.text }

    @Test
    fun theBuiltInScriptIsTheContractWordForWord() {
        val s = DemoScripts.default
        assertEquals("Dr. Green", s.destination)
        assertEquals("Reception Point", s.reception)
        assertEquals(DemoStart.PERSON, s.start)
        assertEquals(1.2, s.personM, 1e-9)
        assertEquals(DemoPart.ALL, s.parts.keys.toList())
        assertEquals(
            listOf(
                "Hello, I'm Otto. How can I help you? Do you have an appointment?",
                "Thank you. You're checked in for Dr. Green.",
                "Your ticket number is {ticket}. I only use this number, never your name.",
                "Shall I walk at a normal pace, or a little slower?",
                "Of course. Please walk on my right. Let's go!",
            ),
            lines(s, DemoPart.RECEPTION, DemoWho.OTTO),
        )
        // what the patient learns by heart, in this order
        val patient = DemoPart.ALL.flatMap { lines(s, it, DemoWho.PATIENT) }
        assertEquals(
            listOf(
                "Yes, I have an appointment with Dr. Green.",
                "A little slower, please.",
                "No, it's perfect, thank you.",
                "Sorry, here I am.",
                "Thank you, Otto!",
            ),
            patient,
        )
        assertEquals(listOf("Is everything okay? Am I too fast?", "Great, let's go on."), lines(s, DemoPart.FIRST_CHECK, DemoWho.OTTO))
        assertEquals(listOf("I'll wait for you. Take your time.", "No problem. We're almost there."), lines(s, DemoPart.FELL_BACK, DemoWho.OTTO))
        assertEquals(
            listOf("Here we are, this is Dr. Green's room. Please take a seat. Dr. Green's team knows you're here.", "You're welcome. Get well soon!"),
            lines(s, DemoPart.ARRIVE, DemoWho.OTTO),
        )
        assertEquals(listOf("Excuse me, may I pass?"), lines(s, DemoPart.OBSTACLE, DemoWho.OTTO))
        assertEquals(listOf("This way is blocked. Let's take another way."), lines(s, DemoPart.DETOUR, DemoWho.OTTO))
        // the actions in their places: check-in after the patient's first line, the pace after "slower", wait_close
        val reception = s.part(DemoPart.RECEPTION)
        assertEquals(DemoAction.CHECK_IN, reception[2].action)
        assertEquals(DemoAction.PACE, reception[7].action)
        assertEquals(Pace.SLOW, reception[7].pace)
        assertEquals(Pace.SLOW, s.pace)
        assertEquals(DemoAction.WAIT_CLOSE, s.part(DemoPart.FELL_BACK)[1].action)
        assertEquals("Yes, I have an appointment with Dr. Green.", s.firstPatientLine(DemoPart.RECEPTION))
    }

    @Test
    fun aBrokenScriptFallsBackToTheBuiltInOne() {
        val notJson = DemoScripts.parse("{ this is not json")
        assertTrue(notJson.builtIn)
        assertEquals(DemoScripts.default, notJson.script)
        assertTrue(notJson.warnings.isNotEmpty())
        val noParts = DemoScripts.parse("""{"destination": "Dr. Blue"}""")
        assertTrue(noParts.builtIn)
        assertTrue(noParts.warnings.any { it.contains("no parts") })
        val nothingUsable = DemoScripts.parse("""{"parts": {"reception": [{"who": "robot", "text": "hi"}, {"who": "otto"}]}}""")
        assertTrue(nothingUsable.builtIn)
        assertTrue(nothingUsable.warnings.any { it.contains("no usable step") })
        assertTrue(DemoScripts.parse(null).builtIn)
    }

    @Test
    fun missingPartsAreSkippedUnknownFieldsIgnoredBadValuesWarn() {
        val r = DemoScripts.parse(
            """{"version": 2, "destination": "Dr. Blue", "reception": "Reception", "start": "next", "personM": 9, "colour": "blue",
               "parts": {
                 "reception": [
                   {"who": "otto", "text": "  Hi,   I'm Otto. ", "mood": "happy"},
                   {"who": "patient", "text": "Hello.", "waitS": 2.5},
                   {"who": "action", "do": "pace", "value": "fast"},
                   {"who": "action", "do": "dance"},
                   {"who": "otto", "text": "Off we go.", "waitS": -1}
                 ],
                 "party": [{"who": "otto", "text": "Never said"}]
               }}""",
        )
        assertFalse(r.builtIn)
        val s = r.script
        assertEquals("Dr. Blue", s.destination)
        assertEquals("Reception", s.reception)
        assertEquals(DemoStart.NEXT, s.start)
        assertEquals(2, s.version)
        // out of range: the default, with a warning
        assertEquals(DemoScripts.PERSON_M, s.personM, 1e-9)
        assertTrue(r.warnings.any { it.startsWith("personM") })
        // only the reception part; the unknown part is not read
        assertEquals(listOf(DemoPart.RECEPTION), s.parts.keys.toList())
        assertFalse(s.has(DemoPart.FIRST_CHECK))
        val steps = s.part(DemoPart.RECEPTION)
        assertEquals(listOf("Hi, I'm Otto.", "Hello.", "Off we go."), steps.map { it.text })
        assertEquals(2.5, steps[1].waitS!!, 1e-9)
        // a bad waitS keeps the step with the default wait
        assertNull(steps[2].waitS)
        assertTrue(r.warnings.any { it.contains("step 3: left out") && it.contains("pace value") })
        assertTrue(r.warnings.any { it.contains("step 4: left out") && it.contains("do must be") })
        assertTrue(r.warnings.any { it.contains("step 5: waitS") && it.contains("default wait") })
        // no destination or reception: the built-in ones, with a warning
        val bare = DemoScripts.parse("""{"parts": {"arrive": [{"who": "otto", "text": "Here we are."}]}}""")
        assertEquals(DemoScripts.DESTINATION, bare.script.destination)
        assertEquals(DemoScripts.RECEPTION, bare.script.reception)
        assertEquals(2, bare.warnings.size)
    }

    @Test
    fun placeholdersAndThePatientsWait() {
        assertEquals("Your ticket number is A17. Bye.", DemoText.fill("Your ticket number is {ticket}. Bye.", "A17", "Dr. Green"))
        assertEquals("This is Dr. Green.", DemoText.fill("This is {place}.", null, "Dr. Green"))
        // no ticket yet: nothing is left behind
        assertEquals("Your ticket is .", DemoText.fill("Your ticket is {ticket} .", null, "x"))
        assertEquals(8, DemoText.words("Yes, I have an appointment with Dr. Green."))
        assertEquals(3, DemoText.words("Thank you, Otto! -"))
        // 1.2 s + 0.4 s per word
        assertEquals(4_400L, DemoText.patientWaitMs(DemoStep(DemoWho.PATIENT, "Yes, I have an appointment with Dr. Green.")))
        assertEquals(2_800L, DemoText.patientWaitMs(DemoStep(DemoWho.PATIENT, "A little slower, please.")))
        assertEquals(1_000L, DemoText.patientWaitMs(DemoStep(DemoWho.PATIENT, "A little slower, please.", waitS = 1.0)))
    }

    @Test
    fun thePlayerSaysWaitsActsAndEnds() {
        val s = DemoScripts.default
        var ticket: String? = null
        val p = DemoPlayer(DemoPart.RECEPTION, s.part(DemoPart.RECEPTION), pauseMs = 800) { DemoText.fill(it, ticket, s.destination) }
        var t = t0
        assertEquals(9, p.total)
        assertEquals("Yes, I have an appointment with Dr. Green.", p.nextPatientLine())
        // 1: otto's line, said to its end before anything else
        assertEquals(DemoDo.Say("Hello, I'm Otto. How can I help you? Do you have an appointment?"), p.next(t))
        assertEquals(1, p.stepNo())
        assertTrue(p.next(t + 5_000) is DemoDo.Wait)
        t += 3_000
        p.said(t)
        // the pause after the line (voice.linePauseS)
        assertEquals(DemoDo.Wait(t + 800), p.next(t))
        assertNull(p.waitingFor())
        t += 800
        // 2: the patient's line: 1.2 s + 0.4 s per word, or Next
        assertEquals(DemoDo.Wait(t + 4_400), p.next(t))
        assertEquals(DemoWait.PATIENT, p.waitingFor())
        assertEquals(2, p.stepNo())
        t += 1_000
        assertTrue(p.skip(t))
        // 3: the check-in
        val act = p.next(t) as DemoDo.Act
        assertEquals(DemoAction.CHECK_IN, act.step.action)
        assertFalse(p.skip(t))
        ticket = "B42"
        p.done()
        assertEquals(DemoDo.Say("Thank you. You're checked in for Dr. Green."), p.next(t))
        p.said(t)
        t += 800
        // the ticket is filled in once it is there
        assertEquals(DemoDo.Say("Your ticket number is B42. I only use this number, never your name."), p.next(t))
        p.said(t)
        assertEquals("A little slower, please.", p.nextPatientLine())
        // a hold stops before the next step, not in the middle of a wait
        t += 800
        assertEquals(DemoDo.Held, p.next(t, held = true))
        assertEquals(DemoDo.Say("Shall I walk at a normal pace, or a little slower?"), p.next(t, held = false))
        p.said(t)
        t += 800
        assertTrue(p.next(t) is DemoDo.Wait)
        t += 2_800
        assertEquals(DemoAction.PACE, (p.next(t) as DemoDo.Act).step.action)
        p.done()
        assertEquals(DemoDo.Say("Of course. Please walk on my right. Let's go!"), p.next(t))
        assertEquals("Of course. Please walk on my right. Let's go!", p.line)
        p.said(t)
        t += 800
        assertEquals(DemoDo.Done, p.next(t))
        assertNull(p.nextPatientLine())
    }

    @Test
    fun waitCloseIsAnActionAndAStepsOwnWaitWins() {
        val steps = listOf(
            DemoStep(DemoWho.OTTO, "I'll wait.", waitS = 0.0),
            DemoStep(DemoWho.ACTION, action = DemoAction.WAIT_CLOSE),
            DemoStep(DemoWho.PATIENT, "Here I am.", waitS = 2.0),
        )
        val p = DemoPlayer(DemoPart.FELL_BACK, steps, pauseMs = 800)
        assertTrue(p.next(t0) is DemoDo.Say)
        p.said(t0)
        // waitS 0: no pause after the line
        val act = p.next(t0) as DemoDo.Act
        assertEquals(DemoAction.WAIT_CLOSE, act.step.action)
        assertEquals(DemoWait.CLOSE, p.waitingFor())
        assertEquals("Here I am.", p.nextPatientLine())
        p.done()
        assertEquals(DemoDo.Wait(t0 + 2_000), p.next(t0))
        assertEquals(DemoDo.Done, p.next(t0 + 2_000))
    }

    // the camera every 200 ms, as on the robot: the first time the watch says "someone is there" (ms from t0), null = never
    private fun startsAfter(watch: PresenceWatch, seen: List<Double?>): Long? {
        seen.forEachIndexed { i, d -> if (watch.seen(t0 + i * 200L, d)) return i * 200L }
        return null
    }

    @Test
    fun aPersonHasToStandThereForAboutASecond() {
        assertEquals(1_000L, startsAfter(PresenceWatch(personM = 1.2), List(10) { 1.0 }))
        // too far, or nobody: no start
        assertNull(startsAfter(PresenceWatch(personM = 1.2), List(20) { 1.6 }))
        assertNull(startsAfter(PresenceWatch(personM = 1.2), List(20) { null }))
        // one dropped frame is no break
        assertEquals(1_000L, startsAfter(PresenceWatch(personM = 1.2), listOf(1.0, 1.0, null, 1.0, 1.0, 1.0, 1.0)))
        // a longer gap is: the second ever counts from where they came back
        val back = listOf(1.0, 1.0, null, null, null, null) + List(8) { 1.1 }
        assertEquals(2_200L, startsAfter(PresenceWatch(personM = 1.2), back))
        // further away is fine with a bigger personM
        assertEquals(1_000L, startsAfter(PresenceWatch(personM = 2.0), List(10) { 1.8 }))
    }

    // drives for ms in quarter seconds (blocked = standing at an obstacle), from time `from`; the time after it
    private fun DemoWalkPlan.drive(from: Long, ms: Long, blocked: Boolean = false): Long {
        var t = from
        tick(t, blocked)
        while (t < from + ms) {
            t += 250
            tick(t, blocked)
        }
        pause()
        return t
    }

    @Test
    fun theDemoWalkIsAFixedRoutineByDrivingTime() {
        // walk.firstCheckS 10 s, the script's fellBackAfterS 6 s
        val plan = DemoWalkPlan(DemoScripts.default, firstCheckMs = 10_000)
        assertEquals(6_000L, plan.fellBackAfterMs)
        var t = plan.drive(t0, 9_750)
        assertNull(plan.due())
        // standing at an obstacle is no driving
        t = plan.drive(t, 5_000, blocked = true)
        assertNull(plan.due())
        t = plan.drive(t, 250)
        assertEquals(DemoPart.FIRST_CHECK, plan.due())
        // the firstCheck stop: no ticks while otto stands, the part is played
        plan.played(DemoPart.FIRST_CHECK)
        assertNull(plan.due())
        // fellBack after 6 s of driving after the firstCheck part, once
        t = plan.drive(t + 8_000, 5_750)
        assertNull(plan.due())
        t = plan.drive(t, 250)
        assertEquals(DemoPart.FELL_BACK, plan.due())
        plan.played(DemoPart.FELL_BACK)
        plan.drive(t, 60_000)
        assertNull(plan.due())
        assertNull(plan.nextByHand())
        assertTrue(plan.wasPlayed(DemoPart.FELL_BACK))
    }

    @Test
    fun nextPlaysTheWalkPartsInOrderAndAMissingFirstCheckStillTimesFellBack() {
        val byHand = DemoWalkPlan(DemoScripts.default, firstCheckMs = 10_000)
        assertEquals(DemoPart.FIRST_CHECK, byHand.nextByHand())
        // Next at once after the start: firstCheck now, fellBack 6 s of driving after it
        byHand.played(byHand.nextByHand()!!)
        assertEquals(DemoPart.FELL_BACK, byHand.nextByHand())
        val t = byHand.drive(t0, 6_000)
        assertEquals(DemoPart.FELL_BACK, byHand.due())
        // a script without firstCheck: fellBack 6 s after the firstCheck moment
        val noFirst = DemoScripts.default.copy(parts = DemoScripts.default.parts - DemoPart.FIRST_CHECK)
        val plan = DemoWalkPlan(noFirst, firstCheckMs = 10_000)
        assertEquals(DemoPart.FELL_BACK, plan.nextByHand())
        var t2 = plan.drive(t, 10_000)
        assertNull(plan.due())
        t2 = plan.drive(t2, 6_000)
        assertEquals(DemoPart.FELL_BACK, plan.due())
        // no walk parts at all: nothing is ever due
        val bare = DemoWalkPlan(DemoScripts.default.copy(parts = mapOf(DemoPart.ARRIVE to DemoScripts.default.part(DemoPart.ARRIVE))), 10_000)
        bare.drive(t2, 60_000)
        assertNull(bare.due())
        assertNull(bare.nextByHand())
    }

    @Test
    fun theDemoWalkFieldsAreReadWithTheirRanges() {
        val d = DemoScripts.default
        assertEquals(WalkSide.RIGHT, d.walkSide)
        assertEquals(60.0, d.glanceDeg, 1e-9)
        assertEquals(6.0, d.fellBackAfterS, 1e-9)
        assertEquals(8.0, d.closeMaxS, 1e-9)
        val parts = """"parts": {"arrive": [{"who": "otto", "text": "Here we are."}]}"""
        val ok = DemoScripts.parse("""{"destination": "Dr. Green", "reception": "Reception", "walkSide": "LEFT", "glanceDeg": 0, "fellBackAfterS": 12, "closeMaxS": 3, $parts}""")
        assertTrue(ok.warnings.toString(), ok.warnings.isEmpty())
        assertEquals(WalkSide.LEFT, ok.script.walkSide)
        assertEquals(0.0, ok.script.glanceDeg, 1e-9)
        assertEquals(12.0, ok.script.fellBackAfterS, 1e-9)
        assertEquals(3.0, ok.script.closeMaxS, 1e-9)
        // missing: the defaults, without a warning
        val bare = DemoScripts.parse("""{"destination": "Dr. Green", "reception": "Reception", $parts}""")
        assertTrue(bare.warnings.isEmpty())
        assertEquals(WalkSide.RIGHT, bare.script.walkSide)
        assertEquals(60.0, bare.script.glanceDeg, 1e-9)
        // unknown or out of range: the default, with a warning each
        val bad = DemoScripts.parse("""{"destination": "Dr. Green", "reception": "Reception", "walkSide": "behind", "glanceDeg": 120, "fellBackAfterS": 1, "closeMaxS": "long", $parts}""")
        assertFalse(bad.builtIn)
        assertEquals(WalkSide.RIGHT, bad.script.walkSide)
        assertEquals(60.0, bad.script.glanceDeg, 1e-9)
        assertEquals(6.0, bad.script.fellBackAfterS, 1e-9)
        assertEquals(8.0, bad.script.closeMaxS, 1e-9)
        assertEquals(4, bad.warnings.size)
        assertTrue(bad.warnings.any { it == "walkSide must be right or left, using right" })
        assertTrue(bad.warnings.any { it == "glanceDeg must be 0-90 deg, using 60" })
        assertTrue(bad.warnings.any { it == "fellBackAfterS must be 2-30 s, using 6" })
        assertTrue(bad.warnings.any { it == "closeMaxS must be 3-20 s, using 8" })
    }

    @Test
    fun aGuideForTheDemosOwnWalkIsNeverRefused() {
        val guide = RobotCommand("g1", RobotCommand.GUIDE, journeyId = "j1", legId = "l1", ticket = "E67", to = Stop("Dr. Green", "Dr. Green"))
        // b16 run 1: the poll got the walk while the check-in call still ran
        assertTrue(DemoGuides.ours(guide, null, null, null, checkInSince = t0, now = t0 + 1_200))
        assertTrue(DemoGuides.ours(guide, null, null, null, checkInSince = t0, now = t0 + 5_000))
        assertFalse(DemoGuides.ours(guide, null, null, null, checkInSince = t0, now = t0 + 5_001))
        // runs 2-4: the answer came first
        assertTrue(DemoGuides.ours(guide, commandId = "g1", journeyId = null, ticket = null, checkInSince = null, now = t0))
        assertTrue(DemoGuides.ours(guide.copy(id = "g2"), null, journeyId = "j1", ticket = null, checkInSince = null, now = t0))
        assertTrue(DemoGuides.ours(guide.copy(id = "g3", journeyId = "j9"), null, null, ticket = "e 67", checkInSince = null, now = t0))
        // another walk, outside the check-in: refused as before; other commands are no walk
        assertFalse(DemoGuides.ours(guide.copy(id = "g4", journeyId = "j2", ticket = "A11"), "g1", "j1", "E67", null, t0))
        assertFalse(DemoGuides.ours(RobotCommand("s1", RobotCommand.SAY, text = "hi"), null, null, null, t0, t0))
    }

    @Test
    fun aBurstOfPushesGoesToTheLogOncePer10s() {
        val b = BurstLog(everyMs = 10_000)
        // the first one at once
        assertEquals(1, b.note(t0))
        // b16: 36 within a second after an emergency stop: held back
        for (i in 1..35) assertNull(b.note(t0 + i * 25L))
        assertNull(b.flush(t0 + 9_999))
        assertEquals(1L, b.msToFlush(t0 + 9_999))
        // one line with the count once the 10 s are over
        assertEquals(35, b.flush(t0 + 10_000))
        assertNull(b.flush(t0 + 30_000))
        // a quiet while later: at once again, with what was held back counted in
        assertNull(b.note(t0 + 15_000))
        assertEquals(2, b.note(t0 + 25_000))
    }

    @Test
    fun placesByNameThenSpotThenTheMap() {
        val places = listOf(
            Place("rec", "Reception", spot = "Reception Point", kind = PlaceKind.RECEPTION),
            Place("g", "Dr. Green", spot = "Dr. Green", kind = PlaceKind.ROOM, selfCheckIn = true),
            Place("b", "Dr. Blue", room = "B2", spot = "Blue Door", kind = PlaceKind.ROOM, selfCheckIn = true),
            Place("old", "Dr. Old", spot = "Old Spot", active = false),
        )
        // the default reception finds the place "Reception" by its spot
        val rec = DemoPlaces.resolve("Reception Point", places)
        assertEquals("rec", rec.place?.id)
        assertEquals("Reception Point", rec.spot)
        assertEquals("g", DemoPlaces.resolve("dr. green", places).place?.id)
        assertEquals("b", DemoPlaces.resolve("b", places).place?.id)
        val blue = DemoPlaces.resolve("Dr. Blue", places)
        assertEquals("Blue Door", blue.spot)
        assertEquals("Room B2", blue.label)
        assertEquals(Stop("Dr. Blue", "Blue Door", room = "B2", kind = PlaceKind.ROOM), blue.stop())
        // inactive places do not count; no places at all: the spot on otto's map
        assertNull(DemoPlaces.resolve("Dr. Old", places).place)
        val map = DemoPlaces.resolve(" Reception Point ", emptyList())
        assertNull(map.place)
        assertEquals("Reception Point", map.spot)
        assertEquals(Stop("Reception Point", "Reception Point"), map.stop())
    }

    @Test
    fun theHeartbeatHasTheDemoObject() {
        val d = DemoState(
            running = true, part = DemoPart.RECEPTION, step = 2, total = 9, waitingFor = DemoWait.PATIENT,
            line = "Hello, I'm Otto.", nextPatientLine = "Yes, I have an appointment with Dr. Green.", ticket = null, runs = 1,
        )
        val o = Json.heartbeat("nova", BrainStatus(), demo = d).getJSONObject("demo")
        assertTrue(o.getBoolean("running"))
        assertEquals("reception", o.getString("part"))
        assertEquals(2, o.getInt("step"))
        assertEquals(9, o.getInt("total"))
        assertEquals("patient", o.getString("waitingFor"))
        assertEquals("Hello, I'm Otto.", o.getString("line"))
        assertEquals("Yes, I have an appointment with Dr. Green.", o.getString("nextPatientLine"))
        assertTrue(o.isNull("ticket"))
        assertEquals(1, o.getInt("runs"))
        // off: everything empty, but there
        val off = Json.demo(DemoState())
        assertFalse(off.getBoolean("running"))
        assertTrue(off.isNull("part") && off.isNull("waitingFor") && off.isNull("line") && off.isNull("nextPatientLine"))
        // no demo state: no demo object (as before)
        assertFalse(Json.heartbeat("nova", BrainStatus()).has("demo"))
    }

    @Test
    fun theDemoCommandIsRead() {
        val c = Json.command("""{"command": {"id": "d1", "type": "demo", "op": "START", "script": {"destination": "Dr. Blue", "parts": {}}}}""")!!
        assertEquals(RobotCommand.DEMO, c.type)
        assertEquals("start", c.op)
        assertEquals("Dr. Blue", JSONObject(c.script!!).getString("destination"))
        val say = Json.command("""{"id": "d2", "type": "demo", "op": "say", "text": "One moment, please."}""")!!
        assertEquals("say", say.op)
        assertEquals("One moment, please.", say.text)
        assertNull(say.script)
    }

    @Test
    fun demoModeIsMicOffButTalks() {
        assertEquals(RobotMode.DEMO, RobotMode.of(" Demo "))
        assertTrue(RobotMode.DEMO.micOff)
        assertFalse(RobotMode.DEMO.silent)
        assertTrue(RobotMode.SHOW.micOff && RobotMode.CALM.micOff)
        assertFalse(RobotMode.NORMAL.micOff)
        // settings.mode "demo" is a mode like the others
        assertEquals(RobotMode.DEMO, SettingsSpec.parse("""{"settings": {"mode": "demo"}}""", Settings())!!.settings.mode)
    }

    @Test
    fun theDemoRefusesWalksAndAskButNotSayOrGoTo() {
        assertEquals("Otto is in Demo mode", ModeRules.refuses(RobotMode.DEMO, RobotCommand.GUIDE))
        assertEquals("Otto is in Demo mode", ModeRules.refuses(RobotMode.DEMO, RobotCommand.ASK))
        assertTrue(ModeRules.refuses(RobotMode.DEMO, RobotCommand.SWITCH_MAP)!!.contains("after the demo"))
        for (type in listOf(RobotCommand.SAY, RobotCommand.GO_TO, RobotCommand.SET_MODE, RobotCommand.CONFIG_CHANGED, RobotCommand.RECHECK, RobotCommand.DEMO)) {
            assertNull(type, ModeRules.refuses(RobotMode.DEMO, type))
        }
        // nothing of normal mode on his own
        assertFalse(ModeRules.greets(RobotMode.DEMO))
        assertFalse(ModeRules.selfCheckIn(RobotMode.DEMO))
        assertFalse(ModeRules.drivesHome(RobotMode.DEMO))
        assertFalse(ModeRules.drivesToCharger(RobotMode.DEMO))
        assertFalse(ModeRules.leavesCharger(RobotMode.DEMO))
        assertFalse(ModeRules.idleTurns(RobotMode.DEMO))
        // show and calm as before
        assertTrue(ModeRules.refuses(RobotMode.SHOW, RobotCommand.SAY)!!.contains("does not talk"))
        assertNull(ModeRules.refuses(RobotMode.SHOW, RobotCommand.SWITCH_MAP))
        assertTrue(ModeRules.drivesToCharger(RobotMode.SHOW))
    }

    @Test
    fun theBrainGivesTheDemoTheBody() {
        val idle = Snapshot(now = t0, robotReady = true, homeSpot = "Home", atHome = true, battery = 80, quietSince = t0 - 1_000, mode = RobotMode.DEMO)
        assertEquals(Plan.Demo, Brain.decide(idle))
        // not without the chassis
        assertEquals(Plan.Wait, Brain.decide(idle.copy(robotReady = false)))
        // a walk is refused first, a visitor is not greeted, away and low: no drive of its own
        val guide = RobotCommand("c1", RobotCommand.GUIDE, journeyId = "j", legId = "l", to = Stop("Dr. One", "Room One"))
        assertEquals(Plan.Refuse(guide, "Otto is in Demo mode"), Brain.decide(idle.copy(commands = listOf(guide))))
        assertEquals(Plan.Demo, Brain.decide(idle.copy(visitor = Visitor(3, 1.0), atHome = false, battery = 10, quietSince = t0 - 120_000)))
        // leaving demo mode stops the run; commands left from before do not preempt it
        assertTrue(Brain.stopsFor(Plan.Demo, RobotMode.NORMAL))
        assertTrue(Brain.stopsFor(Plan.Demo, RobotMode.SHOW))
        assertFalse(Brain.stopsFor(Plan.Demo, RobotMode.DEMO))
        val goTo = RobotCommand("g1", RobotCommand.GO_TO, spot = "Room Two")
        assertFalse(Brain.shouldPreempt(idle.copy(commands = listOf(goTo))))
        assertTrue(Brain.shouldPreempt(idle.copy(mode = RobotMode.NORMAL, commands = listOf(goTo))))
        assertEquals("Demo mode", Brain.goal(Plan.Wait, idle))
        assertEquals(RobotState.IDLE, Brain.state(Plan.Demo, "Home"))
    }

    @Test
    fun inDemoModeOnlyScriptLinesAreSaidAndNeverDropped() {
        val t = SpeechTurns(timing = { TurnTiming(maxWaiting = 1) })
        t.setScriptOnly(true, "demo mode")
        val outcome = HashMap<String, TurnOutcome>()
        fun offer(text: String, kind: SpeechKind, script: Boolean, now: Long) =
            t.offer(SpeechLine(kind, text, script = script, fits = { false }), now) { outcome[text] = it }
        var now = t0
        // the walk's own lines, the llm, a greeting: not said at all
        offer("Good. Let's keep going.", SpeechKind.WALK, script = false, now)
        t.offer(SpeechLine(SpeechKind.GREETING, "hello", task = "Greet them"), now) { outcome["llm"] = it }
        assertEquals(TurnOutcome.SILENT, outcome["Good. Let's keep going."])
        assertEquals(TurnOutcome.SILENT, outcome["llm"])
        // script lines: queued above the cap, never replaced, and they always fit
        offer("Line one.", SpeechKind.SCRIPT, script = true, now)
        offer("Line two.", SpeechKind.SCRIPT, script = true, now)
        offer("A team line.", SpeechKind.TEAM, script = true, now)
        assertEquals(setOf("Line one.", "Line two.", "A team line."), t.waitingText().toSet())
        assertEquals(3, t.waitingText().size)
        // the team's line first (more important), then the script in order, each on its own, no pause held
        val said = ArrayList<String>()
        repeat(3) {
            val step = t.next(now)
            assertTrue("$step", step is TurnStep.Play)
            val u = (step as TurnStep.Play).utterance
            said += u.text
            now += 2_000
            t.finished(u, TurnOutcome.SAID, now)
        }
        assertEquals(listOf("A team line.", "Line one.", "Line two."), said)
        assertTrue(t.next(now) is TurnStep.Idle)
        assertEquals(TurnOutcome.SAID, outcome["Line one."])
        // out of demo mode: a normal line can not drop a waiting script line either
        val n = SpeechTurns(timing = { TurnTiming(maxWaiting = 1) })
        val o2 = HashMap<String, TurnOutcome>()
        n.offer(SpeechLine(SpeechKind.SCRIPT, "Kept.", script = true), t0) { o2["Kept."] = it }
        n.offer(SpeechLine(SpeechKind.ALERT, "Alert.", urgent = false), t0) { o2["Alert."] = it }
        assertEquals(listOf("Kept."), n.waitingText())
        assertEquals(TurnOutcome.DROPPED, o2["Alert."])
        assertNull(o2["Kept."])
        // dropping the demo's lines (stop, restart)
        assertFalse(n.dropScript("restart"))
        assertEquals(TurnOutcome.DROPPED, o2["Kept."])
    }
}
