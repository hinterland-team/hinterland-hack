package com.hinterland.clinic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// v3.3 short stops: what a check-back does, and the short listening window after a question
class StopsTest {

    private val t0 = 10_000_000_000L

    private fun kind(no: Int, beside: Double?, last: Double? = 1.0, fellBack: Boolean = false, reason: CheckReason = CheckReason.REGULAR) =
        StopRules.kind(StopInput(reason, no, beside, last, fellBack))

    @Test
    fun patientCloseBesideMeansNoStopAndNoListening() {
        // the radar sees them 1 m beside: a 2-3 s line while walking, no question
        assertEquals(StopKind.ROLLING, kind(no = 1, beside = 1.0))
        assertEquals(StopKind.ROLLING, kind(no = 3, beside = 1.8))
        // not seen beside right now: stop, turn to them, look, a short line - still no listening
        assertEquals(StopKind.QUICK, kind(no = 1, beside = null))
        // beside but more than 2 m away is not "beside"
        assertEquals(StopKind.QUICK, kind(no = 1, beside = 2.6))
    }

    @Test
    fun aRealQuestionOnlyEverySecondCheck() {
        val kinds = (1..6).map { kind(no = it, beside = 1.0) }
        assertEquals(
            listOf(StopKind.ROLLING, StopKind.ASK, StopKind.ROLLING, StopKind.ASK, StopKind.ROLLING, StopKind.ASK),
            kinds,
        )
    }

    @Test
    fun aQuestionWhenThePatientWasFurtherOrFellBack() {
        // 2.5 m away at the last check: ask, even on an odd check and with the radar seeing them now
        assertEquals(StopKind.ASK, kind(no = 3, beside = 1.0, last = 2.5))
        // waited for them / found them again since the last check
        assertEquals(StopKind.ASK, kind(no = 3, beside = 1.0, fellBack = true))
        // a close last check keeps it short
        assertEquals(StopKind.ROLLING, kind(no = 3, beside = 1.0, last = 1.9))
    }

    @Test
    fun theSideWatchFiringMeansAStopWithAReminderNotAQuestion() {
        // nobody beside for 5 s: mostly the patient walks behind, where the radar is blind. stop, turn to
        // them, "please stay next to me" - also on an even check
        assertEquals(StopKind.QUICK, kind(no = 1, beside = null, reason = CheckReason.FOLLOWER_LOST))
        assertEquals(StopKind.QUICK, kind(no = 2, beside = null, reason = CheckReason.FOLLOWER_LOST))
        // they had already fallen back before: a real question
        assertEquals(StopKind.ASK, kind(no = 3, beside = null, fellBack = true, reason = CheckReason.FOLLOWER_LOST))
    }

    @Test
    fun theListeningWindowIsAtMostFiveSeconds() {
        val w = ListenWindow(askedAt = t0, startedAt = t0 + 2_000)
        assertFalse(w.done(t0 + 6_900, userSpokeAt = 0, decided = false))
        assertTrue(w.done(t0 + 7_000, userSpokeAt = 0, decided = false))
        assertFalse(w.answeredByVoice)
        assertEquals(0.5f, w.listened(t0 + 4_500), 1e-6f)
    }

    @Test
    fun anAnswerEndsItWithoutWaitingForTheAgentsReply() {
        val w = ListenWindow(askedAt = t0, startedAt = t0 + 2_000)
        // "yes" at 3.0-3.4 s; 0.9 s of quiet later the walk goes on (the agent answers by itself)
        assertFalse(w.done(t0 + 3_400, userSpokeAt = t0 + 3_400, decided = false))
        assertFalse(w.done(t0 + 4_200, userSpokeAt = t0 + 3_400, decided = false))
        assertTrue(w.done(t0 + 4_300, userSpokeAt = t0 + 3_400, decided = false))
        assertTrue(w.answeredByVoice)
    }

    @Test
    fun anAnswerOverTheQuestionCounts() {
        // the patient answered while otto was still asking (barge-in): over as soon as they are quiet
        val w = ListenWindow(askedAt = t0, startedAt = t0 + 2_000)
        assertTrue(w.done(t0 + 2_100, userSpokeAt = t0 + 1_000, decided = false))
        assertTrue(w.answeredByVoice)
        // words from before the question do not count
        val v = ListenWindow(askedAt = t0, startedAt = t0 + 2_000)
        assertFalse(v.done(t0 + 2_100, userSpokeAt = t0 - 500, decided = false))
        assertFalse(v.answeredByVoice)
    }

    @Test
    fun aTapEndsItAtOnce() {
        val w = ListenWindow(askedAt = t0, startedAt = t0)
        assertTrue(w.done(t0 + 300, userSpokeAt = 0, decided = true))
    }

    @Test
    fun aStopStaysUnderTenSeconds() {
        // the timing the stop is built from (Guide.checkBack): release, turn at 40 deg/s towards the patient
        // beside (~110 deg), at most 4 looks, the question during the turn, at most 5 s of listening
        val turnS = 110.0 / 40.0 + 0.2
        val quick = Robot.NAV_RELEASE_MS / 1000.0 + turnS + 0.35 + 0.3
        val askTypical = quick + 2.0
        val askWorst = Robot.NAV_RELEASE_MS / 1000.0 + turnS + 0.35 + 3 * 0.3 + ListenWindow.MAX_MS / 1000.0
        assertTrue("quick $quick s", quick < 5.0)
        assertTrue("ask $askTypical s", askTypical < 7.0)
        assertTrue("ask at most $askWorst s", askWorst < 10.0)
    }
}
