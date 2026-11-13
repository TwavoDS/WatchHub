package com.twavods.watchhub

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.ByteArrayOutputStream

/** Size of the frames sent to the round watch screen. */
const val WATCH_SIZE = 240
const val JPEG_QUALITY = 70

/** Settings for the Xiaomi camera mode. */
object Prefs {
    private const val NAME = "watchcam"

    /** Top of the square cut-out, as a fraction of the screen height. */
    fun cropTop(ctx: Context): Float =
        ctx.getSharedPreferences(NAME, 0).getFloat("cropTop", 0.26f)

    fun setCropTop(ctx: Context, v: Float) =
        ctx.getSharedPreferences(NAME, 0).edit().putFloat("cropTop", v).apply()

    /** Vertical position of the Xiaomi camera shutter button, as a fraction of the screen height. */
    fun shutterY(ctx: Context): Float =
        ctx.getSharedPreferences(NAME, 0).getFloat("shutterY", 0.88f)

    fun setShutterY(ctx: Context, v: Float) =
        ctx.getSharedPreferences(NAME, 0).edit().putFloat("shutterY", v).apply()
}

object Screen {
    /** Full screen size in pixels, including system bars, in the current orientation. */
    fun size(ctx: Context): Pair<Int, Int> {
        val wm = ctx.getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(m)
            m.widthPixels to m.heightPixels
        }
    }
}

/** Scales a square bitmap to the watch size and encodes it as JPEG. */
fun squareToJpeg(square: Bitmap): ByteArray {
    val scaled = if (square.width == WATCH_SIZE) square
    else Bitmap.createScaledBitmap(square, WATCH_SIZE, WATCH_SIZE, true)
    val bos = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bos)
    if (scaled !== square) scaled.recycle()
    return bos.toByteArray()
}

/**
 * Follows how the phone is physically held (0, 90, 180 or 270 degrees, clockwise),
 * even when the screen itself is locked in portrait.
 */
class DeviceOrientation(ctx: Context) {
    @Volatile var degrees = 0
        private set

    private val listener = object : android.view.OrientationEventListener(ctx) {
        override fun onOrientationChanged(orientation: Int) {
            if (orientation == ORIENTATION_UNKNOWN) return
            degrees = when (orientation) {
                in 45 until 135 -> 90
                in 135 until 225 -> 180
                in 225 until 315 -> 270
                else -> 0
            }
        }
    }

    /** The matching Surface.ROTATION_* value for CameraX target rotation. */
    val surfaceRotation: Int
        get() = when (degrees) {
            90 -> android.view.Surface.ROTATION_270
            180 -> android.view.Surface.ROTATION_180
            270 -> android.view.Surface.ROTATION_90
            else -> android.view.Surface.ROTATION_0
        }

    fun start() { if (listener.canDetectOrientation()) listener.enable() }
    fun stop() = listener.disable()
}

/**
 * Turns an upright camera picture into the JPEG for the watch.
 *  - fill = true:  centre square, fills the whole round screen
 *  - fill = false: the whole picture, shrunk to fit inside the round screen
 *                  (portrait or landscape), on a black background
 */
fun frameToJpeg(full: Bitmap, fill: Boolean): ByteArray {
    if (fill) {
        val side = minOf(full.width, full.height)
        val square = Bitmap.createBitmap(full, (full.width - side) / 2, (full.height - side) / 2, side, side)
        val out = squareToJpeg(square)
        if (square !== full) square.recycle()
        return out
    }
    val canvasBmp = Bitmap.createBitmap(WATCH_SIZE, WATCH_SIZE, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(canvasBmp)
    canvas.drawColor(android.graphics.Color.BLACK)
    // Largest rectangle with this aspect ratio that fits inside the circle
    val diameter = WATCH_SIZE - 4f
    val scale = diameter / kotlin.math.sqrt((full.width * full.width + full.height * full.height).toFloat())
    val w = full.width * scale
    val h = full.height * scale
    val left = (WATCH_SIZE - w) / 2f
    val top = (WATCH_SIZE - h) / 2f
    val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
    canvas.drawBitmap(full, null, android.graphics.RectF(left, top, left + w, top + h), paint)
    val bos = ByteArrayOutputStream()
    canvasBmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bos)
    canvasBmp.recycle()
    return bos.toByteArray()
}
