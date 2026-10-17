package com.hinterland.clinic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

// the modes (the user's spec 17.10): normal = receptionist, show = lively face, small turns, silent, no walks;
// calm = quiet and passive, no driving on his own. the gates in the brain and the speech manager
class ModesTest {

    private val now = 10_000_000_000L
    private val home = "Home Spot"

    private val idle = Snapshot(now = now, robotReady = true, homeSpot = home, atHome = true, battery = 80, quietSince = now - 1_000)
    private val visitor = Visitor(id = 7, distance = 1.0)

    private fun guide(id: String = "c1") = RobotCommand(
        id, RobotCommand.GUIDE, journeyId = "j1", legId = "l1", ticket = "A17",
        from = Stop("Front Desk", "Desk Spot"), to = Stop("Dr. One", "Room One", room = "B204"),
    )

    private fun show(s: Snapshot = idle) = s.copy(mode = RobotMode.SHOW)
    private fun calm(s: Snapshot = idle) = s.copy(mode = RobotMode.CALM)

    @Test
    fun modesAreReadFromTheWire() {
        assertEquals(RobotMode.SHOW, RobotMode.of(" Show "))
        assertEquals(RobotMode.CALM, RobotMode.of("calm"))
        assertEquals(RobotMode.NORMAL, RobotMode.of("normal"))
        assertNull(RobotMode.of("party"))
        assertNull(RobotMode.of(null))
        assertFalse(RobotMode.NORMAL.silent)
        assertTrue(RobotMode.SHOW.silent)
        assertTrue(RobotMode.CALM.silent)
    }

    @Test
    fun walksAndWordsAreRefusedInShowAndCalm() {
        assertNull(ModeRules.refuses(RobotMode.NORMAL, RobotCommand.GUIDE))
        assertEquals("Otto is in Show mode", ModeRules.refuses(RobotMode.SHOW, RobotCommand.GUIDE))
        assertEquals("Otto is in Calm mode", ModeRules.refuses(RobotMode.CALM, RobotCommand.GUIDE))
        assertTrue(ModeRules.refuses(RobotMode.SHOW, RobotCommand.SAY)!!.contains("does not talk"))
        assertTrue(ModeRules.refuses(RobotMode.CALM, RobotCommand.ASK)!!.contains("does not talk"))
        // the team can still send him somewhere, switch the map, change settings
        for (type in listOf(RobotCommand.GO_TO, RobotCommand.SWITCH_MAP, RobotCommand.CONFIG_CHANGED, RobotCommand.RECHECK, RobotCommand.SET_MODE)) {
            assertNull(type, ModeRules.refuses(RobotMode.SHOW, type))
            assertNull(type, ModeRules.refuses(RobotMode.CALM, type))
        }
    }

    @Test
    fun theBrainRefusesAWalkInShowMode() {
        val s = show(idle.copy(commands = listOf(guide())))
        val p = Brain.decide(s)
        assertEquals(Plan.Refuse(guide(), "Otto is in Show mode"), p)
        assertEquals("Saying no to a task (Show mode)", Brain.goal(p, s))
        // in normal mode the walk runs
        assertTrue(Brain.decide(idle.copy(commands = listOf(guide()))) is Plan.RunCommand)
        // a go_to still runs in calm mode
        val goTo = RobotCommand("g1", RobotCommand.GO_TO, spot = "Room Two")
        assertEquals(Plan.RunCommand(goTo, excuse = false), Brain.decide(calm(idle.copy(commands = listOf(goTo)))))
    }

    @Test
    fun noGreetingAndNoTalkInShowAndCalm() {
        assertEquals(Plan.Greet(7), Brain.decide(idle.copy(visitor = visitor)))
        assertEquals(Plan.Wait, Brain.decide(show(idle.copy(visitor = visitor))))
        assertEquals(Plan.Wait, Brain.decide(calm(idle.copy(visitor = visitor))))
        // even when the robot thinks someone talks
        assertEquals(Plan.Wait, Brain.decide(calm(idle.copy(talk = TalkLevel.ACTIVE))))
        assertEquals("Calm mode", Brain.goal(Plan.Wait, calm()))
        assertEquals("Show mode", Brain.goal(Plan.Wait, show()))
    }

    @Test
    fun calmDoesNotDriveOnItsOwnButReportsTheLowBattery() {
        val away = idle.copy(atHome = false, quietSince = now - 120_000)
        assertEquals(Plan.ReturnHome, Brain.decide(away))
        assertEquals(Plan.Wait, Brain.decide(calm(away)))
        val low = idle.copy(battery = 12)
        assertEquals(Plan.GoCharge, Brain.decide(low))
        assertEquals(Plan.Wait, Brain.decide(calm(low)))
        assertTrue(Brain.lowBatteryNote(calm(low))!!.contains("Battery low (12 %)"))
        assertNull(Brain.lowBatteryNote(low))
        // docked and full: in calm mode he stays on the charger
        val full = idle.copy(charging = true, dockedByOtto = true, battery = 90)
        assertEquals(Plan.LeaveCharger, Brain.decide(full))
        assertEquals(Plan.Charge, Brain.decide(calm(full)))
        // a drive that runs stops when otto changes to a mode that does not allow it
        assertTrue(Brain.stopsFor(Plan.ReturnHome, RobotMode.CALM))
        assertTrue(Brain.stopsFor(Plan.GoCharge, RobotMode.CALM))
        assertTrue(Brain.stopsFor(Plan.ReturnHome, RobotMode.SHOW))
        assertFalse(Brain.stopsFor(Plan.GoCharge, RobotMode.SHOW))
        assertFalse(Brain.stopsFor(Plan.ReturnHome, RobotMode.NORMAL))
        assertTrue(Brain.stopsFor(Plan.Turn(ShowTurn(10.0, true)), RobotMode.NORMAL))
        assertFalse(Brain.stopsFor(Plan.Turn(ShowTurn(10.0, true)), RobotMode.SHOW))
        // the mode itself does not preempt (a show turn must not stop itself every second)
        assertFalse(Brain.shouldPreempt(calm()))
        assertFalse(Brain.shouldPreempt(show()))
        assertTrue(Brain.shouldPreempt(show(idle.copy(commands = listOf(RobotCommand("m", RobotCommand.SWITCH_MAP, mapName = "Upper"))))))
    }

