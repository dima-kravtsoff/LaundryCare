@file:OptIn(ExperimentalMaterial3Api::class)

package com.laundrycare.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.laundrycare.care.Machines
import com.laundrycare.data.ImportMode
import com.laundrycare.data.Provider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(vm: ClosetViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val provider = settings.provider
    var key by remember(provider) { mutableStateOf(settings.key(provider)) }
    var model by remember(provider) { mutableStateOf(settings.model(provider)) }
    var showKey by remember { mutableStateOf(false) }
    var importUri by remember { mutableStateOf<Uri?>(null) }
    val check = vm.modelCheck
    val date = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) }

    val exportFull = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let { vm.exportCloset(it, includePhotos = true) }
    }
    val exportCare = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { vm.exportCloset(it, includePhotos = false) }
    }
    val importPick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importUri = uri
    }
    LaunchedEffect(Unit) { vm.clearModelCheck() }
    // Check key may have swapped in a model the key actually offers; the text
    // field must follow, or the next Save would put the unavailable one back.
    LaunchedEffect(check?.autoPicked) { check?.autoPicked?.let { model = it } }

    Scaffold(topBar = { BackTopBar("Settings", onBack = onBack) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {

            SectionTitle("Tag reader")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Provider.entries.forEachIndexed { i, p ->
                    SegmentedButton(
                        selected = p == provider,
                        onClick = { vm.clearModelCheck(); vm.setProvider(p) },
                        shape = SegmentedButtonDefaults.itemShape(index = i, count = Provider.entries.size),
                    ) { Text(p.label) }
                }
            }
            Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Column(Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = key, onValueChange = { key = it }, singleLine = true,
                        label = { Text("${provider.label} API key (stays on this device)") },
                        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showKey = !showKey }) {
                                Icon(if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = if (showKey) "Hide key" else "Show key")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = model, onValueChange = { model = it }, singleLine = true,
                        label = { Text("Model") }, placeholder = { Text(provider.defaultModel) },
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilledTonalButton(
                            onClick = { vm.checkKey(provider, key, model) },
                            enabled = key.isNotBlank() && check?.busy != true,
                            modifier = Modifier.weight(1f),
                        ) { Text("Check key & list models") }
                        Button(onClick = { vm.saveProvider(provider, key, model) }, modifier = Modifier.weight(1f)) { Text("Save") }
                    }
                    check?.let { c ->
                        Spacer(Modifier.height(10.dp))
                        when {
                            c.busy -> LinearProgressIndicator(Modifier.fillMaxWidth())
                            c.error != null -> Note(NoteKind.BAD, c.error)
                            c.models.isEmpty() -> Note(NoteKind.WARN, "The key works, but offers no models that can read images. Check the key's restrictions.")
                            else -> {
                                Note(NoteKind.GOOD, "Key works: ${c.models.size} usable model${if (c.models.size == 1) "" else "s"}. Best first; tap one to use it.")
                                c.autoPicked?.let { Note(NoteKind.INFO, "Your saved model wasn't available, so $it is selected instead.", Modifier.padding(top = 6.dp)) }
                                val current = settings.model(provider)
                                c.models.take(12).forEach { m ->
                                    Row(
                                        Modifier.fillMaxWidth().clickable { model = m.id; vm.pickModel(provider, m.id) }.padding(vertical = 8.dp),
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(m.id, fontWeight = if (m.id == current) FontWeight.Bold else FontWeight.Normal)
                                            val sub = listOfNotNull(if (m.id == current) "In use" else null, m.label.takeIf { it.isNotBlank() }).joinToString(" · ")
                                            if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Text(
                        "Where do I get a key? ${provider.whereToGet}. It lives only in this app and is sent only to ${provider.label}. " +
                            "Don't guess model names: tap Check key and pick from what your key actually offers.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }

            SectionTitle("Your machines")
            DropdownField("Washer and dryer", settings.machinesId, Machines.ALL.map { it.id to it.label }, onChange = vm::setMachines)
            val m = settings.machines
            MachineBox(m.washer.name, m.washer.model, m.washer.programs.map { "${it.name}: ${it.maxLoad.label.lowercase()}" + (if (it.forText.isNotBlank()) ". ${it.forText}" else "") }, m.washer.optionNotes)
            MachineBox(m.dryer.name, m.dryer.model, m.dryer.programs.map { "${it.name}: ${it.maxLoad.label.lowercase()}" + (if (it.forText.isNotBlank()) ". ${it.forText}" else "") }, m.dryer.optionNotes)
            Text("Recommendations are tuned to these two machines, from their use-and-care manuals.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))

            SectionTitle("Backup")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Export the closet to a file you can keep or move to another device. Photos dominate the size (roughly 10–15 MB for 50 garments); the care-only export is tiny and worth taking often.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = { exportFull.launch("closet-care-$date.zip") }, enabled = !vm.transferBusy, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Export closet with photos")
                    }
                    OutlinedButton(onClick = { exportCare.launch("closet-care-$date.json") }, enabled = !vm.transferBusy, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Text("Export care data only")
                    }
                    OutlinedButton(
                        onClick = { importPick.launch(arrayOf("application/zip", "application/json", "application/octet-stream", "*/*")) },
                        enabled = !vm.transferBusy, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) {
                        Icon(Icons.Default.Upload, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Import closet")
                    }
                    if (vm.transferBusy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                }
            }

            SectionTitle("Privacy")
            Text(
                "Garments and photos stay on this device. Nothing is uploaded except the photos sent to ${provider.label} when a tag is read or a garment is looked up. " +
                    "The API key is stored in the app's private settings and the app is excluded from Android's cloud backup; use Export above instead.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BottomSpacer(40)
        }
    }

    importUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { importUri = null },
            title = { Text("Import closet") },
            text = { Text("Merge adds garments you don't already have and leaves existing ones alone. Replace wipes this closet first, photos included.") },
            confirmButton = {
                TextButton(onClick = { vm.importCloset(uri, ImportMode.MERGE); importUri = null }) { Text("Merge") }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = { vm.importCloset(uri, ImportMode.REPLACE); importUri = null },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text("Replace") }
                    TextButton(onClick = { importUri = null }) { Text("Cancel") }
                }
            },
        )
    }
}

@Composable
private fun MachineBox(name: String, model: String, programs: List<String>, options: List<String>) {
    var open by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(name, fontWeight = FontWeight.SemiBold)
            Text(model, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                (if (open) "▾ " else "▸ ") + "${programs.size} programs",
                color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clickable { open = !open }.padding(top = 8.dp, bottom = 2.dp),
            )
            if (open) {
                BulletList(programs, Modifier.padding(top = 4.dp))
                if (options.isNotEmpty()) {
                    Text("Options", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                    BulletList(options)
                }
            }
        }
    }
}
