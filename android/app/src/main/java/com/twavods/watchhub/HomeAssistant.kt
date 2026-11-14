package com.twavods.watchhub

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Home Assistant on the watch ("Huis"): shows chosen entities with their state and
 * switches them with a tap. Talks to the Home Assistant REST API with a long-lived token.
 *
 * Watch -> phone: {"cmd":"ha"} (send states)  {"cmd":"hatog","id":"light.keuken"}
 * Phone -> watch: {"t":"ha","items":[{"id","name","state","on","tap"}],"err":"…"}
 */
object HomeAssistant {
    class Entity(val id: String, val name: String, val state: String, val unit: String) {
        val domain get() = id.substringBefore('.')
    }

    /** Domains you can tap on the watch. */
    private val TAPPABLE = setOf("light", "switch", "fan", "input_boolean", "automation", "cover", "lock",
        "script", "scene", "button", "input_button", "media_player", "siren", "humidifier", "vacuum")
    /** Domains worth offering in the picker (tappable ones + things to read). */
    val PICKABLE = TAPPABLE + setOf("sensor", "binary_sensor", "climate", "weather", "person", "device_tracker", "alarm_control_panel")

    private val main = Handler(Looper.getMainLooper())
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("watchhub_ha", Context.MODE_PRIVATE)

    fun url(ctx: Context) = prefs(ctx).getString("url", "") ?: ""
    fun token(ctx: Context) = prefs(ctx).getString("token", "") ?: ""
    fun chosen(ctx: Context): List<String> = (prefs(ctx).getString("ids", "") ?: "").split('\n').filter { it.isNotBlank() }
    fun configured(ctx: Context) = url(ctx).isNotBlank() && token(ctx).isNotBlank()

    fun saveLogin(ctx: Context, url: String, token: String) =
        prefs(ctx).edit().putString("url", url.trim().trimEnd('/')).putString("token", token.trim()).apply()

    fun saveChosen(ctx: Context, ids: List<String>) = prefs(ctx).edit().putString("ids", ids.joinToString("\n")).apply()

    private fun request(ctx: Context, method: String, path: String, body: JSONObject? = null): String {
        val c = URL(url(ctx) + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 8000; c.readTimeout = 12000
        c.setRequestProperty("Authorization", "Bearer " + token(ctx))
        c.setRequestProperty("Content-Type", "application/json")
        if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toString().toByteArray()) } }
        val code = c.responseCode
        if (code == 401) throw Exception("token klopt niet (401)")
        if (code !in 200..299) throw Exception("Home Assistant gaf fout $code")
        return c.inputStream.bufferedReader().use { it.readText() }.also { c.disconnect() }
    }

    /** All entities (for the picker). Calls back on the main thread. */
    fun fetchAll(ctx: Context, done: (List<Entity>?, String?) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            try {
                val list = parse(JSONArray(request(app, "GET", "/api/states")))
                    .filter { it.domain in PICKABLE }.sortedWith(compareBy({ it.domain }, { it.name.lowercase() }))
                main.post { done(list, null) }
            } catch (e: Exception) {
                HubLog.e("huis", "verbinden mislukt: ${e.message}")
                main.post { done(null, e.message ?: e.javaClass.simpleName) }
            }
        }.start()
    }

    private fun parse(a: JSONArray): List<Entity> = (0 until a.length()).map { i ->
        val o = a.getJSONObject(i)
        val attr = o.optJSONObject("attributes") ?: JSONObject()
        Entity(o.optString("entity_id"), attr.optString("friendly_name", o.optString("entity_id")),
            o.optString("state"), attr.optString("unit_of_measurement", ""))
    }

    /** Sends the chosen entities with their state to the watch. */
    fun sendStates(ctx: Context) {
        val app = ctx.applicationContext
        if (!configured(app)) {
            WatchLink.post(JSONObject().put("t", "ha").put("items", JSONArray()).put("err", "Stel Home Assistant in op je telefoon"))
            return
        }
        Thread {
            val out = JSONObject().put("t", "ha")
            try {
                val all = parse(JSONArray(request(app, "GET", "/api/states"))).associateBy { it.id }
                val items = JSONArray()
                chosen(app).forEach { id ->
                    val e = all[id] ?: return@forEach
                    items.put(JSONObject().put("id", id).put("name", e.name.take(30))
                        .put("state", label(e)).put("on", isOn(e)).put("tap", e.domain in TAPPABLE))
                }
                out.put("items", items)
                if (items.length() == 0) out.put("err", "Kies apparaten in WatchHub op je telefoon")
            } catch (e: Exception) {
                out.put("items", JSONArray()).put("err", "Home Assistant: ${e.message}")
            }
            WatchLink.post(out)
        }.start()
    }

    private fun isOn(e: Entity) = e.state in setOf("on", "open", "unlocked", "playing", "home", "cleaning", "heat", "cool")

    private fun label(e: Entity): String = when {
        e.unit.isNotEmpty() -> "${e.state} ${e.unit}"
        else -> when (e.state) {
            "on" -> "aan"; "off" -> "uit"; "open" -> "open"; "closed" -> "dicht"
            "locked" -> "op slot"; "unlocked" -> "open"; "home" -> "thuis"; "not_home" -> "weg"
            "playing" -> "speelt"; "paused" -> "gepauzeerd"; "idle" -> "inactief"; "unavailable" -> "onbereikbaar"
            else -> if (e.domain in setOf("script", "scene", "button", "input_button")) "tik om te starten" else e.state
        }
    }

    /** Tap on the watch: switch, open/close, run. */
    fun tap(ctx: Context, id: String) {
        val app = ctx.applicationContext
        val domain = id.substringBefore('.')
        Thread {
            try {
                val (svcDomain, service) = when (domain) {
                    "script" -> "script" to "turn_on"
                    "scene" -> "scene" to "turn_on"
                    "button" -> "button" to "press"
                    "input_button" -> "input_button" to "press"
                    "media_player" -> "media_player" to "media_play_pause"
                    "vacuum" -> "vacuum" to "start"
                    "lock" -> {
                        val st = JSONObject(request(app, "GET", "/api/states/$id")).optString("state")
                        "lock" to (if (st == "locked") "unlock" else "lock")
                    }
                    else -> domain to "toggle"
                }
                request(app, "POST", "/api/services/$svcDomain/$service", JSONObject().put("entity_id", id))
                HubLog.i("huis", "$id: $svcDomain.$service")
                WatchLink.post(JSONObject().put("t", "hares").put("id", id).put("ok", true))
            } catch (e: Exception) {
                HubLog.e("huis", "$id mislukt: ${e.message}")
                WatchLink.post(JSONObject().put("t", "hares").put("id", id).put("ok", false).put("msg", (e.message ?: "fout").take(40)))
            }
            main.postDelayed({ sendStates(app) }, 800)
        }.start()
    }
}
