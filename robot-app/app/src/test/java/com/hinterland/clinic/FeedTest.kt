package com.hinterland.clinic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// the live feed: patient words only with the dashboard's demo switch on
class FeedTest {

    @Test
    fun patientWordsStayOnTheRobotByDefault() {
        val f = FeedBuffer()
        assertFalse(f.patientSaid(1, "my name is Sam and my knee hurts"))
        f.patientIntent(2, "Asked to walk slower")
        f.novaSaid(3, "Of course, I'll walk slower.")
        val out = f.drain()
        assertEquals(listOf("intent", "said"), out.map { it.kind })
        assertTrue(out.none { it.text.contains("Sam") })
    }

    @Test
    fun demoSwitchLetsThemThrough() {
        val f = FeedBuffer()
        f.showPatientText = true
        assertTrue(f.patientSaid(1, "How far is it?"))
        assertEquals(FeedItem(1, "patient", "said", "How far is it?"), f.drain().single())
    }

    @Test
    fun drainsInBatchesAndPutsBackInOrder() {
        val f = FeedBuffer(capacity = 5)
        repeat(7) { f.novaDid(it.toLong(), "line $it") }
        // full: the oldest are dropped
        assertEquals(5, f.size())
        val batch = f.drain(3)
        assertEquals(listOf("line 2", "line 3", "line 4"), batch.map { it.text })
        f.putBack(batch)
        assertEquals(listOf("line 2", "line 3", "line 4", "line 5", "line 6"), f.drain(10).map { it.text })
        assertEquals(0, f.size())
    }

    @Test
    fun blankLinesAreDroppedLongOnesCut() {
        val f = FeedBuffer()
        f.novaSaid(1, "   ")
        f.novaSaid(2, "x".repeat(500))
        val out = f.drain()
        assertEquals(1, out.size)
        assertEquals(FeedBuffer.MAX_TEXT, out[0].text.length)
    }
}
