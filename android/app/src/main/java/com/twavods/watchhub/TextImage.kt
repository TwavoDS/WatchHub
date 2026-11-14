package com.twavods.watchhub

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * Draws notification text on the phone as a PNG for the watch. The watch font has
 * no emoji, so text with emoji (or other symbols the watch can't show) is sent as an image.
 */
object TextImage {

    class Result(val base64: String, val width: Int, val height: Int, val format: String = "png")

    /** A photo from a notification, scaled to [width] (height follows, at most [maxH]). */
    fun photo(bmp: android.graphics.Bitmap, width: Int = 320, maxH: Int = 420): Result {
        val h = (bmp.height.toFloat() * width / bmp.width).toInt().coerceIn(40, maxH)
        val scaled = android.graphics.Bitmap.createScaledBitmap(bmp, width, h, true)
        val bos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 72, bos)
        if (scaled !== bmp) scaled.recycle()
        return Result(Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP), width, h, "jpg")
    }

    /** A picture (e.g. album art) as a small JPEG for the watch. */
    fun picture(bmp: android.graphics.Bitmap, size: Int = 180): Result {
        val scaled = android.graphics.Bitmap.createScaledBitmap(bmp, size, size, true)
        val bos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 75, bos)
        if (scaled !== bmp) scaled.recycle()
        return Result(Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP), size, size, "jpg")
    }

    /** True when the text contains characters the watch font probably can't draw. */
    fun needsImage(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (cp > 0xFFFF) return true                 // emoji and other supplementary characters
            if (cp in 0x2190..0x2BFF) return true         // arrows, symbols, dingbats (❤ ✔ ★ …)
            if (cp == 0xFE0F || cp == 0x200D) return true // emoji modifiers
            i += Character.charCount(cp)
        }
        return false
    }

    /** Title (bold, white) above the text (grey), centred. */
    fun body(title: String, text: String, titleSize: Float = 28f, width: Int = 340, maxHeight: Int = 1400): Result {
        val sb = SpannableStringBuilder()
        if (title.isNotEmpty()) {
            sb.append(title)
            sb.setSpan(StyleSpan(Typeface.BOLD), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(AbsoluteSizeSpan(titleSize.toInt()), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(Color.WHITE), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (text.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append("\n")
            val start = sb.length
            sb.append(text)
            sb.setSpan(AbsoluteSizeSpan(25), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(Color.rgb(0xDD, 0xDD, 0xDD)), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return render(sb, width, maxHeight, 25f, Color.BLACK)
    }

    /** Colour of the quick-reply buttons on the watch (.reply in notif.ux). */
    val REPLY_BG = Color.rgb(0x26, 0x32, 0x38)

    /** One short line, e.g. a quick reply with an emoji, on the button colour so it blends in. */
    fun line(text: String, width: Int = 280, size: Float = 22f): Result {
        val sb = SpannableStringBuilder(text)
        sb.setSpan(ForegroundColorSpan(Color.WHITE), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(StyleSpan(Typeface.BOLD), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return render(sb, width, 48, size, REPLY_BG)
    }

    private fun render(text: CharSequence, width: Int, maxHeight: Int, size: Float, bg: Int): Result {
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            color = Color.WHITE
        }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(4f, 1f)
            .setIncludePad(false)
            .build()
        val h = layout.height.coerceIn(1, maxHeight)
        val bmp = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(bg)
        layout.draw(c)
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
        bmp.recycle()
        return Result(Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP), width, h)
    }
}
