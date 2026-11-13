package com.twavods.watchhub

import android.app.TimePickerDialog
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import com.twavods.watchhub.ui.parseColor
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.twavods.watchhub.ui.Hint
import com.twavods.watchhub.ui.HubBlue
import com.twavods.watchhub.ui.HubCard
import com.twavods.watchhub.ui.HubGreen
import com.twavods.watchhub.ui.HubTheme
import com.twavods.watchhub.ui.HubYellow

/** General notification settings: calls, quiet hours, phone screen, quick replies. */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { HubTheme { SettingsScreen(onBack = { finish() }) } }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Hint(subtitle)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun fmt(min: Int) = if (min < 0) "--:--" else String.format("%02d:%02d", min / 60, min % 60)

@Composable
private fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var calls by remember { mutableStateOf(NotifRules.callsEnabled(ctx)) }
    var screenOn by remember { mutableStateOf(NotifRules.skipWhenScreenOn(ctx)) }
    var qStart by remember { mutableIntStateOf(NotifRules.quietStart(ctx)) }
    var qEnd by remember { mutableIntStateOf(NotifRules.quietEnd(ctx)) }
    val replies = remember { mutableStateListOf<String>().apply { addAll(NotifRules.replies(ctx)) } }
    var newReply by remember { mutableStateOf("") }
    var editIndex by remember { mutableIntStateOf(-1) }
    var dnd by remember { mutableStateOf(NotifRules.dndMode(ctx)) }
    val contacts = remember { mutableStateListOf<ContactRule>().apply { addAll(NotifRules.contacts(ctx)) } }
    var editContact by remember { mutableIntStateOf(-1) }
    var draft by remember { mutableStateOf(ContactRule("")) }
    var batLow by remember { mutableIntStateOf(NotifRules.batteryLow(ctx)) }
    var batFull by remember { mutableStateOf(NotifRules.batteryFull(ctx)) }
    var loss by remember { mutableStateOf(NotifRules.lossAlert(ctx)) }
    var editText by remember { mutableStateOf("") }

    fun saveQuiet(s: Int, e: Int) { qStart = s; qEnd = e; NotifRules.setQuiet(ctx, s, e) }
    fun saveReplies() = NotifRules.setReplies(ctx, replies.toList())

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
                Text("Algemeen", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }

            HubCard("Oproepen", Icons.Filled.Call, HubGreen) {
                SwitchRow("Oproepen op het horloge", "Naam van de beller met Opnemen en Weigeren", calls) {
                    calls = it; NotifRules.setCallsEnabled(ctx, it)
                }
            }

            HubCard("Wanneer doorsturen", Icons.Filled.Settings, HubBlue) {
                SwitchRow("Niet als mijn telefoonscherm aan staat",
                    "Je kijkt dan toch al naar je telefoon", screenOn) {
                    screenOn = it; NotifRules.setSkipWhenScreenOn(ctx, it)
                }
            }

            HubCard("Stille uren", Icons.Filled.DateRange, HubYellow) {
                Hint("Meldingen komen dan wel binnen in WatchHub, maar zonder trillen en zonder herhaling.")
                Text(
                    if (qStart < 0) "Uit" else "${fmt(qStart)} – ${fmt(qEnd)}",
                    style = MaterialTheme.typography.titleLarge
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(onClick = {
                        TimePickerDialog(ctx, { _, h1, m1 ->
                            TimePickerDialog(ctx, { _, h2, m2 -> saveQuiet(h1 * 60 + m1, h2 * 60 + m2) }, 7, 0, true)
                                .apply { setTitle("Einde stille uren") }.show()
                        }, 22, 0, true).apply { setTitle("Begin stille uren") }.show()
                    }) { Text(if (qStart < 0) "Instellen" else "Wijzigen") }
                    if (qStart >= 0) OutlinedButton(onClick = { saveQuiet(-1, -1) }) { Text("Uitzetten") }
                }
            }

            HubCard("Niet storen op je telefoon", Icons.Filled.Notifications, HubYellow) {
                Hint("Wat WatchHub doet als je telefoon op Niet storen staat.")
                ChoiceChips(NotifRules.DND_MODES, dnd) { dnd = it; NotifRules.setDndMode(ctx, it) }
            }

            HubCard("Contacten en groepen", Icons.Filled.Person, HubGreen) {
                Hint("Regels voor één persoon of groep, op (een deel van) de naam zoals die in de melding staat, " +
                    "bv. \"Mama\" of \"Voetbal\". Geldt voor alle apps.")
                contacts.forEachIndexed { i, c ->
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { editContact = i; draft = c }) {
                        Box(Modifier.size(14.dp).clip(CircleShape).background(parseColor(if (c.mode == "mute") "#666666" else c.color)))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.match, style = MaterialTheme.typography.bodyLarge)
                            Hint(when (c.mode) {
                                "vip" -> "Altijd tonen · ${NotifRules.PATTERNS[c.vib] ?: c.vib}"
                                "mute" -> "Nooit tonen"
                                else -> "Eigen trilpatroon: ${NotifRules.PATTERNS[c.vib] ?: c.vib}"
                            })
                        }
                        Icon(Icons.Filled.Edit, contentDescription = "Aanpassen")
                    }
                }
                FilledTonalButton(onClick = { editContact = contacts.size; draft = ContactRule("") }) { Text("Contact of groep toevoegen") }
            }

            if (editContact >= 0) {
                val isNew = editContact >= contacts.size
                AlertDialog(
                    onDismissRequest = { editContact = -1 },
                    title = { Text(if (isNew) "Contact of groep" else draft.match) },
                    text = {
                        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(value = draft.match, onValueChange = { draft = draft.copy(match = it) },
                                label = { Text("Naam (of een deel ervan)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            ChoiceChips(NotifRules.CONTACT_MODES, draft.mode) { draft = draft.copy(mode = it) }
                            if (draft.mode != "mute") {
                                Text("Trilpatroon", style = MaterialTheme.typography.titleSmall)
                                ChoiceChips(NotifRules.PATTERNS, draft.vib) { draft = draft.copy(vib = it) }
                                Text("Kleur", style = MaterialTheme.typography.titleSmall)
                                ChoiceChips(NotifRules.COLORS, draft.color) { draft = draft.copy(color = it) }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            if (draft.match.isNotBlank()) {
                                val c = draft.copy(match = draft.match.trim())
                                if (isNew) contacts.add(c) else contacts[editContact] = c
                                NotifRules.setContacts(ctx, contacts.toList())
                            }
                            editContact = -1
                        }) { Text("Opslaan") }
                    },
                    dismissButton = {
                        Row {
                            if (!isNew) TextButton(onClick = {
                                contacts.removeAt(editContact); NotifRules.setContacts(ctx, contacts.toList()); editContact = -1
                            }) { Text("Verwijderen") }
                            TextButton(onClick = { editContact = -1 }) { Text("Annuleren") }
                        }
                    }
                )
            }

            HubCard("Telefoon", Icons.Filled.Phone, HubBlue) {
                Text("Melding op je horloge als je telefoonbatterij bijna leeg is", style = MaterialTheme.typography.bodyLarge)
                ChoiceChips(NotifRules.BATTERY_LEVELS, batLow) { batLow = it; NotifRules.setBatteryLow(ctx, it) }
                SwitchRow("Melding als je telefoon volledig opgeladen is", "Op 100% tijdens het laden", batFull) {
                    batFull = it; NotifRules.setBatteryFull(ctx, it)
                }
                SwitchRow("Waarschuwen als je horloge niet meer verbonden is",
                    "Je telefoon trilt als de verbinding ± 2 minuten weg is, zodat je hem niet laat liggen", loss) {
                    loss = it; NotifRules.setLossAlert(ctx, it)
                }
            }

            HubCard("Snel antwoorden", Icons.Filled.Send, HubBlue) {
                Hint("Deze zinnen verschijnen op je horloge bij berichten waarop je kan antwoorden (WhatsApp, Messenger, sms…).")
                Hint("Tik op een antwoord om het aan te passen. Per app aan- of uitzetten doe je bij Meldingen per app.")
                if (replies.isEmpty()) Hint("Geen antwoorden: op het horloge verschijnen geen snelle antwoorden.")
                replies.forEachIndexed { i, r ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(r, modifier = Modifier.weight(1f).clickable { editIndex = i; editText = r },
                            style = MaterialTheme.typography.bodyLarge)
                        IconButton(onClick = {
                            if (i > 0) { val x = replies.removeAt(i); replies.add(i - 1, x); saveReplies() }
                        }, enabled = i > 0) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Omhoog") }
                        IconButton(onClick = { editIndex = i; editText = r }) {
                            Icon(Icons.Filled.Edit, contentDescription = "Aanpassen")
                        }
                        IconButton(onClick = { replies.removeAt(i); saveReplies() }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Verwijderen")
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newReply, onValueChange = { newReply = it },
                        placeholder = { Text("Nieuw antwoord…") }, singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(onClick = {
                        if (newReply.isNotBlank() && replies.size < 10) {
                            replies.add(newReply.trim()); newReply = ""; saveReplies()
                        }
                    }, enabled = newReply.isNotBlank() && replies.size < 10) { Text("Toevoegen") }
                }
                if (replies.size >= 10) Hint("Maximaal 10 antwoorden.")
                TextButton(onClick = {
                    replies.clear(); replies.addAll(NotifRules.DEFAULT_REPLIES); saveReplies()
                }) { Text("Standaardantwoorden terugzetten") }
            }

            if (editIndex >= 0 && editIndex < replies.size) {
                AlertDialog(
                    onDismissRequest = { editIndex = -1 },
                    title = { Text("Antwoord aanpassen") },
                    text = {
                        OutlinedTextField(value = editText, onValueChange = { editText = it }, singleLine = true,
                            modifier = Modifier.fillMaxWidth())
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            if (editText.isNotBlank()) { replies[editIndex] = editText.trim(); saveReplies() }
                            editIndex = -1
                        }) { Text("Opslaan") }
                    },
                    dismissButton = {
                        Row {
                            TextButton(onClick = { replies.removeAt(editIndex); saveReplies(); editIndex = -1 }) { Text("Verwijderen") }
                            TextButton(onClick = { editIndex = -1 }) { Text("Annuleren") }
                        }
                    }
                )
            }

            HubCard {
                Hint("Zet in Mi Fitness de meldingen uit voor de apps die je in WatchHub aanzet, anders krijg je ze dubbel.")
                OutlinedButton(onClick = {
                    ctx.packageManager.getLaunchIntentForPackage("com.xiaomi.wearable")?.let { ctx.startActivity(it) }
                }) { Text("Mi Fitness openen") }
            }
        }
    }
}
