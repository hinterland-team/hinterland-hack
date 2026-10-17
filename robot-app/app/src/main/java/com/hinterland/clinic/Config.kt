package com.hinterland.clinic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

// live settings (docs/robot-setup.md 3): GET /api/robot/config on start, every 60 s and on the command
// config_changed. applied at once without a restart; what a walk uses is read when the walk starts, the rest
// (speech, the live map, logs, volume, the screen) right away. the parsing and the ranges are in RobotSettings.kt
object Config {

    private const val EVERY_MS = 60_000L
    // the same problem goes to the log again after this long
    private const val PROBLEM_LOG_MS = 10 * 60_000L

    // the built-in defaults: the local.properties value (BuildConfig) where there is one, else the code's own
    val builtIn: Settings = Settings(
        walkSide = WalkSide.of(BuildConfig.WALK_SIDE),
        callLanguages = Lang.tagList(BuildConfig.CALL_LANGUAGES),
        debugLogs = BuildConfig.LOG_DEBUG,
    )

    private val _flow = MutableStateFlow(builtIn)
    val flow: StateFlow<Settings> = _flow.asStateFlow()
    val current: Settings get() = _flow.value

    // null = only the built-in settings so far
    @Volatile var version: Int? = null
        private set
    @Volatile var appliedAt = 0L
        private set
    @Volatile var warnings = 0
        private set
    // why the last fetch did not work, for the staff menu (null = it worked)
    @Volatile var problem: String? = null
        private set
    @Volatile private var problemLoggedAt = 0L
    @Volatile private var fromServer = false

    private val wanted = Channel<String>(Channel.CONFLATED)

    private fun now() = System.currentTimeMillis()

    // config_changed, recheck, the staff menu
    fun requestNow(why: String) {
        wanted.trySend(why)
    }

    // runs with the brain
    suspend fun loop() {
        RobotLog.shipDebug(builtIn.debugLogs)
        var why = "start"
        while (true) {
            runCatching { fetch(why) }.onFailure { RobotLog.w(LogSource.APP, "Settings: ${it.javaClass.simpleName} ${it.message.orEmpty().take(80)}") }
            why = withTimeoutOrNull(EVERY_MS) { wanted.receive() } ?: "every 60 s"
        }
    }

    suspend fun fetch(why: String) {
        if (!Backend.configured) return
        val r = withContext(Dispatchers.IO) { runCatching { Backend.config() } }
        val body = r.getOrElse { e ->
            val code = (e as? BackendException)?.code
            note(
                if (code == 404) "this server has no /api/robot/config yet, Otto uses his built-in settings"
                else "settings not fetched: ${Backend.friendly(e)}",
            )
            return
        }
        val read = SettingsSpec.parse(body, builtIn)
        if (read == null) {
            note("the settings answer is not json")
            return
        }
        problem = null
        apply(read, why)
    }

    private fun note(msg: String) {
        val now = now()
        if (msg != problem || now - problemLoggedAt >= PROBLEM_LOG_MS) {
            problemLoggedAt = now
            RobotLog.w(LogSource.APP, "Settings: $msg")
        }
        problem = msg
    }

    // the same version with the same values: nothing to do (the 60 s fetch)
    fun apply(read: ConfigRead, why: String) {
        val old = current
        val changes = SettingsSpec.changes(old, read.settings)
        val first = !fromServer
        if (!first && read.version == version && changes.isEmpty()) return
        fromServer = true
        _flow.value = read.settings
        version = read.version
        appliedAt = now()
        warnings = read.warnings.size
        val v = vText(read.version)
        read.warnings.forEach { RobotLog.w(LogSource.APP, "Settings $v: $it") }
        RobotLog.i(
            LogSource.APP,
            "Settings $v applied ($why): ${read.fromServer} from the dashboard, the rest built in" +
                if (changes.isEmpty()) "" else "; changed: " + changes.take(8).joinToString() + if (changes.size > 8) " and ${changes.size - 8} more" else "",
        )
        effects(old, read.settings, first)
    }

    // what does not wait for the next walk
    private fun effects(old: Settings, new: Settings, first: Boolean) {
        RobotLog.shipDebug(new.debugLogs)
        if (first || old.volume != new.volume) Audio.apply(new.volume)
        if (old.openMicOnWalks != new.openMicOnWalks) Clinic.walkMicChanged()
        if (first || old.hideVoiceBar != new.hideVoiceBar || old.styleNote != new.styleNote) Screen.settingsChanged(old, new)
        Modes.fromConfig(new.mode)
    }

    fun vText(v: Int? = version): String = v?.let { "v$it" } ?: "(no version)"

    // the staff menu line
    fun summary(): String = when {
        appliedAt == 0L -> "Built-in settings" + (problem?.let { ": $it" } ?: ", nothing from the dashboard yet")
        else -> "Settings ${vText()}, applied ${Clock.hhmm(appliedAt.toString()) ?: "-"}" +
            (if (warnings > 0) ", $warnings warning${if (warnings == 1) "" else "s"}" else "") +
            (problem?.let { " (last fetch: $it)" } ?: "")
    }

    val ok: Boolean get() = appliedAt != 0L && warnings == 0 && problem == null
}
