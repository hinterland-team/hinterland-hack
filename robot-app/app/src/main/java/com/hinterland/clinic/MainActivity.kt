package com.hinterland.clinic

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ainirobot.agent.OnAgentStatusChangedListener
import com.ainirobot.agent.OnTranscribeListener
import com.ainirobot.agent.PageAgent
import com.ainirobot.agent.base.Transcription
import com.hinterland.clinic.ui.ClinicScreen
import com.hinterland.clinic.ui.NovaTheme
import com.hinterland.clinic.ui.ScreenActions
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // page actions are live while this screen is visible. listeners return false
        // so the system subtitles and voice bar keep working.
        PageAgent(this)
            .registerActions(ClinicActions.all())
            .setOnTranscribeListener(object : OnTranscribeListener {
                override fun onASRResult(transcription: Transcription): Boolean {
                    Clinic.onUserSpeech(transcription.text, transcription.final)
                    return false
                }

                override fun onTTSResult(transcription: Transcription): Boolean {
                    Clinic.onRobotSpeech(transcription.text, transcription.final)
                    return false
                }
            })
            .setOnAgentStatusChangedListener(object : OnAgentStatusChangedListener {
                override fun onStatusChanged(status: String, message: String?): Boolean {
                    Clinic.onAgentStatus(status, message)
                    return false
                }
            })

        Robot.onHealth = Clinic::onRobotHealth

        // the screen's buttons: "That's me", pace, "I'm here", feedback and the self check-in steps
        val actions = ScreenActions(
            thatsMe = Clinic::tapThatsMe,
            pace = Clinic::tapPace,
            imHere = Clinic::tapImHere,
            goOn = Clinic::tapGoOn,
            feedback = Clinic::tapFeedback,
            checkIn = Clinic::tapCheckIn,
            room = Clinic::tapRoom,
            checkInPace = Clinic::tapCheckInPace,
            checkInBack = Clinic::tapCheckInBack,
            checkInClose = Clinic::tapCheckInClose,
            checkInConfirm = Clinic::tapCheckInConfirm,
        )

        setContent {
            NovaTheme {
                val state by Clinic.ui.collectAsState()
                ClinicScreen(state, actions)
            }
        }

        // nova's brain runs while this screen is in front: it greets, takes the team's guide commands,
        // charges and goes home on its own, and sends the heartbeat and the live feed
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                Robot.connect(this@MainActivity)
                Clinic.refreshScreenInfo()
                NovaBrain.run()
            }
        }
    }
}
