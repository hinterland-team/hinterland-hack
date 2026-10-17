package com.hinterland.clinic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// server list (docs/journeys.md 6.1) and robot logs (6.2). urls are test data only.
class ServersLogsTest {

    private val online = "https://clinic.example.app"
    private val laptop = "http://10.0.0.20:3000"

    // ---- servers ----

    @Test
    fun theListIsCleanedAndFallsBackToTheSingleUrl() {
        assertEquals(listOf(online, laptop), ServerList.parse(" $online/ , $laptop", null))
        // the old single url joins at the end, once
        assertEquals(listOf(online, laptop), ServerList.parse(online, laptop))
        assertEquals(listOf(laptop), ServerList.parse("", laptop))
        assertEquals(listOf(laptop), ServerList.parse("$laptop,$laptop/", laptop))
        // not a url: dropped
        assertEquals(listOf(laptop), ServerList.parse("clinic.example.app, ftp://x", laptop))
        assertTrue(ServerList.parse(null, null).isEmpty())
    }

    @Test
    fun theFirstServerThatAnswersWins() {
        val states = listOf(ServerState(online, up = false), ServerState(laptop, up = true, ms = 12))
        // field test 17.10: the online server was not reachable from the robot's wifi
        assertEquals(laptop, ServerList.pick(states, online))
        assertEquals(online, ServerList.pick(listOf(ServerState(online, up = true), ServerState(laptop, up = true)), laptop))
        // nobody answers: stay, don't flip back and forth
        assertEquals(laptop, ServerList.pick(listOf(ServerState(online, up = false), ServerState(laptop, up = false)), laptop))
        assertEquals(online, ServerList.pick(listOf(ServerState(online), ServerState(laptop)), ""))
    }

    @Test
    fun healthAnswersAreJudged() {
        assertTrue(ServerList.judge(200, """{"ok":true,"version":"3.2","time":"2026-10-17T12:00:00Z"}""")!!.up)
        assertFalse(ServerList.judge(200, """{"ok": false}""")!!.up)
        assertTrue(ServerList.judge(200, "")!!.up)
        // an older server without /api/health is still ours
        assertTrue(ServerList.judge(404, "not found")!!.up)
        assertTrue(ServerList.judge(401, null)!!.up)
        assertFalse(ServerList.judge(502, "bad gateway")!!.up)
        assertEquals("clinic.example.app", ServerList.host(online))
        assertEquals("10.0.0.20:3000", ServerList.host(laptop))
        assertEquals("10.0.0.20:3000", ServerState(laptop).host)
    }

    // ---- logs ----

    @Test
    fun linesAreOneLineAndShort() {
        assertEquals("a b c", LogQueue.clean("a\nb\r\n\tc"))
        assertEquals(LogQueue.MAX_TEXT, LogQueue.clean("x".repeat(1_000)).length)
        val q = LogQueue()
        assertNull(q.add(1, LogLevel.INFO, LogSource.APP, "   "))
    }

    @Test
    fun theRingKeepsTheLastLinesAndDebugStaysOnTheRobot() {
        val q = LogQueue(keep = 3)
        q.add(1, LogLevel.DEBUG, LogSource.NAV, "d")
        q.add(2, LogLevel.INFO, LogSource.NAV, "i1")
        q.add(3, LogLevel.WARN, LogSource.NET, "w")
        q.add(4, LogLevel.ERROR, LogSource.LIDAR, "e")
        assertEquals(listOf("i1", "w", "e"), q.recent().map { it.text })
        // debug is only in the staff menu
        assertEquals(3, q.pending())
        assertEquals(listOf("i1", "w", "e"), q.drain(now = 9).map { it.text })
        assertEquals(0, q.pending())
    }

    @Test
    fun offlineLinesWaitAndTheOldestGoFirstWhenFull() {
        val q = LogQueue(queueMax = 3)
        repeat(5) { q.add(it.toLong(), LogLevel.INFO, LogSource.APP, "line $it") }
        val batch = q.drain(max = 10, now = 100)
        // two were dropped while the server was away: that is said first
        assertEquals(LogLevel.WARN, batch.first().level)
        assertTrue(batch.first().text.startsWith("2 log lines were dropped"))
        assertEquals(listOf("line 2", "line 3", "line 4"), batch.drop(1).map { it.text })
        // the post failed: back in front, in order
        q.putBack(batch.drop(1))
        q.add(9, LogLevel.INFO, LogSource.APP, "new")
        assertEquals(listOf("line 3", "line 4", "new"), q.drain(now = 200).filter { it.level == LogLevel.INFO }.map { it.text })
    }

    @Test
    fun batchesAreAtMostFifty() {
        val q = LogQueue()
        repeat(120) { q.add(it.toLong(), LogLevel.INFO, LogSource.BRAIN, "n$it") }
        assertEquals(50, q.drain(now = 0).size)
        assertEquals(70, q.pending())
    }

    @Test
    fun sendingSlowsDownWhileTheServerIsAway() {
        val p = ShipPace()
        assertEquals(5_000, p.waitMs())
        p.failed()
        assertEquals(10_000, p.waitMs())
        p.failed()
        p.failed()
        assertEquals(40_000, p.waitMs())
        repeat(5) { p.failed() }
        assertEquals(60_000, p.waitMs())
        p.ok()
        assertEquals(5_000, p.waitMs())
    }

    @Test
    fun logsBodyHasLevelSourceAndTime() {
        val body = Json.logs("nova", listOf(LogLine(0, LogLevel.WARN, LogSource.LIDAR, "Radar covers 280 deg")))
        assertEquals("nova", body.getString("robot"))
        val item = body.getJSONArray("items").getJSONObject(0)
        assertEquals("warn", item.getString("level"))
        assertEquals("lidar", item.getString("source"))
        assertEquals("1970-01-01T00:00:00Z", item.getString("at"))
        assertEquals("Radar covers 280 deg", item.getString("text"))
    }
}
