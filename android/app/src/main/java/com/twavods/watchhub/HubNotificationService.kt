package com.twavods.watchhub

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
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
 *  - Incoming calls are shown with Answer / Decline.
 *  - Apps that are switched on in WatchHub are shown on the watch with their own
 *    vibration, colour, display time, buttons and quick replies.
 *
 * Phone -> watch:
 *   {"t":"notif","id":N,"pkg":P,"app":..,"title":..,"text":..,"color":"#25D366","vib":"SS",
 *    "time":"14:32","quiet":false,"dur":10,"call":false,
 *    "actions":[{"i":0,"title":"Markeren als gelezen"}],"replies":["Oké 👍",...]}
 *   {"t":"goto","p":"notif","n":{notif}}   open the notification screen with this notification
 *   {"t":"remind","id":N,"vib":"SS"}       still unread
 *   {"t":"gone","id":N}                    removed on the phone
 *   {"t":"list","items":[notif...]}        all current notifications (answer to "sync")
 *   {"t":"icon","pkg":P,"d":"<base64 png>"}
 *
 * Watch -> phone:
 *   {"cmd":"dismiss","id":N}  {"cmd":"action","id":N,"i":I}  {"cmd":"reply","id":N,"text":".."}
 *   {"cmd":"sync"}  {"cmd":"icon","pkg":P}  {"cmd":"page","p":"camera"}  {"cmd":"findphone","on":true}
 */
class HubNotificationService : NotificationListenerService() {

    companion object {
        @Volatile var instance: HubNotificationService? = null; private set
        @Volatile var connected = false; private set
        @Volatile var forwarded = 0; private set
        @Volatile var lastInfo = ""
            set(v) {
                if (v.isNotEmpty() && v != field) {
                    if (v.contains("mislukt") || v.contains("onbekend")) HubLog.e("melding", v) else HubLog.i("melding", v)
                }
                field = v
            }
        /** Which page the watch app shows right now ("" = closed). */
        @Volatile var watchPage = ""
        private const val ICON_SIZE = 72
        private const val MAX_ITEMS = 20
        private const val MAX_ACTIONS = 3
    }

