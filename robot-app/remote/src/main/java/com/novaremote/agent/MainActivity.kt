package com.novaremote.agent

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

// the screen the dev sees on the robot. shows the address + 9-digit code to type on the laptop,
// and the switches for the three things Android must allow: screen view, remote taps, apk installs.
class MainActivity : AppCompatActivity() {

    private lateinit var address: TextView
    private lateinit var code: TextView
    private lateinit var status: TextView
    private lateinit var captureBtn: Button

    private val handler = Handler(Looper.getMainLooper())

    // the system screen-capture consent dialog
    private val projectionRequest =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                val intent = Intent(this, RemoteService::class.java).apply {
                    action = RemoteService.ACTION_START_CAPTURE
                    putExtra(RemoteService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(RemoteService.EXTRA_RESULT_DATA, data)
                }
                startService(intent)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildUi())
        startRemoteService()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    private fun startRemoteService() {
        val intent = Intent(this, RemoteService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
    }

    private fun refresh() {
        val svc = RemoteService.instance
        val ip = svc?.robotIp
        address.text = if (ip != null) "http://$ip:${BuildConfig.PORT}" else "not on Wi-Fi"
        code.text = svc?.pairing?.code?.let { "$it".chunked(3).joinToString(" ") } ?: "…"

        val capturing = svc?.capturing == true
        captureBtn.text = if (capturing) "Stop screen view" else "Start screen view"

        status.text = listOf(
            line("Screen view", capturing),
            line("Remote taps (Accessibility)", RemoteAccessibilityService.enabled),
            line("Driving (robot API)", Driving.connected),
        ).joinToString("\n")
    }

    private fun line(label: String, on: Boolean) = (if (on) "✓  " else "•  ") + label + (if (on) "" else "  — off")

    private fun toggleCapture() {
        val svc = RemoteService.instance
        if (svc?.capturing == true) {
            startService(Intent(this, RemoteService::class.java).apply { action = RemoteService.ACTION_STOP_CAPTURE })
        } else {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projectionRequest.launch(mpm.createScreenCaptureIntent())
        }
    }

    private fun openAccessibility() =
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    private fun openInstallSources() {
        if (Build.VERSION.SDK_INT >= 26) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
            )
        }
    }

    // ---- ui, built in code so the module needs no layout xml ----

    private fun buildUi(): View {
        val pad = dp(24)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B1F3A"))
            setPadding(pad, pad, pad, pad)
        }

        root.addView(title("Nova Remote"))
        root.addView(sub("Open this on your laptop (same Wi-Fi) and type the code."))

        address = big("")
        root.addView(address)
        root.addView(label("Pairing code"))
        code = big("")
        root.addView(code)

        status = TextView(this).apply {
            setTextColor(Color.parseColor("#CFE0F5"))
            textSize = 16f
            setPadding(0, dp(20), 0, dp(20))
        }
        root.addView(status)

        captureBtn = button("Start screen view") { toggleCapture() }
        root.addView(captureBtn)
        root.addView(button("Enable remote taps") { openAccessibility() })
        root.addView(button("Allow app installs") { openInstallSources() })

        return ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0B1F3A"))
            addView(root)
        }
    }

    private fun title(t: String) = TextView(this).apply {
        text = t; setTextColor(Color.WHITE); textSize = 30f
        setTypeface(typeface, Typeface.BOLD)
    }

    private fun sub(t: String) = TextView(this).apply {
        text = t; setTextColor(Color.parseColor("#9DB6D6")); textSize = 15f
        setPadding(0, dp(6), 0, dp(18))
    }

    private fun label(t: String) = TextView(this).apply {
        text = t; setTextColor(Color.parseColor("#9DB6D6")); textSize = 13f
        setPadding(0, dp(14), 0, dp(2))
    }

    private fun big(t: String) = TextView(this).apply {
        text = t; setTextColor(Color.parseColor("#7FD4FF")); textSize = 28f
        setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
    }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
