package com.hinterland.clinic

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// the walk's ready lines: one llm answer in the patient's language, english for anything missing.
// place names and lines are test data only.
class WalkLinesTest {

    private val ctx = LineContext(ticket = "A17", destination = "Room B204", side = WalkSide.LEFT)

    @Test
    fun englishFallbacksForEveryLine() {
        LineKey.entries.forEach { k ->
            val s = LineSet.english(k, ctx)
            assertTrue("$k", s.isNotBlank() && s.length <= LineSet.MAX_CHARS && !s.contains('{'))
        }
        assertTrue(LineSet.english(LineKey.START, ctx).contains("on my left side"))
        assertTrue(LineSet.english(LineKey.START, ctx.copy(side = WalkSide.RIGHT)).contains("on my right side"))
        assertTrue(LineSet.english(LineKey.START, ctx).contains("Room B204"))
        // the start line never says the ticket: asked at the robot, TICKET_IS is said in one line with the
        // pace question or the start (SpeechTurns, field test 17.10: no line twice)
        assertFalse(LineSet.english(LineKey.START, ctx).contains("A17"))
        assertFalse(LineSet.english(LineKey.START, ctx.copy(ownRequest = true)).contains("A17"))
        assertEquals("Your ticket is A17.", LineSet.english(LineKey.TICKET_IS, ctx.copy(ownRequest = true)))
        assertEquals("You're checked in. Your ticket is A17.", LineSet.english(LineKey.TICKET_IS, ctx.copy(ownRequest = true, checkIn = true)))
        assertTrue(LineSet.task(LineKey.TICKET_IS, ctx.copy(checkIn = true)).contains("checked in"))
        assertTrue(LineSet.task(LineKey.START, ctx).contains("Do not mention the ticket"))
        // the dashboard's style note goes into the walk lines' prompt
        assertEquals(LineSet.SYSTEM, LineSet.system(""))
        assertTrue(LineSet.system("Speak a little slower.").endsWith("Speak a little slower."))
        assertTrue(LineSet.english(LineKey.CALL_OUT, ctx).contains("A17"))
        // the arrival line fits the place
        assertTrue(LineSet.english(LineKey.ARRIVED, ctx).contains("This is Room B204"))
        assertTrue(LineSet.english(LineKey.ARRIVED, ctx.copy(arrival = Arrival.EXIT)).contains("exit"))
        assertTrue(LineSet.english(LineKey.ARRIVED, ctx.copy(arrival = Arrival.WAITING)).contains("A17"))
        // otto's calm style: hardly any exclamation marks
        assertTrue(LineKey.entries.count { LineSet.english(it, ctx).contains('!') } <= 1)
    }

    @Test
    fun thePromptAsksForEveryKeyInThePatientsLanguage() {
        val p = LineSet.prompt(ctx, Lang("de"))
        assertTrue(p.contains("\"de\""))
        LineKey.entries.forEach { assertTrue("${it.name} missing", p.contains("${it.name}: ")) }
        assertTrue(p.contains("JSON"))
        // from the patient's own words when there are some
        assertTrue(LineSet.prompt(ctx, Lang(null, "Können Sie langsamer gehen?")).contains("Können Sie langsamer gehen?"))
        // otto's voice for every line, never a name
        assertTrue(LineSet.SYSTEM.contains(Voice.STYLE))
        assertTrue(LineSet.SYSTEM.contains("never a name"))
    }

    @Test
    fun theLlmAnswerIsReadEvenWithTextAround() {
        val raw = """
            Sure, here are the lines:
            ```json
            {"START": "Bitte gehen Sie links neben mir, etwas hinter mir. Wir gehen zu Raum B204.",
             "CHECK_OK": "Gut. Wir gehen weiter.",
             "ASK": "Alles gut? Soll ich langsamer gehen?",
             "UNKNOWN_KEY": "ignored",
             "GO_ON": 42}
            ```
        """.trimIndent()
        val m = LineSet.parse(raw)
        assertEquals(3, m.size)
        assertEquals("Gut. Wir gehen weiter.", m[LineKey.CHECK_OK])
        assertFalse(m.containsKey(LineKey.GO_ON))
    }

    @Test
    fun brokenOrOddLinesAreLeftOut() {
        assertTrue(LineSet.parse(null).isEmpty())
        assertTrue(LineSet.parse("no json here").isEmpty())
        assertTrue(LineSet.parse("{\"START\": \"half").isEmpty())
        val m = LineSet.parse(
            """{"CHECK_OK": "  Gut.\n  Weiter so.  ", "ASK": "${"x".repeat(300)}", "GO_ON": "Weiter zu {destination}", "WAIT_FOR_YOU": "   ", "HALF_WAY": "\"Halbzeit.\""}""",
        )
        // whitespace and line breaks become one line, quotes around it go
        assertEquals("Gut. Weiter so.", m[LineKey.CHECK_OK])
        assertEquals("Halbzeit.", m[LineKey.HALF_WAY])
        // too long, an echoed placeholder, empty: not used
        assertFalse(m.containsKey(LineKey.ASK))
        assertFalse(m.containsKey(LineKey.GO_ON))
        assertFalse(m.containsKey(LineKey.WAIT_FOR_YOU))
    }

    @Test
    fun aMissingOrFailedLineFallsBackToEnglish() {
        val lines = WalkLines(ctx)
        assertFalse(lines.ready)
        assertEquals(LineSet.english(LineKey.ASK, ctx), lines.text(LineKey.ASK))
        assertFalse(lines.localized(LineKey.ASK))
        lines.accept(mapOf(LineKey.ASK to "Alles gut? Soll ich langsamer gehen?"), fromWords = true)
        assertTrue(lines.ready)
        assertTrue(lines.fromWords)
        assertEquals("Alles gut? Soll ich langsamer gehen?", lines.text(LineKey.ASK))
        assertTrue(lines.localized(LineKey.ASK))
        // a line the llm left out: english, never silence
        assertEquals(LineSet.english(LineKey.GO_ON, ctx), lines.text(LineKey.GO_ON))
    }

    @Test
    fun waitingForTheLinesEndsWhenTheyComeOrFail() = runBlocking {
        val slow = WalkLines(ctx)
        // nothing comes: the wait ends after the time, english from then on
        val t = System.currentTimeMillis()
        assertFalse(slow.await(150))
        assertTrue(System.currentTimeMillis() - t >= 140)
        // the llm failed: nobody waits any longer
        slow.failed()
        val t2 = System.currentTimeMillis()
        assertFalse(slow.await(5_000))
        assertTrue(System.currentTimeMillis() - t2 < 1_000)
        val ok = WalkLines(ctx)
        ok.accept(mapOf(LineKey.START to "Bitte links neben mir."), fromWords = false)
        assertTrue(ok.await(5_000))
    }

    @Test
    fun theWalkSideComesFromTheConfig() {
        assertEquals(WalkSide.LEFT, WalkSide.of(null))
        assertEquals(WalkSide.LEFT, WalkSide.of("left"))
        assertEquals(WalkSide.RIGHT, WalkSide.of(" Right "))
        assertEquals(WalkSide.LEFT, WalkSide.of("links"))
        assertEquals(1, WalkSide.LEFT.sign)
        assertEquals(-1, WalkSide.RIGHT.sign)
    }
}
