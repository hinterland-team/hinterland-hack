package com.hinterland.clinic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// names here are test data only - the app gets them from the backend and the robot map
class BrainTest {

    private val now = 10_000_000_000L
    private val home = "Home Spot"

    // at home, battery fine, nobody there, quiet for a second
    private val idle = Snapshot(
        now = now,
        robotReady = true,
        homeSpot = home,
        atHome = true,
        battery = 80,
        quietSince = now - 1_000,
    )

    private val visitor = Visitor(id = 7, distance = 1.0)

    private fun call(id: String = "c1") = RobotCommand(
        id, RobotCommand.GUIDE, journeyId = "j1", legId = "l1", ticket = "A17",
        from = Stop("Front Desk", "Desk Spot"), to = Stop("Dr. One", "Room One", room = "B204"),
    )

    private fun ask(id: String = "a1") = RobotCommand(id, RobotCommand.ASK, text = "Tell the visitor we close at six")

    private fun goTo(id: String = "g1", spot: String = "Room Two") = RobotCommand(id, RobotCommand.GO_TO, spot = spot)

    private fun say(id: String = "s1") = RobotCommand(id, RobotCommand.SAY, text = "Hello")

    private fun decide(s: Snapshot) = Brain.decide(s)

    // ---- 6. idle ----

    @Test
    fun idleAtHomeWaitsForPatients() {
        assertEquals(Plan.Wait, decide(idle))
        assertEquals("Waiting for patients", Brain.goal(Plan.Wait, idle))
        assertEquals(RobotState.IDLE, Brain.state(Plan.Wait, home))
    }

    // ---- 1. help ----

    @Test
    fun urgentHelpBeatsEverything() {
        val s = idle.copy(helpActive = true, commands = listOf(call()), visitor = visitor, battery = 5, atHome = false, quietSince = 0)
        assertEquals(Plan.StayWithPerson, decide(s))
        assertEquals("Staying with someone who needs help", Brain.goal(Plan.StayWithPerson, s))
    }

    // ---- 2. commands ----

    @Test
    fun commandRunsWhenNobodyTalks() {
        assertEquals(Plan.RunCommand(call(), excuse = false), decide(idle.copy(commands = listOf(call()))))
    }

    @Test
    fun guideBeatsGoToBeatsSay() {
        assertEquals("c1", (decide(idle.copy(commands = listOf(say(), goTo(), call()))) as Plan.RunCommand).command.id)
        assertEquals("g1", (decide(idle.copy(commands = listOf(say(), goTo()))) as Plan.RunCommand).command.id)
        // oldest first within a type
        assertEquals("g1", (decide(idle.copy(commands = listOf(goTo("g1"), goTo("g2")))) as Plan.RunCommand).command.id)
        // unknown types come last
        val other = RobotCommand("x1", "dance")
        assertEquals("s1", (decide(idle.copy(commands = listOf(other, say()))) as Plan.RunCommand).command.id)
    }

    @Test
    fun commandWaitsWhileSomeoneTalks() {
        val s = idle.copy(commands = listOf(call()), talk = TalkLevel.ACTIVE, waitingSince = now - 5_000)
        assertEquals(Plan.WaitForQuiet(call()), decide(s))
        // first tick of the wait
        assertEquals(Plan.WaitForQuiet(call()), decide(s.copy(waitingSince = null)))
        // a "listening" status counts as talking here
        assertEquals(Plan.WaitForQuiet(call()), decide(s.copy(talk = TalkLevel.LISTENING)))
        assertEquals(
            "Finishing this talk, then guiding A17 to Room B204",
            Brain.goal(Plan.WaitForQuiet(call()), s),
        )
    }

    @Test
    fun afterTwentySecondsNovaSaysSorryAndGoes() {
        val s = idle.copy(commands = listOf(call()), talk = TalkLevel.ACTIVE, waitingSince = now - 20_000)
        assertEquals(Plan.RunCommand(call(), excuse = true), decide(s))
        assertEquals(Plan.RunCommand(goTo(), excuse = true), decide(s.copy(commands = listOf(goTo()))))
        // say and ask do not leave, so no excuse
        assertEquals(Plan.RunCommand(say(), excuse = false), decide(s.copy(commands = listOf(say()))))
        assertEquals(Plan.RunCommand(ask(), excuse = false), decide(s.copy(commands = listOf(ask()))))
    }

