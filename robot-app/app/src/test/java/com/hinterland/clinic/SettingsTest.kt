package com.hinterland.clinic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// live settings (docs/robot-setup.md 3): GET /api/robot/config -> { version, settings }. defaults, ranges, fallbacks
class SettingsTest {

    private val d = Settings()

    private fun read(json: String): ConfigRead = SettingsSpec.parse(json, d)!!

    @Test
    fun nestedGroupsAreRead() {
        val r = read(
            """{"version": 4, "settings": {
                 "walk": {"speedNormal": 0.55, "speedSlow": 0.35, "walkSide": "right", "firstCheckS": 8, "askEvery": 3, "listenS": 6},
                 "way": {"blockedS": 30, "backOff": false, "autoWaypoints": "off", "maxWaypoints": 1, "helpAfterS": 120},
                 "voice": {"volume": 70, "callLanguages": ["de", "tr", "en"], "styleNote": "Speak a little slower.",
                           "hideVoiceBar": false, "openMicOnWalks": true, "linePauseS": 1.2, "answerWaitS": 6, "maxWaitingLines": 2},
                 "sensors": {"radarWatch": false, "mapMovingS": 3, "mapIdleS": 20, "debugLogs": true},
                 "screen": {"privacyLine": "", "talkHints": false},
                 "mode": "show"
               }}""",
        )
        assertEquals(4, r.version)
        assertTrue(r.warnings.toString(), r.warnings.isEmpty())
        val s = r.settings
        assertEquals(0.55, s.speedNormal, 1e-9)
        assertEquals(0.35, s.speedSlow, 1e-9)
        // not in the answer: the default
        assertEquals(d.speedVerySlow, s.speedVerySlow, 1e-9)
        assertEquals(WalkSide.RIGHT, s.walkSide)
        assertEquals(8, s.firstCheckS)
        assertEquals(3, s.askEvery)
        assertEquals(6, s.listenS)
        assertEquals(30, s.blockedS)
        assertEquals(false, s.backOff)
        assertEquals(false, s.autoWaypoints)
        assertEquals(1, s.maxWaypoints)
        assertEquals(120, s.helpAfterS)
        assertEquals(70, s.volume)
        assertEquals(listOf("de", "tr", "en"), s.callLanguages)
        assertEquals("Speak a little slower.", s.styleNote)
        assertEquals(false, s.hideVoiceBar)
        assertEquals(TurnTiming(pauseMs = 1_200, answerWaitMs = 6_000, afterUserMs = 700, maxWaiting = 2), s.turns)
        assertEquals(false, s.radarWatch)
        assertEquals(3, s.mapMovingS)
        assertEquals(20, s.mapIdleS)
        assertEquals(true, s.debugLogs)
        // an empty privacy line = none on the screen
        assertEquals("", s.privacyLine)
        assertEquals(false, s.talkHints)
        assertEquals(RobotMode.SHOW, s.mode)
        assertEquals(26, r.fromServer)
    }

    @Test
    fun flatAndOtherSpellingsMeanTheSameSetting() {
        val r = read("""{"version": "7", "settings": {"walk.speedNormal": 0.5, "walk_speed_slow": "0.3", "firstCheckSeconds": 12, "walk": {"check": {"minS": 8}}, "volume": 40}}""")
        assertEquals(7, r.version)
        assertEquals(0.5, r.settings.speedNormal, 1e-9)
        assertEquals(0.3, r.settings.speedSlow, 1e-9)
        assertEquals(12, r.settings.firstCheckS)
        assertEquals(8, r.settings.checkMinS)
        assertEquals(40, r.settings.volume)
        // a { value, default, min, max } object is one setting
        assertEquals(0.45, read("""{"settings": {"walk": {"speedNormal": {"value": 0.45, "default": 0.6, "min": 0.1}}}}""").settings.speedNormal, 1e-9)
        // a bare object without "settings" is read too
        assertEquals(WalkSide.RIGHT, read("""{"version": 1, "walk": {"walkSide": "right"}}""").settings.walkSide)
    }

