package com.twavods.watchhub

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import org.json.JSONObject

/**
 * Music control from the watch: shows what is playing on the phone (any app: Spotify,
 * YouTube Music, …) and sends play/pause/next/previous/volume.
 *
 * Phone -> watch: {"t":"music","title":..,"artist":..,"app":..,"playing":true,"art":"<img key>","vol":0..100}
 *                 {"t":"music","none":true}
 * Watch -> phone: {"cmd":"music"} (send state)  {"cmd":"media","a":"toggle|next|prev|volup|voldown"}
 */
object MediaControl {
    private lateinit var ctx: Context
    private var msm: MediaSessionManager? = null
    private var controller: MediaController? = null
    private val main = Handler(Looper.getMainLooper())
    private var sendPosted = false
    private var lastSent = ""
    private var artKey = ""

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = stateChanged()
        override fun onPlaybackStateChanged(state: PlaybackState?) = stateChanged()
        override fun onSessionDestroyed() { pick() }
    }

    fun start(service: HubNotificationService) {
        ctx = service.applicationContext
        try {
            val m = ctx.getSystemService(MediaSessionManager::class.java)
            msm = m
            val me = ComponentName(ctx, HubNotificationService::class.java)
            m.addOnActiveSessionsChangedListener({ pick() }, me, main)
            pick()
        } catch (e: Exception) {
            HubLog.e("muziek", "kan mediasessies niet lezen", e)
        }
    }

    /** Follows the session that is playing (or else the most recent one). */
    private fun pick() {
        val list = try {
            msm?.getActiveSessions(ComponentName(ctx, HubNotificationService::class.java)).orEmpty()
        } catch (_: Exception) { emptyList() }
        val best = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
        if (best?.sessionToken == controller?.sessionToken) return
        controller?.unregisterCallback(callback)
        controller = best
        best?.registerCallback(callback, main)
        stateChanged()
    }

    private fun stateChanged() {
        if (sendPosted) return
        sendPosted = true
        main.postDelayed({ sendPosted = false; if (HubNotificationService.watchPage == "music") sendState() }, 300)
    }

    fun sendState() {
        if (!::ctx.isInitialized) return
        val c = controller
        val o = JSONObject().put("t", "music")
        if (c == null) {
            o.put("none", true)
        } else {
            val md = c.metadata
            val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
            val artist = (md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)).orEmpty()
            val art: Bitmap? = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
            o.put("title", title.take(60)).put("artist", artist.take(60))
                .put("app", HubNotificationService.instance?.appLabel(c.packageName) ?: c.packageName)
                .put("playing", playing)
                .put("vol", volumePct())
            if (art != null) {
                val key = "art" + Integer.toHexString("$title|$artist".hashCode())
                if (key != artKey) {
                    artKey = key
                    val copy = art.copy(Bitmap.Config.ARGB_8888, false)
                    HubNotificationService.instance?.offerImage(key) { TextImage.picture(copy) }
                }
                o.put("art", key)
            }
        }
        val s = o.toString()
        if (s == lastSent && HubNotificationService.watchPage != "music") return
        lastSent = s
        WatchLink.post(o)
    }

    private fun volumePct(): Int {
        val am = ctx.getSystemService(AudioManager::class.java)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
    }

    fun command(a: String) {
        val t = controller?.transportControls
        val am = ctx.getSystemService(AudioManager::class.java)
        when (a) {
            "toggle" -> {
                if (controller?.playbackState?.state == PlaybackState.STATE_PLAYING) t?.pause() else t?.play()
                if (t == null) HubLog.w("muziek", "geen muziek-app actief")
            }
            "next" -> t?.skipToNext()
            "prev" -> t?.skipToPrevious()
            "volup" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
            "voldown" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
        }
        main.postDelayed({ sendState() }, 400)
    }
}