    // ---- the person in front asked (GUIDE_TO_PLACE, CHECK_IN_FOR, self check-in screen) ----

    @Test
    fun ownRequestRunsAtOnceWithoutExcuse() {
        val own = call("own1")
        val s = idle.copy(
            commands = listOf(goTo(), say(), own), ownCommandIds = setOf("own1"),
            talk = TalkLevel.ACTIVE, waitingSince = now - 1_000,
        )
        assertEquals(Plan.RunCommand(own, excuse = false), decide(s))
        // before a staff guide that came first
        val staff = call("staff1")
        assertEquals("own1", (decide(s.copy(commands = listOf(staff, own))) as Plan.RunCommand).command.id)
        // urgent help still comes first
        assertEquals(Plan.StayWithPerson, decide(s.copy(helpActive = true)))
    }

    @Test
    fun commandBeatsChargingAndGreeting() {
        val docked = idle.copy(dockedByNova = true, charging = true, battery = 22, visitor = visitor, commands = listOf(goTo()))
        assertEquals(Plan.RunCommand(goTo(), excuse = false), decide(docked))
    }

    // ---- conversation ----

    @Test
    fun conversationBlocksGreetingChargingAndGoingHome() {
        val s = idle.copy(
            talk = TalkLevel.ACTIVE, lastTalkAt = now, visitor = visitor, battery = 10, atHome = false,
            returnHomeNow = true, quietSince = now,
        )
        assertEquals(Plan.Talk, decide(s))
        assertEquals(RobotState.TALKING, Brain.state(Plan.Talk, home))
    }

    @Test
    fun listeningStatusAloneStillLetsNovaGreet() {
        assertEquals(Plan.Greet(7), decide(idle.copy(visitor = visitor, talk = TalkLevel.LISTENING)))
        assertEquals(Plan.Talk, decide(idle.copy(talk = TalkLevel.LISTENING)))
    }

    // ---- 3. greeting ----

    @Test
    fun greetsAVisitorWhoWalksUp() {
        val s = idle.copy(visitor = visitor)
        assertEquals(Plan.Greet(7), decide(s))
        assertEquals("Greeting a visitor", Brain.goal(Plan.Greet(7), s))
        assertEquals(RobotState.GREETING, Brain.state(Plan.Greet(7), home))
    }

    @Test
    fun noGreetingWhenTooFarOrNoCompleteFace() {
        assertEquals(Plan.Wait, decide(idle.copy(visitor = Visitor(7, 2.0))))
        assertEquals(Plan.Wait, decide(idle.copy(visitor = Visitor(-1, 1.0))))
        assertEquals(Plan.Wait, decide(idle.copy(visitor = Visitor(7, 1.0, withFace = false))))
        assertEquals(Plan.Wait, decide(idle.copy(visitor = Visitor(7, 0.0))))
        assertEquals(Plan.Greet(7), decide(idle.copy(visitor = Visitor(7, 1.5))))
    }

    @Test
    fun samePersonIsNotGreetedAgainWithinTwoMinutes() {
        val s = idle.copy(visitor = visitor, engaged = mapOf(7 to now - 60_000))
        assertEquals(Plan.Wait, decide(s))
        assertEquals(Plan.Greet(7), decide(s.copy(engaged = mapOf(7 to now - 121_000))))
        // someone else is welcome
        assertEquals(Plan.Greet(8), decide(s.copy(visitor = Visitor(8, 1.2))))
    }

    @Test
    fun thirtySecondsBetweenGreetingsAndAfterATalk() {
        assertEquals(Plan.Wait, decide(idle.copy(visitor = visitor, lastGreetAt = now - 10_000)))
        assertEquals(Plan.Wait, decide(idle.copy(visitor = visitor, lastTalkAt = now - 10_000)))
        assertEquals(Plan.Greet(7), decide(idle.copy(visitor = visitor, lastGreetAt = now - 31_000, lastTalkAt = now - 31_000)))
    }

    @Test
    fun noGreetingAtARoomDoorButWhereTheTeamParkedHer() {
        val away = idle.copy(visitor = visitor, atHome = false, quietSince = now)
        assertEquals(Plan.Wait, decide(away))
        assertEquals(Plan.Greet(7), decide(away.copy(parkedAt = "Entrance")))
        // home not known yet: still greets
        assertEquals(Plan.Greet(7), decide(idle.copy(visitor = visitor, atHome = null)))
    }

