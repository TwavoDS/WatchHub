package com.twavods.watchhub

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The WatchHub log: what happened, on the phone and on the watch, kept in a file so it
 * survives crashes and restarts. Shared from the app with one button ("Logboek delen").
 */
object HubLog {
    private const val MAX_LINES = 1500
    private val lines = ArrayDeque<String>()
    private var file: File? = null
    private val fmt = SimpleDateFormat("dd/MM HH:mm:ss", Locale.ROOT)
    private val writer by lazy { HandlerThread("hublog").apply { start() }.let { Handler(it.looper) } }
    private var dirty = false

    @Synchronized
    fun init(ctx: Context) {
        if (file != null) return
        val f = File(ctx.filesDir, "watchhub-log.txt")
        file = f
        try {
            if (f.exists()) f.readLines().takeLast(MAX_LINES).forEach { lines.addLast(it) }
        } catch (_: Exception) {}
    }

    fun i(tag: String, msg: String) = add("I", tag, msg)
    fun w(tag: String, msg: String) = add("W", tag, msg)
    fun e(tag: String, msg: String, t: Throwable? = null) =
        add("E", tag, if (t == null) msg else "$msg: ${t.javaClass.simpleName}: ${t.message}")

    /** A line sent by the watch app. */
    fun add2(tag: String, level: String, msg: String) = add(if (level == "E") "E" else if (level == "W") "W" else "I", tag, msg)

    @Synchronized
    private fun add(level: String, tag: String, msg: String) {
        val line = "${fmt.format(Date())} $level $tag: ${msg.replace('\n', ' ').take(400)}"
        // Don't fill the log with the same line over and over
        if (lines.lastOrNull()?.substringAfter(' ')?.substringAfter(' ') == line.substringAfter(' ').substringAfter(' ')) return
        lines.addLast(line)
        while (lines.size > MAX_LINES) lines.removeFirst()
        if (!dirty) {
            dirty = true
            writer.postDelayed({ flush() }, 2000)
        }
    }

    @Synchronized
    fun flush() {
        dirty = false
        val f = file ?: return
        try { f.writeText(lines.joinToString("\n", postfix = "\n")) } catch (_: Exception) {}
    }

    @Synchronized
    fun text(): String = lines.joinToString("\n")

    @Synchronized
    fun recent(n: Int): List<String> = lines.toList().takeLast(n)

    @Synchronized
    fun errorCount(): Int = lines.count { it.contains(" E ") }

    @Synchronized
    fun clear() { lines.clear(); flush() }

    /** Opens the share sheet with the log and some facts about the phone and versions. */
    fun share(ctx: Context) {
        flush()
        val head = buildString {
            appendLine("WatchHub logboek")
            appendLine("Telefoon-app: ${Updater.phoneVersionName(ctx)} (${Updater.phoneVersion(ctx)})")
            appendLine("Horloge-app: ${if (Updater.watchVersion > 0) Updater.watchVersion else "onbekend"}")
            appendLine("Toestel: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
            appendLine("Verbinding: ${WatchLink.linkState}")
            appendLine()
        }
        val i = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "WatchHub logboek")
            .putExtra(Intent.EXTRA_TEXT, head + text().takeLast(90_000))
        ctx.startActivity(Intent.createChooser(i, "Logboek delen").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Starts the log first thing, and writes a crash into it before the app dies. */
class HubApp : Application() {
    override fun onCreate() {
        super.onCreate()
        HubLog.init(this)
        HubLog.i("app", "gestart, versie ${Updater.phoneVersionName(this)}")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            HubLog.e("CRASH", "in ${t.name}: ${e.stackTraceToString().lines().take(8).joinToString(" | ")}")
            HubLog.flush()
            previous?.uncaughtException(t, e)
        }
    }
}
