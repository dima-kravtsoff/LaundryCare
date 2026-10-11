@file:OptIn(ExperimentalMaterial3Api::class)

package com.laundrycare.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.laundrycare.data.Bleach
import com.laundrycare.data.CareFields
import com.laundrycare.data.CareProfile
import com.laundrycare.data.ColorGroup
import com.laundrycare.data.DryClean
import com.laundrycare.data.GarmentKind
import com.laundrycare.data.Iron
import com.laundrycare.data.NaturalDry
import com.laundrycare.data.TumbleHeat
import com.laundrycare.data.WashAction

@Composable
fun AddEditScreen(
    vm: ClosetViewModel,
    onSaved: (String, Boolean) -> Unit,
    onCancel: () -> Unit,
    onCopyFrom: () -> Unit,
) {
    val draft = vm.draft
    val garments by vm.garments.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var pendingField by rememberSaveable { mutableStateOf(PhotoField.GARMENT.name) }
    var showSource by remember { mutableStateOf(false) }
    val picker = rememberPhotoPicker { uris -> vm.addPhotos(PhotoField.valueOf(pendingField), uris) }

    if (draft == null) {
        Box(Modifier.fillMaxSize())
        return
    }

    val goBack = {
        if (draft.saving) { /* photos are being moved into the store; discarding now would delete them half-way */ }
        else if (draft.step == DraftStep.REVIEW && !draft.editing) vm.backToPhotos()
        else { vm.discardDraft(); onCancel() }
    }
    BackHandler(onBack = goBack)

    Scaffold(
        topBar = {
            BackTopBar(
                title = if (draft.editing) "Edit garment" else "Add garment",
                subtitle = if (draft.step == DraftStep.PHOTOS) "Snap the item and its care tag" else draft.name.ifBlank { "Review the care details" },
                onBack = goBack,
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(8.dp))
            PhotoStrip(
                label = "Garment photos",
                hint = "Front, back and any distinctive detail: more angles make Snap-to-find more accurate. Tap a photo to make it the main one.",
                photos = draft.garmentPhotos,
                max = ClosetViewModel.MAX_GARMENT_PHOTOS,
                onAdd = { pendingField = PhotoField.GARMENT.name; showSource = true },
                onRemove = { vm.removePhoto(PhotoField.GARMENT, it) },
                onMakeMain = { vm.makeMain(PhotoField.GARMENT, it) },
            )
            PhotoStrip(
                label = "Care tag (at least one)",
                hint = "Add every label: fibre content and the symbols are often on separate tags. Fill the frame, keep it sharp and well lit.",
                photos = draft.tagPhotos,
                max = ClosetViewModel.MAX_TAG_PHOTOS,
                onAdd = { pendingField = PhotoField.TAG.name; showSource = true },
                onRemove = { vm.removePhoto(PhotoField.TAG, it) },
                onMakeMain = { vm.makeMain(PhotoField.TAG, it) },
            )
            draft.error?.let { Note(NoteKind.BAD, it, Modifier.padding(bottom = 10.dp)) }
            if (draft.step == DraftStep.PHOTOS) {
                PhotosActions(draft, settings.hasKey, settings.provider.label, garments.isNotEmpty(), vm, onCopyFrom)
            } else {
                ReviewForm(draft, settings.hasKey, vm) { id -> onSaved(id, draft.editing) }
            }
            BottomSpacer(40)
        }
    }
    if (showSource) {
        PhotoSourceDialog(
            title = if (pendingField == PhotoField.GARMENT.name) "Garment photo" else "Care tag photo",
            onDismiss = { showSource = false },
            onCamera = picker.camera,
            onGallery = picker.gallery,
        )
    }
}

