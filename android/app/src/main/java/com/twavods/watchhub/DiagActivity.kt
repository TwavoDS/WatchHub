package com.twavods.watchhub

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.twavods.watchhub.ui.Hint
import com.twavods.watchhub.ui.HubBlue
import com.twavods.watchhub.ui.HubCard
import com.twavods.watchhub.ui.HubOrange
import com.twavods.watchhub.ui.HubTheme
import com.twavods.watchhub.ui.StatusLine
import org.json.JSONObject

/** Test list (one button per feature, tick when it works) and the log. */
class DiagActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WatchLink.init(this)
        setContent { HubTheme { DiagScreen(onBack = { finish() }) } }
    }
}

private class TestItem(val key: String, val title: String, val what: String, val button: String?, val run: (Context) -> Unit)

private fun tests(): List<TestItem> = listOf(
    TestItem("link", "Verbinding met horloge", "Bovenaan staat een groene status.", "Opnieuw verbinden") { WatchLink.connect() },
    TestItem("open", "WatchHub openen op horloge", "WatchHub springt open op het horloge.", "Open op horloge") { WatchLink.openOnWatch() },
    TestItem("notif", "Melding op WatchHub-scherm", "Springt open met kleur, icoon, titel, tekst en emoji; trilt; blijft de ingestelde tijd aan.",
        "Stuur testmelding") { ctx -> HubNotificationService.instance?.sendTest(ctx.packageName) ?: noService() },
    TestItem("popup", "Gewone horlogemelding", "Een normale melding van het horloge zelf verschijnt.", "Stuur") {
        WatchLink.notify("WatchHub test", "Gewone horlogemelding 👍"); HubLog.i("test", "gewone melding verstuurd")
    },
    TestItem("call", "Oproep", "Belscherm met Opnemen (groen) en Weigeren (rood), trilt lang.", "Testoproep") {
        HubNotificationService.instance?.sendTestCall() ?: noService()
    },
    TestItem("reply", "Snel antwoorden", "Stuur jezelf een WhatsApp- of sms-bericht vanaf een ander toestel en antwoord vanaf je horloge.", null) {},
    TestItem("nav", "Navigatie", "Start een route in Google Maps: het navigatiescherm opent vanzelf met pijl, afstand en straat.",
        "Open navigatiescherm") { WatchLink.gotoOnWatch(JSONObject().put("t", "goto").put("p", "nav"), appWasClosed = true) },
    TestItem("camera", "Camera", "Live beeld op het horloge, sluiter maakt een foto.", "Open camera") { ctx ->
        ctx.startActivity(Intent(ctx, CameraActivity::class.java))
    },
    TestItem("find", "Zoek telefoon", "Tik op het horloge op Zoek telefoon: de telefoon gaat luid af. Of test hier 3 seconden.", "Laat afgaan") { ctx ->
        FindPhone.start(ctx); android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ FindPhone.stop() }, 3000)
    },
    TestItem("music", "Muziek", "Speel muziek af op je telefoon: titel, hoes en knoppen werken op het horloge.", "Open muziekscherm") {
        WatchLink.gotoOnWatch(JSONObject().put("t", "goto").put("p", "music"), appWasClosed = true)
    },
    TestItem("actions", "Acties", "Je tegels staan op het horloge; een tik voert ze uit (maak er eerst een bij Muziek, acties en herinneringen).",
        "Open acties") { WatchLink.gotoOnWatch(JSONObject().put("t", "goto").put("p", "actions"), appWasClosed = true) },
    TestItem("reminder", "Herinnering", "Gele herinnering met trillen, blijft staan tot je ze wegtikt.", "Test nu") {
        HubNotificationService.instance?.sendReminder("Dit is een testherinnering", "LL") ?: noService()
    },
    TestItem("battery", "Batterijmelding", "Gewone horlogemelding over je telefoonbatterij.", "Test nu") {
        WatchLink.notify("🔋 Test", "Zo ziet de batterijmelding eruit"); HubLog.i("test", "batterijmelding verstuurd")
    },
    TestItem("update", "Updates", "Zoekt naar een nieuwe versie zonder fout.", "Zoek updates") { ctx -> Updater.check(ctx) },
)

private fun noService() {
    HubLog.e("test", "meldingsdienst draait niet — geef WatchHub toegang tot meldingen")
}

private fun prefs(ctx: Context) = ctx.getSharedPreferences("watchhub_tests", Context.MODE_PRIVATE)

@Composable
private fun DiagScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val tick = rememberTicker()
    var showAll by remember { mutableStateOf(false) }
    val version = Updater.phoneVersion(ctx)
    // Ticks are kept per app version: a new version starts with an empty list
    val checks = remember { mutableStateOf(tests().associate { it.key to prefs(ctx).getBoolean("$version:${it.key}", false) }) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Filled.Close, contentDescription = "Sluiten") }
                Text("Testen en logboek", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }

            val done = checks.value.count { it.value }
            HubCard("Testlijst ($done/${checks.value.size})", Icons.Filled.Build, HubBlue) {
                Hint("Versie ${Updater.phoneVersionName(ctx)}. Test elke functie en vink aan wat werkt. " +
                    "Werkt iets niet: tik op Logboek delen en stuur het door.")
                StatusLine(tick >= 0 && WatchLink.permissionOk, WatchLink.linkState)
                tests().forEach { t ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Checkbox(checked = checks.value[t.key] == true, onCheckedChange = { v ->
                            prefs(ctx).edit().putBoolean("$version:${t.key}", v).apply()
                            checks.value = checks.value + (t.key to v)
                            HubLog.i("test", "${t.title}: ${if (v) "werkt" else "werkt niet"}")
                        })
                        Column(Modifier.weight(1f)) {
                            Text(t.title, style = MaterialTheme.typography.bodyLarge)
                            Hint(t.what)
                        }
                    }
                    if (t.button != null) {
                        FilledTonalButton(onClick = { t.run(ctx) }, modifier = Modifier.padding(start = 48.dp)) { Text(t.button) }
                    }
                }
            }

            val errors = HubLog.errorCount()
            HubCard("Logboek", Icons.Filled.List, if (errors > 0) HubOrange else HubBlue) {
                Hint(if (errors > 0) "$errors foutmelding(en) in het logboek." else "Geen fouten in het logboek.")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(onClick = { HubLog.share(ctx) }) { Text("Logboek delen") }
                    OutlinedButton(onClick = { HubLog.clear(); HubLog.i("app", "logboek gewist") }) { Text("Wissen") }
                }
                val lines = if (tick >= 0) HubLog.recent(if (showAll) 400 else 40).reversed() else emptyList()
                Column(Modifier.horizontalScroll(rememberScrollState())) {
                    lines.forEach { l ->
                        Text(
                            l, fontFamily = FontFamily.Monospace, fontSize = 11.sp, softWrap = false,
                            color = if (l.contains(" E ")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Minder tonen" else "Meer tonen") }
            }
        }
    }
}
