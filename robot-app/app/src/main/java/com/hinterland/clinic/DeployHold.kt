package com.hinterland.clinic

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// tells nova deploy (the updater app on the robot) to wait while nova walks, fetches or drives
// back (BusyHold). explicit broadcast, so it reaches nova deploy on android 8+; when nova deploy
// is not installed it just goes nowhere.
object DeployHold {

    private const val ACTION = "com.novadeploy.action.HOLD"
    private const val CHECK_MS = 1_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val hold = BusyHold()
    @Volatile private var started = false

    // runs for the whole process: a walk goes on when the screen is in the background
    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        scope.launch {
            while (isActive) {
                runCatching { hold.next(NovaBrain.taskState(), System.currentTimeMillis()) }
                    .getOrNull()?.let { send(app, it) }
                delay(CHECK_MS)
            }
        }
    }

    private fun send(context: Context, m: HoldMessage) {
        val target = BuildConfig.NOVA_DEPLOY_PACKAGE.trim()
        if (target.isEmpty()) return
        runCatching {
            context.sendBroadcast(
                Intent(ACTION).setPackage(target)
                    .putExtra("package", context.packageName)
                    .putExtra("seconds", m.seconds)
                    .putExtra("reason", m.reason),
            )
        }.onFailure { Log.w("DeployHold", "hold not sent: ${it.message}") }
    }
}
