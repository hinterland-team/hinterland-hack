package com.hinterland.clinic

import java.util.Locale
import java.util.concurrent.locks.ReentrantLock

// the radar while nova guides someone (docs/journeys.md 6.3): turns the radar data on, finds out the
// frame and which angles the scan covers (logged on the first data), and tracks the follower behind.
// the maths is in Lidar.kt (unit tested); this object only wires it to RobotApi data.
object Radar {

    // the robot may send many frames a second; a few are plenty
    private const val MIN_FRAME_MS = 150L
    // frames summed before nova decides whether she sees her back
    private const val COVERAGE_FRAMES = 15
    private const val FRESH_MS = 1_000L
    private const val MAP_SCAN_FRESH_MS = 2_000L
    // in front after the turn: people up to this far count
    private const val AHEAD_MAX_M = 6.0
    private const val STATUS_EVERY_MS = 5_000L
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

    @Volatile var active = false
        private set
    @Volatile private var lastFrameAt = 0L
    @Volatile private var frames = 0
    @Volatile private var loggedFirst = false
    @Volatile private var loggedBad = false
    @Volatile private var robotScan: Pair<Long, List<Point>>? = null
    @Volatile private var mapScan: Pair<Long, List<Point>>? = null
    @Volatile private var statusAt = 0L
    @Volatile private var startedAt = 0L
    @Volatile private var warnedSilent = false

    // decided after COVERAGE_FRAMES frames; until then the walk uses the schedule only
    @Volatile var rearVisible = false
        private set
    @Volatile var follower: FollowerFix? = null
        private set
    // for the staff menu
    @Volatile var summary: String = "Off. Radar runs while Nova guides someone."
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
            mapScan = null
        } finally {
            lock.unlock()
        }
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
        val seen = if (tracker.everSeen) "follower seen" else "no follower seen"
        RobotLog.i(LogSource.LIDAR, "Radar data off after $frames frames, $seen, back ${if (rearVisible) "visible" else "not visible"}")
        summary = "Off. Last walk: $frames frames, back ${if (rearVisible) "visible" else "not visible"}, $seen"
    }

    // binder thread
    private fun onData(data: String?) {
        val now = System.currentTimeMillis()
        if (!active || now - lastFrameAt < MIN_FRAME_MS) return
        if (!lock.tryLock()) return
        try {
            lastFrameAt = now
            process(now, data)
        } catch (e: Exception) {
            if (!loggedBad) {
                loggedBad = true
                RobotLog.w(LogSource.LIDAR, "Radar frame failed: ${e.javaClass.simpleName} ${e.message.orEmpty().take(80)}")
            }
        } finally {
            lock.unlock()
        }
    }

    private fun process(now: Long, data: String?) {
        val raw = Json.scan(data)
        if (raw == null) {
            if (!loggedBad) {
                loggedBad = true
                RobotLog.w(LogSource.LIDAR, "Radar data not understood: ${data?.take(80)}")
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
        ScanMath.mapFrame(raw, frame, pose)?.let { mapScan = now to it }
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
                    if (rearVisible) "visible, follower watch is continuous" else "not visible, check-backs on the schedule only",
            )
        } else if (frames > COVERAGE_FRAMES && !rearVisible && coverage.rearVisible()) {
            rearVisible = true
            RobotLog.i(LogSource.LIDAR, "Radar sees the back now (${coverage.describe()}), follower watch is continuous")
        }
        val had = follower != null
        follower = tracker.update(now, LegDetector.candidates(pts, Sector.REAR), pose)
        val f = follower
        // seen / not seen: every change, and a status line every few seconds while walking
        if (had != (f != null) || now - statusAt >= STATUS_EVERY_MS) {
            statusAt = now
            RobotLog.i(
                LogSource.LIDAR,
                if (f != null) "Follower seen %.1f m behind".format(Locale.ROOT, f.distance)
                else "No follower behind" + (tracker.lastSeenAt?.let { " for ${(now - it) / 1000} s" } ?: if (rearVisible) "" else " (back not visible)"),
            )
        }
        summary = "On: ${coverage.describe()}, back ${if (rearVisible) "visible" else "not visible"}" +
            (follower?.let { ", follower %.1f m".format(Locale.ROOT, it.distance) } ?: ", no follower now")
    }

    // called now and then during a walk: says once when no radar data came at all
    fun checkSilent(now: Long = System.currentTimeMillis()) {
        if (!active || frames > 0 || warnedSilent || now - startedAt < SILENT_WARN_MS) return
        warnedSilent = true
        summary = "On, but no radar data arrived"
        RobotLog.w(LogSource.LIDAR, "No usable radar data ${SILENT_WARN_MS / 1000} s after switching it on: check-backs on the schedule only")
    }

    fun everSeen(): Boolean = tracker.everSeen

    fun lastSeenAt(): Long? = tracker.lastSeenAt

    // the follower's distance when seen just now
    fun followerDistance(now: Long = System.currentTimeMillis()): Double? =
        follower?.takeIf { now - it.at <= FRESH_MS }?.distance

    // nearest person-like shape in front (after nova turned around), null when none or no fresh data
    fun nearestAhead(now: Long = System.currentTimeMillis()): Double? {
        val (at, pts) = robotScan ?: return null
        if (now - at > FRESH_MS) return null
        return LegDetector.nearest(pts, Sector.FRONT, AHEAD_MAX_M)?.distance
    }

    // the latest scan in the map frame for the live map
    fun mapScan(now: Long = System.currentTimeMillis()): List<Point>? =
        mapScan?.takeIf { now - it.first <= MAP_SCAN_FRESH_MS }?.second
}
