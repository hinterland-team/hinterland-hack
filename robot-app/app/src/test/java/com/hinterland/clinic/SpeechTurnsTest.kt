package com.hinterland.clinic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// otto's turns when he talks (SpeechTurns): one line at a time, at most N waiting, the same kind replaces,
// lines that no longer fit are dropped, info + question as one line, a question waits for the answer, the
// patient is let finish, a short pause between lines, nothing at all in show / calm mode. times are made up
class SpeechTurnsTest {

    private val log = ArrayList<String>()
    private var timing = TurnTiming(pauseMs = 800, answerWaitMs = 7_000, afterUserMs = 700, maxWaiting = 1)
    private val t = SpeechTurns({ timing }, { log += it })
    private val outcomes = LinkedHashMap<String, TurnOutcome>()

    private fun line(kind: SpeechKind, text: String, question: Boolean = false, urgent: Boolean = false, topic: String? = null, fits: () -> Boolean = { true }) =
        SpeechLine(kind, text, question = question, urgent = urgent, topic = topic, fits = fits)

    private fun offer(l: SpeechLine, now: Long): Offered = t.offer(l, now) { outcomes[l.text] = it }

    // the next utterance that may play at `now`, or null
    private fun play(now: Long): Utterance? = (t.next(now) as? TurnStep.Play)?.utterance

    private fun waitUntil(now: Long): Long? = (t.next(now) as? TurnStep.Wait)?.untilMs

    @Test
    fun oneLineAtATimeWithAShortPauseBetween() {
        offer(line(SpeechKind.WALK, "We're going to Room B204."), 0)
        val first = play(0)!!
        assertEquals("We're going to Room B204.", first.text)
        offer(line(SpeechKind.MILESTONE, "We're half way there."), 500)
        // the first still plays: the second waits
        assertTrue(t.next(600) is TurnStep.Wait)
        t.finished(first, TurnOutcome.SAID, 3_000)
        assertEquals(TurnOutcome.SAID, outcomes["We're going to Room B204."])
        // the pause after a line
        assertEquals(3_800L, waitUntil(3_100))
        assertEquals("We're half way there.", play(3_800)!!.text)
        assertTrue(log.any { it.startsWith("Said walk (0.0 s in line, 3.0 s): \"We're going to Room B204.\"") })
    }

    @Test
    fun aNewerLineOfTheSameKindReplacesTheWaitingOne() {
        offer(line(SpeechKind.WALK, "Good. Let's keep going."), 0)
        val playing = play(0)!!
        offer(line(SpeechKind.MILESTONE, "We're half way there."), 100)
        offer(line(SpeechKind.MILESTONE, "We're almost there."), 200)
        assertEquals(TurnOutcome.REPLACED, outcomes["We're half way there."])
        assertEquals(listOf("milestone"), t.waitingKinds())
        assertTrue(log.any { it.startsWith("Replaced milestone by a newer one") })
        t.finished(playing, TurnOutcome.SAID, 2_000)
        assertEquals("We're almost there.", play(2_800)!!.text)
    }

    @Test
    fun atMostNLinesWaitTheLessImportantOneGoes() {
        offer(line(SpeechKind.WALK, "Let's go on."), 0)
        val playing = play(0)!!
        offer(line(SpeechKind.REPLY, "Ticket A17: next in line."), 100)
        // a milestone is less important than the reply that waits: dropped
        assertFalse(offer(line(SpeechKind.MILESTONE, "We're half way there."), 200).queued)
        assertEquals(TurnOutcome.DROPPED, outcomes["We're half way there."])
        assertTrue(log.any { it.startsWith("Dropped milestone: 1 line waiting already (max 1)") })
        // an alert is more important: it takes the place of the reply
        assertTrue(offer(line(SpeechKind.ALERT, "Please call 112 now."), 300).queued)
        assertEquals(TurnOutcome.DROPPED, outcomes["Ticket A17: next in line."])
        // with room for two, both wait
        timing = timing.copy(maxWaiting = 2)
        offer(line(SpeechKind.REPLY, "The team is informed."), 400)
        assertEquals(listOf("alert", "reply"), t.waitingKinds())
        t.finished(playing, TurnOutcome.SAID, 1_000)
        // the more important one first
        assertEquals("Please call 112 now.", play(1_800)!!.text)
    }

    @Test
    fun aLineThatNoLongerFitsIsDroppedBeforeItIsSaid() {
        var waitingForPickup = true
        offer(line(SpeechKind.WAY, "Excuse me, may I pass?"), 0)
        val playing = play(0)!!
        offer(line(SpeechKind.CALL, "Ticket A17, please come to me.", fits = { waitingForPickup }), 100)
        // the patient tapped "That's me" while otto was still talking
        waitingForPickup = false
        t.finished(playing, TurnOutcome.SAID, 1_500)
        assertNull(play(5_000))
        assertTrue(t.next(5_000) is TurnStep.Idle)
        assertEquals(TurnOutcome.DROPPED, outcomes["Ticket A17, please come to me."])
        assertTrue(log.any { it.startsWith("Dropped call: no longer fits: \"Ticket A17, please come to me.\"") })
    }

