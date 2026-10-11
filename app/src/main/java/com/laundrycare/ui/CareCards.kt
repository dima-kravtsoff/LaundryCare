package com.laundrycare.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Dry
import androidx.compose.material.icons.filled.DryCleaning
import androidx.compose.material.icons.filled.Iron
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.LocalLaundryService
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.laundrycare.care.CareText
import com.laundrycare.care.DryMode
import com.laundrycare.care.DryPlan
import com.laundrycare.care.Dryer
import com.laundrycare.care.ExtraKind
import com.laundrycare.care.LoadFit
import com.laundrycare.care.LoadFraction
import com.laundrycare.care.WashPlan
import com.laundrycare.care.Washer
import com.laundrycare.data.CareProfile
import com.laundrycare.data.Garment

@Composable
private fun CardHeader(icon: ImageVector, title: String, model: String, tint: Color, soft: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
        Row(Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(soft), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(8.dp))
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(model, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FitTable(frac: LoadFraction, count: (LoadFraction, com.laundrycare.data.GarmentKind) -> Int) {
    if (frac == LoadFraction.ONE_ITEM) return
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(
            (if (open) "▾ " else "▸ ") + "What else fits in ${frac.label.lowercase()}?",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.clickable { open = !open }.padding(vertical = 4.dp),
        )
        if (open) {
            LoadFit.REFERENCE.forEach { kind ->
                Text("• about ${count(frac, kind)} ${kind.plural}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                "Rough guide only. Go by eye: loosely placed, the load should fill about " +
                    (if (frac == LoadFraction.FULL) "the drum" else frac.label.lowercase().replace(" drum", " of the drum")) + ".",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
fun WashCard(plan: WashPlan, washer: Washer, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            CardHeader(Icons.Default.LocalLaundryService, "Washer", washer.model, Tone.info(), Tone.infoSoft())
            val program = plan.program
            if (program == null) {
                plan.warnings.forEach { Note(NoteKind.BAD, it, Modifier.padding(top = 6.dp)) }
                plan.notes.forEach { Note(NoteKind.INFO, it, Modifier.padding(top = 6.dp)) }
                return@Column
            }
            SettingRow("Program", program.name, plan.programReason, big = true)
            HorizontalDivider()
            plan.temp?.let { SettingRow("Temperature", it.label, plan.tempReason); HorizontalDivider() }
            plan.spin?.let { SettingRow("Spin", it.label); HorizontalDivider() }
            SettingRow("Max load", program.maxLoad.label, LoadFit.text(LoadFit.count(washer, program.maxLoad, plan.kind), program.maxLoad))
            if (plan.options.isNotEmpty()) {
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
                    Text("Options", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(110.dp))
                    Pills(plan.options, Tone.info(), Tone.infoSoft())
                }
            }
            if (program.maxLoad.tight) {
                Note(NoteKind.INFO, "${program.maxLoad.label} maximum: don't pack it. A gentle cycle only works if the load can move freely.", Modifier.padding(top = 6.dp))
            }
            FitTable(program.maxLoad) { frac, kind -> LoadFit.count(washer, frac, kind) }
            plan.warnings.forEach { Note(NoteKind.WARN, it, Modifier.padding(top = 8.dp)) }
            plan.notes.forEach { Note(NoteKind.INFO, it, Modifier.padding(top = 8.dp)) }
            plan.extras.forEach {
                Note(
                    NoteKind.INFO, it.text, Modifier.padding(top = 8.dp),
                    icon = if (it.kind == ExtraKind.BAG) Icons.Default.ShoppingBag else Icons.Default.WaterDrop,
                )
            }
        }
    }
}

@Composable
fun DryCard(plan: DryPlan, dryer: Dryer, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            CardHeader(Icons.Default.Dry, "Dryer", dryer.model, Tone.good(), Tone.goodSoft())
            when (plan.mode) {
                DryMode.NONE -> {
                    plan.notes.forEach { Note(NoteKind.INFO, it, Modifier.padding(top = 6.dp)) }
                }
                DryMode.AIR -> {
                    plan.warnings.forEach { Note(NoteKind.WARN, it, Modifier.padding(top = 6.dp)) }
                    SettingRow("Method", "Air dry", big = true)
                    plan.airText?.let {
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.Top) {
                            Icon(Icons.Default.Air, contentDescription = null, tint = Tone.good(), modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(it, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    plan.notes.forEach { Note(NoteKind.INFO, it, Modifier.padding(top = 8.dp)) }
                }
                DryMode.MACHINE -> {
                    val program = plan.program ?: return@Column
                    SettingRow("Program", program.name, program.forText, big = true)
                    HorizontalDivider()
                    plan.heat?.let { SettingRow("Heat", it.label); HorizontalDivider() }
                    SettingRow("Max load", program.maxLoad.label, LoadFit.text(LoadFit.count(dryer, program.maxLoad, plan.kind), program.maxLoad))
                    if (plan.options.isNotEmpty()) {
                        HorizontalDivider()
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
                            Text("Options", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(110.dp))
                            Pills(plan.options, Tone.good(), Tone.goodSoft())
                        }
                    }
                    if (program.maxLoad.tight) {
                        Note(NoteKind.INFO, "${program.maxLoad.label} maximum: clothes need room to tumble or they come out damp and creased.", Modifier.padding(top = 6.dp))
                    }
                    FitTable(program.maxLoad) { frac, kind -> LoadFit.count(dryer, frac, kind) }
                    plan.warnings.forEach { Note(NoteKind.WARN, it, Modifier.padding(top = 8.dp)) }
                    plan.notes.forEach { Note(NoteKind.INFO, it, Modifier.padding(top = 8.dp)) }
                }
            }
        }
    }
}

private fun iconFor(row: CareText.Row): ImageVector = when (row) {
    CareText.Row.FABRIC -> Icons.Default.Label
    CareText.Row.WASH -> Icons.Default.LocalLaundryService
    CareText.Row.TEMP -> Icons.Default.Thermostat
    CareText.Row.BLEACH -> Icons.Default.Science
    CareText.Row.DRY -> Icons.Default.Dry
    CareText.Row.IRON -> Icons.Default.Iron
    CareText.Row.DRY_CLEAN -> Icons.Default.DryCleaning
    CareText.Row.COLOUR -> Icons.Default.Palette
    CareText.Row.NOTES -> Icons.Default.Notes
}

@Composable
fun TagSummaryCard(care: CareProfile, garment: Garment?, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            CareText.rows(care, garment).forEachIndexed { i, (row, value) ->
                if (i > 0) HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(iconFor(row), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(row.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    }
                }
            }
            if (garment != null && garment.description.isNotBlank()) {
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Visibility, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Looks like", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(garment.description, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
