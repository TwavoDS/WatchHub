package com.twavods.watchhub

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import android.media.projection.MediaProjectionManager
import androidx.compose.material3.Slider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.core.content.ContextCompat
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.twavods.watchhub.ui.Hint
import com.twavods.watchhub.ui.HubBlue
import com.twavods.watchhub.ui.HubCard
import com.twavods.watchhub.ui.HubGreen
import com.twavods.watchhub.ui.HubOrange
import com.twavods.watchhub.ui.HubRed
import com.twavods.watchhub.ui.HubTheme
import com.twavods.watchhub.ui.HubYellow
import com.twavods.watchhub.ui.StatusLine
import kotlinx.coroutines.delay

/** Start screen of WatchHub. */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WatchLink.init(this)
        if (!WatchLink.permissionOk) WatchLink.connect()
        setContent { HubTheme { HomeScreen() } }
    }
}

fun hasNotificationAccess(ctx: Context): Boolean {
    val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
    val me = ComponentName(ctx, HubNotificationService::class.java).flattenToString()
    return flat.split(":").any { it == me }
}

/** Re-reads plain (non-Compose) state once a second. */
@Composable
fun rememberTicker(): Int {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }
    return tick
}

/** Stream a piece of the Google Maps map to the watch (screen recording while Maps is open). */
@Composable
private fun MapControls() {
    val ctx = LocalContext.current
    val running = ScreenStreamService.runningFor(ScreenStreamService.TARGET_MAP)
    var mapTop by remember { mutableFloatStateOf(Prefs.mapTop(ctx)) }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val data = res.data
        if (res.resultCode == android.app.Activity.RESULT_OK && data != null) {
            val i = Intent(ctx, ScreenStreamService::class.java)
                .putExtra(ScreenStreamService.EXTRA_CODE, res.resultCode)
                .putExtra(ScreenStreamService.EXTRA_DATA, data)
                .putExtra(ScreenStreamService.EXTRA_TARGET, ScreenStreamService.TARGET_MAP)
            ContextCompat.startForegroundService(ctx, i)
        }
    }
    Hint("Kaart op je horloge: WatchHub neemt het scherm van Google Maps op en stuurt het stuk rond je positie door " +
        "(ongeveer 1 beeld per seconde). Google Maps moet daarvoor open staan op je telefoon.")
    if (running) {
        OutlinedButton(onClick = {
            ctx.startService(Intent(ctx, ScreenStreamService::class.java).setAction(ScreenStreamService.ACTION_STOP))
        }) { Text("Kaart stoppen") }
        Hint("Uitsnede (hoogte op het scherm): ${(mapTop * 100).toInt()}%")
        Slider(value = mapTop, onValueChange = { mapTop = it; Prefs.setMapTop(ctx, it) }, valueRange = 0f..0.6f)
    } else {
        FilledTonalButton(onClick = {
            val mpm = ctx.getSystemService(MediaProjectionManager::class.java)
            consent.launch(mpm.createScreenCaptureIntent())
        }) { Text("Kaart naar horloge sturen") }
    }
}

@Composable
private fun HomeScreen() {
    val ctx = LocalContext.current
    val tick = rememberTicker()

    // Read inside the composition so it refreshes every tick
    val linked = tick >= 0 && WatchLink.permissionOk
    val access = hasNotificationAccess(ctx)
    val nav = NavHandler.lastNav
    val enabledApps = NotifRules.enabledPackages(ctx).size

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("WatchHub", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = HubBlue)
            StatusLine(linked, WatchLink.linkState)

            if (!linked || !access) {
                HubCard("Even instellen", Icons.Filled.Warning, HubYellow) {
                    if (!access) {
                        Hint("WatchHub heeft toegang tot meldingen nodig voor meldingen, oproepen en navigatie.")
                        Button(onClick = { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) {
                            Text("Toegang tot meldingen geven")
                        }
                    }
                    if (!linked) {
                        Hint("Mi Fitness (de mod) moet met je horloge verbonden zijn.")
                        FilledTonalButton(onClick = { WatchLink.connect() }) { Text("Verbinden met horloge") }
                    }
                }
            }

            HubCard("Meldingen", Icons.Filled.Notifications, HubGreen) {
                Hint(
                    if (enabledApps == 0) "Nog geen apps gekozen. Kies welke apps WatchHub op je horloge toont."
                    else "$enabledApps apps via WatchHub · ${HubNotificationService.forwarded} doorgestuurd"
                )
                if (HubNotificationService.lastInfo.isNotEmpty()) Hint("Laatste: ${HubNotificationService.lastInfo}")
                if (NotifRules.isQuietNow(ctx)) Hint("🌙 Stille uren zijn nu actief")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { ctx.startActivity(Intent(ctx, AppsActivity::class.java)) }) { Text("Apps kiezen") }
                    OutlinedButton(onClick = { ctx.startActivity(Intent(ctx, SettingsActivity::class.java)) }) { Text("Algemeen") }
                }
            }

            HubCard("Camera", Icons.Filled.PlayArrow, HubOrange) {
                Hint("Livebeeld op je horloge, sluiter met timer, ook met de Xiaomi-camera.")
                Button(
                    onClick = { ctx.startActivity(Intent(ctx, CameraActivity::class.java)) },
                    colors = ButtonDefaults.buttonColors(containerColor = HubOrange)
                ) { Text("Camera openen") }
            }

            HubCard("Navigatie", Icons.Filled.LocationOn, HubBlue) {
                if (nav == null) Hint("Start navigatie in Google Maps: WatchHub opent vanzelf op je horloge.")
                else {
                    Text(listOf(nav.dist, nav.instr).filter { it.isNotEmpty() }.joinToString(" · "),
                        style = MaterialTheme.typography.titleMedium)
                    if (nav.street.isNotEmpty()) Text(nav.street, color = HubBlue)
                    if (nav.eta.isNotEmpty()) Hint(nav.eta)
                }
                MapControls()
            }

            HubCard("Oproepen", Icons.Filled.Call, HubGreen) {
                Hint(
                    if (NotifRules.callsEnabled(ctx)) "Inkomende oproepen verschijnen op je horloge met Opnemen en Weigeren."
                    else "Oproepen op het horloge staan uit (zie Algemeen)."
                )
            }

            HubCard("Zoek mijn telefoon", Icons.Filled.Phone, if (FindPhone.ringing) HubRed else HubYellow) {
                Hint("Tik op je horloge op \"Zoek telefoon\": je telefoon gaat luid af, ook op stil.")
                if (FindPhone.ringing) {
                    Button(onClick = { FindPhone.stop() }, colors = ButtonDefaults.buttonColors(containerColor = HubRed)) {
                        Text("Stoppen")
                    }
                }
            }

            HubCard("Horloge", Icons.Filled.Settings, HubBlue) {
                if (WatchLink.lastLaunch.isNotEmpty()) Hint("App openen: ${WatchLink.lastLaunch}")
                if (WatchLink.lastError.isNotEmpty()) Hint("Laatste fout: ${WatchLink.lastError}")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    FilledTonalButton(onClick = { WatchLink.connect() }) { Text("Opnieuw verbinden") }
                    OutlinedButton(onClick = { WatchLink.openOnWatch() }) { Text("Open op horloge") }
                }
            }
        }
    }
}