@Composable
private fun Busy(text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PhotosActions(
    draft: Draft,
    hasKey: Boolean,
    providerLabel: String,
    hasOthers: Boolean,
    vm: ClosetViewModel,
    onCopyFrom: () -> Unit,
) {
    val hasTag = draft.tagPhotos.isNotEmpty()
    if (draft.busy) {
        Busy("Reading the tag…")
        return
    }
    Button(onClick = vm::readTag, enabled = hasTag && hasKey, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.AutoAwesome, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("Read tag with AI")
    }
    if (!(hasTag && hasKey)) {
        val why = when {
            !hasTag && !hasKey -> "Add a photo of the care tag, and your $providerLabel key in Settings, to read it automatically."
            !hasTag -> "Add a photo of the care tag to read it automatically."
            else -> "Add your $providerLabel key in Settings to read tags automatically, or enter the details by hand."
        }
        Text(why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
    }
    if (hasOthers) {
        OutlinedButton(onClick = onCopyFrom, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Icon(Icons.Default.ContentCopy, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Same tag as something I own")
        }
    }
    TextButton(onClick = vm::enterManually, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) { Text("Enter details by hand") }
}

@Composable
private fun CareTextField(label: String, value: String, unsure: Boolean = false, minLines: Int = 1, onChange: (String) -> Unit) {
    val warn = Tone.warn()
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(if (unsure) "$label · CHECK THIS" else label) },
            minLines = minLines,
            singleLine = minLines == 1,
            modifier = Modifier.fillMaxWidth(),
            colors = if (unsure) OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = warn, focusedBorderColor = warn, unfocusedLabelColor = warn, focusedLabelColor = warn,
            ) else OutlinedTextFieldDefaults.colors(),
        )
        if (unsure) {
            Text("The AI inferred this rather than reading it clearly. Confirm it against the tag.",
                style = MaterialTheme.typography.bodySmall, color = warn, modifier = Modifier.padding(top = 4.dp, start = 4.dp))
        }
    }
}

private fun washMode(c: CareProfile) = when {
    c.doNotWash -> "none"
    c.handWashOnly -> "hand"
    else -> "machine"
}

private fun dryMode(c: CareProfile) = when {
    c.doNotTumbleDry -> "none"
    c.tumbleDry -> "tumble"
    c.naturalDry != null -> "air"
    else -> "tumble"
}