    @Test
    fun outOfRangeAndUnknownValuesFallBackWithAWarning() {
        val r = read(
            """{"version": 2, "settings": {"walk": {"speedNormal": 2.0, "walkSide": "middle", "listenS": -1, "pickupTimeoutS": "soon", "askEvery": 99},
               "voice": {"volume": 150, "callLanguages": ["german", "?"], "styleNote": "${"x".repeat(400)}"},
               "screen": {"talkHints": "maybe"}, "mode": "party", "colour": "blue"}}""",
        )
        val s = r.settings
        assertEquals(d.speedNormal, s.speedNormal, 1e-9)
        assertEquals(d.walkSide, s.walkSide)
        assertEquals(d.listenS, s.listenS)
        assertEquals(d.pickupTimeoutS, s.pickupTimeoutS)
        assertEquals(d.askEvery, s.askEvery)
        assertNull(s.volume)
        assertEquals(d.callLanguages, s.callLanguages)
        assertEquals("", s.styleNote)
        assertEquals(true, s.talkHints)
        assertNull(s.mode)
        // one warning per value, and one for the unknown keys; nothing came from the server
        assertEquals(r.warnings.joinToString("\n"), 11, r.warnings.size)
        assertTrue(r.warnings.any { it.startsWith("walk.speedNormal = 2.0 is not 0.1-0.85 m/s, using 0.6") })
        assertTrue(r.warnings.any { it.contains("walk.walkSide = \"middle\" is not left or right, using left") })
        assertTrue(r.warnings.any { it.contains("voice.volume = 150") && it.contains("as set on the robot") })
        assertTrue(r.warnings.last().contains("unknown settings left out: colour"))
        assertEquals(listOf("colour"), r.unknown)
        assertEquals(0, r.fromServer)
    }

    @Test
    fun valuesThatOnlyWorkTogetherAreChecked() {
        // slow faster than normal: all three speeds back to the defaults
        val speeds = read("""{"settings": {"walk": {"speedNormal": 0.3, "speedSlow": 0.5}}}""")
        assertEquals(d.speedNormal, speeds.settings.speedNormal, 1e-9)
        assertEquals(d.speedSlow, speeds.settings.speedSlow, 1e-9)
        assertTrue(speeds.warnings.single().startsWith("walk speeds must go normal >= slow >= very slow"))
        // the shortest check interval longer than the longest
        val checks = read("""{"settings": {"walk": {"checkMinS": 40, "checkMaxS": 20}}}""")
        assertEquals(d.checkMinS, checks.settings.checkMinS)
        assertEquals(d.checkMaxS, checks.settings.checkMaxS)
        assertEquals(1, checks.warnings.size)
    }

    @Test
    fun numbersAndSwitchesComeInManyForms() {
        val s = read("""{"settings": {"walk": {"firstCheckS": 7.6, "farWaitS": "25"}, "way": {"backOff": 0, "autoWaypoints": "yes"}, "sensors": {"debugLogs": "on"}}}""").settings
        // whole seconds are rounded
        assertEquals(8, s.firstCheckS)
        assertEquals(25, s.farWaitS)
        assertEquals(false, s.backOff)
        assertEquals(true, s.autoWaypoints)
        assertEquals(true, s.debugLogs)
        // call languages as a comma list; a tag that is none is left out with a warning
        val r = read("""{"settings": {"voice": {"callLanguages": "de, en, klingon!"}}}""")
        assertEquals(listOf("de", "en"), r.settings.callLanguages)
        assertTrue(r.warnings.single().contains("left out klingon!"))
    }

    @Test
    fun nullKeepsTheDefaultOrLeavesItAsItIs() {
        val base = Settings(volume = 50, mode = RobotMode.CALM)
        val r = SettingsSpec.parse("""{"settings": {"voice": {"volume": null}, "mode": null, "walk": {"listenS": null}}}""", base)!!
        // volume null = leave the robot's volume alone; mode null = the mode stays
        assertNull(r.settings.volume)
        assertNull(r.settings.mode)
        assertEquals(base.listenS, r.settings.listenS)
        assertTrue(r.warnings.isEmpty())
    }

    @Test
    fun notJsonIsNoAnswerAndAnEmptyOneGivesTheDefaults() {
        assertNull(SettingsSpec.parse("<html>502</html>", d))
        assertNull(SettingsSpec.parse(null, d))
        val empty = read("{}")
        assertNull(empty.version)
        assertEquals(d, empty.settings)
        assertTrue(empty.warnings.isEmpty())
    }