    @Test
    fun showStaysWhereItIsAndTurnsALittle() {
        val away = idle.copy(atHome = false, quietSince = now - 120_000)
        assertEquals(Plan.Wait, Brain.decide(show(away)))
        // low battery: the show still goes to the charger (calm does not)
        assertEquals(Plan.GoCharge, Brain.decide(show(idle.copy(battery = 12))))
        // a small turn when one is due
        val turn = ShowTurn(12.0, left = true)
        assertEquals(Plan.Turn(turn), Brain.decide(show(idle.copy(showTurn = turn))))
        assertEquals(RobotState.IDLE, Brain.state(Plan.Turn(turn), home))
        // not in the other modes, and not without the chassis
        assertEquals(Plan.Wait, Brain.decide(idle.copy(showTurn = turn)))
        assertEquals(Plan.Wait, Brain.decide(calm(idle.copy(showTurn = turn))))
        assertEquals(Plan.Wait, Brain.decide(show(idle.copy(showTurn = turn, robotReady = false))))
    }

    @Test
    fun theShowTurnsAreSmallGentleAndStayNearTheStart() {
        val moves = ShowMoves(Random(7))
        var t = 1_000L
        // the first turn comes after a gap of 18-40 s
        assertNull(moves.due(t, Double.MAX_VALUE, charging = false))
        var turns = 0
        repeat(400) {
            t += 1_000
            val turn = moves.due(t, Double.MAX_VALUE, charging = false) ?: return@repeat
            turns++
            assertTrue("${turn.degrees}", turn.degrees in ShowMoves.MIN_DEG..ShowMoves.MAX_DEG)
            moves.turned(turn, ok = true, now = t)
            assertTrue("${moves.offsetDeg}", kotlin.math.abs(moves.offsetDeg) <= ShowMoves.MAX_OFFSET_DEG + 1e-9)
        }
        // 400 s: one turn every 18-40 s (checked once a second)
        assertTrue("$turns", turns in 8..23)
    }

    @Test
    fun noShowTurnWithSomeoneCloseOnTheChargerOrWithoutTheCamera() {
        val moves = ShowMoves(Random(1), minGapMs = 1_000, maxGapMs = 1_000)
        assertNull(moves.due(0, Double.MAX_VALUE, false))
        // someone 0.8 m away: no turn, try again 5 s later
        assertNull(moves.due(1_000, 0.8, charging = false))
        assertNull(moves.due(5_999, Double.MAX_VALUE, charging = false))
        // the camera gives nothing: no turn, to be safe
        assertNull(moves.due(6_000, null, charging = false))
        assertNull(moves.due(11_000, Double.MAX_VALUE, charging = true))
        // nobody near, not charging: a turn
        val t = moves.due(16_000, 2.5, charging = false)!!
        assertTrue(t.degrees >= ShowMoves.MIN_DEG)
        // far out to the left: the next turn goes back right
        val far = ShowMoves(Random(3), minGapMs = 1_000, maxGapMs = 1_000)
        far.due(0, Double.MAX_VALUE, false)
        far.turned(ShowTurn(14.0, left = true), true, 0)
        far.turned(ShowTurn(14.0, left = true), true, 0)
        assertFalse(far.due(2_000, Double.MAX_VALUE, false)!!.left)
    }

    @Test
    fun theScreenIsTheFaceAloneInShowAndCalm() {
        val places = listOf(Place("1", "Dr. One", room = "B204", spot = "Room One", kind = PlaceKind.ROOM, selfCheckIn = true))
        val normal = UiState(places = places)
        assertTrue(normal.canSelfCheckIn)
        val showUi = normal.copy(mode = RobotMode.SHOW, help = HelpUi(urgent = true, sent = true))
        // no check-in offer, no info next to the face
        assertFalse(showUi.canSelfCheckIn)
        assertNull(showUi.focus())
        assertEquals("Show mode", showUi.mirror().title)
        assertEquals("idle", showUi.mirror().view)
        assertFalse(normal.copy(mode = RobotMode.CALM).canSelfCheckIn)
        // the planner gets no check-in rooms in show / calm
        assertFalse(normal.copy(mode = RobotMode.CALM).screenInfo().contains("CHECK_IN_FOR"))
        assertTrue(normal.screenInfo().contains("CHECK_IN_FOR"))
    }

    @Test
    fun noLineGetsOutInShowOrCalm() {
        for (mode in listOf(RobotMode.SHOW, RobotMode.CALM)) {
            val said = ArrayList<String>()
            val t = SpeechTurns()
            t.setSilent(mode.silent, "${mode.label} mode")
            var now = 0L
            for (k in SpeechKind.entries) {
                t.offer(SpeechLine(k, "a $k line", urgent = true), now) { if (it == TurnOutcome.SAID) said += k.wire }
                t.offer(SpeechLine(k, "an llm $k line", task = "Say something", question = true), now) { if (it == TurnOutcome.SAID) said += k.wire }
                now += 10_000
                val step = t.next(now)
                assertTrue("$mode $k: $step", step is TurnStep.Idle)
            }
            assertTrue(said.isEmpty())
        }
    }
}