    @Test
    fun infoAndTheNextQuestionAreSaidAsOneLine() {
        offer(line(SpeechKind.WAY, "Excuse me."), 0)
        val playing = play(0)!!
        offer(line(SpeechKind.REPLY, "You're checked in. Your ticket is A17."), 100)
        offer(line(SpeechKind.QUESTION, "Shall I walk normally, or a bit slower?", question = true), 200)
        assertEquals(1, t.waitingKinds().size)
        assertEquals("reply+question", t.waitingKinds().single())
        assertTrue(log.any { it.startsWith("Merged question into reply: \"You're checked in. Your ticket is A17. Shall I walk") })
        t.finished(playing, TurnOutcome.SAID, 1_000)
        val u = play(1_800)!!
        assertEquals("You're checked in. Your ticket is A17. Shall I walk normally, or a bit slower?", u.text)
        assertTrue(u.question)
        t.finished(u, TurnOutcome.SAID, 5_000)
        // both callers hear that it was said
        assertEquals(TurnOutcome.SAID, outcomes["You're checked in. Your ticket is A17."])
        assertEquals(TurnOutcome.SAID, outcomes["Shall I walk normally, or a bit slower?"])
    }

    @Test
    fun onlyThingsThatBelongTogetherAreJoined() {
        offer(line(SpeechKind.WALK, "Please walk next to me."), 0)
        val playing = play(0)!!
        timing = timing.copy(maxWaiting = 3)
        // a question first and then info: not joined (otto would answer his own question)
        offer(line(SpeechKind.QUESTION, "All good?", question = true), 100)
        offer(line(SpeechKind.REPLY, "The team knows you're here."), 200)
        assertEquals(listOf("question", "reply"), t.waitingKinds())
        // unrelated lines are not joined, lines of one topic are
        offer(line(SpeechKind.MILESTONE, "Half way.", topic = "walk"), 300)
        offer(line(SpeechKind.WAY, "Excuse me.", topic = "walk"), 400)
        assertEquals(listOf("question", "reply", "milestone+way"), t.waitingKinds())
        // an llm line is never joined (it is written in the patient's language as one)
        offer(SpeechLine(SpeechKind.CALL, "Ticket A17", task = "Call ticket A17"), 500)
        assertEquals(listOf("question", "reply", "call"), t.waitingKinds())
        t.finished(playing, TurnOutcome.SAID, 1_000)
    }

    @Test
    fun whileThePatientTalksOttoWaits() {
        t.userSpeech(1_000, final = false)
        offer(line(SpeechKind.REPLY, "Ticket A17: next in line."), 1_100)
        // more words may come: wait, and after the last word a moment
        assertEquals(1_000L + SpeechTurns.PARTIAL_GAP_MS + 700, waitUntil(1_200))
        t.userSpeech(2_500, final = true)
        assertEquals(3_200L, waitUntil(2_600))
        assertEquals("Ticket A17: next in line.", play(3_200)!!.text)
    }

    @Test
    fun afterAQuestionOttoWaitsForTheAnswer() {
        offer(line(SpeechKind.QUESTION, "Shall I walk normally, or a bit slower?", question = true), 0)
        val q = play(0)!!
        t.finished(q, TurnOutcome.SAID, 2_000)
        offer(line(SpeechKind.WALK, "Please walk next to me."), 2_100)
        // nothing else for up to 7 s after the question
        assertEquals(9_000L, waitUntil(2_500))
        assertTrue(t.awaitingAnswer(8_999))
        // no answer: after 7 s it goes on
        assertEquals("Please walk next to me.", play(9_000)!!.text)
    }

    @Test
    fun aSpokenAnswerEndsTheWaitAndThePatientIsLetFinish() {
        offer(line(SpeechKind.QUESTION, "All good?", question = true), 0)
        t.finished(play(0)!!, TurnOutcome.SAID, 1_000)
        offer(line(SpeechKind.WALK, "Alright, let's go on."), 1_100)
        // the patient starts talking: still waiting for the answer while the words come
        t.userSpeech(2_000, final = false)
        assertEquals(8_000L, waitUntil(2_100))
        assertTrue(t.awaitingAnswer(2_100))
        t.userSpeech(3_000, final = false)
        // the end of what they said: the answer, then 0.7 s
        t.userSpeech(3_400, final = true)
        assertFalse(t.awaitingAnswer(3_500))
        assertEquals(4_100L, waitUntil(3_500))
        assertEquals("Alright, let's go on.", play(4_100)!!.text)
        assertTrue(log.contains("Answer heard (speech)"))
    }

    @Test
    fun aTapEndsTheWaitForTheAnswer() {
        offer(line(SpeechKind.QUESTION, "How shall we walk?", question = true), 0)
        t.finished(play(0)!!, TurnOutcome.SAID, 1_000)
        offer(line(SpeechKind.WALK, "Please walk next to me."), 1_200)
        assertEquals(8_000L, waitUntil(1_300))
        t.answered(2_000, "tapped slow pace")
        assertTrue(log.contains("Answer: tapped slow pace"))
        // only the pause after the question is left
        assertEquals("Please walk next to me.", play(2_000)!!.text)
    }

