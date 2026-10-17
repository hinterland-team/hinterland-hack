package com.novaremote.agent

import android.content.Context
import android.util.Log
import com.ainirobot.coreservice.client.ApiListener
import com.ainirobot.coreservice.client.RobotApi
import com.ainirobot.coreservice.client.listener.CommandListener
import java.util.concurrent.atomic.AtomicInteger

// arrow-key driving. maps key presses from the laptop onto RobotApi chassis motion. this only
// works while Nova Remote is the app in front and was started from the robot home screen,
// otherwise OrionStar does not hand the chassis to the app (same rule as the clinic app).
//
// safety: every move is a short pulse that the watchdog cancels if no new key arrives within
// HOLD_MS, so the robot stops the moment you let go of the key or the laptop drops off wifi.
object Driving {

    private const val TAG = "NovaRemoteDrive"
    private const val HOLD_MS = 500L

    // doc ranges: lineSpeed -1.2..1.2 m/s, angularSpeed -2..2 rad/s. kept gentle for indoor demo.
    const val LINEAR = 0.35
    const val ANGULAR = 0.7

    private val api get() = RobotApi.getInstance()
    private val reqIds = AtomicInteger(1)

    @Volatile var connected = false
        private set

    @Volatile private var lastCmdAt = 0L
    @Volatile private var moving = false
    @Volatile private var watchdog: Thread? = null

    private val apiListener = object : ApiListener {
        override fun handleApiConnected() {
            connected = true
            Log.i(TAG, "RobotApi connected")
        }

        override fun handleApiDisabled() {
            connected = false
            Log.w(TAG, "RobotApi disabled")
        }

        override fun handleApiDisconnected() {
            connected = false
            Log.w(TAG, "RobotApi disconnected")
        }
    }

    fun connect(context: Context) {
        runCatching { api.connectServer(context.applicationContext, apiListener) }
            .onFailure { Log.w(TAG, "connectServer failed", it) }
        startWatchdog()
    }

    // dir: one of forward / back / left / right. line/ang default to the gentle demo speeds.
    fun drive(dir: String, line: Double = LINEAR, ang: Double = ANGULAR) {
        if (!connected) return
        val (l, a) = when (dir) {
            "forward" -> line to 0.0
            "back" -> -line to 0.0
            "left" -> 0.0 to ang
            "right" -> 0.0 to -ang
            else -> return
        }
        lastCmdAt = System.currentTimeMillis()
        moving = true
        runCatching { api.motionArcWithObstacles(reqIds.getAndIncrement(), l.toFloat(), a.toFloat(), silent()) }
            .onFailure { Log.w(TAG, "motionArc failed", it) }
    }

    fun stop() {
        moving = false
        if (!connected) return
        runCatching { api.stopMove(reqIds.getAndIncrement(), silent()) }
            .onFailure { Log.w(TAG, "stopMove failed", it) }
    }

    // we drive in short pulses and do not act on the per-command result, so an empty listener
    private fun silent() = object : CommandListener() {
        override fun onResult(result: Int, message: String?) {}
    }

    // keeps the robot from running on if the browser stops sending (closed tab, dropped wifi)
    private fun startWatchdog() {
        if (watchdog != null) return
        watchdog = Thread {
            while (true) {
                try {
                    Thread.sleep(100)
                } catch (e: InterruptedException) {
                    return@Thread
                }
                if (moving && System.currentTimeMillis() - lastCmdAt > HOLD_MS) stop()
            }
        }.apply { isDaemon = true; start() }
    }
}
