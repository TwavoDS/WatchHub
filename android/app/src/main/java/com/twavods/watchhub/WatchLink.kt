package com.twavods.watchhub

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import com.xiaomi.xms.wearable.Wearable
import com.xiaomi.xms.wearable.auth.AuthApi
import com.xiaomi.xms.wearable.auth.Permission
import com.xiaomi.xms.wearable.message.MessageApi
import com.xiaomi.xms.wearable.message.OnMessageReceivedListener
import com.xiaomi.xms.wearable.node.NodeApi
import com.xiaomi.xms.wearable.notify.NotifyApi
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Link with the watch app over Xiaomi's interconnect channel (Bluetooth, via Mi Fitness).
 * Lives for the whole process, so it keeps working while another app (the Xiaomi camera)
 * is in the foreground.
 *
 * Watch -> phone (JSON):
 *   {"cmd":"start"}            start streaming preview frames
 *   {"cmd":"stop"}             stop streaming
 *   {"cmd":"ack","s":N}        watch received frame N
 *   {"cmd":"shutter"}          take a photo
 *   {"cmd":"switch"}           switch front/back camera
 *   {"cmd":"mode","m":"fill"|"fit"}   fill the round screen, or show the whole picture
 *
 *   {"cmd":"hello"}            watch app opened; phone resends navigation state
 *   {"cmd":"dismiss","id":K}   notification K was dismissed on the watch
 *   {"cmd":"icon","pkg":P}     watch needs the icon of app P
 *
 * Phone -> watch (JSON):
 *   {"t":"c","s":N,"i":I,"n":TOTAL,"d":"<base64 part>"}   one chunk of frame N
 *   {"t":"nav",...} {"t":"buzz"} {"t":"end"}               navigation (see HubNotificationService)
 *   {"t":"notif",...} {"t":"remind","id":K} {"t":"gone","id":K} {"t":"icon","pkg":P,"d":B64}
 *   {"t":"shot","ok":true|false,"e":"..."}                 result of a photo
 *   {"t":"hello"}                                          phone app is ready
 */
object WatchLink {

    /** Where frames come from and where camera commands go. */
    interface Source {
        fun latestJpeg(): ByteArray?
        /** Take a photo; call [done] with null on success or an error message. */
        fun shutter(done: (String?) -> Unit)
        fun switchCamera()
    }

    @Volatile var source: Source? = null

    const val PAGE_HOME = "/pages/index"
    const val PAGE_CAMERA = "/pages/camera"
    const val PAGE_NAV = "/pages/nav"
    const val PAGE_NOTIF = "/pages/notif"

    /** Handlers for watch commands other than the camera ones (main thread). */
    val handlers = HashMap<String, (JSONObject) -> Unit>()

    private val main = Handler(Looper.getMainLooper())
    private lateinit var nodeApi: NodeApi
    private lateinit var messageApi: MessageApi
    private lateinit var authApi: AuthApi
    private lateinit var notifyApi: NotifyApi
    private var initialized = false
    private var listening = false

    var nodeId: String? = null; private set
    var nodeName: String = ""; private set
    var linkState = "Horloge zoeken…"; private set
    var permissionOk = false; private set

    // Streaming state (main thread only)
    var streaming = false; private set
    private var transmitting = false
    private val inFlight = HashSet<Int>()
    private var lastSentJpeg: ByteArray? = null
    private var retryPosted = false
    private var seq = 0
    private var lastSendMs = 0L
    var framesSent = 0; private set
    var messagesIn = 0; private set
    var lastFrameBytes = 0; private set
    var photosTaken = 0; private set
    var fps = 0.0; private set
    var lastError = ""
    /** true: fill the round screen; false: show the whole picture. Chosen on the watch. */
    @Volatile var fillMode = true

