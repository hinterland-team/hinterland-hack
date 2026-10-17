package com.novaremote.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import java.util.Collections

// holds everything while Nova Remote runs: the lan web server, the websocket stream, the pairing
// lock, the robot driving link and (once the dev allows it) the screen capture. a foreground
// service so Android keeps it alive while the dev works from the laptop.
class RemoteService : Service() {

    lateinit var pairing: Pairing
        private set

    var robotIp: String? = null
        private set

    // real screen size in px - remote taps are mapped onto this
    var screenW = 0
        private set
    var screenH = 0
        private set
    private var densityDpi = DisplayMetrics.DENSITY_DEFAULT

    private var web: WebServer? = null
    private var ws: WsServer? = null
    private var capture: ScreenCapture? = null

    // authenticated live sockets that receive screen frames
    private val sockets = Collections.synchronizedSet(mutableSetOf<RemoteSocket>())

    val wsPort: Int get() = BuildConfig.PORT + 1
    val capturing: Boolean get() = capture != null

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForegroundNotice()

        pairing = Pairing(Net.parseMacAllow(BuildConfig.MAC_ALLOW))
        robotIp = Net.wifiIpv4()
        readScreen()

        Driving.connect(this)

        runCatching {
            web = WebServer(this, BuildConfig.PORT).also { it.start() }
            ws = WsServer(this, wsPort).also { it.start() }
            Log.i(TAG, "serving on http://$robotIp:${BuildConfig.PORT} (code ${pairing.code})")
        }.onFailure { Log.e(TAG, "server start failed", it) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_CAPTURE -> startCapture(intent)
            ACTION_STOP_CAPTURE -> stopCapture()
        }
        return START_STICKY
    }

    private fun readScreen() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(m)
        screenW = m.widthPixels
        screenH = m.heightPixels
        densityDpi = m.densityDpi
    }

    private fun startCapture(intent: Intent) {
        if (capture != null) return
        val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)
        data ?: return
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection: MediaProjection = mpm.getMediaProjection(code, data) ?: return
        capture = ScreenCapture(projection, screenW, screenH, densityDpi) { frame ->
            synchronized(sockets) { sockets.toList() }.forEach { it.sendFrame(frame) }
        }.also { it.start() }
        Log.i(TAG, "screen capture started")
    }

    fun stopCapture() {
        capture?.stop()
        capture = null
    }

    fun addSocket(s: RemoteSocket) { sockets.add(s) }
    fun removeSocket(s: RemoteSocket) { sockets.remove(s) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopCapture()
        runCatching { web?.stop() }
        runCatching { ws?.stop() }
        Driving.stop()
        pairing.revokeAll()
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun startForegroundNotice() {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL, "Nova Remote", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n: Notification = Notification.Builder(this)
            .apply { if (Build.VERSION.SDK_INT >= 26) setChannelId(CHANNEL) }
            .setContentTitle("Nova Remote is on")
            .setContentText("Reachable on this robot's Wi-Fi with the 9-digit code")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .build()
        startForeground(1, n)
    }

    companion object {
        private const val TAG = "NovaRemoteService"
        private const val CHANNEL = "nova-remote"

        const val ACTION_START_CAPTURE = "com.novaremote.agent.START_CAPTURE"
        const val ACTION_STOP_CAPTURE = "com.novaremote.agent.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"

        @Volatile
        var instance: RemoteService? = null
            private set
    }
}
