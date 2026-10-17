package com.hinterland.clinic

import com.ainirobot.agent.AgentCore
import com.ainirobot.agent.PageAgent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// the mode otto is in (RobotModes.kt): set from the staff menu, by the command set_mode or by settings.mode.
// a change during a walk waits until the walk is over (the patient must not lose otto's voice half way)
object Modes {

    private val _flow = MutableStateFlow(RobotMode.NORMAL)
    val flow: StateFlow<RobotMode> = _flow.asStateFlow()
    val current: RobotMode get() = _flow.value

    // asked for during a walk
    @Volatile var pending: RobotMode? = null
        private set
    @Volatile private var pendingSource = ""
    // the last mode the dashboard's settings said: settings.mode only counts when it changes, so a mode set in
    // the staff menu stays until the dashboard really changes it (or the app starts again)
    @Volatile private var lastFromConfig: RobotMode? = null
    @Volatile var since = 0L
        private set
    @Volatile var source = "start"
        private set

    // staff menu, set_mode, settings
    fun request(mode: RobotMode, source: String): CommandResult {
        if (Guide.current != null && mode != current) {
            pending = mode
            pendingSource = source
            RobotLog.i(LogSource.APP, "${mode.label} mode asked for ($source): it starts when this walk is over")
            return CommandResult(true, "${mode.label} mode after the walk that is under way")
        }
        pending = null
        if (mode == current) return CommandResult(true, "Otto is in ${mode.label} mode")
        apply(mode, source)
        return CommandResult(true, "Otto is in ${mode.label} mode now")
    }

    fun fromConfig(mode: RobotMode?) {
        if (mode == null || mode == lastFromConfig) return
        lastFromConfig = mode
        request(mode, "dashboard settings")
    }

    // a walk ended: a mode asked for meanwhile starts now
    fun guideEnded() {
        val p = pending ?: return
        pending = null
        apply(p, "$pendingSource, after the walk")
    }

    private fun apply(mode: RobotMode, source: String) {
        val old = current
        if (old == mode) return
        _flow.value = mode
        since = System.currentTimeMillis()
        this.source = source
        RobotLog.i(LogSource.APP, "Mode: ${mode.wire} (was ${old.wire}, from $source). ${ModeRules.describe(mode)}")
        Speech.setSilent(mode.silent, "${mode.label} mode, Otto is silent")
        // calm: the robot system's own push warning off too, back on after
        Robot.pushWarning(mode != RobotMode.CALM)
        Clinic.modeChanged(old, mode)
        OttoBrain.modeChanged(old, mode)
        Screen.apply()
    }
}

// what only works while otto's screen is in front: the mic, the system voice bar and the page's speaking style.
// AgentOS opens the mic by itself when the app comes to the front (SDK 4.1), so show and calm close it again
object Screen {

    @Volatile var resumed = false
        private set
    @Volatile private var page: PageAgent? = null
    @Volatile private var micLogged: Boolean? = null

    fun attach(p: PageAgent) {
        page = p
        runCatching { p.setStyle(Voice.style(Config.current.styleNote)) }
    }

    fun resumed() {
        resumed = true
        apply()
    }

    // other apps get the voice bar back
    fun stopped() {
        resumed = false
        voiceBar(true)
    }

    // the mode and the settings: voice bar hidden (voice.hideVoiceBar, always in show / calm), mic off in show / calm.
    // normal mode only opens the mic again when show / calm closed it (it never touches it otherwise)
    fun apply() {
        if (!resumed) return
        val silent = Modes.current.silent
        voiceBar(!(silent || Config.current.hideVoiceBar))
        if (silent || mutedByUs) mic(muted = silent)
    }

    // every second from the brain
    fun enforce() {
        if (resumed && Modes.current.silent) mic(muted = true)
    }

    fun settingsChanged(old: Settings, new: Settings) {
        if (old.styleNote != new.styleNote) style(new.styleNote)
        apply()
    }

    private fun voiceBar(on: Boolean) {
        runCatching { AgentCore.isEnableVoiceBar = on }
            .onFailure { RobotLog.w(LogSource.VOICE, "Voice bar switch failed: ${it.message}") }
    }

    @Volatile private var mutedByUs = false

    private fun mic(muted: Boolean) {
        runCatching {
            if (AgentCore.isMicrophoneMuted != muted) AgentCore.isMicrophoneMuted = muted
            mutedByUs = muted
            if (micLogged != muted) {
                micLogged = muted
                RobotLog.i(LogSource.VOICE, if (muted) "Microphone off (${Modes.current.label} mode)" else "Microphone on again")
            }
        }.onFailure { RobotLog.w(LogSource.VOICE, "Microphone switch failed: ${it.message}") }
    }

    // AgentOS reads the page's style when the page begins (each time the screen comes to the front). a new note
    // is pushed at once when otto is free, else it comes with the next start of the screen. our own llm lines
    // use it at once (Voice.style)
    private fun style(note: String) {
        val p = page ?: return
        runCatching { p.setStyle(Voice.style(note)) }
        if (resumed && Guide.current == null && !OttoBrain.aiBusy()) {
            runCatching { p.begin() }
                .onSuccess { RobotLog.i(LogSource.VOICE, "Speaking style note given to Otto's agent") }
                .onFailure { RobotLog.w(LogSource.VOICE, "Speaking style note waits for the next screen start: ${it.message}") }
        } else {
            RobotLog.i(LogSource.VOICE, "Speaking style note: Otto's own lines use it now, his agent from the next screen start")
        }
    }
}
