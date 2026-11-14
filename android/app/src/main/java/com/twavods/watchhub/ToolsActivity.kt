package com.twavods.watchhub

import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.twavods.watchhub.ui.Hint
import com.twavods.watchhub.ui.HubBlue
import com.twavods.watchhub.ui.HubCard
import com.twavods.watchhub.ui.HubGreen
import com.twavods.watchhub.ui.HubTheme
import com.twavods.watchhub.ui.HubYellow
import com.twavods.watchhub.ui.parseColor

/** Music, action tiles and reminders. */
class ToolsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WatchLink.init(this)
        setContent { HubTheme { ToolsScreen(onBack = { finish() }) } }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val tick = rememberTicker()
    val actions = remember { mutableStateListOf<Action>().apply { addAll(Actions.list(ctx)) } }
    var editAction by remember { mutableIntStateOf(-1) }
    var draftA by remember { mutableStateOf(Action("", "")) }
    val reminders = remember { mutableStateListOf<Reminder>().apply { addAll(Reminders.list(ctx)) } }
    var editRem by remember { mutableIntStateOf(-1) }
    var draftR by remember { mutableStateOf(Reminder(0, "", 9 * 60)) }
    var haUrl by remember { mutableStateOf(HomeAssistant.url(ctx)) }
    var haToken by remember { mutableStateOf(HomeAssistant.token(ctx)) }
    var haStatus by remember { mutableStateOf("") }
    var haAll by remember { mutableStateOf<List<HomeAssistant.Entity>>(emptyList()) }
    var haFilter by remember { mutableStateOf("") }
    val haChosen = remember { mutableStateListOf<String>().apply { addAll(HomeAssistant.chosen(ctx)) } }

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
                Text("Muziek, acties en herinneringen", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }

            HubCard("Muziek", Icons.Filled.PlayArrow, HubGreen) {
                Hint("Op je horloge: WatchHub → Muziek. Toont wat er speelt (Spotify, YouTube Music, …) met hoes, " +
                    "en bedient afspelen, pauze, volgende, vorige en volume. Werkt met de toegang tot meldingen, er is niets in te stellen.")
            }

            // ---- Actions
            HubCard("Acties", Icons.Filled.Star, HubBlue) {
                Hint("Tegels op je horloge (WatchHub → Acties). Een tik voert de actie uit op je telefoon:\n" +
                    "• Zaklamp, Trillen / geluid en Muziek pauzeren werken meteen\n" +
                    "• Webadres: bv. een Home Assistant-webhook of IFTTT\n" +
                    "• Tasker: stuurt het signaal ${Actions.BROADCAST} met de naam; in Tasker vang je het met Gebeurtenis → Intent ontvangen.")
                actions.forEachIndexed { i, a ->
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { editAction = i; draftA = a }) {
                        Box(Modifier.size(14.dp).clip(CircleShape).background(parseColor(a.color)))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(a.name, style = MaterialTheme.typography.bodyLarge)
                            Hint(when (a.type) {
                                "http" -> "${a.method} ${a.url.take(40)}"
                                else -> Actions.TYPES[a.type] ?: a.type
                            })
                        }
                        TextButton(onClick = { Actions.run(ctx, a.id, report = false) }) { Text("Test") }
                        Icon(Icons.Filled.Edit, contentDescription = "Aanpassen")
                    }
                }
                FilledTonalButton(onClick = {
                    editAction = actions.size
                    draftA = Action("a" + System.currentTimeMillis().toString(36), "")
                }) { Text("Actie toevoegen") }
                val missing = Actions.DEFAULTS.filter { d -> actions.none { it.type == d.type } }
                if (missing.isNotEmpty()) OutlinedButton(onClick = {
                    actions.addAll(0, missing); Actions.save(ctx, actions.toList())
                }) { Text("Zaklamp, trillen en muziek toevoegen") }
            }

            // ---- Home Assistant
            HubCard("Home Assistant", Icons.Filled.Home, HubBlue) {
                Hint("Je apparaten op je horloge (WatchHub → Huis): zie de toestand en tik om te schakelen. " +
                    "Token maken: Home Assistant → je profiel → Beveiliging → Langlevende toegangstokens → Token aanmaken.")
                OutlinedTextField(haUrl, { haUrl = it }, label = { Text("Adres, bv. http://192.168.1.10:8123") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(haToken, { haToken = it }, label = { Text("Langlevend toegangstoken") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(onClick = {
                        HomeAssistant.saveLogin(ctx, haUrl, haToken)
                        haStatus = "Verbinden…"
                        HomeAssistant.fetchAll(ctx) { list, err ->
                            if (list != null) { haAll = list; haStatus = "Verbonden: ${list.size} apparaten gevonden" }
                            else haStatus = "Mislukt: $err"
                        }
                    }, enabled = haUrl.isNotBlank() && haToken.isNotBlank()) { Text("Verbinden") }
                }
                if (haStatus.isNotEmpty()) Hint(haStatus)
                if (haChosen.isNotEmpty()) {
                    Text("Op je horloge (${haChosen.size})", style = MaterialTheme.typography.titleSmall)
                    haChosen.forEach { id ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(haAll.firstOrNull { it.id == id }?.name ?: id, Modifier.weight(1f))
                            TextButton(onClick = { haChosen.remove(id); HomeAssistant.saveChosen(ctx, haChosen.toList()) }) { Text("Weg") }
                        }
                    }
                }
                if (haAll.isNotEmpty()) {
                    OutlinedTextField(haFilter, { haFilter = it }, label = { Text("Zoek apparaat") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    haAll.filter { it.id !in haChosen && (haFilter.isBlank() ||
                            it.name.contains(haFilter, true) || it.id.contains(haFilter, true)) }
                        .take(40).forEach { e ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(e.name)
                                    Hint("${e.id} · ${e.state}${if (e.unit.isNotEmpty()) " " + e.unit else ""}")
                                }
                                TextButton(onClick = { haChosen.add(e.id); HomeAssistant.saveChosen(ctx, haChosen.toList()) }) { Text("Toevoegen") }
                            }
                        }
                }
            }

            // ---- Reminders
            HubCard("Herinneringen", Icons.Filled.DateRange, HubYellow) {
                Hint("Verschijnen op je horloge met trillen, op het uur en de dagen die je kiest. " +
                    "Afspraken uit je agenda: zet de app Agenda aan bij Meldingen per app.")
                if (!Reminders.canExact(ctx)) {
                    Hint("Voor herinneringen op de minuut moet WatchHub \"Wekkers en herinneringen\" mogen. Zonder kan het een paar minuten later zijn.")
                    OutlinedButton(onClick = {
                        if (Build.VERSION.SDK_INT >= 31) ctx.startActivity(
                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")))
                    }) { Text("Toestaan") }
                }
                reminders.forEachIndexed { i, r ->
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { editRem = i; draftR = r }) {
                        Column(Modifier.weight(1f)) {
                            Text(r.text, style = MaterialTheme.typography.bodyLarge)
                            Hint(Reminders.describe(r))
                        }
                        Switch(checked = r.on, onCheckedChange = { v ->
                            reminders[i] = r.copy(on = v); Reminders.save(ctx, reminders.toList())
                        })
                    }
                }
                FilledTonalButton(onClick = {
                    editRem = reminders.size
                    draftR = Reminder(Reminders.newId(ctx).coerceAtLeast((reminders.maxOfOrNull { it.id } ?: 0) + 1), "", 9 * 60)
                }) { Text("Herinnering toevoegen") }
            }
            if (tick < 0) Text("")
        }
    }

    if (editAction >= 0) {
        val isNew = editAction >= actions.size
        AlertDialog(
            onDismissRequest = { editAction = -1 },
            title = { Text(if (isNew) "Nieuwe actie" else draftA.name) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(draftA.name, { draftA = draftA.copy(name = it) }, label = { Text("Naam op het horloge") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    ChoiceChips(Actions.TYPES, draftA.type) { draftA = draftA.copy(type = it) }
                    if (draftA.type == "http") {
                        OutlinedTextField(draftA.url, { draftA = draftA.copy(url = it) }, label = { Text("Webadres (https://…)") },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        ChoiceChips(Actions.METHODS, draftA.method) { draftA = draftA.copy(method = it) }
                        OutlinedTextField(draftA.token, { draftA = draftA.copy(token = it) },
                            label = { Text("Token (optioneel, bv. Home Assistant)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        if (draftA.method == "POST") OutlinedTextField(draftA.body, { draftA = draftA.copy(body = it) },
                            label = { Text("Inhoud (JSON, optioneel)") }, modifier = Modifier.fillMaxWidth())
                    }
                    Text("Kleur", style = MaterialTheme.typography.titleSmall)
                    ChoiceChips(NotifRules.COLORS, draftA.color) { draftA = draftA.copy(color = it) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (draftA.name.isNotBlank()) {
                        val a = draftA.copy(name = draftA.name.trim(), url = draftA.url.trim())
                        if (isNew) actions.add(a) else actions[editAction] = a
                        Actions.save(ctx, actions.toList())
                    }
                    editAction = -1
                }) { Text("Opslaan") }
            },
            dismissButton = {
                Row {
                    if (!isNew) TextButton(onClick = {
                        actions.removeAt(editAction); Actions.save(ctx, actions.toList()); editAction = -1
                    }) { Text("Verwijderen") }
                    TextButton(onClick = { editAction = -1 }) { Text("Annuleren") }
                }
            }
        )
    }

    if (editRem >= 0) {
        val isNew = editRem >= reminders.size
        AlertDialog(
            onDismissRequest = { editRem = -1 },
            title = { Text(if (isNew) "Nieuwe herinnering" else "Herinnering") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(draftR.text, { draftR = draftR.copy(text = it) }, label = { Text("Tekst") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(onClick = {
                        TimePickerDialog(ctx, { _, h, m -> draftR = draftR.copy(minutes = h * 60 + m) },
                            draftR.minutes / 60, draftR.minutes % 60, true).show()
                    }) { Text(String.format("Om %02d:%02d", draftR.minutes / 60, draftR.minutes % 60)) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Reminders.DAY_NAMES.forEachIndexed { i, d ->
                            val on = draftR.days and (1 shl i) != 0
                            FilterChip(selected = on, onClick = { draftR = draftR.copy(days = draftR.days xor (1 shl i)) },
                                label = { Text(d) })
                        }
                    }
                    Text("Trilpatroon", style = MaterialTheme.typography.titleSmall)
                    ChoiceChips(NotifRules.PATTERNS, draftR.vib) { draftR = draftR.copy(vib = it) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (draftR.text.isNotBlank() && draftR.days != 0) {
                        val r = draftR.copy(text = draftR.text.trim())
                        if (isNew) reminders.add(r) else reminders[editRem] = r
                        Reminders.save(ctx, reminders.toList())
                    }
                    editRem = -1
                }) { Text("Opslaan") }
            },
            dismissButton = {
                Row {
                    if (!isNew) TextButton(onClick = {
                        reminders.removeAt(editRem); Reminders.save(ctx, reminders.toList()); editRem = -1
                    }) { Text("Verwijderen") }
                    TextButton(onClick = { editRem = -1 }) { Text("Annuleren") }
                }
            }
        )
    }
}
