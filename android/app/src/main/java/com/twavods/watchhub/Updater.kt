package com.twavods.watchhub

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Updates from the newest GitHub build (ci-logs branch, written by the CI workflow):
 *  - phone app: downloads the APK and opens the Android installer (you tap "Installeren")
 *  - watch app: downloads the RPK, saves it in Downloads and hands it to Mi Fitness (mod)
 */
object Updater {
    // Every CI build puts its files on the ci-logs branch
    private const val BASE = "https://raw.githubusercontent.com/TwavoDS/WatchHub/ci-logs/"
    const val WEAR_PKG = "com.xiaomi.wearable"

    @Volatile var status = ""
    @Volatile var busy = false
    @Volatile var latestPhone = 0
    @Volatile var latestPhoneName = ""
    @Volatile var latestWatch = 0
    @Volatile var latestWatchName = ""
    @Volatile var checked = false
    /** Version of the app on the watch, as it reported in its "hello" (0 = unknown). */
    @Volatile var watchVersion = 0

    private val main = Handler(Looper.getMainLooper())

    fun phoneVersion(ctx: Context): Int = try {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else @Suppress("DEPRECATION") pi.versionCode
    } catch (_: Exception) { 0 }

    fun phoneVersionName(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
    } catch (_: Exception) { "" }

    fun phoneUpdate(ctx: Context) = checked && latestPhone > phoneVersion(ctx)
    fun watchUpdate() = checked && latestWatch > 0 && (watchVersion == 0 || latestWatch > watchVersion)

    private fun bg(work: () -> Unit) {
        if (busy) return
        busy = true
        Thread {
            try { work() } catch (e: Exception) { status = "Mislukt: ${e.message ?: e.javaClass.simpleName}" }
            busy = false
        }.start()
    }

    private fun download(name: String, target: File) {
        var url = URL(BASE + name)
        var conn: HttpURLConnection
        var hops = 0
        while (true) {
            conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            val code = conn.responseCode
            if (code in 300..399 && hops < 5) {
                url = URL(url, conn.getHeaderField("Location")); conn.disconnect(); hops++; continue
            }
            if (code != 200) throw Exception("HTTP $code bij $name")
            break
        }
        target.parentFile?.mkdirs()
        conn.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
        conn.disconnect()
    }

    fun check(ctx: Context) {
        val app = ctx.applicationContext
        bg {
            status = "Zoeken naar updates…"
            val st = File(app.cacheDir, "updates/STATUS.txt")
            download("STATUS.txt", st)
            if (!st.readText().contains("apk=success rpk=success")) {
                checked = false
                status = "Er wordt net een nieuwe versie gebouwd — probeer straks opnieuw"
                return@bg
            }
            val f = File(app.cacheDir, "updates/version.json")
            download("version.json", f)
            val j = JSONObject(f.readText())
            latestPhone = j.optInt("phone")
            latestPhoneName = j.optString("phoneName")
            latestWatch = j.optInt("watch")
            latestWatchName = j.optString("watchName")
            checked = true
            status = when {
                phoneUpdate(app) && watchUpdate() -> "Nieuwe versies voor telefoon en horloge"
                phoneUpdate(app) -> "Nieuwe versie voor de telefoon: $latestPhoneName"
                watchUpdate() -> "Nieuwe versie voor het horloge: $latestWatchName"
                else -> "Alles is up-to-date"
            }
        }
    }

    /** Android must allow WatchHub to install apps; otherwise opens that setting. */
    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(ctx: Context) {
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun updatePhone(ctx: Context) {
        val app = ctx.applicationContext
        if (!canInstall(app)) {
            status = "Geef WatchHub eerst toestemming om apps te installeren, en tik dan opnieuw"
            openInstallPermission(ctx)
            return
        }
        bg {
            status = "Telefoonapp downloaden…"
            val f = File(app.cacheDir, "updates/WatchHub-phone.apk")
            download("WatchHub-phone.apk", f)
            status = "Tik op Installeren"
            val uri = FileProvider.getUriForFile(app, app.packageName + ".files", f)
            val i = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            main.post { app.startActivity(i) }
        }
    }

    fun updateWatch(ctx: Context) {
        val app = ctx.applicationContext
        bg {
            status = "Horloge-app downloaden…"
            val f = File(app.cacheDir, "updates/WatchHub-watch.rpk")
            download("WatchHub-watch.rpk", f)
            val saved = saveToDownloads(app, f)
            val uri = FileProvider.getUriForFile(app, app.packageName + ".files", f)
            main.post {
                val opened = handToMiFitness(app, uri)
                status = when {
                    opened -> "Doorgegeven aan Mi Fitness — installeer het daar." +
                        (if (saved) " (Ook bewaard in Downloads: WatchHub-watch.rpk)" else "")
                    saved -> "Bewaard in Downloads als WatchHub-watch.rpk — installeer het via Mi Fitness (mod)"
                    else -> "Mi Fitness nam het bestand niet aan"
                }
            }
        }
    }

    private fun handToMiFitness(ctx: Context, uri: Uri): Boolean {
        val tries = listOf(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/octet-stream").setPackage(WEAR_PKG),
            Intent(Intent.ACTION_SEND).setType("application/octet-stream").putExtra(Intent.EXTRA_STREAM, uri).setPackage(WEAR_PKG),
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "*/*").setPackage(WEAR_PKG)
        )
        for (i in tries) {
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            if (i.resolveActivity(ctx.packageManager) != null) {
                try { ctx.startActivity(i); return true } catch (_: Exception) {}
            }
        }
        // Not accepted directly: let the user pick (Mi Fitness, file manager, …)
        return try {
            val send = Intent(Intent.ACTION_SEND).setType("application/octet-stream")
                .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ctx.startActivity(Intent.createChooser(send, "Horloge-app openen met").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: Exception) { false }
    }

    private fun saveToDownloads(ctx: Context, f: File): Boolean = try {
        if (Build.VERSION.SDK_INT >= 29) {
            val res = ctx.contentResolver
            try {
                res.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    "${MediaStore.MediaColumns.DISPLAY_NAME}=?", arrayOf(f.name))
            } catch (_: Exception) {}
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = res.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) { res.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }; true } else false
        } else false
    } catch (_: Exception) { false }
}
