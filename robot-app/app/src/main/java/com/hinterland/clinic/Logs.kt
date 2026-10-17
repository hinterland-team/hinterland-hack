package com.hinterland.clinic

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

// robot logs (docs/journeys.md 6.2): shown in the staff menu and shipped in batches to
// POST /api/robot/logs. never patient words or names - only what nova does, tickets at most.

enum class LogLevel(val wire: String) { DEBUG("debug"), INFO("info"), WARN("warn"), ERROR("error") }

enum class LogSource(val wire: String) {
    APP("app"), NET("net"), NAV("nav"), VOICE("voice"), BRAIN("brain"), VISION("vision"), LIDAR("lidar"),
}

data class LogLine(val at: Long, val level: LogLevel, val source: LogSource, val text: String)

// the ring for the staff menu plus the queue for the server. plain kotlin, unit tested.
class LogQueue(
    private val keep: Int = KEEP,
    private val queueMax: Int = QUEUE_MAX,
    private val shipFrom: LogLevel = LogLevel.INFO,
) {
    private val recent = ArrayDeque<LogLine>()
    private val queue = ArrayDeque<LogLine>()
    private var dropped = 0

    @Synchronized
    fun add(at: Long, level: LogLevel, source: LogSource, raw: String): LogLine? {
        val text = clean(raw)
        if (text.isEmpty()) return null
        val line = LogLine(at, level, source, text)
        recent.addLast(line)
        while (recent.size > keep) recent.removeFirst()
        if (level.ordinal >= shipFrom.ordinal) {
            queue.addLast(line)
            while (queue.size > queueMax) {
                queue.removeFirst()
                dropped++
            }
        }
        return line
    }

    // newest last
    @Synchronized
    fun recent(): List<LogLine> = recent.toList()

    @Synchronized
    fun pending(): Int = queue.size

    // the next batch for the server. lines lost while offline are reported as one warning first.
    @Synchronized
    fun drain(max: Int = BATCH, now: Long): List<LogLine> {
        val out = ArrayList<LogLine>(minOf(max, queue.size + 1))
        if (dropped > 0) {
            out += LogLine(now, LogLevel.WARN, LogSource.APP, "$dropped log lines were dropped while the server was not reachable")
            dropped = 0
        }
        while (out.size < max && queue.isNotEmpty()) out += queue.removeFirst()
        return out
    }

    // a failed post: back to the front, the oldest go when full
    @Synchronized
    fun putBack(batch: List<LogLine>) {
        batch.asReversed().forEach { queue.addFirst(it) }
        while (queue.size > queueMax) {
            queue.removeFirst()
            dropped++
        }
    }

    companion object {
        const val KEEP = 300
        const val QUEUE_MAX = 1_000
        const val BATCH = 50
        const val MAX_TEXT = 300

        // one line, no control characters, never longer than MAX_TEXT
        fun clean(raw: String): String =
            raw.replace(Regex("[\\r\\n\\t]+"), " ").filter { !it.isISOControl() }.trim().take(MAX_TEXT)
    }
}

// send interval with back-off after failures. plain kotlin, unit tested.
class ShipPace(private val everyMs: Long = EVERY_MS, private val maxMs: Long = MAX_BACKOFF_MS) {
    private var failures = 0

    fun ok() {
        failures = 0
    }

    fun failed() {
        failures++
    }

    // how long to wait before the next try
    fun waitMs(): Long = if (failures == 0) everyMs else minOf(maxMs, everyMs * (1L shl minOf(failures, 4)))

    companion object {
        const val EVERY_MS = 5_000L
        const val MAX_BACKOFF_MS = 60_000L
    }
}

// the app's log: android logcat, the staff menu and the server
object RobotLog {

    private const val TAG = "Nova"

    private val lines = LogQueue(shipFrom = if (BuildConfig.LOG_DEBUG) LogLevel.DEBUG else LogLevel.INFO)
    private val _recent = MutableStateFlow<List<LogLine>>(emptyList())
    val recent: StateFlow<List<LogLine>> = _recent.asStateFlow()
    private val pace = ShipPace()

    fun d(source: LogSource, text: String) = add(LogLevel.DEBUG, source, text)
    fun i(source: LogSource, text: String) = add(LogLevel.INFO, source, text)
    fun w(source: LogSource, text: String) = add(LogLevel.WARN, source, text)
    fun e(source: LogSource, text: String) = add(LogLevel.ERROR, source, text)

    fun add(level: LogLevel, source: LogSource, text: String) {
        val line = lines.add(System.currentTimeMillis(), level, source, text) ?: return
        val tag = "$TAG/${source.wire}"
        when (level) {
            LogLevel.DEBUG -> Log.d(tag, line.text)
            LogLevel.INFO -> Log.i(tag, line.text)
            LogLevel.WARN -> Log.w(tag, line.text)
            LogLevel.ERROR -> Log.e(tag, line.text)
        }
        _recent.value = lines.recent()
    }

    fun pending(): Int = lines.pending()

    // runs with the brain: one batch every 5 s, slower while the server does not answer
    suspend fun ship() {
        while (true) {
            delay(pace.waitMs())
            if (!Backend.configured || lines.pending() == 0) continue
            val batch = lines.drain(now = System.currentTimeMillis())
            if (batch.isEmpty()) continue
            val body = Json.logs(Backend.robot, batch)
            val ok = withContext(Dispatchers.IO) {
                runCatching { Backend.logs(body) }.isSuccess
            }
            if (ok) {
                pace.ok()
            } else {
                // not logged again here: that would only fill the queue with its own failures
                lines.putBack(batch)
                pace.failed()
            }
        }
    }
}
