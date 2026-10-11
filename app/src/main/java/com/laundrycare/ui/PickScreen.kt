@file:OptIn(ExperimentalMaterial3Api::class)

package com.laundrycare.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Two jobs share this list: picking a garment whose care gets copied into the
 * draft ("copy"), and picking garments that receive the current garment's care
 * ("apply"). Identical tags, one profile.
 */
@Composable
fun PickScreen(vm: ClosetViewModel, mode: String, sourceId: String, onBack: () -> Unit) {
    val garments by vm.garments.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val applying = mode == "apply"
    val source = garments.firstOrNull { it.id == sourceId }
    val selected = remember { mutableStateListOf<String>() }
    val pool = garments.filter { it.id != sourceId && (!applying || source != null) }
    val washer = settings.machines.washer

    Scaffold(
        topBar = {
            BackTopBar(
                title = if (applying) "Apply care to…" else "Copy care from…",
                subtitle = if (applying) "Identical tags, one profile" else "Pick a garment with the same tag",
                onBack = onBack,
            )
        },
        bottomBar = {
            if (applying) {
                Surface(tonalElevation = 3.dp) {
                    Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                        Button(
                            onClick = { source?.let { vm.applyCareTo(it, selected.toList()) { onBack() } } },
                            enabled = selected.isNotEmpty() && source != null,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Apply to ${selected.size} garment${if (selected.size == 1) "" else "s"}") }
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            item {
                Note(
                    NoteKind.INFO,
                    if (applying) "Choose garments with the SAME care label. Their care details are overwritten with this one's; photos and names are untouched."
                    else "Choose a garment with the same care label and its care details are copied over, skipping the AI read entirely.",
                    Modifier.padding(bottom = 8.dp),
                )
            }
            if (pool.isEmpty()) {
                item { Note(NoteKind.WARN, "No other garments saved yet.") }
            }
            items(pool, key = { it.id }) { g ->
                val on = g.id in selected
                GarmentRow(
                    name = g.name,
                    subtitle = listOfNotNull(g.care.fabric.takeIf { it.isNotBlank() }, washSubtitle(g, washer)).joinToString(" · "),
                    photo = g.coverPhoto?.let(vm::photoFile),
                    selected = if (applying) on else null,
                    trailing = if (applying) {
                        { Checkbox(checked = on, onCheckedChange = { if (!selected.remove(g.id)) selected.add(g.id) }) }
                    } else null,
                ) {
                    if (applying) {
                        if (!selected.remove(g.id)) selected.add(g.id)
                    } else {
                        vm.copyCareFrom(g)
                        onBack()
                    }
                }
            }
            item { BottomSpacer(40) }
        }
    }
}
