package com.twavods.watchhub

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Action tiles on the watch. Each tile does one thing on the phone:
 *  - "http":   calls a web address (Home Assistant webhook, IFTTT, …)
 *  - "tasker": sends the broadcast com.twavods.watchhub.ACTION with extra "name"
 *              (in Tasker: Event → Intent Received → Action com.twavods.watchhub.ACTION)
 *
 * Watch -> phone: {"cmd":"actions"} (send list)  {"cmd":"act","id":"…"}
 * Phone -> watch: {"t":"actions","items":[{"id","name","color"}]}  {"t":"actres","id","ok","msg"}
 */
data class Action(
    val id: String,
    val name: String,
    val type: String = "http",
    val url: String = "",
    val method: String = "POST",
    val token: String = "",
    val body: String = "",
    val color: String = "#4FC3F7",
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("type", type).put("url", url)
        .put("method", method).put("token", token).put("body", body).put("color", color)

    companion object {
        fun fromJson(o: JSONObject) = Action(
            o.optString("id"), o.optString("name"), o.optString("type", "http"), o.optString("url"),
            o.optString("method", "POST"), o.optString("token"), o.optString("body"), o.optString("color", "#4FC3F7")
        )
    }
}

object Actions {
    const val BROADCAST = "com.twavods.watchhub.ACTION"
    val TYPES = linkedMapOf(
        "torch" to "Zaklamp aan/uit",
        "ringer" to "Trillen / geluid",
        "media" to "Muziek pauzeren/spelen",
        "http" to "Webadres (Home Assistant, IFTTT…)",
        "tasker" to "Tasker / automatisering",
    )
    /** Built-in actions that work without other apps; added the first time. */
    val DEFAULTS = listOf(
        Action("torch", "Zaklamp", "torch", color = "#FFCA28"),
        Action("ringer", "Trillen / geluid", "ringer", color = "#4FC3F7"),
        Action("media", "Muziek pauzeren", "media", color = "#AB47BC"),
    )
    private var torchOn = false
    private var torchWatched = false
    val METHODS = linkedMapOf("POST" to "POST", "GET" to "GET")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("watchhub_actions", Context.MODE_PRIVATE)

    fun list(ctx: Context): List<Action> = try {
        val a = JSONArray(prefs(ctx).getString("list", null) ?: return DEFAULTS)
        (0 until a.length()).map { Action.fromJson(a.getJSONObject(it)) }
    } catch (_: Exception) { emptyList() }

    fun save(ctx: Context, list: List<Action>) {
        val a = JSONArray(); list.forEach { a.put(it.toJson()) }
        prefs(ctx).edit().putString("list", a.toString()).apply()
        sendList(ctx)
    }

    fun sendList(ctx: Context) {
        val items = JSONArray()
        list(ctx).forEach { items.put(JSONObject().put("id", it.id).put("name", it.name).put("color", it.color)) }
        WatchLink.post(JSONObject().put("t", "actions").put("items", items))
    }

    fun run(ctx: Context, id: String, report: Boolean = true) {
        val a = list(ctx).firstOrNull { it.id == id } ?: return
        val done = { ok: Boolean, msg: String ->
            if (ok) HubLog.i("actie", "${a.name}: $msg") else HubLog.e("actie", "${a.name}: $msg")
            if (report) WatchLink.post(JSONObject().put("t", "actres").put("id", id).put("ok", ok).put("msg", msg.take(40)))
        }
        when (a.type) {
            "torch" -> try {
                val cm = ctx.getSystemService(android.hardware.camera2.CameraManager::class.java)
                val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
                if (id == null) done(false, "geen zaklamp") else {
                    if (!torchWatched) {
                        torchWatched = true
                        cm.registerTorchCallback(object : android.hardware.camera2.CameraManager.TorchCallback() {
                            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) { if (cameraId == id) torchOn = enabled }
                        }, android.os.Handler(android.os.Looper.getMainLooper()))
                    }
                    torchOn = !torchOn
                    cm.setTorchMode(id, torchOn)
                    done(true, if (torchOn) "zaklamp aan" else "zaklamp uit")
                }
            } catch (e: Exception) { done(false, "zaklamp: ${e.message}") }
            "ringer" -> try {
                val am = ctx.getSystemService(android.media.AudioManager::class.java)
                if (am.ringerMode == android.media.AudioManager.RINGER_MODE_NORMAL) {
                    am.ringerMode = android.media.AudioManager.RINGER_MODE_VIBRATE
                    done(true, "telefoon op trillen")
                } else {
                    am.ringerMode = android.media.AudioManager.RINGER_MODE_NORMAL
                    done(true, "geluid aan")
                }
            } catch (e: SecurityException) {
                done(false, "Niet storen staat aan")
            } catch (e: Exception) { done(false, e.message ?: "mislukt") }
            "media" -> {
                MediaControl.command("toggle")
                done(true, "muziek gepauzeerd/gestart")
            }
            "tasker" -> {
                ctx.sendBroadcast(Intent(BROADCAST).putExtra("name", a.name).putExtra("id", a.id))
                done(true, "verstuurd")
            }
            else -> Thread {
                try {
                    val c = URL(a.url).openConnection() as HttpURLConnection
                    c.requestMethod = a.method
                    c.connectTimeout = 10000; c.readTimeout = 15000
                    if (a.token.isNotBlank()) c.setRequestProperty("Authorization", "Bearer ${a.token.trim()}")
                    if (a.method == "POST") {
                        c.doOutput = true
                        c.setRequestProperty("Content-Type", "application/json")
                        c.outputStream.use { it.write(a.body.ifBlank { "{}" }.toByteArray()) }
                    }
                    val code = c.responseCode
                    c.disconnect()
                    done(code in 200..299, if (code in 200..299) "gelukt" else "fout $code")
                } catch (e: Exception) {
                    done(false, e.message ?: e.javaClass.simpleName)
                }
            }.start()
        }
    }
}
