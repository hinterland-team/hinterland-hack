package com.hinterland.clinic

import android.os.Process
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

// the radar while otto guides someone (docs/journeys.md 6.3, 6.7): turns the radar data on, finds out the
// frame and which angles the scan covers (logged on the first data), and tracks the patient walking beside
// otto (the side sectors; on our robot the back is blind). the maths is in Lidar.kt (unit tested); this
// object only wires it to RobotApi data.
// v3.3: the binder thread only hands the frame over. parsing and the leg search run on one background
// thread at most 5 times a second, always on the newest frame (older ones are dropped).
object Radar {

    // at most 5 frames a second: plenty for a person walking beside otto
    private const val MIN_FRAME_MS = 200L
    // frames summed before otto decides which angles the radar sees
    private const val COVERAGE_FRAMES = 15
    private const val FRESH_MS = 1_000L
    // a side fix this fresh means "beside otto right now"
    private const val BESIDE_FRESH_MS = 1_000L
    private const val MAP_SCAN_FRESH_MS = 2_000L
    // in front after the turn: people up to this far count
    private const val AHEAD_MAX_M = 6.0
    private const val STATUS_EVERY_MS = 10_000L
    // the lock's line while it sees the patient, at most this often
    private const val LOCK_LOG_MS = 2_000L
    // nobody beside for this long is worth a log line (shorter gaps are the normal flicker)
    private const val UNSEEN_LOG_MS = 2_000L
    private const val SUMMARY_EVERY_MS = 1_000L
    private const val SILENT_WARN_MS = 5_000L

    private val vote = FrameVote(
        fixed = when (BuildConfig.SCAN_FRAME.trim().lowercase(Locale.ROOT)) {
            "map" -> ScanFrame.MAP
            "robot" -> ScanFrame.ROBOT
            else -> null
        },
    )
    private val lock = ReentrantLock()
    private var coverage = Coverage()
    private val tracker = FollowerTracker()

