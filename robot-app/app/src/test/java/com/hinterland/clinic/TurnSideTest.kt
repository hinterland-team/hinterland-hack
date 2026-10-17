package com.hinterland.clinic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// the turn rule for every walk (docs/demo-mode.md, field test b16: turns of 251 and 257 deg looked like
// spinning): walk.turnSide right = the shorter way, except a real turn-around (more than 150 deg away on the
// left, behind) goes via the right; never more than 210 deg. left mirrors it, shortest = always the shorter way
class TurnSideTest {

    @Test
    fun rightTakesTheShorterWayButTurnsAroundViaTheRight() {
        // the b16 cases: a radar fix at +103..+109 deg is a turn of about 105 deg to the left now, not 251-257 right
        assertEquals(Turn(103f, left = true), TurnPlan.choose(103.0, TurnSide.RIGHT))
        assertEquals(Turn(109f, left = true), TurnPlan.choose(109.0, TurnSide.RIGHT))
        assertEquals(Turn(135f, left = true), TurnPlan.choose(135.0, TurnSide.RIGHT))
        assertEquals(Turn(150f, left = true), TurnPlan.choose(150.0, TurnSide.RIGHT))
        // a real turn-around on the left: via the right, at most 210 deg
        assertEquals(Turn(200f, left = false), TurnPlan.choose(160.0, TurnSide.RIGHT))
        assertEquals(Turn(180f, left = false), TurnPlan.choose(180.0, TurnSide.RIGHT))
        assertEquals(Turn(180f, left = false), TurnPlan.choose(-180.0, TurnSide.RIGHT))
        val justOver = TurnPlan.choose(150.5, TurnSide.RIGHT)!!
        assertEquals(false, justOver.left)
        assertEquals(209.5f, justOver.degrees, 1e-3f)
        // on the right: the shorter way is the right anyway
        assertEquals(Turn(135f, left = false), TurnPlan.choose(-135.0, TurnSide.RIGHT))
        assertEquals(Turn(170f, left = false), TurnPlan.choose(-170.0, TurnSide.RIGHT))
        assertEquals(Turn(60f, left = false), TurnPlan.choose(-60.0, TurnSide.RIGHT))
        assertEquals(Turn(60f, left = true), TurnPlan.choose(60.0, TurnSide.RIGHT))
        // the default of the setting and of TurnSide.of
        assertEquals(TurnSide.RIGHT, Settings().turnSide)
        assertEquals(TurnSide.RIGHT, TurnSide.of("sideways"))
        assertEquals(TurnSide.SHORTEST, TurnSide.of(" Shortest "))
    }

    @Test
    fun neverMoreThan210Deg() {
        for (side in TurnSide.entries) {
            var b = -180.0
            while (b <= 180.0) {
                val t = TurnPlan.choose(b, side)
                if (t != null) assertTrue("$side $b: ${t.degrees}", t.degrees <= TurnPlan.MAX_TURN_DEG.toFloat())
                b += 0.5
            }
        }
    }

    @Test
    fun leftMirrorsItAndShortestIsAlwaysTheShorterWay() {
        assertEquals(Turn(200f, left = true), TurnPlan.choose(-160.0, TurnSide.LEFT))
        assertEquals(Turn(135f, left = false), TurnPlan.choose(-135.0, TurnSide.LEFT))
        assertEquals(Turn(160f, left = true), TurnPlan.choose(160.0, TurnSide.LEFT))
        assertEquals(Turn(70f, left = false), TurnPlan.choose(-70.0, TurnSide.LEFT))
        assertEquals(Turn(160f, left = true), TurnPlan.choose(160.0, TurnSide.SHORTEST))
        assertEquals(Turn(160f, left = false), TurnPlan.choose(-160.0, TurnSide.SHORTEST))
        // an angle past the half circle comes in wrapped; nothing to turn
        assertEquals(Turn(90f, left = false), TurnPlan.choose(270.0, TurnSide.RIGHT))
        assertNull(TurnPlan.choose(0.0, TurnSide.RIGHT))
        assertNull(TurnPlan.choose(360.0, TurnSide.RIGHT))
    }

    @Test
    fun theCheckBackTurnTowardsThePatientFollowsTheRule() {
        // the patient last seen behind on the left: the shorter way
        assertEquals(Turn(135f, left = true), TurnPlan.toward(135.0, WalkSide.LEFT, TurnSide.RIGHT))
        // right behind on the left: the turn-around goes via the right
        assertEquals(Turn(195f, left = false), TurnPlan.toward(165.0, WalkSide.LEFT, TurnSide.RIGHT))
        // nothing known: the walk side, a little behind
        assertEquals(Turn(135f, left = true), TurnPlan.toward(null, WalkSide.LEFT, TurnSide.RIGHT))
        assertEquals(Turn(135f, left = false), TurnPlan.toward(null, WalkSide.RIGHT, TurnSide.RIGHT))
        // almost straight ahead: no turn
        assertNull(TurnPlan.toward(12.0, WalkSide.LEFT, TurnSide.RIGHT))
        // after the turn-around via the right the patient is straight ahead (the target follows the turn made)
        val p = Predicted(bearingDeg = 165.0, distance = 2.0, source = FixSource.RADAR, ageMs = 500, relative = true)
        val turn = TurnPlan.toward(p.bearingDeg, WalkSide.LEFT, TurnSide.RIGHT)
        val target = TurnPlan.target(p, turn, null, WalkSide.LEFT)
        assertEquals(0.0, target.bearingDeg, 1e-6)
        assertEquals(2.0, target.distance!!, 1e-9)
    }

    @Test
    fun theSettingIsReadAndCheckedLikeTheOthers() {
        val ok = SettingsSpec.parse("""{"version": 3, "settings": {"walk": {"turnSide": "shortest"}}}""", Settings())!!
        assertEquals(TurnSide.SHORTEST, ok.settings.turnSide)
        assertTrue(ok.warnings.isEmpty())
        assertEquals(TurnSide.LEFT, SettingsSpec.parse("""{"walk": {"turnSide": "LEFT"}}""", Settings())!!.settings.turnSide)
        // an unknown value: the default, with a warning
        val bad = SettingsSpec.parse("""{"walk": {"turnSide": "clockwise"}}""", Settings())!!
        assertEquals(TurnSide.RIGHT, bad.settings.turnSide)
        assertTrue(bad.warnings.any { it.contains("walk.turnSide = \"clockwise\" is not right or left or shortest, using right") })
        assertEquals(listOf("walk.turnSide right -> left"), SettingsSpec.changes(Settings(), Settings(turnSide = TurnSide.LEFT)))
    }
}