    @Test
    fun theDefaultsAreTheOldValues() {
        assertEquals(0.6, PaceTable.speed(Pace.NORMAL, d).linear, 1e-9)
        assertEquals(0.4, PaceTable.speed(Pace.SLOW, d).linear, 1e-9)
        assertEquals(0.25, PaceTable.speed(Pace.VERY_SLOW, d).linear, 1e-9)
        assertEquals(CheckSchedule.FIRST_MS, d.firstCheckS * 1000L)
        assertEquals(CheckSchedule.MIN_MS, d.checkMinS * 1000L)
        assertEquals(CheckSchedule.MAX_MS, d.checkMaxS * 1000L)
        assertEquals(SideWatch.LOST_MS, d.besideLostS * 1000L)
        assertEquals(ListenWindow.MAX_MS, d.listenS * 1000L)
        assertEquals(PickupTimeline.TIMEOUT_MS, d.pickupTimeoutS * 1000L)
        assertEquals(PickupTimeline.REMIND_EVERY_MS, d.pickupRemindS * 1000L)
        assertEquals(FarWait.MAX_MS, d.farWaitS * 1000L)
        assertEquals(LostTimeline.SECOND_CALL_MS, d.lostCallAgainS * 1000L)
        assertEquals(LostTimeline.REPORT_MS, d.lostReportS * 1000L)
        assertEquals(LostTimeline.GIVE_UP_AFTER_REPORT_MS, d.lostGiveUpS * 1000L)
        assertEquals(BlockedWatch.TOTAL_LIMIT_MS, d.blockedS * 1000L)
        assertEquals(DetourSteps.MAX_WAYPOINTS, d.maxWaypoints)
        assertEquals(MapRate.MOVING_MS, d.mapMovingS * 1000L)
        assertEquals(MapRate.IDLE_MS, d.mapIdleS * 1000L)
        assertEquals(TurnTiming(800, 7_000, 700, 1), d.turns)
        // every setting has a range and a line of help for the dashboard
        SettingsSpec.fields.forEach { assertTrue(it.path, it.help.isNotBlank() && it.range().isNotBlank()) }
        assertEquals(SettingsSpec.fields.size, SettingsSpec.fields.map { it.path }.toSet().size)
    }

    @Test
    fun theSettingsDriveTheWalk() {
        val s = read("""{"settings": {"walk": {"speedNormal": 0.7, "askEvery": 0, "checkMinS": 6, "checkMaxS": 40}}}""").settings
        assertEquals(0.7, PaceTable.speed(Pace.NORMAL, s).linear, 1e-9)
        // askEvery 0: no question on the schedule, only when the patient fell behind
        val second = StopInput(CheckReason.REGULAR, 2, null, null, fellBack = false)
        assertEquals(StopKind.QUICK, StopRules.kind(second, s.askEvery))
        assertEquals(StopKind.ASK, StopRules.kind(second.copy(fellBack = true), s.askEvery))
        assertEquals(StopKind.ASK, StopRules.kind(second, 2))
        // radar watch off: no stop is skipped on the radar's word
        val beside = StopInput(CheckReason.REGULAR, 1, 1.0, null, fellBack = false)
        assertEquals(StopKind.ROLLING, StopRules.kind(beside, 2, rolling = true))
        assertEquals(StopKind.QUICK, StopRules.kind(beside, 2, rolling = false))
        // the check interval follows the new range
        assertEquals(40_000L, CheckSchedule.intervalFor(1.0, 6_000, 40_000))
        assertEquals(6_000L, CheckSchedule.intervalFor(5.0, 6_000, 40_000))
        assertEquals(23_000L, CheckSchedule.intervalFor(null, 6_000, 40_000))
    }

    @Test
    fun changesAreListedForTheLog() {
        val a = Settings()
        val b = a.copy(speedSlow = 0.35, volume = 60, mode = RobotMode.SHOW)
        val c = SettingsSpec.changes(a, b)
        assertEquals(listOf("walk.speedSlow 0.4 -> 0.35", "voice.volume as set on the robot -> 60", "mode as it is -> show"), c)
        assertTrue(SettingsSpec.changes(a, a).isEmpty())
        assertNotNull(SettingsSpec.find("mode"))
        assertEquals("walk.speedNormal", SettingsSpec.find("speed_normal")?.path)
    }

    @Test
    fun volumeStepsFromPercent() {
        assertEquals(0, VolumeMath.index(0, 15))
        assertEquals(15, VolumeMath.index(100, 15))
        assertEquals(11, VolumeMath.index(70, 15))
        assertEquals(15, VolumeMath.index(140, 15))
        assertEquals(0, VolumeMath.index(50, 0))
    }
}
