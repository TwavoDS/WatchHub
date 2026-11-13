package com.twavods.watchhub

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** "Zoek mijn telefoon": rings loudly (as an alarm, so also on silent) until stopped. */
object FindPhone {

    @Volatile var ringing = false; private set

    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var audio: AudioManager? = null
    private var oldVolume = -1
    private val main = Handler(Looper.getMainLooper())
    private val autoStop = Runnable { stop() }

    fun start(ctx: Context) {
        if (ringing) return
        ringing = true
        val app = ctx.applicationContext
        val am = app.getSystemService(AudioManager::class.java)
        audio = am
        oldVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
        am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)

        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        ringtone = RingtoneManager.getRingtone(app, uri)?.apply {
            audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            if (Build.VERSION.SDK_INT >= 28) isLooping = true
            play()
        }

        vibrator = if (Build.VERSION.SDK_INT >= 31)
            app.getSystemService(VibratorManager::class.java).defaultVibrator
        else @Suppress("DEPRECATION") app.getSystemService(Vibrator::class.java)
        vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 400), 0))

        // Never ring forever
        main.removeCallbacks(autoStop)
        main.postDelayed(autoStop, 60_000)
    }

    fun stop() {
        if (!ringing) return
        ringing = false
        main.removeCallbacks(autoStop)
        try { ringtone?.stop() } catch (_: Exception) {}
        try { vibrator?.cancel() } catch (_: Exception) {}
        if (oldVolume >= 0) try { audio?.setStreamVolume(AudioManager.STREAM_ALARM, oldVolume, 0) } catch (_: Exception) {}
        ringtone = null
        vibrator = null
        oldVolume = -1
    }
}
