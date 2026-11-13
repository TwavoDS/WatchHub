package com.twavods.watchhub

import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity

/** Start screen of WatchHub: status and the way into camera, navigation and notifications. */
class MainActivity : ComponentActivity() {

    private lateinit var status: TextView
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WatchLink.init(this)
        setContentView(buildUi())
        if (!WatchLink.permissionOk) WatchLink.connect()
        tick()
    }

    private fun section(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(0xFF4FC3F7.toInt())
        textSize = 18f
        setPadding(0, 36, 0, 8)
    }

    private fun note(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(0xFFAAAAAA.toInt())
        textSize = 13f
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun buildUi(): View {
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(48, 96, 48, 64)
            addView(TextView(this@MainActivity).apply {
                text = "WatchHub"
                setTextColor(0xFF4FC3F7.toInt())
                textSize = 30f
            })
            addView(status)
            addView(button("Verbinden met horloge") { WatchLink.connect() })
            addView(button("Toegang tot meldingen") {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            })

            addView(section("Camera"))
            addView(button("Camera openen") { startActivity(Intent(this@MainActivity, CameraActivity::class.java)) })

            addView(section("Navigatie"))
            addView(note("Start navigatie in Google Maps: WatchHub opent vanzelf op je horloge met pijl, " +
                "afstand, straat en aankomsttijd, en trilt vlak voor een afslag."))

            addView(section("Meldingen"))
            addView(note("Kies per app of WatchHub de meldingen toont, met welk trilpatroon, kleur en herhaling. " +
                "Zet voor die apps de meldingen in Mi Fitness uit, anders krijg je ze dubbel."))
            addView(button("Meldingen per app") { startActivity(Intent(this@MainActivity, AppsActivity::class.java)) })
            addView(button("Algemene instellingen") { showGeneralSettings() })
            addView(button("Mi Fitness openen (meldingen daar uitzetten)") {
                packageManager.getLaunchIntentForPackage("com.xiaomi.wearable")?.let { startActivity(it) }
            })
        }
        return ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(root)
        }
    }

    private fun hasNotificationAccess(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        val me = ComponentName(this, HubNotificationService::class.java).flattenToString()
        return flat.split(":").any { it == me }
    }

    private fun fmt(min: Int) = if (min < 0) "uit" else String.format("%02d:%02d", min / 60, min % 60)

    private fun showGeneralSettings() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val screenOn = CheckBox(this).apply {
            text = "Niet doorsturen als mijn telefoonscherm aan staat"
            isChecked = NotifRules.skipWhenScreenOn(this@MainActivity)
        }
        var start = NotifRules.quietStart(this)
        var end = NotifRules.quietEnd(this)
        val quietLabel = TextView(this).apply { textSize = 15f }
        fun showQuiet() {
            quietLabel.text = if (start < 0) "Stille uren: uit" else "Stille uren: ${fmt(start)} – ${fmt(end)}\n(meldingen komen wel binnen, maar zonder trillen)"
        }
        showQuiet()
        val setQuiet = button("Stille uren instellen") {
            TimePickerDialog(this, { _, h1, m1 ->
                TimePickerDialog(this, { _, h2, m2 ->
                    start = h1 * 60 + m1
                    end = h2 * 60 + m2
                    showQuiet()
                }, 7, 0, true).apply { setTitle("Einde stille uren") }.show()
            }, 22, 0, true).apply { setTitle("Begin stille uren") }.show()
        }
        val quietOff = button("Stille uren uitzetten") { start = -1; end = -1; showQuiet() }
        box.addView(screenOn)
        box.addView(quietLabel)
        box.addView(setQuiet)
        box.addView(quietOff)
        AlertDialog.Builder(this)
            .setTitle("Algemene instellingen")
            .setView(box)
            .setPositiveButton("Opslaan") { _, _ ->
                NotifRules.setSkipWhenScreenOn(this, screenOn.isChecked)
                NotifRules.setQuiet(this, start, end)
            }
            .setNegativeButton("Annuleren", null)
            .show()
    }

    private fun tick() {
        val nav = NavHandler.lastNav
        status.text = buildString {
            append("Horloge: ").append(WatchLink.linkState)
            append("\nMeldingstoegang: ").append(if (hasNotificationAccess()) "✓" else "nog niet gegeven")
            if (hasNotificationAccess() && !HubNotificationService.connected) append(" (start nog)")
            append("\nApps naar WatchHub: ").append(NotifRules.enabledPackages(this@MainActivity).size)
            append("  ·  doorgestuurd: ").append(HubNotificationService.forwarded)
            if (HubNotificationService.lastInfo.isNotEmpty()) append("\nLaatste melding: ").append(HubNotificationService.lastInfo)
            append("\nNavigatie: ")
            append(if (nav == null) "niet actief" else "${nav.dist} · ${nav.instr}")
            if (NotifRules.isQuietNow(this@MainActivity)) append("\nStille uren actief")
            if (WatchLink.lastLaunch.isNotEmpty()) append("\nHorloge-app openen: ").append(WatchLink.lastLaunch)
            if (WatchLink.lastError.isNotEmpty()) append("\nFout: ").append(WatchLink.lastError)
        }
        main.postDelayed({ tick() }, 1000)
    }
}
