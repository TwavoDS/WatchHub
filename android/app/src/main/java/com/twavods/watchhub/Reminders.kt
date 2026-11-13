package com.twavods.watchhub

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Own reminders on the watch ("Medicijnen", "Vuilbak buiten"), at a time on chosen weekdays.
 * Delivered as a WatchHub notification with vibration.
 */
data class Reminder(
    val id: Int,
    val text: String,
    val minutes: Int,          // time of day, minutes since midnight
    val days: Int = 0x7F,      // bit 0 = Monday … bit 6 = Sunday
    val vib: String = "LL",
    val on: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("text", text).put("min", minutes)
        .put("days", days).put("vib", vib).put("on", on)

    companion object {
        fun fromJson(o: JSONObject) = Reminder(o.optInt("id"), o.optString("text"), o.optInt("min"),
            o.optInt("days", 0x7F), o.optString("vib", "LL"), o.optBoolean("on", true))
    }
}

object Reminders {
    val DAY_NAMES = listOf("ma", "di", "wo", "do", "vr", "za", "zo")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("watchhub_reminders", Context.MODE_PRIVATE)

    fun list(ctx: Context): List<Reminder> = try {
        val a = JSONArray(prefs(ctx).getString("list", "[]"))
        (0 until a.length()).map { Reminder.fromJson(a.getJSONObject(it)) }
    } catch (_: Exception) { emptyList() }

    fun save(ctx: Context, list: List<Reminder>) {
        val a = JSONArray(); list.forEach { a.put(it.toJson()) }
        prefs(ctx).edit().putString("list", a.toString()).apply()
        scheduleAll(ctx)
    }

    fun newId(ctx: Context): Int = (list(ctx).maxOfOrNull { it.id } ?: 0) + 1

    fun describe(r: Reminder): String {
        val days = when (r.days) {
            0x7F -> "elke dag"
            0x1F -> "werkdagen"
            0x60 -> "weekend"
            else -> DAY_NAMES.filterIndexed { i, _ -> r.days and (1 shl i) != 0 }.joinToString(" ")
        }
        return String.format("%02d:%02d · %s", r.minutes / 60, r.minutes % 60, days)
    }

    /** Next moment this reminder is due, or null when it has no days. */
    fun next(r: Reminder, from: Calendar = Calendar.getInstance()): Long? {
        if (r.days == 0) return null
        for (add in 0..7) {
            val c = (from.clone() as Calendar).apply {
                add(Calendar.DAY_OF_YEAR, add)
                set(Calendar.HOUR_OF_DAY, r.minutes / 60); set(Calendar.MINUTE, r.minutes % 60)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val dayIndex = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7   // Monday = 0
            if (c.timeInMillis > from.timeInMillis && r.days and (1 shl dayIndex) != 0) return c.timeInMillis
        }
        return null
    }

    private fun pending(ctx: Context, id: Int): PendingIntent =
        PendingIntent.getBroadcast(ctx, 9000 + id, Intent(ctx, ReminderReceiver::class.java).putExtra("id", id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun scheduleAll(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        // Cancel a generous range so deleted reminders are gone too
        (1..200).forEach { am.cancel(pending(ctx, it)) }
        list(ctx).filter { it.on }.forEach { r -> schedule(ctx, r) }
    }

    private fun schedule(ctx: Context, r: Reminder, after: Long = System.currentTimeMillis()) {
        val from = Calendar.getInstance().apply { timeInMillis = after }
        val at = next(r, from) ?: return
        val am = ctx.getSystemService(AlarmManager::class.java)
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(ctx, r.id))
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(ctx, r.id))
    }

    fun canExact(ctx: Context) = Build.VERSION.SDK_INT < 31 ||
        ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun fire(ctx: Context, id: Int) {
        val r = list(ctx).firstOrNull { it.id == id } ?: return
        HubLog.i("herinnering", r.text)
        val svc = HubNotificationService.instance
        if (svc != null) svc.sendReminder(r.text, r.vib)
        else WatchLink.notify("⏰ Herinnering", r.text)
        // Next time: at least a minute from now, so an alarm that fires a little early isn't repeated
        if (r.on) schedule(ctx, r, System.currentTimeMillis() + 61_000)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> Reminders.scheduleAll(ctx)
            else -> {
                WatchLink.init(ctx)
                Reminders.fire(ctx, intent.getIntExtra("id", -1))
            }
        }
    }
}
