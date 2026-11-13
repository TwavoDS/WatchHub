package com.twavods.watchhub

import android.content.Context
import org.json.JSONObject
import java.util.Calendar

/** How WatchHub treats the notifications of one app. */
data class AppRule(
    /** true: WatchHub shows this app's notifications on the watch. */
    val enabled: Boolean = false,
    /** Vibration pattern: S = short, L = long, e.g. "SS" or "LSS". */
    val vib: String = "S",
    /** Accent colour on the watch, as #RRGGBB. */
    val color: String = "#4FC3F7",
    /** Repeat the vibration every N minutes until dismissed; 0 = off. */
    val repeatMin: Int = 0,
    /** Only forward when the text contains one of these words (comma separated); empty = all. */
    val onlyWords: String = "",
    /** Never forward when the text contains one of these words (comma separated). */
    val blockWords: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("enabled", enabled).put("vib", vib).put("color", color)
        .put("repeatMin", repeatMin).put("onlyWords", onlyWords).put("blockWords", blockWords)

    companion object {
        fun fromJson(o: JSONObject) = AppRule(
            enabled = o.optBoolean("enabled", false),
            vib = o.optString("vib", "S"),
            color = o.optString("color", "#4FC3F7"),
            repeatMin = o.optInt("repeatMin", 0),
            onlyWords = o.optString("onlyWords", ""),
            blockWords = o.optString("blockWords", ""),
        )
    }
}

object NotifRules {
    private const val PREFS = "watchhub_rules"

    val PATTERNS = linkedMapOf(
        "S" to "1× kort",
        "SS" to "2× kort",
        "SSS" to "3× kort",
        "L" to "1× lang",
        "LL" to "2× lang",
        "SL" to "kort – lang",
        "LSS" to "lang – kort – kort",
        "" to "niet trillen",
    )

    val COLORS = linkedMapOf(
        "#4FC3F7" to "Blauw",
        "#66BB6A" to "Groen",
        "#25D366" to "WhatsApp-groen",
        "#FFCA28" to "Geel",
        "#FF7043" to "Oranje",
        "#EF5350" to "Rood",
        "#AB47BC" to "Paars",
        "#FFFFFF" to "Wit",
    )

    val REPEATS = linkedMapOf(0 to "Niet herhalen", 1 to "Elke minuut", 2 to "Elke 2 minuten",
        5 to "Elke 5 minuten", 10 to "Elke 10 minuten")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, 0)

    fun get(ctx: Context, pkg: String): AppRule {
        val raw = prefs(ctx).getString("app:$pkg", null) ?: return AppRule()
        return try { AppRule.fromJson(JSONObject(raw)) } catch (_: Exception) { AppRule() }
    }

    fun set(ctx: Context, pkg: String, rule: AppRule) {
        prefs(ctx).edit().putString("app:$pkg", rule.toJson().toString()).apply()
    }

    fun enabledPackages(ctx: Context): Set<String> =
        prefs(ctx).all.keys.filter { it.startsWith("app:") }.map { it.removePrefix("app:") }
            .filter { get(ctx, it).enabled }.toSet()

    // ---- Apps seen recently (to show them first in the settings list) --------

    fun markSeen(ctx: Context, pkg: String) {
        prefs(ctx).edit().putLong("seen:$pkg", System.currentTimeMillis()).apply()
    }

    fun seenPackages(ctx: Context): List<String> =
        prefs(ctx).all.entries.filter { it.key.startsWith("seen:") }
            .sortedByDescending { (it.value as? Long) ?: 0L }
            .map { it.key.removePrefix("seen:") }

    // ---- Global settings ------------------------------------------------------

    /** Don't forward while the phone screen is on (you are looking at it anyway). */
    fun skipWhenScreenOn(ctx: Context) = prefs(ctx).getBoolean("skipScreenOn", false)
    fun setSkipWhenScreenOn(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("skipScreenOn", v).apply()

    /** Quiet hours in minutes since midnight; -1 = off. During quiet hours: no vibration, no repeat. */
    fun quietStart(ctx: Context) = prefs(ctx).getInt("quietStart", -1)
    fun quietEnd(ctx: Context) = prefs(ctx).getInt("quietEnd", -1)
    fun setQuiet(ctx: Context, start: Int, end: Int) =
        prefs(ctx).edit().putInt("quietStart", start).putInt("quietEnd", end).apply()

    fun isQuietNow(ctx: Context): Boolean {
        val s = quietStart(ctx)
        val e = quietEnd(ctx)
        if (s < 0 || e < 0 || s == e) return false
        val c = Calendar.getInstance()
        val now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        return if (s < e) now in s until e else now >= s || now < e
    }

    fun words(s: String) = s.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
}