    @Test
    fun greetingComesBeforeCharging() {
        assertEquals(Plan.Greet(7), decide(idle.copy(visitor = visitor, battery = 15)))
    }

    // ---- 4. battery ----

    @Test
    fun lowBatteryDrivesToTheCharger() {
        val low = idle.copy(battery = 20)
        assertEquals(Plan.GoCharge, decide(low))
        assertEquals("Driving to the charger", Brain.goal(Plan.GoCharge, low))
        assertEquals(RobotState.CHARGING, Brain.state(Plan.GoCharge, home))
        assertEquals(Plan.Wait, decide(idle.copy(battery = 21)))
        assertEquals(Plan.Wait, decide(idle.copy(battery = null)))
        assertEquals(Plan.Wait, decide(low.copy(robotReady = false)))
        // it failed a minute ago: try again later
        assertEquals(Plan.Wait, decide(low.copy(chargeFailedAt = now - 60_000)))
        assertEquals(Plan.GoCharge, decide(low.copy(chargeFailedAt = now - 6 * 60_000)))
        // low battery away from home: straight to the charger
        assertEquals(Plan.GoCharge, decide(low.copy(atHome = false, quietSince = now - 120_000)))
    }

    @Test
    fun staysOnTheChargerUntilThirtyPercent() {
        val docked = idle.copy(dockedByNova = true, charging = true, battery = 25, atHome = false, quietSince = now - 600_000)
        assertEquals(Plan.Charge, decide(docked))
        assertEquals("Charging (25 %)", Brain.goal(Plan.Charge, docked))
        assertEquals(Plan.LeaveCharger, decide(docked.copy(battery = 30)))
        // could not get off a minute ago
        assertEquals(Plan.Charge, decide(docked.copy(battery = 30, leaveFailedAt = now - 60_000)))
        // battery not known: stay
        assertEquals(Plan.Charge, decide(docked.copy(battery = null)))
    }

    @Test
    fun chargingStartedByTheTeamIsLeftAlone() {
        val s = idle.copy(charging = true, battery = 90, atHome = false, quietSince = now - 600_000)
        assertEquals(Plan.Charge, decide(s))
    }

    // ---- 5. home ----

    @Test
    fun drivesHomeAfterAMinuteIdleAway() {
        val away = idle.copy(atHome = false, quietSince = now - 61_000)
        assertEquals(Plan.ReturnHome, decide(away))
        assertEquals("Driving back to Home Spot", Brain.goal(Plan.ReturnHome, away))
        assertEquals(RobotState.RETURNING, Brain.state(Plan.ReturnHome, home))
        assertEquals(Plan.Wait, decide(away.copy(quietSince = now - 30_000)))
        // a walk just ended: no need to wait the minute
        assertEquals(Plan.ReturnHome, decide(away.copy(quietSince = now - 5_000, returnHomeNow = true)))
    }

    @Test
    fun staysWhenParkedUnknownOrUnable() {
        val away = idle.copy(atHome = false, quietSince = now - 61_000)
        assertEquals(Plan.Wait, decide(away.copy(parkedAt = "Room Two")))
        assertEquals(Plan.Wait, decide(away.copy(atHome = null)))
        assertEquals(Plan.Wait, decide(away.copy(homeSpot = "")))
        assertEquals(Plan.Wait, decide(away.copy(returnFailedAt = now - 60_000)))
        assertEquals(Plan.ReturnHome, decide(away.copy(returnFailedAt = now - 4 * 60_000)))
        assertEquals(Plan.Wait, decide(away.copy(robotReady = false)))
    }

    // ---- running tasks give way ----

    @Test
    fun driveHomeOrToChargerGivesWay() {
        assertTrue(Brain.shouldPreempt(idle.copy(commands = listOf(call()))))
        assertTrue(Brain.shouldPreempt(idle.copy(commands = listOf(goTo()))))
        assertFalse(Brain.shouldPreempt(idle.copy(commands = listOf(say()))))
        assertFalse(Brain.shouldPreempt(idle.copy(commands = listOf(ask()))))
        assertTrue(Brain.shouldPreempt(idle.copy(helpActive = true)))
        assertFalse(Brain.shouldPreempt(idle))
    }

