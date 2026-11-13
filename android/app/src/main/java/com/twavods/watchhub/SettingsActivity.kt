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