    private const val CHUNK = 8000
    private const val WINDOW = 2
    /** At most ~6 frames per second: faster makes the watch run hot and unstable. */
    private const val MIN_INTERVAL_MS = 160L

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val app = context.applicationContext
        nodeApi = Wearable.getNodeApi(app)
        messageApi = Wearable.getMessageApi(app)
        authApi = Wearable.getAuthApi(app)
        notifyApi = Wearable.getNotifyApi(app)
    }

    private val listener = OnMessageReceivedListener { _, bytes ->
        main.post { onWatchMessage(String(bytes, Charsets.UTF_8)) }
    }

    fun connect() {
        if (!initialized) return
        // Re-register the listener: after Notify/Mi Fitness hand over, the old one may be gone
        listening = false
        permissionOk = false
        lastError = ""
        linkState = "Horloge zoeken…"
        nodeApi.connectedNodes
            .addOnSuccessListener { nodes ->
                main.post {
                    if (nodes.isNullOrEmpty()) {
                        linkState = "Geen horloge verbonden in Mi Fitness"
                        return@post
                    }
                    val node = nodes[0]
                    nodeId = node.id
                    nodeName = node.name ?: node.id
                    linkState = "Verbonden met $nodeName"
                    ensurePermission(node.id)
                }
            }
            .addOnFailureListener { e ->
                main.post { linkState = "Mi Fitness niet bereikbaar: ${e.message}" }
            }
    }

    private fun ensurePermission(id: String) {
        val perms = arrayOf(Permission.DEVICE_MANAGER, Permission.NOTIFY)
        authApi.checkPermissions(id, perms)
            .addOnSuccessListener { results ->
                main.post { if (results.all { it }) onPermissionGranted(id) else requestPermission(id) }
            }
            .addOnFailureListener { main.post { requestPermission(id) } }
    }

    private fun requestPermission(id: String) {
        linkState = "Bevestig de toestemming op je horloge…"
        authApi.requestPermission(id, Permission.DEVICE_MANAGER, Permission.NOTIFY)
            .addOnSuccessListener { granted ->
                main.post {
                    if (granted.any { it == Permission.DEVICE_MANAGER }) onPermissionGranted(id)
                    else linkState = "Toestemming geweigerd op het horloge"
                }
            }
            .addOnFailureListener { e -> main.post { linkState = "Toestemming mislukt: ${e.message}" } }
    }

    private fun onPermissionGranted(id: String) {
        permissionOk = true
        linkState = "Verbonden met $nodeName"
        if (listening) { send(JSONObject().put("t", "hello")); flushWaiting(); return }
        messageApi.addListener(id, listener)
            .addOnSuccessListener { main.post { listening = true; send(JSONObject().put("t", "hello")); flushWaiting() } }
            .addOnFailureListener { e ->
                main.post {
                    // "you have registered" just means the listener is already active
                    if (e.message?.contains("registered") == true) {
                        listening = true
                        send(JSONObject().put("t", "hello"))
                    } else lastError = "luisteren: ${e.message}"
                    flushWaiting()
                }
            }
    }

    /** Result of the last attempt to open the watch app, shown in the phone app. */
    var lastLaunch = ""

    fun openOnWatch(page: String = PAGE_HOME) {
        val id = nodeId
        if (id == null || !permissionOk) { whenReady { openOnWatch(page) }; return }
        nodeApi.isWearAppInstalled(id)
            .addOnSuccessListener { installed ->
                if (installed == true) launch(id, page, fallback = true)
                else main.post { lastLaunch = "WatchHub staat niet op het horloge" }
            }
            .addOnFailureListener { e -> main.post { lastLaunch = "controle mislukt: ${e.message}" } }
    }

    private fun launch(id: String, page: String, fallback: Boolean) {
        nodeApi.launchWearApp(id, page)
            .addOnSuccessListener { main.post { lastLaunch = "geopend ($page)" } }
            .addOnFailureListener { e ->
                main.post {
                    lastLaunch = "openen mislukt ($page): ${e.message}"
                    // Try the start page of the app if the specific page is not accepted
                    if (fallback && page != PAGE_HOME) launch(id, PAGE_HOME, fallback = false)
                }
            }
    }

    /** Called when a new frame source becomes active, to resume a running stream. */
    fun kick() {
        main.post { lastSentJpeg = null; sendNextFrame() }
    }

    private fun onWatchMessage(text: String) {
        messagesIn++
        // The watch may send an object or a JSON-encoded string; accept both.
        val msg = try {
            when (val v = JSONTokener(text.trim()).nextValue()) {
                is JSONObject -> v
                is String -> JSONObject(v)
                else -> return
            }
        } catch (_: Exception) { lastError = "onbekend bericht: ${text.take(60)}"; return }
        when (msg.optString("cmd")) {
            "start" -> {
                if (source == null) {
                    // Camera screen on the phone is not open
                    send(JSONObject().put("t", "nocam"))
                    return
                }
                if (msg.has("m")) fillMode = msg.optString("m") != "fit"
                streaming = true; inFlight.clear(); sendNextFrame()
            }
            "mode" -> { fillMode = msg.optString("m") != "fit"; lastSentJpeg = null }
            "stop" -> streaming = false
            "ack" -> { inFlight.remove(msg.optInt("s")); sendNextFrame() }
            "shutter" -> shutter()
            "switch" -> source?.switchCamera()
            else -> handlers[msg.optString("cmd")]?.invoke(msg)
        }
    }

    private fun shutter() {
        val src = source
        if (src == null) {
            send(JSONObject().put("t", "shot").put("ok", false).put("e", "geen camera actief"))
            return
        }
        src.shutter { err ->
            main.post {
                if (err == null) {
                    photosTaken++
                    send(JSONObject().put("t", "shot").put("ok", true))
                } else {
                    lastError = "foto: $err"
                    send(JSONObject().put("t", "shot").put("ok", false).put("e", err))
                }
            }
        }
    }

    private val waiting = ArrayList<() -> Unit>()
    private var connecting = false

    /** Runs [action] once the link is ready, connecting first if needed. */
    fun whenReady(action: () -> Unit) {
        main.post {
            if (nodeId != null && permissionOk) { action(); return@post }
            waiting.add(action)
            if (connecting) return@post
            connecting = true
            connect()
            main.postDelayed({ connecting = false }, 15000)
        }
    }

    private fun flushWaiting() {
        connecting = false
        val list = ArrayList(waiting)
        waiting.clear()
        list.forEach { it() }
    }

    /**
     * Shows a normal system notification on the watch (pops up straight away,
     * also when the WatchHub app on the watch is closed).
     */
    fun notify(title: String, message: String) = whenReady {
        val id = nodeId ?: return@whenReady
        notifyApi.sendNotify(id, title, message)
            .addOnFailureListener { e -> main.post { lastError = "melding: ${e.message}" } }
    }

    /** Sends a message to the watch app, connecting first if needed. */
    fun post(obj: JSONObject) = whenReady { send(obj) }

    private fun send(obj: JSONObject, onDone: ((Boolean) -> Unit)? = null) {
        val id = nodeId
        if (id == null || !permissionOk) { onDone?.invoke(false); return }
        messageApi.sendMessage(id, obj.toString().toByteArray(Charsets.UTF_8))
            .addOnSuccessListener { main.post { onDone?.invoke(true) } }
            .addOnFailureListener { e ->
                main.post {
                    lastError = "versturen: ${e.message}"
                    onDone?.invoke(false)
                }
            }
    }

    /**
     * Sends the newest frame. Up to WINDOW frames may be on their way at once,
     * so the Bluetooth link never sits idle while the watch is decoding.
     */
    private fun sendNextFrame() {
        if (!streaming || transmitting || inFlight.size >= WINDOW) return
        val wait = MIN_INTERVAL_MS - (SystemClock.elapsedRealtime() - lastSendMs)
        if (wait > 0) {
            if (!retryPosted) {
                retryPosted = true
                main.postDelayed({ retryPosted = false; sendNextFrame() }, wait)
            }
            return
        }
        val jpeg = source?.latestJpeg()
        if (jpeg == null || jpeg === lastSentJpeg) {
            if (!retryPosted) {
                retryPosted = true
                main.postDelayed({ retryPosted = false; sendNextFrame() }, 20)
            }
            return
        }
        lastSentJpeg = jpeg
        transmitting = true
        val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
        val total = (b64.length + CHUNK - 1) / CHUNK
        val s = ++seq
        lastFrameBytes = jpeg.size
        fun sendChunk(i: Int) {
            if (!streaming) { transmitting = false; return }
            val part = b64.substring(i * CHUNK, minOf(b64.length, (i + 1) * CHUNK))
            val obj = JSONObject().put("t", "c").put("s", s).put("i", i).put("n", total).put("d", part)
            send(obj) { ok ->
                if (!ok) {
                    transmitting = false
                    main.postDelayed({ sendNextFrame() }, 300)
                } else if (i + 1 < total) {
                    sendChunk(i + 1)
                } else {
                    transmitting = false
                    framesSent++
                    val now = SystemClock.elapsedRealtime()
                    if (lastSendMs > 0) fps = 0.8 * fps + 0.2 * (1000.0 / (now - lastSendMs).coerceAtLeast(1))
                    lastSendMs = now
                    inFlight.add(s)
                    // Safety net: forget a frame whose ack got lost
                    main.postDelayed({ if (inFlight.remove(s)) sendNextFrame() }, 2000)
                    sendNextFrame()
                }
            }
        }
        sendChunk(0)
    }
}