    @Test
    fun whileTheAgentTalksOurLinesWait() {
        // otto's agent answers by itself (no line of ours plays)
        assertFalse(t.robotSpeech(1_000, final = false))
        offer(line(SpeechKind.REPLY, "Ticket A17."), 1_100)
        assertEquals(1_000L + SpeechTurns.AGENT_PARTIAL_MS + 800, waitUntil(1_200))
        assertFalse(t.robotSpeech(2_000, final = true))
        assertEquals(2_000L + SpeechTurns.AGENT_FINAL_MS + 800, waitUntil(2_100))
        val u = play(3_400)!!
        // while our own line plays, the words that come back are ours
        assertTrue(t.robotSpeech(3_500, final = false))
        t.finished(u, TurnOutcome.SAID, 4_000)
        // ... and shortly after it ended
        assertTrue(t.robotSpeech(4_500, final = true))
    }

    @Test
    fun anUrgentLineDoesNotWaitForTheQuestionAndCutsALesserOne() {
        offer(line(SpeechKind.QUESTION, "All good?", question = true), 0)
        t.finished(play(0)!!, TurnOutcome.SAID, 1_000)
        // urgent help: no wait for the answer, no pause
        offer(line(SpeechKind.ALERT, "Please call 112 now.", urgent = true), 1_100)
        assertEquals("Please call 112 now.", play(1_100)!!.text)
        // a milestone plays, then an urgent line comes: it may cut it
        val t2 = SpeechTurns({ timing }, {})
        t2.offer(line(SpeechKind.MILESTONE, "Half way."), 0)
        (t2.next(0) as TurnStep.Play).utterance
        assertTrue(t2.offer(line(SpeechKind.ALERT, "The team is on the way.", urgent = true), 100).cut)
        assertFalse(t2.offer(line(SpeechKind.REPLY, "Ticket A17."), 200).cut)
        // but the patient talking is still let finish
        val t3 = SpeechTurns({ timing }, {})
        t3.userSpeech(1_000, final = true)
        t3.offer(line(SpeechKind.ALERT, "Please call 112 now.", urgent = true), 1_100)
        assertEquals(1_700L, (t3.next(1_100) as TurnStep.Wait).untilMs)
    }

    @Test
    fun showAndCalmModeSayNothingAtAll() {
        offer(line(SpeechKind.WALK, "Hello."), 0)
        val playing = play(0)!!
        offer(line(SpeechKind.REPLY, "Ticket A17."), 100)
        // entering show mode: the waiting line goes, the playing one is to be stopped
        assertTrue(t.setSilent(true, "Show mode, Otto is silent"))
        assertEquals(TurnOutcome.SILENT, outcomes["Ticket A17."])
        t.finished(playing, TurnOutcome.INTERRUPTED, 200)
        // no line gets out: every kind, also urgent ones and the team's
        for (k in SpeechKind.entries) {
            val r = offer(line(k, "line $k", urgent = k == SpeechKind.ALERT), 1_000)
            assertFalse(r.queued)
            assertEquals(TurnOutcome.SILENT, outcomes["line $k"])
        }
        assertTrue(t.next(60_000) is TurnStep.Idle)
        assertTrue(log.any { it.startsWith("Not said (team, Show mode, Otto is silent)") })
        // normal again: otto talks again
        assertFalse(t.setSilent(false, ""))
        offer(line(SpeechKind.GREETING, "Hello again."), 70_000)
        assertEquals("Hello again.", play(70_000)!!.text)
    }

    @Test
    fun theFieldTestCheckInIsCalmNow() {
        // 17.10: "check in for Dr. Blue at 9:40" gave five lines in a row. now: the agent's own words (if any) finish,
        // then ticket + question as one line, then otto waits for the answer before the start line
        assertFalse(t.robotSpeech(0, final = true))
        offer(line(SpeechKind.REPLY, "You're checked in. Your ticket is A17."), 100)
        offer(line(SpeechKind.QUESTION, "Shall I walk normally, or a bit slower?", question = true), 100)
        assertEquals(1_400L, waitUntil(200))
        val first = play(1_400)!!
        assertEquals("reply+question", first.kinds)
        t.finished(first, TurnOutcome.SAID, 5_000)
        offer(line(SpeechKind.WALK, "Please walk next to me, on my left side."), 5_100)
        // the patient answers after 2 s
        t.userSpeech(7_000, final = true)
        assertEquals(7_700L, waitUntil(7_100))
        assertEquals("Please walk next to me, on my left side.", play(7_700)!!.text)
    }

    @Test
    fun clearDropsWhatWaits() {
        offer(line(SpeechKind.WALK, "One."), 0)
        val p = play(0)!!
        offer(line(SpeechKind.REPLY, "Two."), 10)
        t.clear("back to the welcome screen")
        assertEquals(TurnOutcome.DROPPED, outcomes["Two."])
        assertTrue(log.any { it == "Dropped 1 waiting line: back to the welcome screen" })
        t.finished(p, TurnOutcome.SAID, 100)
        assertTrue(t.next(5_000) is TurnStep.Idle)
    }
}