    // one background thread for the radar maths, low priority so the screen always comes first
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread({
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
            r.run()
        }, "otto-radar").apply { isDaemon = true }
    }
    private val pending = AtomicReference<String?>(null)
    private val queued = AtomicBoolean(false)

    @Volatile var active = false
        private set
    @Volatile private var acceptedAt = 0L
    @Volatile private var frames = 0
    @Volatile private var loggedFirst = false
    @Volatile private var loggedBad = false
    @Volatile private var robotScan: Pair<Long, List<Point>>? = null
    // the newest frame as it came, its frame and the pose: the map frame is only made when asked (live map)
    @Volatile private var lastRaw: RawScan? = null
    @Volatile private var statusAt = 0L
    @Volatile private var summaryAt = 0L
    @Volatile private var startedAt = 0L
    @Volatile private var warnedSilent = false
    @Volatile private var unseenLogged = false
    @Volatile private var seenOnce = false

    private class RawScan(val at: Long, val points: List<Point>, val frame: ScanFrame, val pose: RobotPose?)

    // decided after COVERAGE_FRAMES frames (for the log and the staff menu)
    @Volatile var rearVisible = false
        private set
    @Volatile var follower: FollowerFix? = null
        private set
    // for the staff menu
    @Volatile var summary: String = "Off. Radar runs while Otto guides someone."
        private set

    fun start() {
        if (active) return
        lock.lock()
        try {
            coverage = Coverage()
            tracker.reset()
            frames = 0
            loggedFirst = false
            rearVisible = false
            follower = null
            robotScan = null
            lastRaw = null
            unseenLogged = false
            seenOnce = false
        } finally {
            lock.unlock()
        }
        pending.set(null)
        startedAt = System.currentTimeMillis()
        warnedSilent = false
        active = Robot.startRadar(::onData)
        summary = if (active) "On, waiting for the first data" else "Could not switch the radar data on"
        RobotLog.i(LogSource.LIDAR, if (active) "Radar data on" else "Radar data could not be switched on")
    }

    fun stop() {
        if (!active) return
        Robot.stopRadar()
        active = false
        pending.set(null)
        val seen = if (tracker.everSeen) "patient seen beside" else "nobody seen beside"
        RobotLog.i(LogSource.LIDAR, "Radar data off after $frames frames, $seen, back ${if (rearVisible) "visible" else "not visible"}")
        summary = "Off. Last walk: $frames frames, back ${if (rearVisible) "visible" else "not visible"}, $seen"
    }

    // binder thread: hand the newest frame over and return at once
    private fun onData(data: String?) {
        val now = System.currentTimeMillis()
        if (!active || data == null || now - acceptedAt < MIN_FRAME_MS) return
        acceptedAt = now
        pending.set(data)
        if (queued.compareAndSet(false, true)) {
            runCatching { worker.execute(::drain) }.onFailure { queued.set(false) }
        }
    }

    // the radar thread
    private fun drain() {
        queued.set(false)
        val data = pending.getAndSet(null) ?: return
        val t0 = System.nanoTime()
        lock.lock()
        try {
            if (active) process(System.currentTimeMillis(), data)
        } catch (e: Exception) {
            if (!loggedBad) {
                loggedBad = true
                RobotLog.w(LogSource.LIDAR, "Radar frame failed: ${e.javaClass.simpleName} ${e.message.orEmpty().take(80)}")
            }
        } finally {
            lock.unlock()
        }
        PerfMonitor.radarFrame(System.nanoTime() - t0)
    }

    private fun process(now: Long, data: String) {
        val raw = ScanJson.parse(data)
        if (raw == null) {
            if (!loggedBad) {
                loggedBad = true
                RobotLog.w(LogSource.LIDAR, "Radar data not understood: ${data.take(80)}")
            }
            return
        }
        if (raw.isEmpty()) return
        val pose = Robot.latestPose(now)
        val frame = vote.add(ScanMath.guessFrame(raw, pose)) ?: return
        val pts = ScanMath.robotFrame(raw, frame, pose) ?: return
        frames++
        coverage.add(pts)
        robotScan = now to pts
        lastRaw = RawScan(now, raw, frame, pose)
        if (!loggedFirst) {
            loggedFirst = true
            RobotLog.i(
                LogSource.LIDAR,
                "Radar first data: ${raw.size} points, ${frame.name.lowercase(Locale.ROOT)} frame" +
                    (if (vote.assumed) " (assumed)" else "") + ", covers ${coverage.describe()}",
            )
        }
        if (frames == COVERAGE_FRAMES) {
            rearVisible = coverage.rearVisible()
            RobotLog.i(
                LogSource.LIDAR,
                "Radar covers ${coverage.describe()} after $frames frames: back " +
                    if (rearVisible) "visible" else "not visible, the patient walks beside otto where the radar sees",
            )
        } else if (frames > COVERAGE_FRAMES && !rearVisible && coverage.rearVisible()) {
            rearVisible = true
            RobotLog.i(LogSource.LIDAR, "Radar sees the back now (${coverage.describe()})")
        }
        follower = tracker.update(now, LegDetector.candidates(pts, Sector.SIDES), pose)
        logFollower(now)
        if (now - summaryAt >= SUMMARY_EVERY_MS) {
            summaryAt = now
            summary = "On: ${coverage.describe()}, back ${if (rearVisible) "visible" else "not visible"}" +
                (follower?.let { ", patient beside %.1f m at %+.0f deg".format(Locale.ROOT, it.distance, Bearing.of(it.robot)) } ?: ", nobody beside now")
        }
    }

    // the ticket lock without the 1-2 s flicker: on, the patient at most every LOCK_LOG_MS (with what was
    // ignored), lost after a gap of 2 s, found again, a status now and then
    private fun logFollower(now: Long) {
        val f = follower?.takeIf { now - it.at <= BESIDE_FRESH_MS }
        val last = tracker.lastSeenAt
        val ignoredNote = "ignored ${tracker.ignoredStatic} static, ${tracker.ignoredOther} other"
        when {
            f != null && !seenOnce -> {
                seenOnce = true
                statusAt = now
                RobotLog.i(LogSource.LIDAR, "Lock: on the patient %.1f m at %+.0f deg (%s)".format(Locale.ROOT, f.distance, Bearing.of(f.robot), ignoredNote))
            }
            f != null && unseenLogged -> {
                unseenLogged = false
                statusAt = now
                RobotLog.i(LogSource.LIDAR, "Lock: patient found again %.1f m at %+.0f deg (%s)".format(Locale.ROOT, f.distance, Bearing.of(f.robot), ignoredNote))
            }
            f == null && last != null && !unseenLogged && now - last >= UNSEEN_LOG_MS -> {
                unseenLogged = true
                RobotLog.i(LogSource.LIDAR, "Lock lost: nobody at the patient's spot for ${(now - last) / 1000} s ($ignoredNote)")
            }
            f != null && now - statusAt >= LOCK_LOG_MS -> {
                statusAt = now
                RobotLog.i(LogSource.LIDAR, "Lock: patient %.1f m at %+.0f deg (%s)".format(Locale.ROOT, f.distance, Bearing.of(f.robot), ignoredNote))
            }
            f == null && now - statusAt >= STATUS_EVERY_MS -> {
                statusAt = now
                RobotLog.i(LogSource.LIDAR, (if (tracker.locked) "Lock: nobody at the patient's spot" else "Lock: not on anyone yet") + (last?.let { " for ${(now - it) / 1000} s" } ?: "") + " ($ignoredNote)")
            }
        }
    }

    // the patient's spot at the walk start (map frame): the ticket lock starts from it
    fun lockStart(map: Point?) {
        lock.lock()
        try {
            tracker.lockStart(map)
        } finally {
            lock.unlock()
        }
        if (map != null) RobotLog.i(LogSource.LIDAR, "Lock: starts from the patient's spot at the walk start")
    }

    // a check-back found the patient (camera, robot frame now): the lock is anchored there again
    fun anchor(robot: Point, map: Point?) {
        val now = System.currentTimeMillis()
        lock.lock()
        try {
            tracker.anchor(now, robot, map, Robot.latestPose(now))
            follower = tracker.current
        } finally {
            lock.unlock()
        }
        RobotLog.i(LogSource.LIDAR, "Lock: anchored on the patient %.1f m at %+.0f deg (camera)".format(Locale.ROOT, ScanMath.range(robot), Bearing.of(robot)))
    }

    // where the lock expects the patient next to otto now (robot frame), null = no idea
    fun lockPredicted(now: Long = System.currentTimeMillis()): Point? {
        lock.lock()
        try {
            return if (tracker.locked) tracker.predicted(Robot.latestPose(now)) else null
        } finally {
            lock.unlock()
        }
    }

    // called now and then during a walk: says once when no radar data came at all
    fun checkSilent(now: Long = System.currentTimeMillis()) {
        if (!active || frames > 0 || warnedSilent || now - startedAt < SILENT_WARN_MS) return
        warnedSilent = true
        summary = "On, but no radar data arrived"
        RobotLog.w(LogSource.LIDAR, "No usable radar data ${SILENT_WARN_MS / 1000} s after switching it on: check-backs on the schedule only")
    }

    // the patient was seen beside otto in this walk
    fun everSeen(): Boolean = tracker.everSeen

    fun lastSeenAt(): Long? = tracker.lastSeenAt

    // the patient beside otto right now (robot frame, map point, distance), null when not seen just now
    fun besideFix(now: Long = System.currentTimeMillis()): FollowerFix? =
        follower?.takeIf { now - it.at <= BESIDE_FRESH_MS }

    // the follower's distance when seen just now
    fun followerDistance(now: Long = System.currentTimeMillis()): Double? =
        follower?.takeIf { now - it.at <= FRESH_MS }?.distance

    // nearest person-like shape in front (after otto turned), null when none or no fresh data
    fun nearestAhead(now: Long = System.currentTimeMillis()): Double? {
        val (at, pts) = robotScan ?: return null
        if (now - at > FRESH_MS) return null
        return LegDetector.nearest(pts, Sector.FRONT, AHEAD_MAX_M)?.distance
    }

    // nearest person-like shape beside otto, either side (the demo's wait_close), null when none or no fresh data
    fun nearestBeside(now: Long = System.currentTimeMillis()): Double? {
        val (at, pts) = robotScan ?: return null
        if (now - at > FRESH_MS) return null
        return LegDetector.nearest(pts, Sector.SIDES)?.distance
    }

    // pairs of legs and wide shapes in front (robot frame), for picking the patient after the turn
    fun aheadPoints(now: Long = System.currentTimeMillis()): List<Point> {
        val (at, pts) = robotScan ?: return emptyList()
        if (now - at > FRESH_MS) return emptyList()
        return LegDetector.candidates(pts, Sector.FRONT, AHEAD_MAX_M).filter { it.kind != LegKind.LEG }.map { it.at }
    }

    // a fresh scan has come since `since` (the first look after a turn waits for one)
    fun scanSince(since: Long): Boolean = (robotScan?.first ?: 0L) >= since

    // nothing within m in the part of the back the radar sees (|angle| >= 100 deg). false without fresh data:
    // backing off a blocked way needs this (and the patient beside, not behind)
    fun clearBehind(m: Double, now: Long = System.currentTimeMillis()): Boolean {
        val (at, pts) = robotScan ?: return false
        if (now - at > FRESH_MS) return false
        return pts.none { kotlin.math.abs(ScanMath.angleDeg(it)) >= 100.0 && ScanMath.range(it) in 0.2..m }
    }

    // more free room ahead on the left than on the right (the little turn before trying a way again).
    // null without fresh data
    fun roomLeft(now: Long = System.currentTimeMillis()): Boolean? {
        val (at, pts) = robotScan ?: return null
        if (now - at > FRESH_MS) return null
        fun free(from: Double, to: Double) = pts.filter { ScanMath.angleDeg(it) in from..to && ScanMath.range(it) >= 0.2 }
            .minOfOrNull { ScanMath.range(it) } ?: Coverage.MAX_M
        return free(10.0, 60.0) >= free(-60.0, -10.0)
    }

    // the latest scan in the map frame for the live map, made only when it is asked for
    fun mapScan(now: Long = System.currentTimeMillis()): List<Point>? {
        val r = lastRaw ?: return null
        if (now - r.at > MAP_SCAN_FRESH_MS) return null
        return ScanMath.mapFrame(r.points, r.frame, r.pose)
    }
}