    private class Item(
        val id: Int,
        val key: String,
        val json: JSONObject,
        val rule: AppRule,
        val actions: List<Notification.Action>,
        val replyAction: Notification.Action?,
    )

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
        WatchLink.handlers["action"] = { m -> runAction(m.optInt("id"), m.optInt("i")) }
        WatchLink.handlers["reply"] = { m -> reply(m.optInt("id"), m.optString("text")) }
        WatchLink.handlers["icon"] = { m -> sendIcon(m.optString("pkg")) }
        WatchLink.handlers["img"] = { m -> sendImage(m.optString("k")) }
        WatchLink.handlers["page"] = { m -> watchPage = m.optString("p") }
        WatchLink.handlers["findphone"] = { m -> if (m.optBoolean("on")) FindPhone.start(this) else FindPhone.stop() }
        try {
            activeNotifications?.forEach { if (it.packageName == NavHandler.MAPS) nav.onPosted(it) }
        } catch (_: Exception) {}
    }

    override fun onListenerDisconnected() {
        connected = false
        if (instance === this) instance = null
    }

    private fun nextNumber(): Int { val id = nextId; nextId += 1; return id }

    private fun idFor(key: String): Int {
        val existing = idsByKey[key]
        if (existing != null) return existing
        val id = nextNumber()
        idsByKey[key] = id
        return id
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!::nav.isInitialized) return
        if (nav.onPosted(sbn)) return
        if (sbn.packageName == packageName) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        if (n.category == Notification.CATEGORY_CALL) {
            if (NotifRules.callsEnabled(this)) forwardCall(sbn, n)
            return
        }
        // Skip ongoing things like music players and downloads
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return

        NotifRules.markSeen(this, sbn.packageName)
        val rule = NotifRules.get(this, sbn.packageName)
        if (!rule.enabled) return

        val (title, text) = texts(n)
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

        val id = idFor(sbn.key)
        val (actions, replyAction) = splitActions(n)
        val json = baseJson(id, sbn.packageName, title, text, rule.color, if (quiet) "" else rule.vib, sbn.postTime)
            .put("quiet", quiet)
            .put("dur", rule.duration)
            .put("call", false)
        putActions(json, actions, replyAction, rule)

        remember(Item(id, sbn.key, json, rule, actions, replyAction))
        forwarded++
        lastInfo = "doorgestuurd: ${appLabel(sbn.packageName)}"
        deliver(json, rule, quiet, reminder = false)
        if (!quiet) scheduleRepeat(id, rule)
    }

    /** Incoming (or ongoing) call: always on the WatchHub screen, buzzing, with the call buttons. */
    private fun forwardCall(sbn: StatusBarNotification, n: Notification) {
        val (title, text) = texts(n)
        val sig = "$title|$text"
        if (lastText[sbn.key] == sig) return
        lastText[sbn.key] = sig
        val id = idFor(sbn.key)
        val (actions, _) = splitActions(n)
        // A ringing call has (at least) Answer and Decline
        val ringing = actions.size >= 2
        val rule = AppRule(enabled = true, vib = if (ringing) "LLL" else "", color = "#66BB6A", duration = 0, display = "screen")
        val json = baseJson(id, sbn.packageName, title.ifEmpty { "Oproep" }, text, rule.color, rule.vib, sbn.postTime)
            .put("quiet", false)
            .put("dur", 0)
            .put("call", true)
        putActions(json, actions, null, rule)
        remember(Item(id, sbn.key, json, rule, actions, null))
        lastInfo = "oproep: ${title.ifEmpty { "onbekend" }}"
        deliver(json, rule, quiet = false, reminder = false)
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

    // ---- Building messages ---------------------------------------------------------

    private fun texts(n: Notification): Pair<String, String> {
        val e = n.extras
        val title = e?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (e?.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: e?.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString()?.trim().orEmpty()
        return title to text
    }

    private fun baseJson(id: Int, pkg: String, title: String, text: String, color: String, vib: String, time: Long) =
        JSONObject()
            .put("t", "notif")
            .put("id", id)
            .put("pkg", pkg)
            .put("app", appLabel(pkg))
            .put("title", title.take(80))
            .put("text", text.take(400))
            .put("color", color)
            .put("vib", vib)
            .put("time", android.text.format.DateFormat.getTimeFormat(this).format(java.util.Date(time)))

    /** Buttons of the notification, and the one that takes a typed reply (if any). */
    private fun splitActions(n: Notification): Pair<List<Notification.Action>, Notification.Action?> {
        val all = n.actions?.toList().orEmpty()
        val reply = all.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }
        val buttons = all.filter { it !== reply && it.actionIntent != null && !it.title.isNullOrBlank() }
            .take(MAX_ACTIONS)
        return buttons to reply
    }

    private fun putActions(json: JSONObject, actions: List<Notification.Action>, reply: Notification.Action?, rule: AppRule) {
        val arr = JSONArray()
        actions.forEachIndexed { i, a -> arr.put(JSONObject().put("i", i).put("title", a.title.toString().take(24))) }
        json.put("actions", arr)
        json.put("replies", if (reply != null && rule.replies) JSONArray(NotifRules.replies(this)) else JSONArray())
    }

    /**
     * Text the watch font can't show (emoji) is drawn here as a picture. The watch asks for
     * it with {"cmd":"img","k":key} and gets it back in pieces as {"t":"img",...}.
     */
    private val imgSources = object : LinkedHashMap<String, () -> TextImage.Result>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, () -> TextImage.Result>?) = size > 80
    }

    private fun addImages(json: JSONObject) {
        val id = json.optInt("id")
        val title = json.optString("title")
        val text = json.optString("text")
        if (TextImage.needsImage(title + text)) {
            val key = "b$id"
            val call = json.optBoolean("call")
            imgSources[key] = { TextImage.body(title, text, if (call) 36f else 28f) }
            json.put("img", key)
        }
        val replies = json.optJSONArray("replies") ?: return
        val keys = JSONArray()
        for (i in 0 until replies.length()) {
            val r = replies.optString(i)
            if (TextImage.needsImage(r)) {
                val key = "q" + Integer.toHexString(r.hashCode())
                imgSources[key] = { TextImage.line(r) }
                keys.put(key)
            } else keys.put("")
        }
        json.put("rimg", keys)
    }

    /** Sends the pictures of this notification without waiting for the watch to ask. */
    private fun pushImages(json: JSONObject, delayMs: Long) {
        val keys = mutableListOf<String>()
        json.optString("img").let { if (it.isNotEmpty()) keys.add(it) }
        json.optJSONArray("rimg")?.let { a -> for (i in 0 until a.length()) a.optString(i).let { if (it.isNotEmpty()) keys.add(it) } }
        keys.forEachIndexed { i, k -> main.postDelayed({ sendImage(k) }, delayMs + i * 900L) }
    }

    private fun sendImage(key: String) {
        val make = imgSources[key] ?: run { lastInfo = "afbeelding onbekend: $key"; return }
        val img = try { make() } catch (e: Exception) { lastInfo = "afbeelding mislukt: ${e.message}"; return }
        lastInfo = "emoji-afbeelding verstuurd (${img.base64.length / 1024} kB)"
        val d = img.base64
        val size = 7000
        val n = (d.length + size - 1) / size
        for (c in 0 until n) {
            // A little apart, so the watch isn't flooded
            val msg = JSONObject().put("t", "img").put("k", key).put("c", c).put("n", n)
                    .put("h", img.height).put("w", img.width)
                    .put("d", d.substring(c * size, minOf(d.length, (c + 1) * size)))
            main.postDelayed({ WatchLink.post(msg) }, c * 200L)
        }
    }

    private fun remember(item: Item) {
        addImages(item.json)
        items.remove(item.id)
        items[item.id] = item
        while (items.size > MAX_ITEMS) items.remove(items.keys.first())
    }

    /** Shows the notification on the watch in the way chosen for this app. */
    private fun deliver(json: JSONObject, rule: AppRule, quiet: Boolean, reminder: Boolean) {
        if (rule.display == "popup") {
            // Normal watch notification: pops up immediately, wherever you are on the watch.
            if (!quiet) {
                val title = json.optString("app") + (json.optString("title").let { if (it.isEmpty()) "" else " · $it" })
                WatchLink.notify(if (reminder) "🔔 $title" else title, json.optString("text"))
            }
            WatchLink.post(JSONObject(json.toString()).put("vib", ""))
            return
        }
        // WatchHub screen: open it with this notification in it, unless the watch is busy
        // with camera or navigation (then it only buzzes and shows a short banner)
        if (watchPage.isEmpty() || watchPage == "home") {
            // The notification travels inside the "goto", so the screen never shows an older one first
            val goto = JSONObject().put("t", "goto").put("p", "notif").put("n", json)
            WatchLink.gotoOnWatch(goto, appWasClosed = watchPage.isEmpty())
            pushImages(json, if (watchPage.isEmpty()) 4000L else 300L)
        } else {
            WatchLink.post(json)
            pushImages(json, 300L)
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
                if (item != null) deliver(item.json, rule, quiet = false, reminder = true)
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

    /** Presses a button of the notification, e.g. "Markeren als gelezen" or "Opnemen". */
    private fun runAction(id: Int, index: Int) {
        val item = items[id] ?: return
        val action = item.actions.getOrNull(index) ?: return
        try {
            action.actionIntent.send()
            lastInfo = "knop: ${action.title}"
        } catch (e: Exception) {
            lastInfo = "knop mislukt: ${e.message}"
        }
        stopRepeat(id)
    }

    /** Sends a quick reply through the app's own reply button (like typing it on the phone). */
    private fun reply(id: Int, text: String) {
        val item = items[id] ?: return
        val action = item.replyAction ?: return
        val inputs = action.remoteInputs ?: return
        try {
            val intent = Intent()
            val results = Bundle()
            inputs.forEach { results.putCharSequence(it.resultKey, text) }
            RemoteInput.addResultsToIntent(inputs, intent, results)
            action.actionIntent.send(this, 0, intent)
            lastInfo = "geantwoord: $text"
        } catch (e: Exception) {
            lastInfo = "antwoorden mislukt: ${e.message}"
        }
        stopRepeat(id)
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
        val id = nextNumber()
        val json = baseJson(id, pkg, "Testmelding 👋", "Zo ziet een melding van ${appLabel(pkg)} eruit op je horloge. Emoji-test: 😀 👍 ❤️ 🎉",
            rule.color, rule.vib, System.currentTimeMillis())
            .put("quiet", false)
            .put("dur", rule.duration)
            .put("call", false)
            .put("actions", JSONArray())
            .put("replies", if (rule.replies) JSONArray(NotifRules.replies(this)) else JSONArray())
        remember(Item(id, "test:$id", json, rule, emptyList(), null))
        deliver(json, rule, quiet = false, reminder = false)
    }

    /** Pretend call (for the test screen): shows the call screen with two dummy buttons. */
    fun sendTestCall() {
        val id = nextNumber()
        val json = baseJson(id, packageName, "Testoproep", "Dit is een test, er belt niemand", "#66BB6A", "LLL",
            System.currentTimeMillis())
            .put("quiet", false).put("dur", 0).put("call", true)
            .put("actions", JSONArray()
                .put(JSONObject().put("i", 0).put("title", "Opnemen"))
                .put(JSONObject().put("i", 1).put("title", "Weigeren")))
            .put("replies", JSONArray())
        val rule = AppRule(enabled = true, vib = "LLL", color = "#66BB6A", duration = 0, display = "screen")
        remember(Item(id, "test:$id", json, rule, emptyList(), null))
        deliver(json, rule, quiet = false, reminder = false)
        HubLog.i("test", "testoproep verstuurd")
    }

    fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg }
}