    // ---- inputs ----

    @Test
    fun talkLevels() {
        val t = now
        assertEquals(TalkLevel.NONE, Brain.talkLevel(t, AgentMode.IDLE, 0, 0, 0, 0))
        assertEquals(TalkLevel.ACTIVE, Brain.talkLevel(t, AgentMode.IDLE, 0, t - 7_000, 0, 0))
        assertEquals(TalkLevel.NONE, Brain.talkLevel(t, AgentMode.IDLE, 0, t - 9_000, 0, 0))
        // nova's own speech and running actions count too
        assertEquals(TalkLevel.ACTIVE, Brain.talkLevel(t, AgentMode.IDLE, 0, 0, t - 1_000, 0))
        assertEquals(TalkLevel.ACTIVE, Brain.talkLevel(t, AgentMode.IDLE, 0, 0, 0, 1))
        assertEquals(TalkLevel.ACTIVE, Brain.talkLevel(t, AgentMode.THINKING, t - 5_000, 0, 0, 0))
        // a status that was never reset does not count forever
        assertEquals(TalkLevel.NONE, Brain.talkLevel(t, AgentMode.THINKING, t - 61_000, 0, 0, 0))
        assertEquals(TalkLevel.LISTENING, Brain.talkLevel(t, AgentMode.LISTENING, t - 5_000, 0, 0, 0))
        assertEquals(TalkLevel.NONE, Brain.talkLevel(t, AgentMode.LISTENING, t - 21_000, 0, 0, 0))
    }

    @Test
    fun picksTheNearestCompleteFace() {
        val people = listOf(
            Visitor(1, 1.4), Visitor(2, 0.9), Visitor(-1, 0.5), Visitor(3, 0.7, withFace = false), Visitor(4, 2.0),
        )
        assertEquals(2, Brain.pickVisitor(people)?.id)
        assertEquals(2, Brain.pickVisitor(people, 2.5)?.id)
        assertNull(Brain.pickVisitor(listOf(Visitor(4, 2.0))))
        assertNull(Brain.pickVisitor(emptyList()))
    }

    @Test
    fun goalsAndStatesForCommands() {
        // ticket, never a name
        assertEquals("Guiding A17 to Room B204", Brain.goal(Plan.RunCommand(call(), false), idle))
        assertEquals(RobotState.FETCHING, Brain.state(Plan.RunCommand(call(), false), home))
        assertEquals("Going to Room Two", Brain.goal(Plan.RunCommand(goTo(), false), idle))
        assertEquals(RobotState.WALKING, Brain.state(Plan.RunCommand(goTo(), false), home))
        // go_to the home spot is a way back (any case)
        val back = goTo(spot = "home spot")
        assertEquals("Driving back to home spot", Brain.goal(Plan.RunCommand(back, false), idle))
        assertEquals(RobotState.RETURNING, Brain.state(Plan.RunCommand(back, false), home))
        assertEquals("Passing on a message from the team", Brain.goal(Plan.RunCommand(say(), false), idle))
        assertEquals(RobotState.TALKING, Brain.state(Plan.RunCommand(say(), false), home))
        assertEquals("Thinking about a message from the team", Brain.goal(Plan.RunCommand(ask(), false), idle))
        assertEquals(RobotState.TALKING, Brain.state(Plan.RunCommand(ask(), false), home))
        val bare = RobotCommand("c9", RobotCommand.GUIDE)
        assertEquals("Guiding a patient", Brain.goal(Plan.RunCommand(bare, false), idle))
        val noRoom = call().copy(to = Stop("Way Out", "Door Spot"))
        assertEquals("Guiding A17 to Way Out", Brain.goal(Plan.RunCommand(noRoom, false), idle))
    }

    @Test
    fun blockedClockAddsUpEveryStop() {
        val c = BlockedClock()
        c.blocked(1_000)
        c.clear(11_000)
        c.blocked(20_000)
        assertTrue(c.isBlocked())
        assertEquals(15_000, c.total(25_000))
        // a second "blocked" while still blocked does not restart the stop
        c.blocked(22_000)
        assertEquals(15_000, c.total(25_000))
        c.clear(30_000)
        assertFalse(c.isBlocked())
        assertEquals(20_000, c.total(99_000))
    }
}
