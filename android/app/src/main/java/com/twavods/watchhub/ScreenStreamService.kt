package com.twavods.watchhub

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.util.concurrent.atomic.AtomicReference

/**
 * "Xiaomi camera" mode: records the screen while the Xiaomi camera app is open,
 * cuts out the viewfinder and streams it to the watch. The shutter is pressed
 * through [CameraTapService].
 */
class ScreenStreamService : Service(), WatchLink.Source {

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_TARGET = "target"
        const val TARGET_CAMERA = "camera"
        const val TARGET_MAP = "map"
        const val ACTION_STOP = "com.twavods.watchhub.STOP"
        private const val CHANNEL = "watchcam"
        @Volatile var running = false
            private set
        /** What is being recorded: the Xiaomi camera or the Google Maps map. */
        @Volatile var target = TARGET_CAMERA
            private set
        fun runningFor(t: String) = running && target == t
        @Volatile var lastError = ""
            set(v) { if (v.isNotEmpty() && v != field) HubLog.e("scherm", v); field = v }
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private val thread = HandlerThread("WatchHubCapture").apply { start() }
    private val handler = Handler(thread.looper)
    private val latest = AtomicReference<ByteArray?>(null)
    @Volatile private var lastFrameMs = 0L
    private lateinit var orientation: DeviceOrientation

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (projection == null) target = intent?.getStringExtra(EXTRA_TARGET) ?: TARGET_CAMERA
        startInForeground()
        if (!::orientation.isInitialized) orientation = DeviceOrientation(this)
        orientation.start()

        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA)
        if (data == null || projection != null) {
            if (data == null) stopSelf()
            return START_NOT_STICKY
        }

        try {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val proj = mpm.getMediaProjection(code, data)
            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
            }, handler)
            projection = proj

            val (sw, sh) = Screen.size(this)
            // Half resolution is plenty for a 240 px preview and saves battery
            val w = sw / 2
            val h = sh / 2
            val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            r.setOnImageAvailableListener({ rd -> onImage(rd) }, handler)
            reader = r
            display = proj.createVirtualDisplay(
                "WatchHub", w, h, resources.displayMetrics.densityDpi / 2,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler
            )
        } catch (e: Exception) {
            lastError = "schermopname: ${e.message}"
            stopSelf()
            return START_NOT_STICKY
        }

        running = true
        lastError = ""
        if (target == TARGET_MAP) {
            WatchLink.mapSource = this
            bringMapsToFront()
        } else {
            WatchLink.source = this
            WatchLink.kick()
            launchXiaomiCamera()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        if (::orientation.isInitialized) orientation.stop()
        if (WatchLink.source === this) WatchLink.source = null
        if (WatchLink.mapSource === this) WatchLink.mapSource = null
        try { display?.release() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        display = null
        reader = null
        projection = null
        thread.quitSafely()
        super.onDestroy()
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "WatchHub", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ScreenStreamService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, CameraActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(
                if (target == TARGET_MAP) "WatchHub stuurt de kaart naar je horloge"
                else "WatchHub stuurt de Xiaomi-camera naar je horloge"
            )
            .setContentText("Tik om WatchHub te openen")
            .setContentIntent(open)
            .addAction(0, "Stoppen", stop)
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
        ServiceCompat.startForeground(this, 1, n, type)
    }

    private fun bringMapsToFront() {
        val launch = packageManager.getLaunchIntentForPackage(NavHandler.MAPS) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        try { startActivity(launch) } catch (_: Exception) {}
    }

    private fun launchXiaomiCamera() {
        val launch = packageManager.getLaunchIntentForPackage("com.android.camera")
            ?: Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { startActivity(launch) } catch (e: Exception) { lastError = "camera openen: ${e.message}" }
    }

    /** Cuts the viewfinder square out of the screen image. */
    private fun onImage(rd: ImageReader) {
        val img = rd.acquireLatestImage() ?: return
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastFrameMs < (if (target == TARGET_MAP) 500 else 50)) return
            lastFrameMs = now

            val plane = img.planes[0]
            val pixelStride = plane.pixelStride
            val rowPadding = plane.rowStride - pixelStride * img.width
            val full = Bitmap.createBitmap(
                img.width + rowPadding / pixelStride, img.height, Bitmap.Config.ARGB_8888
            )
            full.copyPixelsFromBuffer(plane.buffer)

            val side = minOf(img.width, img.height)
            val left = (img.width - side) / 2
            if (target == TARGET_MAP) {
                // The map around your position: a square at the chosen height of the screen
                val top = (Prefs.mapTop(this) * img.height).toInt().coerceIn(0, img.height - side)
                val square = Bitmap.createBitmap(full, left, top, side, side)
                latest.set(frameToJpeg(square, true))
                if (square !== full) square.recycle()
                full.recycle()
                return
            }
            val squareTop = (Prefs.cropTop(this) * img.height).toInt().coerceIn(0, img.height - side)
            // The Xiaomi viewfinder is 3:4, centred on the square cut-out
            val (top, height) = if (WatchLink.fillMode) squareTop to side else {
                val vfH = minOf(img.height, side * 4 / 3)
                (squareTop + side / 2 - vfH / 2).coerceIn(0, img.height - vfH) to vfH
            }
            // The Xiaomi camera stays in portrait; turn the picture so it is upright
            // the way the phone is actually held
            val turn = android.graphics.Matrix().apply { postRotate(orientation.degrees.toFloat()) }
            val part = Bitmap.createBitmap(full, left, top, side, height, turn, true)
            latest.set(frameToJpeg(part, WatchLink.fillMode))
            if (part !== full) part.recycle()
            full.recycle()
        } catch (e: Exception) {
            lastError = "beeld: ${e.message}"
        } finally {
            img.close()
        }
    }

    // ---- WatchLink.Source ----------------------------------------------------

    override fun latestJpeg(): ByteArray? = latest.get()

    override fun shutter(done: (String?) -> Unit) {
        val tap = CameraTapService.instance
        if (tap == null) {
            done("zet de WatchHub-sluiter aan bij Toegankelijkheid")
            return
        }
        if (tap.tapShutter()) handler.postDelayed({ done(null) }, 400)
        else done("sluiterknop niet gevonden")
    }

    override fun switchCamera() {
        CameraTapService.instance?.tapSwitch()
    }
}
