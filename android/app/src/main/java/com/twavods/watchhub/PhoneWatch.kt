package com.twavods.watchhub

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat

/**
 * Keeps an eye on the phone for the watch:
 *  - phone battery low / full  → notification on the watch
 *  - watch no longer connected → alert on the phone (so you don't leave it behind)
 * Runs inside the notification service, which Android keeps alive.
 */
object PhoneWatch {
    private var started = false
    private val main = Handler(Looper.getMainLooper())
    private var lastLevel = -1
    private var lowSent = false
    private var fullSent = false
    private var wasConnected = true
    private var misses = 0

    fun start(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = onBattery(app, i)
        }, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        main.postDelayed(object : Runnable {
            override fun run() {
                checkLink(app)
                main.postDelayed(this, 60_000)
            }
        }, 60_000)
        HubLog.i("telefoon", "bewaking batterij/verbinding gestart")
    }

    private fun onBattery(ctx: Context, i: Intent) {
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val pct = level * 100 / scale
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        if (pct == lastLevel) return
        lastLevel = pct

        val low = NotifRules.batteryLow(ctx)
        if (charging) lowSent = false
        if (low > 0 && !charging && pct <= low && !lowSent) {
            lowSent = true
            WatchLink.notify("🔋 Telefoon bijna leeg", "Nog $pct% — tijd om op te laden")
            HubLog.i("telefoon", "batterij laag gemeld ($pct%)")
        }
        if (!charging) fullSent = false
        if (NotifRules.batteryFull(ctx) && charging && pct >= 100 && !fullSent) {
            fullSent = true
            WatchLink.notify("🔋 Telefoon opgeladen", "100% — je kan hem loskoppelen")
            HubLog.i("telefoon", "batterij vol gemeld")
        }
    }

    private fun checkLink(ctx: Context) {
        if (!NotifRules.lossAlert(ctx)) { wasConnected = true; misses = 0; return }
        WatchLink.checkConnected { ok ->
            if (ok) {
                if (!wasConnected) HubLog.i("telefoon", "horloge weer verbonden")
                wasConnected = true; misses = 0
                return@checkConnected
            }
            misses++
            // Two checks in a row (± 2 minutes) before alarming, so a short hiccup doesn't count
            if (misses == 2 && wasConnected) {
                wasConnected = false
                HubLog.w("telefoon", "horloge niet meer verbonden — waarschuwing gegeven")
                alert(ctx)
            }
        }
    }

    private fun alert(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("loss", "Horloge kwijt", NotificationManager.IMPORTANCE_HIGH))
        val n = NotificationCompat.Builder(ctx, "loss")
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("Horloge niet meer verbonden")
            .setContentText("Je Xiaomi Watch is buiten bereik. Heb je hem laten liggen?")
            .setAutoCancel(true)
            .build()
        try { nm.notify(4711, n) } catch (_: SecurityException) {}
        try {
            ctx.getSystemService(Vibrator::class.java)
                ?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 300, 600, 300, 600), -1))
        } catch (_: Exception) {}
    }
}
