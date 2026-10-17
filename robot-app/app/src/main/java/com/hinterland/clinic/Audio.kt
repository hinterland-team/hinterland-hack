package com.hinterland.clinic

import android.content.Context
import android.media.AudioManager

// 0-100 % -> a step of the stream (the music stream has max steps, often 15). plain kotlin, unit tested
object VolumeMath {
    fun index(percent: Int, maxIndex: Int): Int {
        if (maxIndex <= 0) return 0
        return Math.round(percent.coerceIn(0, 100) / 100.0 * maxIndex).toInt().coerceIn(0, maxIndex)
    }
}

// voice.volume (a live setting): the Android music stream. null = leave the robot's own volume alone
object Audio {

    @Volatile private var am: AudioManager? = null

    fun init(context: Context) {
        am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    fun apply(percent: Int?) {
        val p = percent ?: return
        val a = am ?: return
        runCatching {
            val max = a.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val before = a.getStreamVolume(AudioManager.STREAM_MUSIC)
            val step = VolumeMath.index(p, max)
            if (step != before) a.setStreamVolume(AudioManager.STREAM_MUSIC, step, 0)
            RobotLog.i(LogSource.VOICE, "Volume $p % (music stream step $step of $max, was $before)")
        }.onFailure { RobotLog.w(LogSource.VOICE, "Volume not set: ${it.javaClass.simpleName} ${it.message.orEmpty().take(80)}") }
    }

    // for the staff menu: "step 9 of 15", null when unknown
    fun describe(): String? {
        val a = am ?: return null
        return runCatching { "${a.getStreamVolume(AudioManager.STREAM_MUSIC)} of ${a.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}" }.getOrNull()
    }
}
