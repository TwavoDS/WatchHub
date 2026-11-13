package com.twavods.watchhub

import android.app.Notification
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.SystemClock
import android.content.Context
import android.service.notification.StatusBarNotification
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Reads the turn-by-turn notification of Google Maps and forwards it to the watch.
 *
 * Phone -> watch:
 *   {"t":"nav", "dist":"300 m", "instr":"Sla linksaf", "street":"Kerkstraat",
 *    "eta":"12 min · 5,3 km · 14:32", "iconId":N, "icon":"<base64 png, only when changed>"}
 *   {"t":"buzz"}     a turn is coming up
 *   {"t":"end"}      navigation stopped
 */
class NavHandler(private val ctx: Context) {

    companion object {
        const val MAPS = "com.google.android.apps.maps"
        private const val ICON_SIZE = 120

        @Volatile var lastNav: NavState? = null; private set
        @Volatile var updates = 0; private set
    }

    data class NavState(
        val dist: String,
        val instr: String,
        val street: String,
        val eta: String,
        val iconPng: ByteArray?,
        val iconId: Int,
    )

    private var navigating = false
    private var lastIconHash = 0
    private var iconId = 0
    private var buzzedFor = ""
    private var lastSentKey = ""
    private var lastSentMs = 0L

    /** Returns true when this was the Google Maps navigation notification. */
    fun onPosted(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName != MAPS) return false
        val n = sbn.notification ?: return false
        if (!isNavigation(n)) return false
        val e = n.extras ?: Bundle()

        val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (e.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: e.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString()?.trim().orEmpty()
        val sub = e.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim().orEmpty()

        // Google Maps puts the distance in the title ("300 m") and the instruction in the
        // text ("Sla linksaf naar Kerkstraat"); sometimes it is the other way round.
        // Without a distance (e.g. "Loop richting de Schaapsdam") the title is the instruction
        val (dist, instruction) = when {
            looksLikeDistance(title) -> title to text
            looksLikeDistance(text) -> text to title
            else -> "" to title.ifEmpty { text }
        }
        val (instr, street) = splitStreet(instruction)

        val icon = maneuverIcon(n)
        val hash = icon?.contentHashCode() ?: 0
        val iconChanged = hash != lastIconHash
        if (iconChanged) { lastIconHash = hash; iconId++ }

        val state = NavState(dist, instr, street, sub, icon, iconId)
        lastNav = state
        updates++

        if (!navigating) {
            navigating = true
            // The watch opens on its start page; it is told to go to the navigation screen
            val page = HubNotificationService.watchPage
            if (page != "nav" && page != "camera") {
                WatchLink.gotoOnWatch(JSONObject().put("t", "goto").put("p", "nav"), appWasClosed = page.isEmpty())
            }
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ resendFull() }, 3000)
        }

        // Google Maps updates very often; skip identical updates
        val key = "$dist|$instr|$street|$sub|$iconId"
        val now = SystemClock.elapsedRealtime()
        if (key == lastSentKey && now - lastSentMs < 5000) return true
        lastSentKey = key
        lastSentMs = now
        WatchLink.post(toJson(state, withIcon = iconChanged))

        maybeBuzz(dist, "$instr|$street|$iconId")
        return true
    }

    /** Returns true when this was the Google Maps navigation notification. */
    fun onRemoved(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName != MAPS) return false
        val n = sbn.notification ?: return false
        if (!isNavigation(n)) return false
        if (!navigating) return true
        navigating = false
        lastNav = null
        lastSentKey = ""
        buzzedFor = ""
        WatchLink.post(JSONObject().put("t", "end"))
        // Stop recording the map when navigation ends
        if (ScreenStreamService.runningFor(ScreenStreamService.TARGET_MAP)) {
            try {
                ctx.startService(android.content.Intent(ctx, ScreenStreamService::class.java)
                    .setAction(ScreenStreamService.ACTION_STOP))
            } catch (_: Exception) {}
        }
        return true
    }

    /** The watch app just opened: send everything again, including the arrow. */
    fun resendFull() {
        val s = lastNav
        if (s == null) WatchLink.post(JSONObject().put("t", "end"))
        else WatchLink.post(toJson(s, withIcon = true))
    }

    private fun isNavigation(n: Notification): Boolean {
        if (n.category == Notification.CATEGORY_NAVIGATION) return true
        val ongoing = n.flags and Notification.FLAG_ONGOING_EVENT != 0
        val channel = n.channelId?.lowercase().orEmpty()
        return ongoing && (channel.contains("navigation") || channel.contains("nav"))
    }

    private fun toJson(s: NavState, withIcon: Boolean): JSONObject {
        val o = JSONObject()
            .put("t", "nav")
            .put("dist", s.dist)
            .put("instr", s.instr)
            .put("street", s.street)
            .put("eta", s.eta)
            .put("iconId", s.iconId)
        if (withIcon && s.iconPng != null) o.put("icon", Base64.encodeToString(s.iconPng, Base64.NO_WRAP))
        return o
    }

    // ---- Turn warning -----------------------------------------------------------

    /** Buzz once per manoeuvre when it is close (≤ 100 m, ≤ 0,1 km or ≤ 500 ft). */
    private fun maybeBuzz(dist: String, maneuverKey: String) {
        if (maneuverKey == buzzedFor) return
        val meters = toMeters(dist) ?: return
        if (meters <= 100) {
            buzzedFor = maneuverKey
            WatchLink.post(JSONObject().put("t", "buzz"))
        }
    }

    private fun looksLikeDistance(s: String) = toMeters(s) != null

    private fun toMeters(s: String): Double? {
        val m = Regex("""^\s*(\d+(?:[.,]\d+)?)\s*(km|m|mi|ft|yd)\b""", RegexOption.IGNORE_CASE).find(s) ?: return null
        val v = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        return when (m.groupValues[2].lowercase()) {
            "km" -> v * 1000
            "m" -> v
            "mi" -> v * 1609.3
            "ft" -> v * 0.3048
            "yd" -> v * 0.9144
            else -> null
        }
    }

    /** "Sla linksaf naar Kerkstraat" -> ("Sla linksaf", "Kerkstraat") */
    private fun splitStreet(s: String): Pair<String, String> {
        val m = Regex("""^(.*?)\s+(?:naar|richting|op|onto|toward|towards|on|vers|in die|auf die|in)\s+(.+)$""",
            RegexOption.IGNORE_CASE).find(s)
        return if (m != null && m.groupValues[1].length in 3..40) m.groupValues[1] to m.groupValues[2] else s to ""
    }

    // ---- Arrow icon -------------------------------------------------------------

    /** The manoeuvre arrow, drawn white on black, as PNG. */
    private fun maneuverIcon(n: Notification): ByteArray? {
        val icon: Icon = n.getLargeIcon() ?: n.smallIcon ?: return null
        return try {
            val d = icon.loadDrawable(ctx) ?: return null
            val bmp = Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(Color.BLACK)
            // Draw the arrow's shape in white, whatever colour Maps used
            val layer = Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, ICON_SIZE, ICON_SIZE)
            d.draw(Canvas(layer))
            val white = Paint().apply {
                colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                    0f, 0f, 0f, 0f, 255f,
                    0f, 0f, 0f, 0f, 255f,
                    0f, 0f, 0f, 0f, 255f,
                    0f, 0f, 0f, 1f, 0f,
                )))
            }
            c.drawBitmap(layer, 0f, 0f, white)
            layer.recycle()
            val bos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
            bmp.recycle()
            bos.toByteArray()
        } catch (_: Exception) {
            null
        }
    }
}
