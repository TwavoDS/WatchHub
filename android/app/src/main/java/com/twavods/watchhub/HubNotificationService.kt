package com.twavods.watchhub

import android.app.Notification
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Sees every notification on the phone.
 *  - Google Maps navigation goes to [NavHandler].
 *  - Apps that are switched on in WatchHub are shown on the watch with their own
 *    vibration, colour and (optionally) a repeating reminder until dismissed.
 *
 * Phone -> watch:
 *   {"t":"notif","id":N,"pkg":P,"app":"WhatsApp","title":..,"text":..,"color":"#25D366",
 *    "vib":"SS","time":"14:32","quiet":false}
 *   {"t":"remind","id":N,"vib":"SS"}     still unread
 *   {"t":"gone","id":N}                  removed on the phone
 *   {"t":"list","items":[notif...]}      all current notifications (answer to "sync")
 *   {"t":"icon","pkg":P,"d":"<base64 png>"}
 *
 * Watch -> phone:
 *   {"cmd":"dismiss","id":N}   {"cmd":"sync"}   {"cmd":"icon","pkg":P}   {"cmd":"page","p":"camera"}
 */
class HubNotificationService : NotificationListenerService() {

    companion object {
        @Volatile var instance: HubNotificationService? = null; private set
        @Volatile var connected = false; private set
        @Volatile var forwarded = 0; private set
        @Volatile var lastInfo = ""
        /** Which page the watch app shows right now ("" = closed). */
        @Volatile var watchPage = ""
        private const val ICON_SIZE = 72
        private const val MAX_ITEMS = 20
    }

    private class Item(val id: Int, val key: String, val json: JSONObject, val rule: AppRule)

    private val main = Handler(Looper.getMainLooper())
    private lateinit var nav: NavHandler
    private val items = LinkedHashMap<Int, Item>()   // id -> item, oldest first
    private val idsByKey = HashMap<String, Int>()
    private val repeats = HashMap<Int, Runnable>()
    private var nextId = 1
    private val lastText = HashMap<String, String>()

    override fun onListenerConnected() {
        connected = true
        instance = this
        nav = NavHandler(this)
        WatchLink.init(this)
        WatchLink.handlers["hello"] = { nav.resendFull() }
        WatchLink.handlers["sync"] = { sendList() }
        WatchLink.handlers["dismiss"] = { m -> dismissFromWatch(m.optInt("id")) }
        WatchLink.handlers["icon"] = { m -> sendIcon(m.optString("pkg")) }
        WatchLink.handlers["page"] = { m -> watchPage = m.optString("p") }
        try {
            activeNotifications?.forEach { if (it.packageName == NavHandler.MAPS) nav.onPosted(it) }
        } catch (_: Exception) {}
    }

    override fun onListenerDisconnected() {
        connected = false
        if (instance === this) instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!::nav.isInitialized) return
        if (nav.onPosted(sbn)) return
        if (sbn.packageName == packageName) return
        val n = sbn.notification ?: return
        // Skip group summaries and ongoing things like music players and downloads
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return

        NotifRules.markSeen(this, sbn.packageName)
        val rule = NotifRules.get(this, sbn.packageName)
        if (!rule.enabled) return

        val e = n.extras
        val title = e?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (e?.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: e?.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString()?.trim().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return

        // Apps re-post the same notification often; only forward real changes
        val sig = "$title|$text"
        if (lastText[sbn.key] == sig) return
        lastText[sbn.key] = sig

        // Filters
        val haystack = "$title $text".lowercase()
        val only = NotifRules.words(rule.onlyWords)
        if (only.isNotEmpty() && only.none { haystack.contains(it) }) { lastInfo = "gefilterd (trefwoord)"; return }
        if (NotifRules.words(rule.blockWords).any { haystack.contains(it) }) { lastInfo = "geblokkeerd (woord)"; return }
        if (NotifRules.skipWhenScreenOn(this) && getSystemService(PowerManager::class.java).isInteractive) {
            lastInfo = "overgeslagen (telefoonscherm aan)"; return
        }
        val quiet = NotifRules.isQuietNow(this)

        val id = idsByKey[sbn.key] ?: run {
            val newId = nextId
            nextId += 1
            idsByKey[sbn.key] = newId
            newId
        }
        val json = JSONObject()
            .put("t", "notif")
            .put("id", id)
            .put("pkg", sbn.packageName)
            .put("app", appLabel(sbn.packageName))
            .put("title", title.take(80))
            .put("text", text.take(400))
            .put("color", rule.color)
            .put("vib", if (quiet) "" else rule.vib)
            .put("time", android.text.format.DateFormat.getTimeFormat(this).format(java.util.Date(sbn.postTime)))
            .put("quiet", quiet)
        items.remove(id)
        items[id] = Item(id, sbn.key, json, rule)
        while (items.size > MAX_ITEMS) items.remove(items.keys.first())

        forwarded++
        lastInfo = "doorgestuurd: ${appLabel(sbn.packageName)}"
        deliver(json, rule, quiet, reminder = false)
        if (!quiet) scheduleRepeat(id, rule)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (!::nav.isInitialized) return
        if (nav.onRemoved(sbn)) return
        lastText.remove(sbn.key)
        val id = idsByKey.remove(sbn.key) ?: return
        if (items.remove(id) != null) {
            stopRepeat(id)
            WatchLink.post(JSONObject().put("t", "gone").put("id", id))
        }
    }

