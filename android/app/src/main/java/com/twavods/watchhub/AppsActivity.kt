package com.twavods.watchhub

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import java.util.concurrent.Executors

/** List of apps; tap one to choose how its notifications look and feel on the watch. */
class AppsActivity : ComponentActivity() {

    private data class AppEntry(val pkg: String, val label: String, val recent: Boolean)

    private var all = listOf<AppEntry>()
    private var shown = listOf<AppEntry>()
    private lateinit var adapter: BaseAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val search = EditText(this).apply {
            hint = "Zoek een app…"
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF888888.toInt())
        }
        val help = TextView(this).apply {
            text = "Apps met recente meldingen staan bovenaan. Tik op een app om ze in te stellen."
            setTextColor(0xFFAAAAAA.toInt())
            textSize = 13f
            setPadding(0, 8, 0, 8)
        }
        val list = ListView(this)
        adapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(p: Int) = shown[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, convert: View?, parent: ViewGroup?): View {
                val e = shown[p]
                val row = (convert as? LinearLayout) ?: LinearLayout(this@AppsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(16, 20, 16, 20)
                    addView(ImageView(this@AppsActivity), LinearLayout.LayoutParams(96, 96))
                    addView(TextView(this@AppsActivity).apply {
                        setTextColor(Color.WHITE)
                        textSize = 16f
                        setPadding(32, 0, 0, 0)
                    })
                }
                val icon = row.getChildAt(0) as ImageView
                val text = row.getChildAt(1) as TextView
                try { icon.setImageDrawable(packageManager.getApplicationIcon(e.pkg)) } catch (_: Exception) {}
                val rule = NotifRules.get(this@AppsActivity, e.pkg)
                text.text = if (rule.enabled)
                    "${e.label}\n✓ WatchHub · ${NotifRules.PATTERNS[rule.vib] ?: rule.vib}" +
                        (if (rule.repeatMin > 0) " · herhalen" else "")
                else "${e.label}\nvia Mi Fitness"
                return row
            }
        }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, p, _ -> editRule(shown[p]) }
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = filter(s?.toString().orEmpty())
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(32, 96, 32, 0)
            addView(search)
            addView(help)
            addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        })
        loadApps()
    }

    private fun loadApps() {
        Executors.newSingleThreadExecutor().execute {
            val recent = NotifRules.seenPackages(this)
            val enabled = NotifRules.enabledPackages(this)
            val pm = packageManager
            val launchable = pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
            ).map { it.activityInfo.packageName }.toSet()
            val pkgs = (recent + enabled + launchable).distinct().filter { it != packageName }
            val entries = pkgs.mapNotNull { pkg ->
                try {
                    val info: ApplicationInfo = pm.getApplicationInfo(pkg, 0)
                    AppEntry(pkg, pm.getApplicationLabel(info).toString(), pkg in recent || pkg in enabled)
                } catch (_: Exception) { null }
            }.sortedWith(compareBy<AppEntry>({ !it.recent }, { it.label.lowercase() }))
            runOnUiThread {
                all = entries
                filter("")
            }
        }
    }

    private fun filter(q: String) {
        val query = q.trim().lowercase()
        shown = if (query.isEmpty()) all else all.filter { it.label.lowercase().contains(query) }
        adapter.notifyDataSetChanged()
    }

    private fun spinner(options: List<String>, selected: Int) = Spinner(this).apply {
        adapter = ArrayAdapter(this@AppsActivity, android.R.layout.simple_spinner_dropdown_item, options)
        setSelection(selected.coerceAtLeast(0))
    }

    private fun label(t: String) = TextView(this).apply {
        text = t
        textSize = 13f
        setPadding(0, 20, 0, 0)
    }

    private fun editRule(e: AppEntry) {
        val rule = NotifRules.get(this, e.pkg)
        val patterns = NotifRules.PATTERNS.entries.toList()
        val colors = NotifRules.COLORS.entries.toList()
        val repeats = NotifRules.REPEATS.entries.toList()

        val enabled = Switch(this).apply { text = "Meldingen via WatchHub tonen"; isChecked = rule.enabled }
        val vib = spinner(patterns.map { it.value }, patterns.indexOfFirst { it.key == rule.vib })
        val color = spinner(colors.map { it.value }, colors.indexOfFirst { it.key.equals(rule.color, true) })
        val repeat = spinner(repeats.map { it.value }, repeats.indexOfFirst { it.key == rule.repeatMin })
        val only = EditText(this).apply { setText(rule.onlyWords); hint = "bv. mama, dringend" }
        val block = EditText(this).apply { setText(rule.blockWords); hint = "bv. reclame, aanbieding" }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
            addView(enabled)
            addView(label("Trilpatroon"))
            addView(vib)
            addView(label("Kleur op het horloge"))
            addView(color)
            addView(label("Herhalen tot je hem wegtikt op het horloge"))
            addView(repeat)
            addView(label("Alleen doorsturen als de tekst één van deze woorden bevat (leeg = alles)"))
            addView(only)
            addView(label("Nooit doorsturen als de tekst één van deze woorden bevat"))
            addView(block)
        }

        fun current() = AppRule(
            enabled = enabled.isChecked,
            vib = patterns[vib.selectedItemPosition].key,
            color = colors[color.selectedItemPosition].key,
            repeatMin = repeats[repeat.selectedItemPosition].key,
            onlyWords = only.text.toString(),
            blockWords = block.text.toString(),
        )

        AlertDialog.Builder(this)
            .setTitle(e.label)
            .setView(android.widget.ScrollView(this).apply { addView(box) })
            .setPositiveButton("Opslaan") { _, _ ->
                NotifRules.set(this, e.pkg, current())
                adapter.notifyDataSetChanged()
            }
            .setNeutralButton("Test op horloge") { _, _ ->
                NotifRules.set(this, e.pkg, current())
                adapter.notifyDataSetChanged()
                val svc = HubNotificationService.instance
                if (svc == null) {
                    AlertDialog.Builder(this).setMessage("Geef WatchHub eerst toegang tot meldingen.")
                        .setPositiveButton("OK", null).show()
                } else svc.sendTest(e.pkg)
            }
            .setNegativeButton("Annuleren", null)
            .show()
    }
}