@Composable
private fun ReviewForm(draft: Draft, hasKey: Boolean, vm: ClosetViewModel, onSaved: (String) -> Unit) {
    val care = draft.care ?: CareProfile.blank()

    if (draft.busy) Busy("Reading the tag…")
    else if (draft.editing && draft.tagPhotos.isNotEmpty() && hasKey) {
        OutlinedButton(onClick = vm::readTag, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Refresh, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Re-read the tag with AI")
        }
    }

    val unsure = care.unsureLabels
    if (unsure.isNotEmpty()) {
        Note(
            NoteKind.WARN,
            "The AI wasn't sure about ${if (unsure.size == 1) "one field" else "${unsure.size} fields"}: ${unsure.joinToString(", ")}. " +
                "They're marked below; check them against the tag before saving.",
            Modifier.padding(top = 10.dp),
        )
    }

    SectionTitle("Name")
    OutlinedTextField(
        value = draft.name, onValueChange = vm::setName, singleLine = true,
        placeholder = { Text("e.g. Navy merino sweater") }, modifier = Modifier.fillMaxWidth(),
    )

    SectionTitle("Decoded care: edit anything the AI got wrong")
    CareTextField("Fabric", care.fabric, unsure = care.isUnsure(CareFields.FABRIC)) { v -> vm.updateCare { it.copy(fabric = v).confirmed(CareFields.FABRIC) } }
    DropdownField(
        "Colour (for sorting loads)", draft.colorGroup,
        listOf<Pair<ColorGroup?, String>>(null to "Not set") + ColorGroup.entries.map { it to it.label },
        onChange = vm::setColorGroup,
    )
    DropdownField(
        "Kind of item (for load size)", care.garmentKind,
        listOf<Pair<GarmentKind?, String>>(null to "Guess from the name") + GarmentKind.entries.map { it to it.label },
    ) { k -> vm.updateCare { it.copy(garmentKind = k) } }
    DropdownField(
        "Washing", washMode(care),
        listOf("machine" to "Machine washable", "hand" to "Hand wash only", "none" to "Do not wash"),
    ) { v -> vm.updateCare { it.copy(doNotWash = v == "none", handWash = v == "hand", machineWash = v == "machine") } }
    DropdownField(
        "Max wash temperature", care.washTempC,
        listOf<Pair<Int?, String>>(null to "No limit shown", 30 to "30°C (cold)", 40 to "40°C (warm)", 50 to "50°C", 60 to "60°C (hot)", 70 to "70°C", 95 to "95°C (boil)"),
        unsure = care.isUnsure(CareFields.WASH_TEMP),
    ) { v -> vm.updateCare { it.copy(washTempC = v).confirmed(CareFields.WASH_TEMP) } }
    DropdownField(
        "Wash action", care.washAction,
        listOf<Pair<WashAction?, String>>(null to "Not shown") + WashAction.entries.map { it to it.label },
        unsure = care.isUnsure(CareFields.WASH_ACTION),
    ) { v -> vm.updateCare { it.copy(washAction = v).confirmed(CareFields.WASH_ACTION) } }
    DropdownField(
        "Bleach", care.bleach,
        listOf<Pair<Bleach?, String>>(null to "Not specified") + Bleach.entries.map { it to it.label },
        unsure = care.isUnsure(CareFields.BLEACH),
    ) { v -> vm.updateCare { it.copy(bleach = v).confirmed(CareFields.BLEACH) } }
    DropdownField(
        "Drying", dryMode(care),
        listOf("tumble" to "Tumble dry OK", "none" to "Do not tumble dry", "air" to "Air dry only"),
    ) { v ->
        vm.updateCare {
            it.copy(
                doNotTumbleDry = v == "none",
                tumbleDry = v == "tumble",
                naturalDry = if (v == "air" && it.naturalDry == null) NaturalDry.FLAT else it.naturalDry,
            )
        }
    }
    DropdownField(
        "Tumble heat", care.tumbleHeat,
        listOf<Pair<TumbleHeat?, String>>(null to "Not shown") + TumbleHeat.entries.map { it to it.label },
        unsure = care.isUnsure(CareFields.TUMBLE_HEAT),
    ) { v -> vm.updateCare { it.copy(tumbleHeat = v).confirmed(CareFields.TUMBLE_HEAT) } }
    DropdownField(
        "If air-drying, how", care.naturalDry,
        listOf<Pair<NaturalDry?, String>>(null to "Not shown") + NaturalDry.entries.map { it to it.label },
        unsure = care.isUnsure(CareFields.NATURAL_DRY),
    ) { v -> vm.updateCare { it.copy(naturalDry = v).confirmed(CareFields.NATURAL_DRY) } }
    DropdownField(
        "Ironing", care.iron,
        listOf<Pair<Iron?, String>>(null to "Not specified") + Iron.entries.map { it to it.label },
        unsure = care.isUnsure(CareFields.IRON),
    ) { v -> vm.updateCare { it.copy(iron = v).confirmed(CareFields.IRON) } }
    DropdownField(
        "Dry clean", care.dryClean,
        listOf<Pair<DryClean?, String>>(null to "Not specified") + DryClean.entries.map { it to it.label },
        unsure = care.isUnsure(CareFields.DRY_CLEAN),
    ) { v -> vm.updateCare { it.copy(dryClean = v).confirmed(CareFields.DRY_CLEAN) } }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Wash separately", style = MaterialTheme.typography.bodyLarge)
            Text("The tag says it may bleed or should be washed alone.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = care.washSeparately, onCheckedChange = { v -> vm.updateCare { it.copy(washSeparately = v) } })
    }
    Spacer(Modifier.height(10.dp))
    CareTextField("Notes (wool, denim, “wash inside out”…)", care.specialNotes, minLines = 2) { v -> vm.updateCare { it.copy(specialNotes = v) } }
    CareTextField("Looks like (helps Snap-to-find)", draft.description, minLines = 2, onChange = vm::setDescription)

    Spacer(Modifier.height(8.dp))
    Button(onClick = { vm.saveDraft(onSaved) }, enabled = !draft.busy && !draft.saving, modifier = Modifier.fillMaxWidth()) {
        if (draft.saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        else Icon(Icons.Default.Check, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(if (draft.saving) "Saving…" else if (draft.editing) "Save changes" else "Save to closet")
    }
}