    /** Shows the notification on the watch in the way chosen for this app. */
    private fun deliver(json: JSONObject, rule: AppRule, quiet: Boolean, reminder: Boolean) {
        if (rule.display == "popup") {
            // Normal watch notification: pops up immediately, wherever you are on the watch.
            // During quiet hours it only goes into the WatchHub list (no pop-up, no buzz).
            if (!quiet) {
                val title = json.optString("app") + (json.optString("title").let { if (it.isEmpty()) "" else " · $it" })
                WatchLink.notify(if (reminder) "🔔 $title" else title, json.optString("text"))
            }
            // Keep it in the WatchHub list too (if the app is open), without its own buzz
            WatchLink.post(JSONObject(json.toString()).put("vib", ""))
            return
        }
        // WatchHub screen: open it, unless the watch is busy with camera or navigation
        if (watchPage.isEmpty() || watchPage == "home") {
            val wasClosed = watchPage.isEmpty()
            WatchLink.openOnWatch(WatchLink.PAGE_NOTIF)
            // Give a closed watch app a moment to start, so it gets (and vibrates for) the message
            if (wasClosed) main.postDelayed({ WatchLink.post(json) }, 2000) else WatchLink.post(json)
        } else {
            WatchLink.post(json)
        }
    }

    // ---- Reminders --------------------------------------------------------------

    private fun scheduleRepeat(id: Int, rule: AppRule) {
        stopRepeat(id)
        if (rule.repeatMin <= 0) return
        val r = object : Runnable {
            override fun run() {
                if (!items.containsKey(id)) return
                if (NotifRules.isQuietNow(this@HubNotificationService)) {
                    main.postDelayed(this, rule.repeatMin * 60_000L); return
                }
                val item = items[id]
                if (rule.display == "popup" && item != null) deliver(item.json, rule, quiet = false, reminder = true)
                else WatchLink.post(JSONObject().put("t", "remind").put("id", id).put("vib", rule.vib.ifEmpty { "S" }))
                main.postDelayed(this, rule.repeatMin * 60_000L)
            }
        }
        repeats[id] = r
        main.postDelayed(r, rule.repeatMin * 60_000L)
    }

    private fun stopRepeat(id: Int) {
        repeats.remove(id)?.let { main.removeCallbacks(it) }
    }

    // ---- Commands from the watch ------------------------------------------------

    private fun dismissFromWatch(id: Int) {
        val item = items.remove(id) ?: return
        stopRepeat(id)
        idsByKey.remove(item.key)
        lastText.remove(item.key)
        // Also remove it from the phone
        try { cancelNotification(item.key) } catch (_: Exception) {}
    }

    private fun sendList() {
        val arr = JSONArray()
        items.values.forEach { arr.put(it.json) }
        WatchLink.post(JSONObject().put("t", "list").put("items", arr))
    }

    private fun sendIcon(pkg: String) {
        if (pkg.isEmpty()) return
        val png = try {
            val d = packageManager.getApplicationIcon(pkg)
            val bmp = Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, ICON_SIZE, ICON_SIZE)
            d.draw(Canvas(bmp))
            val bos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
            bmp.recycle()
            bos.toByteArray()
        } catch (_: Exception) { return }
        WatchLink.post(
            JSONObject().put("t", "icon").put("pkg", pkg).put("d", Base64.encodeToString(png, Base64.NO_WRAP))
        )
    }

    /** Sends a test notification with the settings of [pkg]. */
    fun sendTest(pkg: String) {
        val rule = NotifRules.get(this, pkg)
        val id = nextId
        nextId += 1
        val json = JSONObject()
            .put("t", "notif").put("id", id).put("pkg", pkg).put("app", appLabel(pkg))
            .put("title", "Testmelding").put("text", "Zo ziet een melding van ${appLabel(pkg)} eruit op je horloge.")
            .put("color", rule.color).put("vib", rule.vib)
            .put("time", android.text.format.DateFormat.getTimeFormat(this).format(java.util.Date()))
            .put("quiet", false)
        items[id] = Item(id, "test:$id", json, rule)
        deliver(json, rule, quiet = false, reminder = false)
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg }
}
