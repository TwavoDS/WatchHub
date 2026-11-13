package com.twavods.watchhub

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import com.twavods.watchhub.ui.Hint
import com.twavods.watchhub.ui.HubCard
import com.twavods.watchhub.ui.HubTheme
import com.twavods.watchhub.ui.parseColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Choose per app how its notifications look and feel on the watch. */
class AppsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { HubTheme { AppsScreen(onBack = { finish() }) } }
    }
}

private class AppEntry(val pkg: String, val label: String, val recent: Boolean, val icon: ImageBitmap?)

@Composable
private fun AppsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<AppEntry?>(null) }
    var version by remember { mutableIntStateOf(0) }   // bump to redraw after saving

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val recent = NotifRules.seenPackages(ctx)
            val enabled = NotifRules.enabledPackages(ctx)
            val pm = ctx.packageManager
            val launchable = pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
            ).map { it.activityInfo.packageName }.toSet()
            (recent + enabled + launchable).distinct().filter { it != ctx.packageName }.mapNotNull { pkg ->
                try {
                    val info = pm.getApplicationInfo(pkg, 0)
                    val icon = try { pm.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() } catch (_: Exception) { null }
                    AppEntry(pkg, pm.getApplicationLabel(info).toString(), pkg in recent || pkg in enabled, icon)
                } catch (_: Exception) { null }
            }.sortedWith(compareBy<AppEntry>({ !it.recent }, { it.label.lowercase() }))
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                IconButton(onClick = onBack) { Icon(Icons.Filled.Close, contentDescription = "Sluiten") }
                Text("Meldingen per app", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text("Zoek een app") }, singleLine = true,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.size(8.dp))
            val list = apps
            if (list == null) {
                Hint("Apps laden…")
            } else {
                val shown = if (query.isBlank()) list else list.filter { it.label.contains(query.trim(), ignoreCase = true) }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(shown, key = { it.pkg }) { app ->
                        // Reading 'version' makes the row refresh after a change
                        val rule = if (version >= 0) NotifRules.get(ctx, app.pkg) else AppRule()
                        AppRow(app, rule,
                            onToggle = { on -> NotifRules.set(ctx, app.pkg, rule.copy(enabled = on)); version++ },
                            onClick = { editing = app })
                    }
                }
            }
        }
    }

    editing?.let { app ->
        RuleEditor(app, onClose = { editing = null; version++ })
    }
}

@Composable
private fun AppRow(app: AppEntry, rule: AppRule, onToggle: (Boolean) -> Unit, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        if (app.icon != null) Image(app.icon, contentDescription = null, modifier = Modifier.size(44.dp))
        else Box(Modifier.size(44.dp).clip(CircleShape).background(Color.Gray))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (rule.enabled) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(parseColor(rule.color)))
                    Spacer(Modifier.width(6.dp))
                    Hint(
                        listOfNotNull(
                            if (rule.display == "popup") "horlogemelding" else (NotifRules.PATTERNS[rule.vib] ?: rule.vib),
                            if (rule.repeatMin > 0) "herhalen" else null,
                            if (rule.display == "screen") NotifRules.DURATIONS[rule.duration] else null,
                        ).filter { it.isNotEmpty() }.joinToString(" · ")
                    )
                }
            } else Hint("via Mi Fitness")
        }
        Switch(checked = rule.enabled, onCheckedChange = onToggle)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceChips(options: Map<T, String>, selected: T, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (key, label) ->
            FilterChip(
                selected = key == selected,
                onClick = { onSelect(key) },
                label = { Text(label) },
                leadingIcon = if (key == selected) {
                    { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                } else null
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuleEditor(app: AppEntry, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val start = remember { NotifRules.get(ctx, app.pkg) }
    var enabled by remember { mutableStateOf(start.enabled) }
    var display by remember { mutableStateOf(start.display) }
    var vib by remember { mutableStateOf(start.vib) }
    var color by remember { mutableStateOf(start.color.uppercase()) }
    var duration by remember { mutableIntStateOf(start.duration) }
    var repeat by remember { mutableIntStateOf(start.repeatMin) }
    var only by remember { mutableStateOf(start.onlyWords) }
    var block by remember { mutableStateOf(start.blockWords) }

    fun current() = AppRule(
        enabled = enabled, vib = vib, color = color, repeatMin = repeat,
        onlyWords = only, blockWords = block, display = display, duration = duration
    )

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (app.icon != null) Image(app.icon, contentDescription = null, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.width(14.dp))
                    Text(app.label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f))
                    IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Sluiten") }
                }

                HubCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Meldingen via WatchHub", style = MaterialTheme.typography.bodyLarge)
                            Hint("Uit: Mi Fitness toont ze zoals gewoonlijk")
                        }
                        Switch(checked = enabled, onCheckedChange = { enabled = it })
                    }
                }

                if (enabled) {
                    HubCard("Weergave") {
                        ChoiceChips(mapOf("screen" to "WatchHub-scherm", "popup" to "Gewone horlogemelding"), display) { display = it }
                        Hint(
                            if (display == "screen") "Springt open met kleur, icoon, knoppen en snel antwoorden."
                            else "Een gewone melding van het horloge, zonder WatchHub-scherm."
                        )
                    }
                    if (display == "screen") {
                        HubCard("Trilpatroon") { ChoiceChips(NotifRules.PATTERNS, vib) { vib = it } }
                        HubCard("Kleur") {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                NotifRules.COLORS.keys.forEach { hex ->
                                    val sel = hex.equals(color, ignoreCase = true)
                                    Box(
                                        Modifier
                                            .size(40.dp)
                                            .clip(CircleShape)
                                            .background(parseColor(hex))
                                            .border(BorderStroke(if (sel) 3.dp else 0.dp, Color.White), CircleShape)
                                            .clickable { color = hex },
                                        contentAlignment = Alignment.Center
                                    ) { if (sel) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.Black) }
                                }
                            }
                        }
                        HubCard("Hoe lang tonen") {
                            ChoiceChips(NotifRules.DURATIONS, duration) { duration = it }
                            Hint("Daarna sluit het scherm vanzelf. Tik je erop, dan blijft het staan.")
                        }
                    }
                    HubCard("Herhalen tot je hem wegtikt") { ChoiceChips(NotifRules.REPEATS, repeat) { repeat = it } }
                    HubCard("Filter") {
                        OutlinedTextField(
                            value = only, onValueChange = { only = it },
                            label = { Text("Alleen als de tekst één van deze woorden bevat") },
                            placeholder = { Text("bv. mama, dringend") }, modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = block, onValueChange = { block = it },
                            label = { Text("Nooit als de tekst één van deze woorden bevat") },
                            placeholder = { Text("bv. reclame, aanbieding") }, modifier = Modifier.fillMaxWidth()
                        )
                        Hint("Woorden scheiden met een komma. Leeg = alles doorsturen.")
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { NotifRules.set(ctx, app.pkg, current()); onClose() }) { Text("Opslaan") }
                    OutlinedButton(
                        onClick = {
                            NotifRules.set(ctx, app.pkg, current())
                            HubNotificationService.instance?.sendTest(app.pkg)
                        },
                        enabled = enabled
                    ) { Text("Test op horloge") }
                }
                if (HubNotificationService.instance == null) Hint("Geef WatchHub eerst toegang tot meldingen om te testen.")
                Spacer(Modifier.size(24.dp))
            }
        }
    }
}
